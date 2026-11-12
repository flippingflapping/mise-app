package app.mise.core.scale

import app.mise.core.edit.RecipeEditor
import app.mise.core.model.Equivalent
import app.mise.core.model.Ingredient
import app.mise.core.model.MeasureUnit
import app.mise.core.model.MeasureUnit.CUP
import app.mise.core.model.MeasureUnit.FL_OZ
import app.mise.core.model.MeasureUnit.G
import app.mise.core.model.MeasureUnit.ML
import app.mise.core.model.MeasureUnit.OZ
import app.mise.core.model.Recipe
import app.mise.core.model.RecipeEdits
import app.mise.core.model.RecipeYield
import app.mise.core.units.UnitMode
import app.mise.core.units.UnitMode.METRIC
import app.mise.core.units.UnitMode.ORIGINAL
import app.mise.core.units.UnitMode.US
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Conversions that use the recipe's own figure, or a typical weight for solids measured in cups. */
class StatedConversionTest {
    private fun ing(name: String, amount: Double, unit: MeasureUnit, eq: Equivalent? = null, max: Double? = null) =
        Ingredient(id = "0001", name = name, amount = amount, amountMax = max, unit = unit, equivalent = eq)

    private fun show(i: Ingredient, mode: UnitMode, factor: Double = 1.0) = AmountRenderer.render(i, factor, mode).text

    private val flour = ing("all-purpose flour", 2.0, CUP, Equivalent(250.0, G))

    @Test fun metricUsesTheWeightTheRecipeStates() {
        assertThat(show(flour, METRIC)).isEqualTo("250 g") // not 475 ml
        assertThat(show(flour, METRIC, 2.0)).isEqualTo("500 g")
        assertThat(show(flour, METRIC, 4.4)).isEqualTo("1.1 kg")
    }

    @Test fun originalAndSameSystemModesIgnoreIt() {
        assertThat(show(flour, ORIGINAL)).isEqualTo("2 cups")
        assertThat(show(flour, US)).isEqualTo("2 cups")
    }

    @Test fun usUsesTheAuthorsFigureToo() {
        val lamb = ing("lamb", 500.0, G, Equivalent(18.0, OZ))
        assertThat(show(lamb, US)).isEqualTo("18 oz") // the author's 1 lb 2 oz, not a computed 17¾ oz
        assertThat(show(lamb, US, 2.0)).isEqualTo("2¼ lb")
        val stock = ing("stock", 250.0, ML, Equivalent(9.0, FL_OZ))
        assertThat(show(stock, US)).isEqualTo("1⅛ cups") // still shown the US way, from the stated 9 fl oz
    }

    @Test fun aRangeKeepsItsProportions() {
        assertThat(show(ing("flour", 2.0, CUP, Equivalent(250.0, G), max = 3.0), METRIC)).isEqualTo("250–375 g")
    }

    @Test fun withoutAStatedWeightSolidsInCupsBecomeGrams() {
        assertThat(show(ing("all-purpose flour", 2.0, CUP), METRIC)).isEqualTo("250 g")
        assertThat(show(ing("brown sugar", 0.5, CUP), METRIC)).isEqualTo("105 g")
        assertThat(show(ing("unsalted butter", 1.0, CUP), METRIC)).isEqualTo("225 g")
        assertThat(show(ing("basmati rice", 1.5, CUP), METRIC)).isEqualTo("280 g")
    }

    @Test fun liquidsAndUnknownsStayInMillilitres() {
        assertThat(show(ing("lukewarm water", 1.0, CUP), METRIC)).isEqualTo("235 ml")
        assertThat(show(ing("coconut milk", 1.0, CUP), METRIC)).isEqualTo("235 ml")
        assertThat(show(ing("buttermilk", 1.0, CUP), METRIC)).isEqualTo("235 ml")
        assertThat(show(ing("rice vinegar", 0.5, CUP), METRIC)).isEqualTo("120 ml")
        assertThat(show(ing("mystery paste", 1.0, CUP), METRIC)).isEqualTo("235 ml")
    }

    @Test fun editingTheAmountDropsTheStatedFigure() {
        val recipe = Recipe(id = "r", title = "R", recipeYield = RecipeYield(2.0), ingredients = listOf(flour))
        fun edited(change: (app.mise.core.model.IngredientEdit) -> app.mise.core.model.IngredientEdit) =
            RecipeEditor.apply(recipe, RecipeEdits(ingredients = mapOf("0001" to change(RecipeEditor.editOf(flour))))).ingredients.single()

        assertThat(edited { it.copy(name = "plain flour") }.equivalent).isEqualTo(Equivalent(250.0, G)) // renaming keeps it
        assertThat(edited { it.copy(amount = 3.0) }.equivalent).isNull()
        assertThat(edited { it.copy(unit = MeasureUnit.TBSP) }.equivalent).isNull()
    }
}
