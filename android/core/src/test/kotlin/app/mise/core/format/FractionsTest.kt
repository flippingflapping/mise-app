package app.mise.core.format

import app.mise.core.table
import org.junit.Test

class FractionsTest {
    @Test fun formatsGlyphsNotDecimals() = table(
        listOf(
            1.5 to "1½", 0.5 to "½", 0.25 to "¼", 0.75 to "¾", 0.125 to "⅛", 2.0 to "2", 500.0 to "500",
            1.0 / 3 to "⅓", 2.0 / 3 to "⅔", 0.33 to "⅓", 0.34 to "⅓", 0.66 to "⅔", 0.67 to "⅔",
            1.333 to "1⅓", 2.6667 to "2⅔", 0.375 to "⅜", 0.625 to "⅝", 0.875 to "⅞",
            1.1 to "1.1", 1.35 to "1⅓", 1.45 to "1.45", 0.3 to "0.3", 2.999 to "3", 4.004 to "4",
        ),
        { "format(${it.first})" },
    ) { (v, exp) -> exp to Fractions.format(v) }

    @Test fun snapsNearThirds() = table(
        listOf(
            0.3 to 1.0 / 3, 0.29 to 0.25, 0.36 to 1.0 / 3, 0.6 to 2.0 / 3, 0.7 to 2.0 / 3, 0.73 to 0.75,
            1.97 to 2.0, 0.04 to 0.25, 2.12 to 2.0,
        ),
        { "snap(${it.first})" },
    ) { (v, exp) -> exp to Fractions.snap(v, Fractions.QUARTERS_AND_THIRDS) }

    @Test fun spokenNumbers() = table(
        listOf("1½" to "1 and a half", "¼" to "a quarter", "10–12" to "10 to 12", "2⅔" to "2 and two thirds"),
        { it.first },
    ) { (s, exp) -> exp to SpokenNumbers.speak(s) }
}
