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
    /** Do it by this time (when the cooking starts, minus the wait; rounded down). */
    val byMillis: Long,
    val overnight: Boolean,
)

/**
 * A chunk of the evening shown as one thing: "About 6:15 · Prep everything · about 45 min".
 * Times are rounded (down) so people see a few friendly times, not a minute-by-minute list.
 */
data class FeastBlock(
    val kind: Kind,
    /** Rounded down to 5 minutes (the very first block to 15). */
    val startMillis: Long,
    /** The next block's start, or serve time. */
    val endMillis: Long,
    /** In order. Empty for a [Kind.BREAK]. */
    val tasks: List<FeastTask>,
) {
    enum class Kind {
        /** All the chopping and measuring, before any cooking. */
        PREP,
        /** Cooking one dish, or juggling a few. */
        COOK,
        /** Nothing to do for a while: things are simmering / resting. */
        BREAK,
    }

    val id: String get() = tasks.firstOrNull()?.id ?: "break@$startMillis"
    val recipeIds: List<String> get() = tasks.map { it.recipeId }.distinct()

    /** Length in friendly minutes: up to an hour, rounded up to 5; beyond, to the nearest quarter hour. */
    val approxMinutes: Int
        get() {
            val m = ceil((endMillis - startMillis) / 60_000.0).toInt()
            return if (m <= 60) max(5, (m + 4) / 5 * 5) else (m + 7) / 15 * 15
        }
}

data class FeastPlan(
    val serveAtMillis: Long,
    val advance: List<AdvanceTask>,
    /** Remaining (not done) tasks, by start time. */
    val tasks: List<FeastTask>,
) {
    /** The tasks grouped into a few blocks of time. */
    val blocks: List<FeastBlock> by lazy { FeastPlanner.blocks(tasks, serveAtMillis) }

    /** When to start, as shown: the first block's (rounded) start. */
    val startAtMillis: Long get() = blocks.firstOrNull()?.startMillis ?: serveAtMillis

    /**
     * How far behind the plan we are at [now], in minutes (0 if on time). Only counts once it's
     * worth worrying about ([FeastPlanner.LATE_AFTER_MIN]), rounded to 5 minutes.
     */
    fun minutesBehind(now: Long): Int {
        val first = tasks.minOfOrNull { it.startMillis } ?: return 0
        val late = ((now - first) / 60_000).toInt()
        return if (late < FeastPlanner.LATE_AFTER_MIN) 0 else (late + 4) / 5 * 5
    }

    /** The block to work on: the first one with anything left (done tasks drop out of the plan). */
    val currentBlock: FeastBlock? get() = blocks.firstOrNull { it.kind != FeastBlock.Kind.BREAK }
}

/**
 * Plans a multi-dish meal for one cook, working backwards from the serve time.
 *
 * * All the mise en place (prepping each dish's ingredients) comes first, in one go, then the
 *   cooking. Simpler to follow than prepping dish by dish between other jobs.
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
    /** A gap with nothing to do at least this long becomes a "free time" block. */
    const val BREAK_MIN = 10
    /** Runs of one dish shorter than this are folded into the neighbouring block. */
    private const val MIN_BLOCK_MIN = 10
    /** Don't call it "behind" for less than this. */
    const val LATE_AFTER_MIN = 10

    fun plan(dishes: List<FeastDish>, serveAtMillis: Long, done: Set<String> = emptySet()): FeastPlan {
        data class Draft(
            val id: String, val dish: FeastDish, val title: String, val step: Step?, val kind: FeastTask.Kind,
            val duration: Long, val active: Long, val timer: TimerSpec?, val prepList: List<String>,
        )

        val chains = mutableListOf<MutableList<Draft>>()
        val mise = mutableListOf<Draft>()
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
                mise += Draft(
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

        // Then all the prep, back to back, finishing when the cooking starts (first-cooked dish last).
        val cookStart = placed.minOfOrNull { it.startMillis } ?: serveAtMillis
        val firstCook = placed.groupBy { it.recipeId }.mapValues { (_, t) -> t.minOf { it.startMillis } }
        var t = cookStart
        for (d in mise.filter { it.id !in done }.sortedBy { firstCook[it.dish.recipe.id] ?: serveAtMillis }) {
            t -= d.duration
            placed += FeastTask(
                id = d.id, recipeId = d.dish.recipe.id, recipeTitle = d.dish.recipe.title, title = d.title,
                stepId = null, kind = d.kind, startMillis = t, endMillis = t + d.duration,
                activeMillis = d.active, timer = null, prepList = d.prepList,
            )
        }

        // "Start by" times count back from when the evening starts (as shown, rounded down).
        val eveningStart = roundDown(placed.minOfOrNull { it.startMillis } ?: serveAtMillis, 15)
        val advance = advanceSteps.filter { "${it.first.recipe.id}/${it.second.id}" !in done }.map { (dish, s) ->
            val r = dish.recipe
            val begin = eveningStart
            val waitMs = (s.timerSeconds?.takeIf { it > StepTimers.LONG_WAIT_SECONDS }?.toLong() ?: (12 * 3600L)) * 1000
            val overnight = s.timerSeconds == null || s.timerSeconds > StepTimers.LONG_WAIT_SECONDS * 3
            AdvanceTask("${r.id}/${s.id}", r.id, r.title, s.title, s.id, r.advancePrep, roundDown(begin - waitMs, 15), overnight)
        }.sortedBy { it.byMillis }

        return FeastPlan(serveAtMillis, advance, placed.sortedWith(compareBy({ it.startMillis }, { it.recipeTitle })))
    }

    /**
     * Groups tasks into blocks: one prep block, then cooking split where the cook has a real break,
     * and within that by dish (short stretches of a dish join their neighbour, so "juggling" several
     * dishes reads as one block). Start times are rounded down so they're easy to read.
     */
    fun blocks(tasks: List<FeastTask>, serveAtMillis: Long): List<FeastBlock> {
        if (tasks.isEmpty()) return emptyList()
        data class Raw(val kind: FeastBlock.Kind, val start: Long, val tasks: List<FeastTask>)

        val raw = mutableListOf<Raw>() // the prep block, if any
        val prep = tasks.filter { it.kind == FeastTask.Kind.MISE_EN_PLACE }.sortedBy { it.startMillis }
        if (prep.isNotEmpty()) raw += Raw(FeastBlock.Kind.PREP, prep.first().startMillis, prep)

        // Split the cooking into sessions at gaps where the cook is free for a while.
        val cooking = tasks.filter { it.kind == FeastTask.Kind.STEP }.sortedBy { it.startMillis }
        var busyUntil = prep.maxOfOrNull { it.startMillis + it.activeMillis }
        val sessions = mutableListOf<MutableList<FeastTask>>()
        val breaks = mutableListOf<Raw>() // breaks[i] comes before sessions[i + 1]
        for (task in cooking) {
            val gap = busyUntil?.let { task.startMillis - it }
            when {
                sessions.isEmpty() -> sessions += mutableListOf(task)
                gap != null && gap >= BREAK_MIN * MINUTE -> {
                    breaks += Raw(FeastBlock.Kind.BREAK, busyUntil!!, emptyList())
                    sessions += mutableListOf(task)
                }
                else -> sessions.last() += task
            }
            busyUntil = max(busyUntil ?: Long.MIN_VALUE, task.startMillis + task.activeMillis)
        }
        val ordered = raw.toMutableList()
        sessions.forEachIndexed { i, session ->
            if (i > 0) ordered += breaks[i - 1]
            ordered += dishRuns(session).map { Raw(FeastBlock.Kind.COOK, it.first().startMillis, it) }
        }

        // Round the starts; drop blocks that collapse into the next one.
        val rounded = ordered.mapIndexed { i, r -> r to roundDown(r.start, if (i == 0) 15 else 5) }
        val kept = mutableListOf<Pair<Raw, Long>>()
        for ((r, start) in rounded) {
            val prev = kept.lastOrNull()
            if (prev != null && start <= prev.second) {
                if (r.kind == FeastBlock.Kind.BREAK) continue
                if (prev.first.kind == FeastBlock.Kind.BREAK) kept.removeAt(kept.lastIndex)
                else { kept[kept.lastIndex] = prev.first.copy(tasks = prev.first.tasks + r.tasks) to prev.second; continue }
            }
            kept += r to start
        }
        val result = kept.mapIndexed { i, (r, start) ->
            FeastBlock(r.kind, start, kept.getOrNull(i + 1)?.second ?: serveAtMillis, r.tasks)
        }
        // A "break" that's shorter than it's worth mentioning after rounding just joins the work before it.
        return result.filter { b -> !(b.kind == FeastBlock.Kind.BREAK && b.endMillis - b.startMillis < BREAK_MIN * MINUTE) }
            .let { list -> list.mapIndexed { i, b -> b.copy(endMillis = list.getOrNull(i + 1)?.startMillis ?: serveAtMillis) } }
    }

    /** Splits a session into runs of one dish, folding short runs into the previous (or next) one. */
    private fun dishRuns(session: List<FeastTask>): List<List<FeastTask>> {
        val runs = mutableListOf<MutableList<FeastTask>>()
        for (t in session) {
            if (runs.isEmpty() || runs.last().last().recipeId != t.recipeId) runs += mutableListOf(t) else runs.last() += t
        }
        fun span(r: List<FeastTask>) = r.maxOf { it.startMillis + it.activeMillis } - r.first().startMillis
        val merged = mutableListOf<MutableList<FeastTask>>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && (span(r) < MIN_BLOCK_MIN * MINUTE || span(last) < MIN_BLOCK_MIN * MINUTE)) last += r else merged += r.toMutableList()
        }
        return merged
    }

    private fun roundDown(millis: Long, minutes: Int): Long = Math.floorDiv(millis, minutes * MINUTE) * minutes * MINUTE

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
