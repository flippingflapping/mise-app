package app.mise.core.scale

import app.mise.core.model.Ingredient
import app.mise.core.model.MeasureUnit
import app.mise.core.model.MeasureUnit.CUP
import app.mise.core.model.MeasureUnit.FL_OZ
import app.mise.core.model.MeasureUnit.G
import app.mise.core.model.MeasureUnit.KG
import app.mise.core.model.MeasureUnit.LB
import app.mise.core.model.MeasureUnit.ML
import app.mise.core.model.MeasureUnit.PINCH
import app.mise.core.model.MeasureUnit.TBSP
import app.mise.core.model.MeasureUnit.TSP
import app.mise.core.table
import app.mise.core.units.UnitMode
import app.mise.core.units.UnitMode.METRIC
import app.mise.core.units.UnitMode.ORIGINAL
import app.mise.core.units.UnitMode.US
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AmountRendererTest {
    data class Row(
        val amount: Double?,
        val unit: MeasureUnit?,
        val factor: Double,
        val mode: UnitMode,
        val expected: String,
        val max: Double? = null,
        val scales: Boolean = true,
        val name: String = "thing",
    )

    private fun ing(r: Row) = Ingredient(
        id = "0001", name = r.name, shortName = r.name, amount = r.amount, amountMax = r.max,
        unit = r.unit, scales = r.scales,
    )

    private fun run(rows: List<Row>) = table(
        rows,
        { "${it.amount}${it.max?.let { m -> "–$m" } ?: ""} ${it.unit} ×${it.factor} ${it.mode} (${it.name})" },
    ) { r -> r.expected to AmountRenderer.render(ing(r), r.factor, r.mode).text }

    @Test fun authoredValuesAreShownExactlyAtScaleOne() = run(
        listOf(
            Row(500.0, G, 1.0, ORIGINAL, "500 g"),
            Row(52.0, G, 1.0, ORIGINAL, "52 g"),
            Row(0.25, TSP, 1.0, ORIGINAL, "¼ tsp"),
            Row(1.5, TBSP, 1.0, ORIGINAL, "1½ tbsp"),
            Row(1000.0, G, 1.0, ORIGINAL, "1000 g"),
            Row(0.5, TBSP, 1.0, ORIGINAL, "½ tbsp"),
            Row(500.0, G, 1.0, METRIC, "500 g"),
            Row(1.0, TSP, 1.0, METRIC, "1 tsp"),
        ),
    )

    @Test fun metricScalingRoundsAndPromotes() = run(
        listOf(
            Row(500.0, G, 2.0, ORIGINAL, "1 kg"),
            Row(500.0, G, 0.8, ORIGINAL, "400 g"),
            Row(52.0, G, 2.0, ORIGINAL, "105 g"), // nearest 5 g above 50 g
            Row(23.0, G, 2.0, ORIGINAL, "46 g"), // nearest 1 g below 50 g
            Row(2.0, G, 1.25, ORIGINAL, "2½ g"),
            Row(550.0, G, 2.0, ORIGINAL, "1.1 kg"),
            Row(1.0, KG, 0.5, ORIGINAL, "500 g"), // demotion
            Row(250.0, ML, 4.0, ORIGINAL, "1 l"),
            Row(250.0, ML, 5.0, ORIGINAL, "1¼ l"),
            Row(998.0, G, 1.0001, ORIGINAL, "1 kg"),
        ),
    )

    @Test fun spoonsPromoteAndDemoteButNeverConvertToMetric() = run(
        listOf(
            Row(1.0, TSP, 3.0, ORIGINAL, "1 tbsp"), // 3 tsp → 1 tbsp
            Row(2.0, TSP, 2.0, ORIGINAL, "4 tsp"), // 1⅓ tbsp isn't clean: stay in tsp
            Row(1.0, TSP, 1.5, ORIGINAL, "1½ tsp"),
            Row(1.5, TSP, 3.0, ORIGINAL, "1½ tbsp"),
            Row(1.0, TBSP, 0.5, ORIGINAL, "1½ tsp"), // demotion
            Row(1.0, TBSP, 1.0 / 3, ORIGINAL, "1 tsp"),
            Row(2.0, TBSP, 8.0, ORIGINAL, "1 cup"), // 16 tbsp → 1 cup
            Row(2.0, TBSP, 8.0, US, "1 cup"),
            Row(2.0, TBSP, 8.0, METRIC, "235 ml"), // a cup isn't metric
            Row(1.5, TBSP, 1.5, ORIGINAL, "2¼ tbsp"),
            Row(0.5, TSP, 2.0 / 3, ORIGINAL, "⅓ tsp"),
            Row(0.25, TSP, 0.25, ORIGINAL, "⅛ tsp"), // never rounds to nothing
            Row(1.0, TSP, 2.0, METRIC, "2 tsp"),
            Row(1.0, TSP, 2.0, US, "2 tsp"),
            Row(1.0, TBSP, 4.0, METRIC, "4 tbsp"),
        ),
    )

    @Test fun usConversion() = run(
        listOf(
            Row(500.0, G, 2.0, US, "2¼ lb"),
            Row(500.0, G, 1.0, US, "1⅛ lb"),
            Row(100.0, G, 1.0, US, "3½ oz"),
            Row(250.0, ML, 1.0, US, "1 cup"),
            Row(100.0, ML, 1.0, US, "⅜ cup"), // cups to the nearest ⅛
            Row(80.0, ML, 1.0, US, "⅓ cup"),
            Row(500.0, ML, 1.0, US, "2⅛ cups"),
            Row(30.0, ML, 1.0, US, "2 tbsp"), // under ¼ cup → spoons
            Row(5.0, ML, 1.0, US, "1 tsp"),
            Row(4.0, FL_OZ, 1.0, US, "½ cup"),
        ),
    )

    @Test fun metricConversion() = run(
        listOf(
            Row(1.0, LB, 1.0, METRIC, "455 g"),
            Row(1.0, CUP, 1.0, METRIC, "235 ml"),
            Row(5.0, CUP, 1.0, METRIC, "1.2 l"),
            Row(8.0, FL_OZ, 1.0, METRIC, "235 ml"),
            Row(4.0, FL_OZ, 2.0, ORIGINAL, "8 fl oz"),
            Row(3.0, LB, 1.0, METRIC, "1.35 kg"),
        ),
    )

    @Test fun rangesScaleBothEnds() = run(
        listOf(
            Row(10.0, null, 1.0, ORIGINAL, "10–12", max = 12.0),
            Row(10.0, null, 2.0, ORIGINAL, "20–24", max = 12.0),
            Row(10.0, null, 0.5, ORIGINAL, "5–6", max = 12.0),
            Row(10.0, null, 0.25, ORIGINAL, "3", max = 12.0), // 2½–3 rounds to one number
            Row(200.0, G, 2.0, ORIGINAL, "400–500 g", max = 250.0),
            Row(200.0, G, 2.0, US, "14–17¾ oz", max = 250.0),
            Row(1.0, TSP, 2.0, ORIGINAL, "2–4 tsp", max = 2.0),
        ),
    )

    @Test fun nonScalingItemsStayFixed() = run(
        listOf(
            Row(1.0, null, 3.0, ORIGINAL, "1", scales = false, name = "bay leaf"),
            Row(1.0, TSP, 4.0, ORIGINAL, "1 tsp", scales = false),
            Row(500.0, G, 4.0, US, "1⅛ lb", scales = false), // still converts units
        ),
    )

    @Test fun countablesRoundSensibly() = run(
        listOf(
            Row(4.0, null, 0.6, ORIGINAL, "2", name = "green cardamom pods"), // never 2.4 pods
            Row(4.0, null, 0.5, ORIGINAL, "2", name = "cloves"),
            Row(1.0, null, 0.5, ORIGINAL, "1", name = "cinnamon stick"), // never ½ a stick → 0
            Row(2.0, null, 1.25, ORIGINAL, "3", name = "green chillies"),
            Row(1.0, null, 1.5, ORIGINAL, "1½", name = "onion"), // halves OK for onions
            Row(1.0, null, 1.5, ORIGINAL, "1½", name = "large ripe tomato"),
            Row(2.0, null, 0.75, ORIGINAL, "1½", name = "lemons"),
            Row(0.5, null, 3.0, ORIGINAL, "1½", name = "bunch of coriander"), // authored as a half
            Row(3.0, null, 1.1, ORIGINAL, "3", name = "eggs"),
        ),
    )

    @Test fun pinchesAndToTaste() = run(
        listOf(
            Row(1.0, PINCH, 1.0, ORIGINAL, "1 pinch"),
            Row(1.0, PINCH, 2.0, ORIGINAL, "2 pinches"),
            Row(1.0, PINCH, 0.5, US, "1 pinch"),
            Row(null, null, 2.0, ORIGINAL, "to taste"),
            Row(null, G, 2.0, US, "to taste"),
        ),
    )

    @Test fun chipTextAndSpoken() {
        val salt = Ingredient(id = "0013", name = "salt", shortName = "salt", amount = 1.0, unit = PINCH)
        assertThat(AmountRenderer.chipText(salt, 1.0, ORIGINAL)).isEqualTo("a pinch of salt")
        assertThat(AmountRenderer.chipText(salt, 2.0, ORIGINAL)).isEqualTo("2 pinches of salt")
        val pepper = Ingredient(id = "0002", name = "black pepper", shortName = "pepper", amount = null, unit = null)
        assertThat(AmountRenderer.chipText(pepper, 1.0, ORIGINAL)).isEqualTo("pepper, to taste")
        val oil = Ingredient(id = "0003", name = "oil", shortName = "oil", amount = 1.5, unit = TBSP)
        assertThat(AmountRenderer.chipSpoken(oil, 1.0, ORIGINAL)).isEqualTo("1 and a half tablespoons oil")
    }
}
