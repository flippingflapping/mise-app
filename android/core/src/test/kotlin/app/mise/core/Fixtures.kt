package app.mise.core

import app.mise.core.model.Recipe
import app.mise.core.model.RecipeJson
import java.io.File

/** Access to the shared contract files at the repo root (schema/, fixtures/). */
object Fixtures {
    val repoRoot: File = File(
        System.getProperty("mise.repoRoot")
            ?: error("mise.repoRoot system property not set (run via Gradle)"),
    )
    val schemaFile: File get() = repoRoot.resolve("schema/recipe.schema.json")
    val expected: List<File>
        get() = repoRoot.resolve("fixtures/expected").listFiles { f -> f.extension == "json" }!!.sorted()

    fun text(path: String): String = repoRoot.resolve(path).readText()

    val lambCurry: Recipe by lazy {
        RecipeJson.decodeRecipe(text("fixtures/expected/sri-lankan-lamb-curry-for-kothu.json"))
    }
}
