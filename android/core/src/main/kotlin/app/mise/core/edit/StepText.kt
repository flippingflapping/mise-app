package app.mise.core.edit

import app.mise.core.model.Ingredient
import app.mise.core.model.Recipe

/**
 * Step wording as people can edit it. Stored steps say `{0003}` (amount + name, scales) and
 * `{0003:name}` (just the name); in the editor those read as `{minced garlic}` and `[minced garlic]`.
 * Labels are the ingredient's short name when that's unique, else its full name, else the name with
 * its group ("minced ginger (Marinade)"), else `#0003`.
 */
object StepText {
    private val STORED = Regex("""\{(\d{4})(:name)?}""")
    private val EDITABLE = Regex("""\{([^{}\[\]]+)}|\[([^{}\[\]]+)]""")

    /** The label each ingredient gets in the editor. */
    fun labels(recipe: Recipe): Map<String, String> {
        val ings = recipe.ingredients
        fun unique(f: (Ingredient) -> String): (Ingredient) -> Boolean {
            val counts = ings.groupingBy { f(it).lowercase() }.eachCount()
            return { counts[f(it).lowercase()] == 1 }
        }
        val byShort = unique { it.shortName }
        val byName = unique { it.name }
        val withGroup: (Ingredient) -> String = { i -> i.group?.takeIf(String::isNotBlank)?.let { "${i.name} ($it)" } ?: i.name }
        val byGroup = unique(withGroup)
        return ings.associate { i ->
            i.id to when {
                byShort(i) -> i.shortName
                byName(i) -> i.name
                byGroup(i) -> withGroup(i)
                else -> "#${i.id}"
            }
        }
    }

    fun toEditable(content: String, recipe: Recipe): String {
        val labels = labels(recipe)
        return STORED.replace(content) { m ->
            val label = labels[m.groupValues[1]] ?: "#${m.groupValues[1]}"
            if (m.groupValues[2].isEmpty()) "{$label}" else "[$label]"
        }
    }

    sealed interface Parsed {
        data class Ok(val content: String) : Parsed
        data class Problem(val message: String) : Parsed
    }

    /** Back to the stored form. Names are matched loosely (case, short or full name) when that's unambiguous. */
    fun fromEditable(text: String, recipe: Recipe): Parsed {
        val labels = labels(recipe)
        // Exact labels first (they're unique by construction), then looser names when unambiguous.
        val exact = labels.entries.associate { (id, label) -> label.lowercase() to id } +
            recipe.ingredients.associate { "#${it.id}" to it.id }
        val loose = HashMap<String, MutableSet<String>>()
        fun add(key: String, id: String) = loose.getOrPut(key.trim().lowercase()) { mutableSetOf() }.add(id)
        recipe.ingredients.forEach { i -> add(i.shortName, i.id); add(i.name, i.id) }
        fun lookup(label: String): Set<String> = exact[label.lowercase()]?.let(::setOf) ?: loose[label.lowercase()].orEmpty()
        val unknown = mutableListOf<String>()
        val ambiguous = mutableListOf<String>()
        val out = EDITABLE.replace(text) { m ->
            val amount = m.groupValues[1].isNotEmpty()
            val label = (if (amount) m.groupValues[1] else m.groupValues[2]).trim()
            val ids = lookup(label)
            when (ids.size) {
                1 -> if (amount) "{${ids.single()}}" else "{${ids.single()}:name}"
                0 -> { unknown += label; m.value }
                else -> { ambiguous += label; m.value }
            }
        }.trim()
        val choices = labels.values.joinToString(", ") { "“$it”" }
        return when {
            out.isEmpty() -> Parsed.Problem("A step needs some words.")
            unknown.isNotEmpty() -> Parsed.Problem("No ingredient called ${unknown.joinToString { "“$it”" }}. Use one of: $choices.")
            ambiguous.isNotEmpty() -> Parsed.Problem("${ambiguous.joinToString { "“$it”" }} matches more than one ingredient. Use one of: $choices.")
            else -> Parsed.Ok(out)
        }
    }

    /** Steps (1-based numbers) whose wording uses [ingredientId]. */
    fun stepsUsing(recipe: Recipe, ingredientId: String): List<Int> =
        recipe.steps.mapIndexedNotNull { i, s -> if ("{$ingredientId}" in s.content || "{$ingredientId:name}" in s.content) i + 1 else null }

    /** [content] with [ingredientId]'s placeholders replaced by its plain short name (for removing it). */
    fun withoutIngredient(content: String, ing: Ingredient): String =
        content.replace("{${ing.id}}", ing.shortName).replace("{${ing.id}:name}", ing.shortName)
}
