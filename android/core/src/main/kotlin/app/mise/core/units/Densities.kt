package app.mise.core.units

import app.mise.core.model.Ingredient
import app.mise.core.model.MeasureUnit

/**
 * Typical weights of solids measured by the cup, so Metric mode can show "2 cups flour" as grams
 * rather than millilitres. Only used when the recipe doesn't state its own weight
 * ([Ingredient.equivalent] wins). Liquids and anything not listed stay in ml.
 *
 * Grams per level US cup (236.6 ml), spooned in, from common baking references. A cup of flour
 * really does vary by 20 % with how it's scooped, so treat the result as a sensible guide.
 */
object Densities {
    private val GRAMS_PER_CUP: Map<String, Double> = mapOf(
        // flours and starches
        "flour" to 125.0, "all-purpose flour" to 125.0, "plain flour" to 125.0, "self-raising flour" to 125.0,
        "self-rising flour" to 125.0, "bread flour" to 130.0, "strong flour" to 130.0, "cake flour" to 115.0,
        "whole wheat flour" to 120.0, "wholemeal flour" to 120.0, "spelt flour" to 110.0, "rye flour" to 105.0,
        "almond flour" to 96.0, "ground almonds" to 96.0, "almond meal" to 96.0, "chickpea flour" to 90.0,
        "gram flour" to 90.0, "besan" to 90.0, "rice flour" to 158.0, "coconut flour" to 112.0,
        "cornstarch" to 128.0, "cornflour" to 128.0, "semolina" to 167.0, "cornmeal" to 138.0, "polenta" to 160.0,
        // sugars
        "sugar" to 200.0, "white sugar" to 200.0, "granulated sugar" to 200.0, "caster sugar" to 200.0,
        "cane sugar" to 200.0, "brown sugar" to 213.0, "light brown sugar" to 213.0, "dark brown sugar" to 213.0,
        "coconut sugar" to 150.0, "icing sugar" to 120.0, "powdered sugar" to 120.0, "confectioners sugar" to 120.0,
        "confectioners' sugar" to 120.0,
        // grains, pulses, pasta
        "rice" to 185.0, "basmati rice" to 185.0, "jasmine rice" to 185.0, "arborio rice" to 200.0,
        "quinoa" to 170.0, "couscous" to 175.0, "bulgur" to 160.0, "oats" to 90.0, "rolled oats" to 90.0,
        "porridge oats" to 90.0, "lentils" to 190.0, "red lentils" to 190.0, "split peas" to 200.0,
        "chickpeas" to 165.0, "breadcrumbs" to 110.0, "panko" to 50.0,
        // fats and dairy solids
        "butter" to 227.0, "vegan butter" to 227.0, "margarine" to 227.0, "shortening" to 190.0,
        "coconut oil" to 218.0, "peanut butter" to 270.0, "yogurt" to 245.0, "yoghurt" to 245.0,
        "greek yogurt" to 227.0, "sour cream" to 230.0, "cream cheese" to 232.0, "ricotta" to 246.0,
        "grated parmesan" to 100.0, "parmesan" to 100.0, "grated cheese" to 113.0, "shredded cheese" to 113.0,
        "cheddar" to 113.0, "mozzarella" to 113.0,
        // baking extras, nuts and dried fruit
        "cocoa powder" to 85.0, "cocoa" to 85.0, "chocolate chips" to 170.0, "desiccated coconut" to 85.0,
        "shredded coconut" to 85.0, "raisins" to 150.0, "sultanas" to 150.0, "dates" to 150.0,
        "almonds" to 142.0, "walnuts" to 113.0, "pecans" to 113.0, "cashews" to 140.0, "peanuts" to 145.0,
        "hazelnuts" to 135.0, "pistachios" to 125.0, "pine nuts" to 135.0, "sesame seeds" to 145.0,
        "chia seeds" to 170.0, "pumpkin seeds" to 130.0, "sunflower seeds" to 140.0,
        // vegetables commonly measured by the cup
        "frozen peas" to 140.0, "peas" to 145.0, "corn kernels" to 165.0, "spinach" to 30.0,
    )

    /** Words that mean the ingredient is poured, whatever else its name says ("coconut milk", "almond milk"). */
    private val LIQUID = listOf(
        "milk", "water", "stock", "broth", "juice", "vinegar", "wine", "beer", "sauce", "syrup", "honey",
        "molasses", "treacle", "cream", "buttermilk", "liquid", "extract", "oil",
    )
    /** Liquid words that are part of a solid's own name. */
    private val SOLID_PHRASES = setOf("sour cream", "cream cheese", "coconut oil", "peanut butter")

    private fun phrase(p: String) = Regex("""(?<![\p{L}])${Regex.escape(p)}(e?s)?(?![\p{L}])""")
    private val PATTERNS = GRAMS_PER_CUP.keys.associateWith(::phrase)
    private val LIQUID_PATTERNS = LIQUID.map(::phrase)

    /**
     * Grams per millilitre for [ingredient], or null when it's a liquid or not one we know. The most
     * specific match wins ("brown sugar" over "sugar").
     */
    fun gramsPerMl(ingredient: Ingredient): Double? {
        val text = (ingredient.name + " / " + ingredient.shortName).lowercase()
        val solid = PATTERNS.entries.filter { (_, re) -> re.containsMatchIn(text) }.maxByOrNull { it.key.length } ?: return null
        val liquid = LIQUID_PATTERNS.any { it.containsMatchIn(text) }
        if (liquid && solid.key !in SOLID_PHRASES) return null
        return GRAMS_PER_CUP.getValue(solid.key) / Units.toBase(MeasureUnit.CUP)
    }
}
