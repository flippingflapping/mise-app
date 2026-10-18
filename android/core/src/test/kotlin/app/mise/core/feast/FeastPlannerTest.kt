package app.mise.core.feast

import app.mise.core.Fixtures
import app.mise.core.model.Ingredient
import app.mise.core.model.MeasureUnit
import app.mise.core.model.Recipe
import app.mise.core.model.RecipeYield
import app.mise.core.model.Step
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeastPlannerTest {
    private val serve = 19 * 3_600_000L // 19:00 on day 0 (UTC millis; the planner only does arithmetic)
    private val min = 60_000L
    private val curry = Fixtures.lambCurry

    private val rice = Recipe(
        id = "rice", title = "Rice", recipeYield = RecipeYield(4.0),
        ingredients = listOf(Ingredient("0001", name = "basmati rice", shortName = "rice", amount = 300.0, unit = MeasureUnit.G, prep = "rinsed")),
        steps = listOf(
            Step("s1", "Boil", "Boil {0001} in plenty of water.", timerSeconds = 600),
            Step("s2", "Steam", "Drain, cover and leave to steam.", timerSeconds = 300),
        ),
    )

    private val stew = Recipe(
        id = "stew", title = "Beef stew", recipeYield = RecipeYield(4.0),
        ingredients = listOf(
            Ingredient("0001", name = "beef shin", amount = 1.0, unit = MeasureUnit.KG, prep = "diced"),
            Ingredient("0002", name = "carrots", amount = 3.0, prep = "chopped"),
        ),
        steps = listOf(
            Step("s1", "Brown", "Brown {0001} in batches.", timerSeconds = 600),
            Step("s2", "Simmer", "Add {0002}, cover and simmer gently.", timerSeconds = 2 * 3600),
            Step("s3", "Season", "Taste and season."),
        ),
    )

    /** The cook never does two hands-on things at once. */
    private fun assertNoOverlap(plan: FeastPlan) {
        val active = plan.tasks.map { it.startMillis until it.startMillis + it.activeMillis }.sortedBy { it.first }
        active.zipWithNext().forEach { (a, b) -> assertThat(b.first).isAtLeast(a.last + 1) }
    }

    @Test fun singleDishEndsAtServeTimeInOrder() {
        val plan = FeastPlanner.plan(listOf(FeastDish(curry)), serve)
        assertThat(plan.tasks.last().endMillis).isEqualTo(serve)
        assertThat(plan.tasks.first().kind).isEqualTo(FeastTask.Kind.MISE_EN_PLACE)
        // steps keep recipe order, each ends before the next starts
        plan.tasks.zipWithNext().forEach { (a, b) -> assertThat(a.endMillis).isAtMost(b.startMillis) }
        assertThat(plan.tasks.map { it.stepId }).doesNotContain("s1") // overnight marinade isn't on the timeline
        assertNoOverlap(plan)
    }

    @Test fun overnightMarinadeBecomesAdvancePrep() {
        val plan = FeastPlanner.plan(listOf(FeastDish(curry)), serve)
        val adv = plan.advance.single()
        assertThat(adv.title).isEqualTo("Marinate overnight")
        assertThat(adv.overnight).isTrue()
        assertThat(adv.notes).containsExactly("Marinate the lamb overnight")
        assertThat(adv.byMillis).isAtMost(plan.startAtMillis - 12 * 3_600_000L)
    }

    @Test fun simmerIsHandsOffSoOtherDishesFitInside() {
        val plan = FeastPlanner.plan(listOf(FeastDish(curry), FeastDish(rice)), serve)
        val simmer = plan.tasks.single { it.id == "${curry.id}/s10" }
        assertThat(simmer.handsOff).isTrue()
        assertThat(simmer.activeMillis).isEqualTo(2 * min)
        assertThat(simmer.timer?.minSeconds).isEqualTo(900)
        assertThat(simmer.endMillis - simmer.startMillis).isEqualTo(18 * min) // middle of 15–20 min, rounded up
        assertNoOverlap(plan)
        // Rice finishes at serve time; the curry finishes as close to it as the cook allows.
        assertThat(plan.tasks.filter { it.recipeId == "rice" }.maxOf { it.endMillis }).isEqualTo(serve)
        assertThat(plan.tasks.filter { it.recipeId == curry.id }.maxOf { it.endMillis }).isAtLeast(serve - 10 * min)
        // Parallel cooking makes the whole meal shorter than doing the dishes one after the other.
        val alone = FeastPlanner.plan(listOf(FeastDish(curry)), serve).startAtMillis
        val riceAlone = FeastPlanner.plan(listOf(FeastDish(rice)), serve).startAtMillis
        assertThat(serve - plan.startAtMillis).isLessThan((serve - alone) + (serve - riceAlone))
    }

    @Test fun miseEnPlaceListsPrep() {
        val mise = FeastPlanner.plan(listOf(FeastDish(curry)), serve).tasks.first()
        assertThat(mise.prepList).contains("onion: sliced")
        assertThat(mise.prepList).doesNotContain("lamb neck or shoulder fillets: diced into 4cm (1½in) pieces") // used only in the marinade
        assertThat(mise.endMillis - mise.startMillis).isAtLeast(3 * min)
    }

    @Test fun doneTasksDropOutAndLatenessIsReported() {
        val plan = FeastPlanner.plan(listOf(FeastDish(curry), FeastDish(rice)), serve)
        val first = plan.tasks.first()
        val after = FeastPlanner.plan(listOf(FeastDish(curry), FeastDish(rice)), serve, done = setOf(first.id))
        assertThat(after.tasks.map { it.id }).doesNotContain(first.id)
        val firstStart = after.tasks.first().startMillis
        assertThat(after.minutesBehind(firstStart - 5 * min)).isEqualTo(0)
        assertThat(after.minutesBehind(firstStart + 7 * min)).isEqualTo(0) // not worth worrying about
        assertThat(after.minutesBehind(firstStart + 12 * min)).isEqualTo(15) // rounded to 5
        assertThat(after.currentBlock).isEqualTo(after.blocks.first())
    }

    @Test fun stableIdsAndEmptyPlan() {
        val a = FeastPlanner.plan(listOf(FeastDish(curry)), serve).tasks.map { it.id }
        val b = FeastPlanner.plan(listOf(FeastDish(curry)), serve + 3_600_000).tasks.map { it.id }
        assertThat(a).isEqualTo(b)
        val empty = FeastPlanner.plan(emptyList(), serve)
        assertThat(empty.tasks).isEmpty()
        assertThat(empty.startAtMillis).isEqualTo(serve)
    }

    /** Blocks tile the evening with a few round times, and every task appears exactly once. */
    private fun assertTidyBlocks(plan: FeastPlan) {
        val blocks = plan.blocks
        if (plan.style == FeastStyle.CALM) assertThat(blocks.first().startMillis % (15 * min)).isEqualTo(0)
        blocks.forEach { assertThat(it.startMillis % (5 * min)).isEqualTo(0) }
        blocks.zipWithNext().forEach { (a, b) -> assertThat(a.endMillis).isEqualTo(b.startMillis) }
        assertThat(blocks.last().endMillis).isEqualTo(serve)
        assertThat(blocks.flatMap { it.tasks }.map { it.id }).containsExactlyElementsIn(plan.tasks.map { it.id })
        blocks.forEach { b -> assertThat(b.startMillis).isAtMost(b.tasks.minOfOrNull { it.startMillis } ?: b.startMillis) }
        // free time never starts before the hands-on work before it is finished
        blocks.zipWithNext().filter { it.second.kind == FeastBlock.Kind.BREAK }.forEach { (work, free) ->
            assertThat(free.startMillis).isAtLeast(work.tasks.maxOf { it.startMillis + it.activeMillis })
        }
    }

    @Test fun prepComesFirstAsOneBlock() {
        val plan = FeastPlanner.plan(listOf(FeastDish(curry), FeastDish(rice)), serve)
        val prep = plan.blocks.first()
        assertThat(prep.kind).isEqualTo(FeastBlock.Kind.PREP)
        assertThat(prep.tasks.map { it.kind }.toSet()).containsExactly(FeastTask.Kind.MISE_EN_PLACE)
        assertThat(prep.recipeIds).containsExactly(curry.id, "rice")
        val cookStart = plan.tasks.filter { it.kind == FeastTask.Kind.STEP }.minOf { it.startMillis }
        plan.tasks.filter { it.kind == FeastTask.Kind.MISE_EN_PLACE }.forEach { assertThat(it.endMillis).isAtMost(cookStart) }
        assertNoOverlap(plan)
        assertTidyBlocks(plan)
        // a handful of blocks, not a list of every minute
        assertThat(plan.blocks.size).isAtMost(4)
    }

    @Test fun longSimmerLeavesFreeTime() {
        val plan = FeastPlanner.plan(listOf(FeastDish(stew), FeastDish(rice)), serve)
        assertTidyBlocks(plan)
        val kinds = plan.blocks.map { it.kind }
        assertThat(kinds.first()).isEqualTo(FeastBlock.Kind.PREP)
        assertThat(kinds).contains(FeastBlock.Kind.BREAK)
        val free = plan.blocks.first { it.kind == FeastBlock.Kind.BREAK }
        assertThat(free.approxMinutes).isAtLeast(60)
        assertThat(free.tasks).isEmpty()
        // once the work before the break is done, the next work block is current, never the break
        val before = plan.blocks.takeWhile { it.kind != FeastBlock.Kind.BREAK }.flatMap { it.tasks }.map { it.id }.toSet()
        val later = FeastPlanner.plan(listOf(FeastDish(stew), FeastDish(rice)), serve, done = before)
        assertThat(later.currentBlock?.kind).isEqualTo(FeastBlock.Kind.COOK)
    }

    @Test fun friendlyDurations() {
        fun b(minutes: Int) = FeastBlock(FeastBlock.Kind.COOK, 0, minutes * min, emptyList()).approxMinutes
        assertThat(b(2)).isEqualTo(5)
        assertThat(b(13)).isEqualTo(15)
        assertThat(b(58)).isEqualTo(60)
        assertThat(b(68)).isEqualTo(75)
        assertThat(b(97)).isEqualTo(90)
        assertThat(b(100)).isEqualTo(105)
    }

    @Test fun proStyleMultitasksAndIsNoSlower() {
        val dishes = listOf(FeastDish(curry), FeastDish(stew), FeastDish(rice))
        val calm = FeastPlanner.plan(dishes, serve)
        val pro = FeastPlanner.plan(dishes, serve, style = FeastStyle.PRO)
        assertThat(pro.style).isEqualTo(FeastStyle.PRO)
        assertNoOverlap(pro)
        assertTidyBlocks(pro)
        // No separate prep block: each dish's prep is part of its own cooking block, before its steps.
        assertThat(pro.blocks.map { it.kind }).doesNotContain(FeastBlock.Kind.PREP)
        for (d in dishes) {
            val mine = pro.tasks.filter { it.recipeId == d.recipe.id }
            assertThat(mine.first().kind).isEqualTo(FeastTask.Kind.MISE_EN_PLACE)
        }
        // Prep fills gaps, so the evening starts no earlier than calm style (usually later).
        val firstStart = { p: FeastPlan -> p.tasks.minOf { it.startMillis } }
        assertThat(firstStart(pro)).isAtLeast(firstStart(calm))
        // Same work either way.
        assertThat(pro.tasks.map { it.id }).containsExactlyElementsIn(calm.tasks.map { it.id })
    }
}
