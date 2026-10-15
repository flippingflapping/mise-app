package app.mise.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object RecipeJson {
    /** For reading anything from the outside world. */
    val lenient: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = false
    }

    /** For writing: schema-valid output, nulls only where the schema requires them. */
    val canonical: Json = Json {
        encodeDefaults = false
        explicitNulls = true
        prettyPrint = false
    }

    /**
     * Parse a recipe, migrating older schema versions first.
     * @throws kotlinx.serialization.SerializationException if it isn't a recipe at all.
     */
    fun decodeRecipe(text: String): Recipe = decodeRecipe(lenient.parseToJsonElement(text))

    fun decodeRecipe(element: JsonElement): Recipe {
        val migrated = RecipeMigrations.migrate(element.jsonObject)
        return lenient.decodeFromJsonElement(Recipe.serializer(), migrated)
    }

    fun encodeRecipe(recipe: Recipe): String = canonical.encodeToString(Recipe.serializer(), recipe)

    fun encodeRecipeElement(recipe: Recipe): JsonObject =
        canonical.encodeToJsonElement(Recipe.serializer(), recipe).jsonObject
}
