package app.mise

import app.mise.core.model.Recipe
import app.mise.core.model.RecipeJson
import java.io.File

object TestFixtures {
    val repoRoot = File(System.getProperty("mise.repoRoot") ?: error("mise.repoRoot not set"))
    val lambJson: String get() = repoRoot.resolve("fixtures/expected/sri-lankan-lamb-curry-for-kothu.json").readText()
    val lambRaw: String get() = repoRoot.resolve("fixtures/raw/lamb-curry-for-kothu.txt").readText()
    val lamb: Recipe get() = RecipeJson.decodeRecipe(lambJson)
}
