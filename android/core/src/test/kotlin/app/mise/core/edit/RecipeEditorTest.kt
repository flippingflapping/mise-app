package app.mise.core.edit

import app.mise.core.Fixtures
import app.mise.core.model.IngredientEdit
import app.mise.core.model.MeasureUnit
import app.mise.core.model.RecipeEdits
import app.mise.core.model.RecipeJson
import app.mise.core.model.ReviewFlag
import app.mise.core.model.StepEdit
import app.mise.core.render.StepRenderer
import app.mise.core.units.UnitMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecipeEditorTest {
    private val curry = Fixtures.lambCurry

    /** The same edits as shared/tests/test_edits.py, as the server serves them. */
    private val editsJson = """
        {"title": "Lamb curry", "yield": {"base_servings": 4, "label": "people"},
         "ingredients": {"0009": {"name": "cinnamon sticks, 9cm (3½in)", "amount": 2},
                         "0002": {"name": "minced ginger", "amount": 1, "amount_max": 2, "unit": "tsp", "prep": "heaped"}},
         "steps": {"s2": {"title": "Brown the lamb well", "timer_seconds": 360, "timer_max_seconds": 480}},
         "checked_flags": ["ingredients[8].amount"]}
    """.trimIndent()

    @Test fun appliesTheSameWayAsThePc() {
        val edits = RecipeJson.lenient.decodeFromString(RecipeEdits.serializer(), editsJson)
        val out = RecipeEditor.apply(curry, edits)
        assertThat(curry.title).isNotEqualTo("Lamb curry") // original untouched
        assertThat(out.title).isEqualTo("Lamb curry")
        assertThat(out.recipeYield.baseServings).isEqualTo(4.0)
        val cinnamon = out.ingredient("0009")!!
        assertThat(cinnamon.amount).isEqualTo(2.0)
        assertThat(cinnamon.unit).isNull()
        val ginger = out.ingredient("0002")!!
        assertThat(listOf(ginger.amount, ginger.amountMax)).containsExactly(1.0, 2.0).inOrder()
        assertThat(ginger.prep).isEqualTo("heaped")
        val s2 = out.steps.single { it.id == "s2" }
        assertThat(listOf(s2.timerSeconds, s2.timerMaxSeconds)).containsExactly(360, 480).inOrder()
        assertThat(edits.checkedFlags).containsExactly("ingredients[8].amount")
    }

    @Test fun editedAmountsFlowIntoStepText() {
        val garlic = curry.ingredient("0003")!!
        val edits = RecipeEdits(ingredients = mapOf("0003" to RecipeEditor.editOf(garlic).copy(amount = 3.0)))
        val out = RecipeEditor.apply(curry, edits)
        val step = out.steps.first { "{0003}" in it.content }
        assertThat(StepRenderer.plainText(step.content, out, 1.0, UnitMode.ORIGINAL)).contains("3 tsp")
    }

    @Test fun flagPathsPointAtTheRightThing() {
        val t = RecipeEditor.target(curry, curry.reviewFlags.single())
        assertThat((t as FlagTarget.IngredientTarget).ingredient.id).isEqualTo("0009")
        assertThat(RecipeEditor.target(curry, ReviewFlag("steps[1].timer_seconds", "m"))).isInstanceOf(FlagTarget.StepTarget::class.java)
        assertThat(RecipeEditor.target(curry, ReviewFlag("yield.base_servings", "m"))).isEqualTo(FlagTarget.Servings)
        assertThat(RecipeEditor.target(curry, ReviewFlag("times.cook_minutes", "m"))).isEqualTo(FlagTarget.Other)
        assertThat(RecipeEditor.target(curry, ReviewFlag("ingredients[99].amount", "m"))).isEqualTo(FlagTarget.Other)
    }

    @Test fun problemsAreExplained() {
        assertThat(RecipeEditor.problems(IngredientEdit("ginger", amount = 3.0, amountMax = 1.0, unit = MeasureUnit.TSP)))
            .containsExactly("The range must end above where it starts.")
        assertThat(RecipeEditor.problems(IngredientEdit("salt"))).isEmpty() // to taste
        assertThat(RecipeEditor.problems(StepEdit("Simmer", timerSeconds = 600, timerMaxSeconds = 300))).isNotEmpty()
        assertThat(RecipeEditor.servingsProblem(0.0)).isNotNull()
    }

    @Test fun amountsAsPeopleTypeThem() {
        mapOf("2" to 2.0, "1.5" to 1.5, "1,5" to 1.5, "½" to 0.5, "1½" to 1.5, "1 ½" to 1.5, "1 1/2" to 1.5, "3/4" to 0.75, "" to null, "abc" to null)
            .forEach { (text, value) -> assertThat(RecipeEditor.parseAmount(text)).isEqualTo(value) }
        assertThat(RecipeEditor.formatAmount(1.5)).isEqualTo("1½")
    }

    @Test fun emptyEditsEncodeAsAnEmptyObject() {
        assertThat(RecipeJson.canonical.encodeToString(RecipeEdits.serializer(), RecipeEdits())).isEqualTo("{}")
    }
}
