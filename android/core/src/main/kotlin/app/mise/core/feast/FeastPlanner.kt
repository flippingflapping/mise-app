package app.mise.core.feast

import app.mise.core.model.Recipe
import app.mise.core.model.Step
import app.mise.core.render.StepRenderer
import app.mise.core.shopping.ShoppingList
import app.mise.core.timer.StepTimers
import app.mise.core.timer.StepTiming
import app.mise.core.timer.TimerSpec
import kotlin.math.ceil
import kotlin.math.max


/** A dish in a feast, at a chosen scale. */
data class FeastDish(val recipe: Recipe, val factor: Double = 1.0)

/** One thing to do, at a time. */
data class FeastTask(
    /** Stable across re-plans: "<recipeId>/<stepId>" or "<recipeId>/mise". */
    val id: String,
    val recipeId: String,
    val recipeTitle: String,
    val title: String,
    /** Null for the mise-en-place task. */
    val stepId: String?,
    val kind: Kind,
    val startMillis: Long,
    val endMillis: Long,
    /** How long the cook is busy from [startMillis]; the rest of a long step is hands-off waiting. */
    val activeMillis: Long,
    val timer: TimerSpec?,
    /** For mise en place: what to prep. */
    val prepList: List<String> = emptyList(),
) {
    enum class Kind { MISE_EN_PLACE, STEP }

    val handsOff: Boolean get() = endMillis - startMillis > activeMillis
}

/** Long waits (marinating overnight…) that happen before the timeline starts. */
data class AdvanceTask(
    val id: String,
    val recipeId: String,
    val recipeTitle: String,
    val title: String,
    val stepId: String?,
    /** "Marinate the lamb overnight" etc. from the recipe's advance_prep. */
    val notes: List<String>,
    /** Do it by this time (start of that dish's timeline minus the wait). */
    val byMillis: Long,
    val overnight: Boolean,
)

data class FeastPlan(
    val serveAtMillis: Long,
    val advance: List<AdvanceTask>,
    /** Remaining (not done) tasks, by start time. */
    val tasks: List<FeastTask>,
) {
    val startAtMillis: Long get() = tasks.minOfOrNull { it.startMillis } ?: serveAtMillis

    /** How far behind the plan we are at [now] (0 if on time): the earliest undone task should have started already. */
    fun minutesBehind(now: Long): Int = max(0L, now - startAtMillis).let { (it / 60_000).toInt() }

    fun current(now: Long): List<FeastTask> = tasks.filter { it.startMillis <= now }
    fun upcoming(now: Long): List<FeastTask> = tasks.filter { it.startMillis > now }
}

/**
 * Plans a multi-dish meal for one cook, working backwards from the serve time.
 *
 * * Each dish is its steps in order, preceded by a mise-en-place task (prepping its ingredients).
 * * Durations: a timed step takes the middle of its range; untimed steps get a short estimate from
 *   their length and the number of ingredients they add. Steps of 5 minutes or more are mostly
 *   hands-off: the cook is busy for the first 2 minutes, then free (that's when other dishes' work
 *   fits in). Shorter or untimed steps need the cook throughout.
 * * Scheduling: repeatedly take, among all dishes, the not-yet-placed step that can finish latest,
 *   and put it as late as possible without the cook doing two things at once. Every dish therefore
 *   ends as close to serve time as the cook's time allows.
 * * Overnight / multi-hour waits aren't on the timeline; they become [AdvanceTask]s.
 */
object FeastPlanner {
    private const val MINUTE = 60_000L
    const val HANDS_OFF_FROM_MIN = 5
    private const val ACTIVE_START_MIN = 2

    fun plan(dishes: List<FeastDish>, serveAtMillis: Long, done: Set<String> = emptySet()): FeastPlan {
        data class Draft(
            val id: String, val dish: FeastDish, val title: String, val step: Step?, val kind: FeastTask.Kind,
            val duration: Long, val active: Long, val timer: TimerSpec?, val prepList: List<String>,
        )

        val chains = mutableListOf<MutableList<Draft>>()
        val advanceSteps = mutableListOf<Pair<FeastDish, Step>>()
        for (dish in dishes) {
            val r = dish.recipe
            val chain = mutableListOf<Draft>()
            val timelineSteps = r.steps.filter { s ->
                val adv = StepTimers.timing(s, r) is StepTiming.AdvancePrep
                if (adv) advanceSteps += dish to s
                !adv
            }
            val usedIds = timelineSteps.flatMap { s -> StepRenderer.ingredientsUsed(s, r).map { it.id } }.toSet()
            val toPrep = r.ingredients.filter { it.id in usedIds }
            if (toPrep.isNotEmpty()) {
                val minutes = toPrep.sumOf { if (it.prep.isNullOrBlank()) 0.5 else 1.5 }.coerceIn(3.0, 30.0)
                val ms = roundToHalfMinute(minutes)
                chain += Draft(
                    "${r.id}/mise", dish, "Mise en place", null, FeastTask.Kind.MISE_EN_PLACE, ms, ms, null,
                    toPrep.filter { !it.prep.isNullOrBlank() }.map { "${ShoppingList.displayName(it.name)}: ${it.prep}" },
                )
            }
            for (s in timelineSteps) {
                val timing = StepTimers.timing(s, r) as? StepTiming.Countdown
                val spec = timing?.spec
                val duration: Long
                val active: Long
                if (spec != null) {
                    val mid = ((spec.minSeconds + (spec.maxSeconds ?: spec.minSeconds)) / 2.0)
                    duration = max(MINUTE, ceil(mid / 60.0).toLong() * MINUTE)
                    active = if (duration >= HANDS_OFF_FROM_MIN * MINUTE) ACTIVE_START_MIN * MINUTE else duration
                } else {
                    val adds = StepRenderer.ingredientsUsed(s, r).size
                    val minutes = (1.0 + 0.5 * adds + s.content.length / 200.0).coerceIn(1.0, 6.0)
                    duration = roundToHalfMinute(minutes)
                    active = duration
                }
                chain += Draft("${r.id}/${s.id}", dish, s.title, s, FeastTask.Kind.STEP, duration, active, spec, emptyList())
            }
            chains += chain.filter { it.id !in done }.toMutableList()
        }

        // Reverse list scheduling with one cook.
        val busy = mutableListOf<LongRange>() // [start, end) as start..end-1
        val deadline = LongArray(chains.size) { serveAtMillis }
        val placed = mutableListOf<FeastTask>()
        while (chains.any { it.isNotEmpty() }) {
            var best = -1
            var bestStart = Long.MIN_VALUE
            for (i in chains.indices) {
                val d = chains[i].lastOrNull() ?: continue
                val start = latestFreeStart(deadline[i] - d.duration, d.active, busy)
                if (start > bestStart) { bestStart = start; best = i }
            }
            val d = chains[best].removeAt(chains[best].lastIndex)
            busy += bestStart until bestStart + d.active
            deadline[best] = bestStart
            placed += FeastTask(
                id = d.id, recipeId = d.dish.recipe.id, recipeTitle = d.dish.recipe.title, title = d.title,
                stepId = d.step?.id, kind = d.kind, startMillis = bestStart, endMillis = bestStart + d.duration,
                activeMillis = d.active, timer = d.timer, prepList = d.prepList,
            )
        }

        val firstStart = placed.groupBy { it.recipeId }.mapValues { (_, t) -> t.minOf { it.startMillis } }
        val advance = advanceSteps.filter { "${it.first.recipe.id}/${it.second.id}" !in done }.map { (dish, s) ->
            val r = dish.recipe
            val begin = firstStart[r.id] ?: serveAtMillis
            val waitMs = (s.timerSeconds?.takeIf { it > StepTimers.LONG_WAIT_SECONDS }?.toLong() ?: (12 * 3600L)) * 1000
            val overnight = s.timerSeconds == null || s.timerSeconds > StepTimers.LONG_WAIT_SECONDS * 3
            AdvanceTask("${r.id}/${s.id}", r.id, r.title, s.title, s.id, r.advancePrep, begin - waitMs, overnight)
        }.sortedBy { it.byMillis }

        return FeastPlan(serveAtMillis, advance, placed.sortedWith(compareBy({ it.startMillis }, { it.recipeTitle })))
    }

    /** Latest start ≤ [latest] such that [start, start+active) doesn't overlap any busy range. */
    private fun latestFreeStart(latest: Long, active: Long, busy: List<LongRange>): Long {
        var s = latest
        var moved = true
        while (moved) {
            moved = false
            for (b in busy) {
                val bStart = b.first
                val bEnd = b.last + 1
                if (s < bEnd && s + active > bStart) {
                    s = bStart - active
                    moved = true
                }
            }
        }
        return s
    }

    private fun roundToHalfMinute(minutes: Double): Long = (ceil(minutes * 2) / 2 * MINUTE).toLong()

}
