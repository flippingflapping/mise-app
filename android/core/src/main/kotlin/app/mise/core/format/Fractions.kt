package app.mise.core.format

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/** Kitchen-friendly number formatting: fraction glyphs, not decimals. */
object Fractions {
    private const val EPS = 1e-6

    /** Fractional parts that have a glyph. */
    private val GLYPHS: List<Pair<Double, String>> = listOf(
        1.0 / 8 to "⅛", 1.0 / 4 to "¼", 1.0 / 3 to "⅓", 3.0 / 8 to "⅜", 1.0 / 2 to "½",
        5.0 / 8 to "⅝", 2.0 / 3 to "⅔", 3.0 / 4 to "¾", 7.0 / 8 to "⅞",
    )

    val HALVES = listOf(0.5)
    val QUARTERS = listOf(0.25, 0.5, 0.75)
    val QUARTERS_AND_THIRDS = listOf(0.25, 1.0 / 3, 0.5, 2.0 / 3, 0.75)
    val EIGHTHS_AND_THIRDS = listOf(0.125, 0.25, 1.0 / 3, 0.375, 0.5, 0.625, 2.0 / 3, 0.75, 0.875)

    /**
     * Snap [value] to the nearest whole number plus one of [fractions] (0 and 1 are always allowed).
     * A positive value never snaps to zero: it becomes the smallest allowed step instead.
     */
    fun snap(value: Double, fractions: List<Double>): Double {
        if (value <= 0) return 0.0
        val whole = floor(value)
        val frac = value - whole
        val candidates = listOf(0.0, 1.0) + fractions
        val best = candidates.minBy { abs(it - frac) }
        val snapped = whole + best
        if (snapped <= EPS) return fractions.minOrNull() ?: 1.0
        return snapped
    }

    /** Round to the nearest multiple of [step]; positive values never round to zero. */
    fun roundTo(value: Double, step: Double): Double {
        if (value <= 0) return 0.0
        val r = round(value / step) * step
        return if (r <= EPS) step else r
    }

    /**
     * "1½", "⅓", "12", "1.1". Fractional parts within [tolerance] of a glyph use the glyph;
     * anything else falls back to a short decimal.
     */
    fun format(value: Double, tolerance: Double = 0.02): String {
        if (abs(value - round(value)) < EPS) return round(value).toLong().toString()
        val whole = floor(value)
        val frac = value - whole
        val glyph = GLYPHS.minBy { abs(it.first - frac) }
        if (abs(glyph.first - frac) <= tolerance) {
            return if (whole == 0.0) glyph.second else "${whole.toLong()}${glyph.second}"
        }
        if (frac > 1 - tolerance) return (whole.toLong() + 1).toString()
        if (frac < tolerance) return whole.toLong().toString()
        return decimal(value)
    }

    /** Up to two decimals, trailing zeros trimmed: 1.10 → "1.1". */
    fun decimal(value: Double): String {
        val s = String.format(java.util.Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }
}

/** Words for screen readers: "1½" → "1 and a half". */
object SpokenNumbers {
    private val WORDS = mapOf(
        '⅛' to "an eighth", '¼' to "a quarter", '⅓' to "a third", '⅜' to "three eighths", '½' to "a half",
        '⅝' to "five eighths", '⅔' to "two thirds", '¾' to "three quarters", '⅞' to "seven eighths",
    )

    fun speak(formatted: String): String {
        val glyph = formatted.lastOrNull()?.takeIf { it in WORDS } ?: return formatted.replace("–", " to ")
        val whole = formatted.dropLast(1)
        val words = WORDS.getValue(glyph)
        return if (whole.isEmpty()) words else "$whole and $words"
    }
}
