package app.mise.core.scale

import app.mise.core.format.Fractions
import app.mise.core.format.SpokenNumbers
import app.mise.core.model.Ingredient
import app.mise.core.model.MeasureUnit
import app.mise.core.units.Dimension
import app.mise.core.units.UnitFamily
import app.mise.core.units.UnitMode
import app.mise.core.units.Units
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/** An ingredient amount after scaling, unit conversion and rounding, ready to show. */
data class RenderedAmount(
    val min: Double?,
    val max: Double?,
    val unit: MeasureUnit?,
    /** "10–12", "1½", or "to taste". */
    val amountText: String,
    /** "g", "cups", or null for countables / to taste. */
    val unitText: String?,
    val toTaste: Boolean,
    /** Screen-reader version: "1 and a half tablespoons". */
    val spoken: String,
) {
    /** "500 g", "10–12", "to taste". */
    val text: String get() = listOfNotNull(amountText, unitText).joinToString(" ")
}

/**
 * Scaling + unit display rules (see PLAN.md §5 for the reasoning):
 *
 * * Weights convert g/kg ↔ oz/lb and liquid volumes ml/l ↔ cups (US) in Metric/US modes.
 *   tsp, tbsp, pinch and countables are never converted.
 * * After scaling, units promote/demote within their family (1000 g → 1 kg, 3 tsp → 1 tbsp,
 *   16 tbsp → 1 cup, ½ tbsp → 1½ tsp).
 * * At scale 1 with no conversion, the authored value is shown exactly as written.
 * * Ranges scale both ends and share one unit. Non-scaling ingredients keep their amount.
 */
object AmountRenderer {
    private const val EPS = 1e-9
    private val TSP_FRACTIONS = listOf(0.125, 0.25, 1.0 / 3, 0.5, 2.0 / 3, 0.75)

    private val HALVABLE = setOf(
        "onion", "lemon", "lime", "avocado", "tomato", "pepper", "potato", "apple", "orange", "cucumber",
        "aubergine", "eggplant", "cabbage", "courgette", "zucchini", "squash", "pumpkin", "grapefruit",
        "mango", "banana", "cauliflower", "broccoli", "lettuce", "fennel", "melon",
    )

    /**
     * @param normalize round and promote even at scale 1 (for computed totals such as shopping-list
     *   sums, which aren't "as authored").
     */
    fun render(ingredient: Ingredient, factor: Double, mode: UnitMode, normalize: Boolean = false): RenderedAmount {
        val amount = ingredient.amount
            ?: return RenderedAmount(null, null, ingredient.unit, "to taste", null, true, "to taste")
        val f = if (ingredient.scales) factor else 1.0
        val lo = amount * f
        val hi = ingredient.amountMax?.let { it * f }
        val unchanged = abs(f - 1.0) < EPS && !normalize

        return when (Units.dimension(ingredient.unit)) {
            Dimension.COUNT -> {
                val step = if (allowsHalves(ingredient)) 0.5 else 1.0
                val r = { v: Double -> if (unchanged) v else roundHalfUp(v, step) }
                build(r(lo), hi?.let(r), null)
            }
            Dimension.PINCH -> {
                val r = { v: Double -> if (unchanged) v else roundHalfUp(v, 1.0) }
                build(r(lo), hi?.let(r), MeasureUnit.PINCH)
            }
            Dimension.MASS, Dimension.VOLUME -> {
                val unit = ingredient.unit!!
                val source = Units.family(unit)
                val target = targetFamily(source, mode)
                if (unchanged && target == source) {
                    build(lo, hi, unit)
                } else {
                    val loBase = lo * Units.toBase(unit)
                    val outUnit = pickUnit(loBase, target, mode)
                    val conv = { base: Double -> roundIn(outUnit, base / Units.toBase(outUnit)) }
                    build(conv(loBase), hi?.let { conv(it * Units.toBase(unit)) }, outUnit)
                }
            }
        }
    }

    /** Text for a `{0001}` placeholder: "500 g lamb", "a pinch of salt", "salt, to taste". */
    fun chipText(ingredient: Ingredient, factor: Double, mode: UnitMode): String {
        val a = render(ingredient, factor, mode)
        val name = ingredient.shortName
        return when {
            a.toTaste -> "$name, to taste"
            a.unit == MeasureUnit.PINCH && a.max == null && a.min == 1.0 -> "a pinch of $name"
            a.unit == MeasureUnit.PINCH -> "${a.text} of $name"
            else -> "${a.text} $name"
        }
    }

    fun chipSpoken(ingredient: Ingredient, factor: Double, mode: UnitMode): String {
        val a = render(ingredient, factor, mode)
        val name = ingredient.shortName
        return if (a.toTaste) "$name, to taste" else "${a.spoken} $name"
    }

    fun targetFamily(source: UnitFamily, mode: UnitMode): UnitFamily = when (mode) {
        UnitMode.ORIGINAL -> source
        UnitMode.METRIC -> when (source) {
            UnitFamily.US_MASS -> UnitFamily.METRIC_MASS
            UnitFamily.US_CUPS, UnitFamily.FL_OZ -> UnitFamily.METRIC_VOLUME
            else -> source
        }
        UnitMode.US -> when (source) {
            UnitFamily.METRIC_MASS -> UnitFamily.US_MASS
            UnitFamily.METRIC_VOLUME, UnitFamily.FL_OZ -> UnitFamily.US_CUPS
            else -> source
        }
    }

    /** Choose the display unit for a base amount (grams or millilitres) within a family. */
    fun pickUnit(base: Double, family: UnitFamily, mode: UnitMode): MeasureUnit = when (family) {
        UnitFamily.METRIC_MASS ->
            if (roundIn(MeasureUnit.G, base) >= 1000 - EPS) MeasureUnit.KG else MeasureUnit.G
        UnitFamily.METRIC_VOLUME ->
            if (roundIn(MeasureUnit.ML, base) >= 1000 - EPS) MeasureUnit.L else MeasureUnit.ML
        UnitFamily.US_MASS ->
            if (roundIn(MeasureUnit.OZ, base / Units.toBase(MeasureUnit.OZ)) >= 16 - EPS) MeasureUnit.LB
            else MeasureUnit.OZ
        UnitFamily.US_CUPS ->
            if (base >= Units.toBase(MeasureUnit.CUP) * 0.25 * 0.97) MeasureUnit.CUP
            else pickSpoon(base, mode)
        UnitFamily.FL_OZ -> MeasureUnit.FL_OZ
        UnitFamily.SPOONS -> pickSpoon(base, mode)
        UnitFamily.PINCH -> MeasureUnit.PINCH
        UnitFamily.COUNT -> error("countables have no unit")
    }

    private fun pickSpoon(base: Double, mode: UnitMode): MeasureUnit {
        val tsp = base / Units.toBase(MeasureUnit.TSP)
        val tbsp = base / Units.toBase(MeasureUnit.TBSP)
        if (roundIn(MeasureUnit.TSP, tsp) < 3 - EPS) return MeasureUnit.TSP
        if (tbsp >= 16 - 1e-6) {
            return if (mode == UnitMode.METRIC) pickUnit(base, UnitFamily.METRIC_VOLUME, mode) else MeasureUnit.CUP
        }
        val cleanHalf = abs(tbsp * 2 - round(tbsp * 2)) < 0.05
        return if (tsp >= 6 - EPS || cleanHalf) MeasureUnit.TBSP else MeasureUnit.TSP
    }

    /** Round a value already expressed in [unit] to that unit's sensible precision. */
    fun roundIn(unit: MeasureUnit, value: Double): Double = when (unit) {
        MeasureUnit.G, MeasureUnit.ML -> when {
            value < 5 -> roundHalfUp(value, 0.5)
            value <= 50 -> roundHalfUp(value, 1.0)
            else -> roundHalfUp(value, 5.0)
        }
        MeasureUnit.KG, MeasureUnit.L -> roundHalfUp(value, 0.05)
        MeasureUnit.OZ, MeasureUnit.FL_OZ -> roundHalfUp(value, 0.25)
        MeasureUnit.LB -> roundHalfUp(value, 0.125)
        MeasureUnit.CUP -> Fractions.snap(value, Fractions.EIGHTHS_AND_THIRDS)
        MeasureUnit.TBSP -> Fractions.snap(value, Fractions.QUARTERS_AND_THIRDS)
        MeasureUnit.TSP -> Fractions.snap(value, TSP_FRACTIONS)
        MeasureUnit.PINCH -> roundHalfUp(value, 1.0)
    }

    fun allowsHalves(ingredient: Ingredient): Boolean {
        val authoredFractional = listOfNotNull(ingredient.amount, ingredient.amountMax)
            .any { abs(it - round(it)) > EPS }
        if (authoredFractional) return true
        val words = (ingredient.shortName + " " + ingredient.name).lowercase()
            .split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        return words.any { w -> singular(w) in HALVABLE }
    }

    private fun singular(w: String): String = when {
        w.endsWith("oes") -> w.dropLast(2) // tomatoes, potatoes, mangoes
        w.endsWith("ies") -> w.dropLast(3) + "y"
        w.endsWith("es") && (w.endsWith("ches") || w.endsWith("shes")) -> w.dropLast(2)
        w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    /** Half-up rounding to a step (kotlin.math.round is half-even); positive never becomes 0. */
    fun roundHalfUp(value: Double, step: Double): Double {
        if (value <= 0) return 0.0
        val r = floor(value / step + 0.5 + 1e-9) * step
        return if (r < EPS) step else r
    }

    /** kg and l read best as decimals ("1.35 kg") unless they land exactly on a quarter. */
    private fun formatIn(unit: MeasureUnit?, v: Double): String =
        if (unit == MeasureUnit.KG || unit == MeasureUnit.L) {
            if (abs(v * 4 - round(v * 4)) < 1e-6) Fractions.format(v) else Fractions.decimal(v)
        } else {
            Fractions.format(v)
        }

    private fun build(min: Double, max: Double?, unit: MeasureUnit?): RenderedAmount {
        val loText = formatIn(unit, min)
        val hiText = max?.let { formatIn(unit, it) }
        val isRange = hiText != null && hiText != loText
        val amountText = if (isRange) "$loText–$hiText" else loText
        val shownMax = if (isRange) max else null
        val pluralBasis = shownMax ?: min
        val unitText = unit?.let { Units.label(it, pluralBasis) }
        val spokenAmount = if (isRange) "${SpokenNumbers.speak(loText)} to ${SpokenNumbers.speak(hiText)}"
        else SpokenNumbers.speak(loText)
        val spoken = listOfNotNull(spokenAmount, unit?.let { Units.spokenLabel(it, pluralBasis) }).joinToString(" ")
        return RenderedAmount(min, shownMax, unit, amountText, unitText, false, spoken)
    }
}
