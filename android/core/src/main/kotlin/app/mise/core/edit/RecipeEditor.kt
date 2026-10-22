package app.mise.core.edit

import app.mise.core.format.Fractions
import app.mise.core.model.Ingredient
import app.mise.core.model.IngredientEdit
import app.mise.core.model.Recipe
import app.mise.core.model.RecipeEdits
import app.mise.core.model.ReviewFlag
import app.mise.core.model.Step
import app.mise.core.model.StepEdit

/** What a review flag is about, so the guided review can show the right editor. */
sealed interface FlagTarget {
    data class IngredientTarget(val ingredient: Ingredient) : FlagTarget
    data class StepTarget(val step: Step, val index: Int) : FlagTarget
    data object Servings : FlagTarget
    data object Title : FlagTarget
    /** Something with no editor here (times, notes, step wording…): read it, then confirm. */
    data object Other : FlagTarget
}

/**
 * Applies the app's recipe edits on top of a converted recipe, and checks them. Mirrors
 * `mise_shared/edits.py` on the PC; keep the two in step.
 */
object RecipeEditor {
    /** [recipe] with [edits] applied. Edits for ingredients or steps that no longer exist are ignored. */
    fun apply(recipe: Recipe, edits: RecipeEdits?): Recipe {
        if (edits == null || edits.isEmpty) return recipe
        return recipe.copy(
            title = edits.title?.takeIf { it.isNotBlank() } ?: recipe.title,
            recipeYield = edits.recipeYield ?: recipe.recipeYield,
            ingredients = recipe.ingredients.map { ing ->
                val e = edits.ingredients[ing.id] ?: return@map ing
                ing.copy(
                    name = e.name,
                    // The short name (used inside step text) followed the name, so keep following it.
                    shortName = if (ing.shortName == ing.name) e.name else ing.shortName,
                    prep = e.prep?.takeIf { it.isNotBlank() },
                    amount = e.amount,
                    amountMax = e.amountMax,
                    unit = e.unit,
                    optional = e.optional,
                )
            },
            steps = recipe.steps.map { step ->
                val e = edits.steps[step.id] ?: return@map step
                step.copy(title = e.title, timerSeconds = e.timerSeconds, timerMaxSeconds = e.timerMaxSeconds)
            },
        )
    }

    fun editOf(ing: Ingredient) = IngredientEdit(ing.name, ing.prep, ing.amount, ing.amountMax, ing.unit, ing.optional)
    fun editOf(step: Step) = StepEdit(step.title, step.timerSeconds, step.timerMaxSeconds)

    /** Plain-English problems with an ingredient edit (empty = fine). */
    fun problems(e: IngredientEdit): List<String> = buildList {
        if (e.name.isBlank()) add("Give the ingredient a name.")
        if (e.amount != null && e.amount <= 0) add("The amount must be more than 0 (leave it empty for “to taste”).")
        if (e.amountMax != null && e.amount == null) add("Enter the start of the range too, or clear the “to” amount.")
        if (e.amountMax != null && e.amount != null && e.amountMax < e.amount) add("The range must end above where it starts.")
    }

    fun problems(e: StepEdit): List<String> = buildList {
        if (e.title.isBlank()) add("Give the step a title.")
        if (e.timerSeconds != null && e.timerSeconds <= 0) add("The timer must be longer than 0.")
        if (e.timerMaxSeconds != null && e.timerSeconds == null) add("Enter the shortest time too, or clear the longest.")
        if (e.timerMaxSeconds != null && e.timerSeconds != null && e.timerMaxSeconds < e.timerSeconds) {
            add("The longest time must be at least the shortest.")
        }
    }

    fun servingsProblem(servings: Double?): String? =
        if (servings == null || servings <= 0) "Servings must be more than 0." else null

    private val PATH = Regex("""^(ingredients|steps)\[(\d+)]""")

    /** Which item a review flag's path ("ingredients[8].amount", "yield.base_servings"…) points at, in [original]. */
    fun target(original: Recipe, flag: ReviewFlag): FlagTarget {
        val path = flag.path.trim()
        PATH.find(path)?.let { m ->
            val i = m.groupValues[2].toInt()
            return when (m.groupValues[1]) {
                "ingredients" -> original.ingredients.getOrNull(i)?.let { FlagTarget.IngredientTarget(it) }
                else -> original.steps.getOrNull(i)?.let { FlagTarget.StepTarget(it, i) }
            } ?: FlagTarget.Other
        }
        return when {
            path.startsWith("yield") -> FlagTarget.Servings
            path == "title" -> FlagTarget.Title
            else -> FlagTarget.Other
        }
    }

    /** Parses what people type for an amount: "2", "1.5", "1,5", "½", "1½", "1 1/2", "3/4". Blank = null. */
    fun parseAmount(text: String): Double? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { return it }
        val glyphs = mapOf('¼' to 0.25, '½' to 0.5, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '⅛' to 0.125,
            '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875)
        glyphs[t.last()]?.let { frac ->
            val whole = t.dropLast(1).trim()
            return if (whole.isEmpty()) frac else whole.toDoubleOrNull()?.plus(frac)
        }
        val m = Regex("""^(?:(\d+)\s+)?(\d+)\s*/\s*(\d+)$""").find(t) ?: return null
        val (whole, num, den) = m.destructured
        if (den.toInt() == 0) return null
        return (whole.toIntOrNull() ?: 0) + num.toDouble() / den.toDouble()
    }

    /** An amount as it should appear in a text field: "1½", "0.3". */
    fun formatAmount(value: Double?): String = value?.let { Fractions.format(it) } ?: ""
}
