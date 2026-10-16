package app.mise.core.scale

import app.mise.core.Fixtures
import app.mise.core.model.MeasureUnit
import app.mise.core.table
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServingsTest {
    @Test fun increment() = table(
        listOf(0.5 to 1.0, 1.0 to 1.5, 1.5 to 2.0, 1.6 to 2.0, 2.0 to 3.0, 2.4 to 3.0, 4.0 to 5.0),
        { "inc(${it.first})" },
    ) { (from, to) -> to to Servings.increment(from) }

    @Test fun decrement() = table(
        listOf(5.0 to 4.0, 3.0 to 2.0, 2.4 to 2.0, 2.0 to 1.5, 1.6 to 1.5, 1.0 to 0.5, 0.5 to 0.5),
        { "dec(${it.first})" },
    ) { (from, to) -> to to Servings.decrement(from) }

    @Test fun format() {
        assertThat(Servings.format(2.0)).isEqualTo("2")
        assertThat(Servings.format(1.5)).isEqualTo("1½")
        assertThat(Servings.format(1.6)).isEqualTo("1.6")
        assertThat(Servings.canDecrement(0.5)).isFalse()
    }

    @Test fun scaleByWhatIHave() {
        val r = Fixtures.lambCurry
        val lamb = r.ingredient("0001")!!
        val f = ScaleByIngredient.factor(lamb, 400.0, MeasureUnit.G)!!
        assertThat(f).isWithin(1e-9).of(0.8)
        assertThat(r.recipeYield.baseServings * f).isWithin(1e-9).of(1.6)
        assertThat(ScaleByIngredient.factor(lamb, 1.0, MeasureUnit.LB)!!).isWithin(1e-4).of(0.9072)
        assertThat(ScaleByIngredient.factor(lamb, 1.0, MeasureUnit.ML)).isNull() // wrong dimension
        assertThat(ScaleByIngredient.factor(lamb, 0.0, MeasureUnit.G)).isNull()
        val pods = r.ingredient("0010")!!
        assertThat(ScaleByIngredient.factor(pods, 6.0, null)!!).isWithin(1e-9).of(1.5)
        assertThat(ScaleByIngredient.inputUnits(pods)).containsExactly(null)
        assertThat(ScaleByIngredient.inputUnits(lamb)).contains(MeasureUnit.OZ)
        val toTaste = lamb.copy(amount = null)
        assertThat(ScaleByIngredient.canScaleBy(toTaste)).isFalse()
        assertThat(ScaleByIngredient.factor(lamb.copy(scales = false), 400.0, MeasureUnit.G)).isNull()
    }

    @Test fun scaledRecipeKeepsTsp() {
        val r = Fixtures.lambCurry
        val f = ScaleByIngredient.factor(r.ingredient("0001")!!, 400.0, MeasureUnit.G)!!
        // 1 tsp ginger × 0.8 = ⅘ tsp → nearest kitchen fraction
        assertThat(AmountRenderer.render(r.ingredient("0002")!!, f, app.mise.core.units.UnitMode.ORIGINAL).text)
            .isEqualTo("¾ tsp")
    }
}
