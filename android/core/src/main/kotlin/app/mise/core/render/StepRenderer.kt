package app.mise.core.render

import app.mise.core.model.Ingredient
import app.mise.core.model.Recipe
import app.mise.core.model.Step
import app.mise.core.scale.AmountRenderer
import app.mise.core.units.UnitMode

/** A piece of rendered step text. */
sealed interface StepSegment {
    val text: String

    data class Text(override val text: String) : StepSegment

    /** A tappable ingredient chip. [nameOnly] for `{0001:name}` re-references. */
    data class IngredientRef(
        val ingredientId: String,
        override val text: String,
        val nameOnly: Boolean,
        val spoken: String,
    ) : StepSegment

    /** A placeholder that doesn't resolve: unknown id or malformed. Rendered as a warning chip. */
    data class Missing(val raw: String, val ingredientId: String?) : StepSegment {
        override val text: String get() = "⚠ $raw"
    }
}

object StepRenderer {
    private val BRACES = Regex("""\{([^{}]*)\}""")
    private val VALID = Regex("""^(\d{4})(:name)?$""")

    fun render(content: String, recipe: Recipe, factor: Double, mode: UnitMode): List<StepSegment> {
        val out = mutableListOf<StepSegment>()
        var last = 0
        for (m in BRACES.findAll(content)) {
            if (m.range.first > last) out += StepSegment.Text(content.substring(last, m.range.first))
            out += resolve(m.value, m.groupValues[1].trim(), recipe, factor, mode)
            last = m.range.last + 1
        }
        if (last < content.length) out += StepSegment.Text(content.substring(last))
        return out
    }

    fun render(step: Step, recipe: Recipe, factor: Double, mode: UnitMode) =
        render(step.content, recipe, factor, mode)

    fun plainText(content: String, recipe: Recipe, factor: Double, mode: UnitMode): String =
        render(content, recipe, factor, mode).joinToString("") { it.text }

    /** Screen-reader text: units spelled out, warning chips described. */
    fun spokenText(content: String, recipe: Recipe, factor: Double, mode: UnitMode): String =
        render(content, recipe, factor, mode).joinToString("") {
            when (it) {
                is StepSegment.IngredientRef -> it.spoken
                is StepSegment.Missing -> "unknown ingredient"
                is StepSegment.Text -> it.text
            }
        }

    /** Ingredients a step measures out (full `{id}` references), in order of first mention. */
    fun ingredientsUsed(step: Step, recipe: Recipe): List<Ingredient> =
        BRACES.findAll(step.content)
            .mapNotNull { VALID.matchEntire(it.groupValues[1].trim()) }
            .filter { it.groupValues[2].isEmpty() }
            .mapNotNull { recipe.ingredient(it.groupValues[1]) }
            .distinctBy { it.id }
            .toList()

    private fun resolve(raw: String, inner: String, recipe: Recipe, factor: Double, mode: UnitMode): StepSegment {
        val m = VALID.matchEntire(inner) ?: return StepSegment.Missing(raw, null)
        val id = m.groupValues[1]
        val ing = recipe.ingredient(id) ?: return StepSegment.Missing(raw, id)
        val nameOnly = m.groupValues[2].isNotEmpty()
        return if (nameOnly) {
            StepSegment.IngredientRef(id, ing.shortName, true, ing.shortName)
        } else {
            StepSegment.IngredientRef(
                id,
                AmountRenderer.chipText(ing, factor, mode),
                false,
                AmountRenderer.chipSpoken(ing, factor, mode),
            )
        }
    }
}
