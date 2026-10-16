package app.mise.core.search

import java.text.Normalizer

/** What library search looks at for one recipe. */
data class SearchDoc(
    val title: String,
    val tags: List<String>,
    val ingredientNames: List<String>,
)

/**
 * Every query word must appear in the title, a tag, or an ingredient (prefix-insensitive substring,
 * case- and accent-insensitive). Title hits rank above tag hits above ingredient hits.
 */
object RecipeSearch {
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

    fun tokens(query: String): List<String> = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** 0 = no match; higher is better. */
    fun score(doc: SearchDoc, query: String): Int {
        val toks = tokens(query)
        if (toks.isEmpty()) return 1
        val title = normalize(doc.title)
        val tags = doc.tags.map(::normalize)
        val ings = doc.ingredientNames.map(::normalize)
        var score = 0
        for (t in toks) {
            score += when {
                title.contains(t) -> 3
                tags.any { it.contains(t) } -> 2
                ings.any { it.contains(t) } -> 1
                else -> return 0
            }
        }
        return score
    }

    fun matches(doc: SearchDoc, query: String): Boolean = score(doc, query) > 0
}
