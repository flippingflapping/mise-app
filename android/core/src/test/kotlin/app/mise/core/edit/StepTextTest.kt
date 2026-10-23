package app.mise.core.edit

import app.mise.core.Fixtures
import app.mise.core.model.Ingredient
import app.mise.core.model.MeasureUnit
import app.mise.core.model.RecipeEdits
import app.mise.core.model.StepEdit
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StepTextTest {
    private val curry = Fixtures.lambCurry

    @Test fun everyStepRoundTrips() {
        for (step in curry.steps) {
            val editable = StepText.toEditable(step.content, curry)
            assertThat(editable).doesNotContainMatch("\\{\\d{4}")
            assertThat(StepText.fromEditable(editable, curry)).isEqualTo(StepText.Parsed.Ok(step.content))
        }
    }

    @Test fun labelsAreReadableAndUnambiguous() {
        val labels = StepText.labels(curry)
        assertThat(labels["0001"]).isEqualTo("lamb") // unique short name
        assertThat(labels["0007"]).isEqualTo("coconut oil, for browning") // short name clashes, full name doesn't
        assertThat(labels["0002"]).isEqualTo("minced ginger (Marinade)") // even the full name clashes
        assertThat(labels.values.toSet()).hasSize(labels.size)
        val s2 = curry.steps.single { it.id == "s2" }
        assertThat(StepText.toEditable(s2.content, curry)).startsWith("Heat {coconut oil, for browning} in a large heavy-based pan. Add the [lamb]")
    }

    @Test fun typingIsForgivingButNotAmbiguous() {
        assertThat(StepText.fromEditable("Add {LAMB} and [Lamb neck or shoulder fillets].", curry))
            .isEqualTo(StepText.Parsed.Ok("Add {0001} and {0001:name}."))
        val unknown = StepText.fromEditable("Add {tofu}.", curry) as StepText.Parsed.Problem
        assertThat(unknown.message).contains("No ingredient called “tofu”")
        val clash = StepText.fromEditable("Add {coconut oil}.", curry) as StepText.Parsed.Problem
        assertThat(clash.message).contains("more than one ingredient")
        assertThat(StepText.fromEditable("   ", curry)).isInstanceOf(StepText.Parsed.Problem::class.java)
    }

    @Test fun addRemoveAndReword() {
        val chicken = Ingredient(id = RecipeEditor.nextAddedId(curry), name = "chicken thighs", shortName = "chicken", amount = 600.0, unit = MeasureUnit.G)
        assertThat(chicken.id).isEqualTo("9001")
        val s2 = curry.steps.single { it.id == "s2" }
        val edits = RecipeEdits(
            removedIngredients = listOf("0001"),
            addedIngredients = listOf(chicken),
            steps = mapOf("s2" to RecipeEditor.editOf(s2).copy(content = StepText.withoutIngredient(s2.content, curry.ingredient("0001")!!))),
        )
        val out = RecipeEditor.apply(curry, edits)
        assertThat(out.ingredient("0001")).isNull()
        assertThat(out.ingredients.last().id).isEqualTo("9001")
        assertThat(RecipeEditor.nextAddedId(out)).isEqualTo("9002")
        assertThat(out.steps.single { it.id == "s2" }.content).contains("Add the lamb and fry")
        assertThat(StepText.stepsUsing(curry, "0001")).contains(2)
        // Editing the added ingredient like any other.
        val again = RecipeEditor.apply(curry, edits.copy(ingredients = mapOf("9001" to RecipeEditor.editOf(chicken).copy(amount = 800.0))))
        assertThat(again.ingredient("9001")!!.amount).isEqualTo(800.0)
        assertThat(StepEdit("t").content).isNull()
    }
}
