package app.mise.core.scale

import app.mise.core.format.Fractions
import app.mise.core.model.Ingredient
import app.mise.core.model.MeasureUnit
import app.mise.core.units.Units
import kotlin.math.ceil
import kotlin.math.floor

/** Servings stepper: ½-steps at or below 2 servings, whole steps above. Never below ½. */
object Servings {
    const val MIN = 0.5
    private const val EPS = 1e-6

    fun increment(current: Double): Double =
        if (current < 2 - EPS) floor(current * 2 + EPS) / 2 + 0.5
        else floor(current + EPS) + 1

    fun decrement(current: Double): Double {
        val next = if (current <= 2 + EPS) ceil(current * 2 - EPS) / 2 - 0.5 else ceil(current - EPS) - 1
        return next.coerceAtLeast(MIN)
    }

    fun canDecrement(current: Double): Boolean = current > MIN + EPS

    fun factor(baseServings: Double, targetServings: Double): Double = targetServings / baseServings

    /** "2", "1½", "1.6". */
    fun format(servings: Double): String = Fractions.format(servings)
}

/** "I've only got 400 g lamb" → a new scale factor for the whole recipe. */
object ScaleByIngredient {
    fun canScaleBy(ingredient: Ingredient): Boolean =
        ingredient.amount != null && ingredient.amount > 0 && ingredient.scales

    /** Units the user may type their amount in (same dimension as the ingredient). */
    fun inputUnits(ingredient: Ingredient): List<MeasureUnit?> =
        Units.unitsFor(Units.dimension(ingredient.unit))

    /** The scale factor that makes [ingredient] equal [have] [haveUnit]; null if impossible. */
    fun factor(ingredient: Ingredient, have: Double, haveUnit: MeasureUnit?): Double? {
        if (!canScaleBy(ingredient) || have <= 0) return null
        if (Units.dimension(haveUnit) != Units.dimension(ingredient.unit)) return null
        val authored = ingredient.amount!! * (ingredient.unit?.let(Units::toBase) ?: 1.0)
        val typed = have * (haveUnit?.let(Units::toBase) ?: 1.0)
        return typed / authored
    }
}
