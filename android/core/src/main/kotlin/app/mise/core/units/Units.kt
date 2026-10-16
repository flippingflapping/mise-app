package app.mise.core.units

import app.mise.core.model.MeasureUnit

/** How amounts are displayed. Original is as authored (with sensible promotion after scaling). */
enum class UnitMode(val label: String) { ORIGINAL("Original"), METRIC("Metric"), US("US") }

enum class Dimension { MASS, VOLUME, PINCH, COUNT }

/** Groups of units that belong together; conversions only happen between families. */
enum class UnitFamily { METRIC_MASS, US_MASS, METRIC_VOLUME, US_CUPS, FL_OZ, SPOONS, PINCH, COUNT }

object Units {
    /** Grams per unit for mass, millilitres per unit for volume. */
    fun toBase(unit: MeasureUnit): Double = when (unit) {
        MeasureUnit.G -> 1.0
        MeasureUnit.KG -> 1000.0
        MeasureUnit.OZ -> 28.349523125
        MeasureUnit.LB -> 453.59237
        MeasureUnit.ML -> 1.0
        MeasureUnit.L -> 1000.0
        MeasureUnit.TSP -> 4.92892159375
        MeasureUnit.TBSP -> 14.78676478125
        MeasureUnit.CUP -> 236.5882365
        MeasureUnit.FL_OZ -> 29.5735295625
        MeasureUnit.PINCH -> 1.0
    }

    fun dimension(unit: MeasureUnit?): Dimension = when (unit) {
        MeasureUnit.G, MeasureUnit.KG, MeasureUnit.OZ, MeasureUnit.LB -> Dimension.MASS
        MeasureUnit.ML, MeasureUnit.L, MeasureUnit.TSP, MeasureUnit.TBSP,
        MeasureUnit.CUP, MeasureUnit.FL_OZ -> Dimension.VOLUME
        MeasureUnit.PINCH -> Dimension.PINCH
        null -> Dimension.COUNT
    }

    fun family(unit: MeasureUnit?): UnitFamily = when (unit) {
        MeasureUnit.G, MeasureUnit.KG -> UnitFamily.METRIC_MASS
        MeasureUnit.OZ, MeasureUnit.LB -> UnitFamily.US_MASS
        MeasureUnit.ML, MeasureUnit.L -> UnitFamily.METRIC_VOLUME
        MeasureUnit.CUP -> UnitFamily.US_CUPS
        MeasureUnit.FL_OZ -> UnitFamily.FL_OZ
        MeasureUnit.TSP, MeasureUnit.TBSP -> UnitFamily.SPOONS
        MeasureUnit.PINCH -> UnitFamily.PINCH
        null -> UnitFamily.COUNT
    }

    /** Units a person might type an amount in, for "scale by what I have". */
    fun unitsFor(dimension: Dimension): List<MeasureUnit?> = when (dimension) {
        Dimension.MASS -> listOf(MeasureUnit.G, MeasureUnit.KG, MeasureUnit.OZ, MeasureUnit.LB)
        Dimension.VOLUME -> listOf(
            MeasureUnit.ML, MeasureUnit.L, MeasureUnit.TSP, MeasureUnit.TBSP, MeasureUnit.CUP, MeasureUnit.FL_OZ,
        )
        Dimension.PINCH -> listOf(MeasureUnit.PINCH)
        Dimension.COUNT -> listOf(null)
    }

    /** Display label; [value] decides singular/plural where English cares. */
    fun label(unit: MeasureUnit, value: Double = 1.0): String {
        val plural = value > 1.0 + 1e-9
        return when (unit) {
            MeasureUnit.G -> "g"
            MeasureUnit.KG -> "kg"
            MeasureUnit.ML -> "ml"
            MeasureUnit.L -> "l"
            MeasureUnit.TSP -> "tsp"
            MeasureUnit.TBSP -> "tbsp"
            MeasureUnit.CUP -> if (plural) "cups" else "cup"
            MeasureUnit.FL_OZ -> "fl oz"
            MeasureUnit.OZ -> "oz"
            MeasureUnit.LB -> "lb"
            MeasureUnit.PINCH -> if (plural) "pinches" else "pinch"
        }
    }

    /** Spoken label for screen readers ("tablespoons", not "tbsp"). */
    fun spokenLabel(unit: MeasureUnit, value: Double = 1.0): String {
        val plural = value > 1.0 + 1e-9
        fun p(s: String, pl: String = s + "s") = if (plural) pl else s
        return when (unit) {
            MeasureUnit.G -> p("gram")
            MeasureUnit.KG -> p("kilogram")
            MeasureUnit.ML -> p("millilitre")
            MeasureUnit.L -> p("litre")
            MeasureUnit.TSP -> p("teaspoon")
            MeasureUnit.TBSP -> p("tablespoon")
            MeasureUnit.CUP -> p("cup")
            MeasureUnit.FL_OZ -> p("fluid ounce")
            MeasureUnit.OZ -> p("ounce")
            MeasureUnit.LB -> p("pound")
            MeasureUnit.PINCH -> p("pinch", "pinches")
        }
    }
}
