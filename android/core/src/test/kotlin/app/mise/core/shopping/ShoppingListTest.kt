package app.mise.core.shopping

import app.mise.core.Fixtures
import app.mise.core.model.Ingredient
import app.mise.core.model.MeasureUnit
import app.mise.core.model.Recipe
import app.mise.core.model.RecipeYield
import app.mise.core.model.Step
import app.mise.core.table
import app.mise.core.units.UnitMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShoppingListTest {
    private val curry = Fixtures.lambCurry
    private fun line(lines: List<ShoppingLine>, key: String) = lines.single { it.key == key }

    /** A small second dish (test data only) that shares ingredients with the curry. */
    private val dal = Recipe(
        id = "dal", title = "Dal", recipeYield = RecipeYield(4.0),
        ingredients = listOf(
            Ingredient("0001", name = "red lentils", shortName = "lentils", amount = 250.0, unit = MeasureUnit.G),
            Ingredient("0002", name = "onion (1 large)", shortName = "onion", amount = 200.0, unit = MeasureUnit.G),
            Ingredient("0003", name = "ground turmeric", shortName = "turmeric", amount = 1.0, unit = MeasureUnit.TSP),
            Ingredient("0004", name = "water", shortName = "water", amount = 1.0, unit = MeasureUnit.L),
            Ingredient("0005", name = "salt", shortName = "salt", amount = null, unit = null),
            Ingredient("0006", name = "curry leaves", shortName = "curry leaves", amount = 8.0, unit = null),
        ),
        steps = listOf(Step("s1", "Cook", "Cook {0001} with {0002}, {0003}, {0004}, {0005} and {0006}.")),
    )

    @Test fun mergesTheSameIngredientWithinARecipe() {
        val lines = ShoppingList.build(listOf(ShoppingSource(curry)), UnitMode.ORIGINAL)
        assertThat(line(lines, "minced ginger").amount).isEqualTo("4 tsp") // 1 tsp + 1 tbsp
        assertThat(line(lines, "coconut oil").amount).isEqualTo("3 tbsp") // 2 + 1, split back together
        assertThat(line(lines, "salt").amount).isEqualTo("¾ tsp + 1 pinch")
        assertThat(line(lines, "ground turmeric").amount).isEqualTo("1 tsp")
        assertThat(line(lines, "curry leaves").amount).isEqualTo("10–12")
        assertThat(line(lines, "green chillies").optional).isTrue()
        assertThat(line(lines, "onion").name).isEqualTo("onion")
        assertThat(lines.map { it.key }).doesNotContain("coconut oil, for browning")
    }

    @Test fun combinesAcrossRecipesAndScales() {
        val lines = ShoppingList.build(listOf(ShoppingSource(curry, 2.0), ShoppingSource(dal, 0.5)), UnitMode.ORIGINAL)
        assertThat(line(lines, "lamb neck or shoulder fillets").amount).isEqualTo("1 kg")
        assertThat(line(lines, "onion").amount).isEqualTo("400 g") // 300 g + 100 g
        assertThat(line(lines, "onion").recipes).containsExactly("Sri Lankan Lamb Curry for Kothu", "Dal").inOrder()
        assertThat(line(lines, "ground turmeric").amount).isEqualTo("2½ tsp") // 2 × 1 tsp + ½ tsp
        assertThat(line(lines, "curry leaves").amount).isEqualTo("24–28") // 20–24 + 4
        assertThat(line(lines, "salt").label).isEqualTo("1½ tsp + 2 pinches salt (+ to taste)")
        assertThat(lines.map { it.key }).doesNotContain("water") // nobody buys tap water
    }

    @Test fun unitModesApply() {
        val lines = ShoppingList.build(listOf(ShoppingSource(curry, 2.0)), UnitMode.US)
        assertThat(line(lines, "lamb neck or shoulder fillets").amount).isEqualTo("2¼ lb")
    }

    @Test fun groupedByAisleInShoppingOrder() {
        val lines = ShoppingList.build(listOf(ShoppingSource(curry), ShoppingSource(dal)), UnitMode.ORIGINAL)
        val order = lines.map { ShoppingList.AISLES.indexOf(it.aisle) }
        assertThat(order).isInOrder()
        assertThat(line(lines, "lamb neck or shoulder fillets").aisle).isEqualTo("Meat & fish")
    }

    @Test fun aisles() = table(
        listOf(
            "minced ginger" to "Fruit & veg", "ground ginger" to "Herbs & spices", "green chillies" to "Fruit & veg",
            "chilli powder" to "Herbs & spices", "coconut milk" to "Cupboard", "coconut oil" to "Cupboard",
            "cloves" to "Herbs & spices", "garlic cloves" to "Fruit & veg", "stock or water" to "Cupboard",
            "lamb neck or shoulder fillets" to "Meat & fish", "eggs" to "Dairy & eggs", "eggplant" to "Fruit & veg",
            "bunch of coriander" to "Fruit & veg", "coriander seeds" to "Herbs & spices", "salt" to "Herbs & spices",
            "roasted curry powder" to "Herbs & spices", "naan" to "Bakery", "frozen peas" to "Fruit & veg",
            "plain flour" to "Cupboard", "something odd" to "Other",
        ),
        { it.first },
    ) { (name, aisle) -> aisle to ShoppingList.aisleFor(name) }

    @Test fun explicitAisleWins() {
        val r = dal.copy(ingredients = listOf(dal.ingredients[0].copy(aisle = "world foods")))
        assertThat(ShoppingList.build(listOf(ShoppingSource(r)), UnitMode.ORIGINAL).single().aisle).isEqualTo("World foods")
    }

    @Test fun shareText() {
        val lines = ShoppingList.build(listOf(ShoppingSource(dal)), UnitMode.ORIGINAL)
        val text = ShoppingList.asText("Saturday", lines.map { it to (it.key == "salt") }, listOf("kitchen roll" to false))
        assertThat(text).startsWith("Saturday\n\nFruit & veg\n☐ 8 curry leaves\n☐ 200 g onion")
        assertThat(text).contains("☑ salt (to taste)")
        assertThat(text).endsWith("Also\n☐ kitchen roll")
    }
}
