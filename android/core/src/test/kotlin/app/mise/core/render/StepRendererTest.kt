package app.mise.core.render

import app.mise.core.Fixtures
import app.mise.core.scale.Servings
import app.mise.core.table
import app.mise.core.units.UnitMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StepRendererTest {
    private val r = Fixtures.lambCurry
    private fun factor(servings: Double) = Servings.factor(r.recipeYield.baseServings, servings)
    private fun chip(placeholder: String, servings: Double, mode: UnitMode) =
        StepRenderer.plainText(placeholder, r, factor(servings), mode)

    @Test fun briefExamples() {
        assertThat(chip("{0001}", 4.0, UnitMode.US)).isEqualTo("2¼ lb lamb")
        assertThat(chip("{0021}", 2.0, UnitMode.ORIGINAL)).isEqualTo("10–12 curry leaves")
    }

    @Test fun fixturePlaceholdersAcrossServingsAndModes() = table(
        listOf(
            Triple("{0001}", 2.0 to UnitMode.ORIGINAL, "500 g lamb"),
            Triple("{0001}", 4.0 to UnitMode.ORIGINAL, "1 kg lamb"),
            Triple("{0001}", 1.0 to UnitMode.ORIGINAL, "250 g lamb"),
            Triple("{0001:name}", 4.0 to UnitMode.US, "lamb"),
            Triple("{0010}", 1.0 to UnitMode.ORIGINAL, "2 cardamom pods"),
            Triple("{0010}", 3.0 to UnitMode.ORIGINAL, "6 cardamom pods"),
            Triple("{0009}", 1.0 to UnitMode.ORIGINAL, "1 cinnamon stick"),
            Triple("{0013}", 2.0 to UnitMode.ORIGINAL, "a pinch of salt"),
            Triple("{0013}", 4.0 to UnitMode.ORIGINAL, "2 pinches of salt"),
            Triple("{0016}", 3.0 to UnitMode.ORIGINAL, "2¼ tbsp roasted curry powder"),
            Triple("{0016}", 2.0 to UnitMode.METRIC, "1½ tbsp roasted curry powder"),
            Triple("{0018}", 3.0 to UnitMode.ORIGINAL, "1 tbsp chilli powder"),
            Triple("{0023}", 2.0 to UnitMode.US, "1 cup stock"),
            Triple("{0023}", 8.0 to UnitMode.ORIGINAL, "1 l stock"),
            Triple("{0024}", 2.0 to UnitMode.US, "⅜ cup coconut milk"),
            Triple("{0021}", 1.0 to UnitMode.ORIGINAL, "5–6 curry leaves"),
            Triple("{0022}", 3.0 to UnitMode.ORIGINAL, "1½ tomato"),
            Triple("{0012}", 2.0 to UnitMode.US, "5¼ oz onion"),
        ),
        { "${it.first} @ ${it.second}" },
    ) { (ph, setting, exp) -> exp to chip(ph, setting.first, setting.second) }

    @Test fun mixedContentKeepsTextAndChips() {
        val segs = StepRenderer.render(r.steps[1], r, 1.0, UnitMode.ORIGINAL)
        assertThat(segs.joinToString("") { it.text }).isEqualTo(
            "Heat 2 tbsp coconut oil in a large heavy-based pan. Add the lamb and fry until browned on all sides. " +
                "Don't stir too much — you want it to brown, not release its juices and stew.",
        )
        val refs = segs.filterIsInstance<StepSegment.IngredientRef>()
        assertThat(refs.map { it.ingredientId }).containsExactly("0007", "0001").inOrder()
        assertThat(refs[1].nameOnly).isTrue()
    }

    @Test fun missingIdRendersWarningChip() {
        val segs = StepRenderer.render("Add {9999} and {lamb} to {0001}.", r, 1.0, UnitMode.ORIGINAL)
        val missing = segs.filterIsInstance<StepSegment.Missing>()
        assertThat(missing.map { it.raw }).containsExactly("{9999}", "{lamb}").inOrder()
        assertThat(missing[0].ingredientId).isEqualTo("9999")
        assertThat(missing[1].ingredientId).isNull()
        assertThat(segs.joinToString("") { it.text }).isEqualTo("Add ⚠ {9999} and ⚠ {lamb} to 500 g lamb.")
        assertThat(StepRenderer.spokenText("Add {9999}.", r, 1.0, UnitMode.ORIGINAL)).isEqualTo("Add unknown ingredient.")
    }

    @Test fun everyFixtureStepResolves() {
        for (step in r.steps) {
            val segs = StepRenderer.render(step, r, 1.0, UnitMode.ORIGINAL)
            assertThat(segs.filterIsInstance<StepSegment.Missing>()).isEmpty()
        }
    }

    @Test fun ingredientsUsedByStep() {
        assertThat(StepRenderer.ingredientsUsed(r.steps[0], r).map { it.id })
            .containsExactly("0001", "0002", "0003", "0004", "0005", "0006").inOrder()
        // name-only re-references aren't measured out again
        assertThat(StepRenderer.ingredientsUsed(r.steps[2], r)).isEmpty()
        assertThat(StepRenderer.ingredientsUsed(r.steps[1], r).map { it.id }).containsExactly("0007")
    }

    @Test fun textWithoutPlaceholders() {
        assertThat(StepRenderer.render("Just stir.", r, 1.0, UnitMode.US))
            .containsExactly(StepSegment.Text("Just stir."))
        assertThat(StepRenderer.render("", r, 1.0, UnitMode.US)).isEmpty()
    }
}
