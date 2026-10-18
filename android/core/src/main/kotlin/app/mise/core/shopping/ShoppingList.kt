package app.mise.core.shopping

import app.mise.core.model.Ingredient
import app.mise.core.model.Recipe
import app.mise.core.scale.AmountRenderer
import app.mise.core.units.Dimension
import app.mise.core.units.UnitMode
import app.mise.core.units.Units

/** A recipe going into a shopping list, at a chosen scale. */
data class ShoppingSource(val recipe: Recipe, val factor: Double = 1.0)

/** One line of a combined shopping list. */
data class ShoppingLine(
    /** Normalised name used to merge ingredients across recipes. */
    val key: String,
    val name: String,
    /** "4 tsp", "1.1 kg", "¾ tsp + 1 pinch", "10–12". Empty when only "to taste". */
    val amount: String,
    /** Some (or all) of it is unmeasured ("salt to taste"). */
    val toTaste: Boolean,
    val aisle: String,
    val optional: Boolean,
    /** Recipe titles this line comes from. */
    val recipes: List<String>,
) {
    val label: String
        get() = listOf(amount, name).filter { it.isNotBlank() }.joinToString(" ") +
            (if (toTaste) (if (amount.isEmpty()) " (to taste)" else " (+ to taste)") else "") +
            if (optional) " (optional)" else ""
}

/**
 * Turns several (scaled) recipes into one shopping list: the same ingredient used in several places
 * is added up (1 tsp + 1 tbsp ginger → 4 tsp), amounts are rounded the same way as in the recipe
 * view, and lines are grouped by aisle in a sensible walking order.
 */
object ShoppingList {
    /** Aisles in shopping order. */
    val AISLES = listOf(
        "Fruit & veg", "Meat & fish", "Dairy & eggs", "Bakery", "Herbs & spices", "Cupboard", "Frozen", "Other",
    )

    fun build(sources: List<ShoppingSource>, mode: UnitMode): List<ShoppingLine> {
        data class Use(val ing: Ingredient, val factor: Double, val recipe: String)

        val groups = LinkedHashMap<String, MutableList<Use>>()
        for (src in sources) {
            for (ing in src.recipe.ingredients) {
                val key = normalizeName(ing.name)
                if (key in SKIP) continue
                groups.getOrPut(key) { mutableListOf() } += Use(ing, src.factor, src.recipe.title)
            }
        }
        return groups.map { (key, uses) ->
            val byDim = uses.filter { it.ing.amount != null }.groupBy { Units.dimension(it.ing.unit) }
            val parts = listOf(Dimension.MASS, Dimension.VOLUME, Dimension.COUNT, Dimension.PINCH).mapNotNull { dim ->
                val list = byDim[dim] ?: return@mapNotNull null
                val unit = list.first().ing.unit // the first-seen unit decides the family to show
                fun base(u: Use, value: Double) = value * (if (u.ing.scales) u.factor else 1.0) *
                    (u.ing.unit?.let(Units::toBase) ?: 1.0)
                val min = list.sumOf { base(it, it.ing.amount!!) }
                val hasRange = list.any { it.ing.amountMax != null }
                val max = if (hasRange) list.sumOf { base(it, it.ing.amountMax ?: it.ing.amount!!) } else null
                val toUnit = unit?.let(Units::toBase) ?: 1.0
                val total = Ingredient(
                    id = "0000", name = key, shortName = key,
                    amount = min / toUnit, amountMax = max?.let { it / toUnit }, unit = unit,
                )
                AmountRenderer.render(total, 1.0, mode, normalize = true).text
            }
            val first = uses.first().ing
            ShoppingLine(
                key = key,
                name = displayName(first.name),
                amount = parts.joinToString(" + "),
                toTaste = uses.any { it.ing.amount == null },
                aisle = first.aisle?.let(::matchAisle) ?: aisleFor(key),
                optional = uses.all { it.ing.optional },
                recipes = uses.map { it.recipe }.distinct(),
            )
        }.sortedWith(compareBy<ShoppingLine>({ AISLES.indexOf(it.aisle).let { i -> if (i < 0) AISLES.size else i } }, { it.key }))
    }

    /** Things nobody buys. */
    private val SKIP = setOf("water", "hot water", "cold water", "boiling water", "ice")

    /**
     * "coconut oil, for browning" → "coconut oil"; "onion (about 1 medium)" → "onion";
     * "Roasted Curry Powder" → "roasted curry powder".
     */
    fun normalizeName(name: String): String =
        name.substringBefore(',').replace(Regex("\\([^)]*\\)"), " ").lowercase().replace(Regex("\\s+"), " ").trim()

    fun displayName(name: String): String =
        name.substringBefore(',').replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()

    private fun matchAisle(label: String): String =
        AISLES.firstOrNull { it.equals(label.trim(), ignoreCase = true) } ?: label.trim().replaceFirstChar { it.uppercase() }

    // First match wins, so specific phrases come before the words they contain.
    private val RULES: List<Pair<String, List<String>>> = listOf(
        "Cupboard" to listOf("coconut milk", "coconut cream", "coconut oil", "stock", "canned", "tinned", "tin of", "passata", "tomato paste", "tomato puree"),
        "Herbs & spices" to listOf("curry powder", "chilli powder", "chili powder", "chilli flakes", "garam masala", "black pepper", "white pepper", "peppercorn", "bay leaf", "bay leaves", "coriander seed", "fennel seed", "mustard seed", "ground "),
        "Fruit & veg" to listOf("garlic", "ginger", "curry leaves", "lemongrass", "pandan", "fresh ", "onion", "shallot", "tomato", "chilli", "chillies", "chili", "lemon", "lime", "potato", "carrot", "pepper", "spinach", "leek", "celery", "mushroom", "coriander", "cilantro", "dill", "basil", "mint", "parsley", "apple", "banana", "avocado", "cucumber", "aubergine", "eggplant", "courgette", "zucchini", "cabbage", "cauliflower", "broccoli", "green beans", "peas", "spring onion", "scallion", "kale", "lettuce", "squash", "pumpkin", "orange", "mango"),
        "Meat & fish" to listOf("lamb", "beef", "pork", "chicken", "mutton", "goat", "fish", "prawn", "shrimp", "salmon", "tuna", "cod", "mince", "bacon", "sausage", "duck", "turkey", "crab", "squid", "anchov"),
        "Dairy & eggs" to listOf("milk", "butter", "cream", "cheese", "yoghurt", "yogurt", "egg", "ghee", "paneer", "crème", "creme fraiche"),
        "Bakery" to listOf("bread", "roti", "naan", "wrap", "tortilla", "bun", "pitta", "pita", "paratha"),
        "Frozen" to listOf("frozen"),
        "Herbs & spices" to listOf("powder", "cumin", "turmeric", "cardamom", "clove", "cinnamon", "paprika", "masala", "seeds", "salt", "nutmeg", "saffron", "star anise", "fenugreek", "oregano", "thyme", "rosemary", "spice"),
        "Cupboard" to listOf("oil", "flour", "sugar", "rice", "pasta", "noodle", "vinegar", "sauce", "honey", "lentil", "dal", "dhal", "nuts", "breadcrumb", "beans", "chickpea", "syrup", "yeast", "baking"),
    )

    fun aisleFor(normalizedName: String): String {
        val n = " $normalizedName "
        for ((aisle, words) in RULES) if (words.any { n.contains(if (it.endsWith(' ')) " $it" else it) }) return aisle
        return "Other"
    }

    /** Plain-text version for sharing (WhatsApp, notes, …). */
    fun asText(title: String, lines: List<Pair<ShoppingLine, Boolean>>, extras: List<Pair<String, Boolean>> = emptyList()): String =
        buildString {
            appendLine(title)
            for ((aisle, items) in lines.groupBy { it.first.aisle }) {
                appendLine()
                appendLine(aisle)
                items.forEach { (line, checked) -> appendLine("${if (checked) "☑" else "☐"} ${line.label}") }
            }
            if (extras.isNotEmpty()) {
                appendLine()
                appendLine("Also")
                extras.forEach { (text, checked) -> appendLine("${if (checked) "☑" else "☐"} $text") }
            }
        }.trimEnd()
}
