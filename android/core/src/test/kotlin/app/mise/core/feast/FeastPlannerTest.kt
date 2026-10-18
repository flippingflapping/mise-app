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
        assertThat(after.minutesBehind(after.startAtMillis - 5 * min)).isEqualTo(0)
        assertThat(after.minutesBehind(after.startAtMillis + 12 * min)).isEqualTo(12)
        assertThat(after.current(after.startAtMillis).map { it.id }).contains(after.tasks.first().id)
    }

    @Test fun stableIdsAndEmptyPlan() {
        val a = FeastPlanner.plan(listOf(FeastDish(curry)), serve).tasks.map { it.id }
        val b = FeastPlanner.plan(listOf(FeastDish(curry)), serve + 3_600_000).tasks.map { it.id }
        assertThat(a).isEqualTo(b)
        val empty = FeastPlanner.plan(emptyList(), serve)
        assertThat(empty.tasks).isEmpty()
        assertThat(empty.startAtMillis).isEqualTo(serve)
    }
}
