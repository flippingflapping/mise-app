package app.mise.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Upgrades older recipe JSON to the current [Recipe.CURRENT_SCHEMA_VERSION], one version at a time.
 *
 * Version 0 is the pre-release draft shape (no `schema_version`, `servings` as a bare number,
 * `cook_notes` as one string). It exists so the migration path is exercised by tests from day one;
 * add a `migrateNtoN+1` step here whenever the schema version is bumped.
 *
 * A file with a *newer* version than this app knows is returned untouched and parsed leniently.
 */
object RecipeMigrations {
    fun versionOf(obj: JsonObject): Int =
        (obj["schema_version"] as? JsonPrimitive)?.intOrNull ?: 0

    fun isNewerThanSupported(obj: JsonObject): Boolean = versionOf(obj) > Recipe.CURRENT_SCHEMA_VERSION

    fun migrate(input: JsonObject): JsonObject {
        var obj = input
        var version = versionOf(obj)
        while (version < Recipe.CURRENT_SCHEMA_VERSION) {
            obj = when (version) {
                0 -> migrate0to1(obj)
                else -> error("No migration from schema_version $version")
            }
            version = versionOf(obj)
        }
        return obj
    }

    private fun migrate0to1(obj: JsonObject): JsonObject = buildJsonObject {
        for ((k, v) in obj) {
            when (k) {
                "servings" -> {} // folded into yield below
                "cook_notes" -> put(k, if (v is JsonPrimitive) splitNotes(v) else v)
                else -> put(k, v)
            }
        }
        if ("yield" !in obj) {
            val servings = (obj["servings"] as? JsonPrimitive) ?: JsonPrimitive(1)
            put("yield", buildJsonObject { put("base_servings", servings) })
        }
        put("schema_version", JsonPrimitive(1))
    }

    private fun splitNotes(v: JsonElement): JsonArray {
        val text = v.jsonPrimitive.content
        return JsonArray(text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive))
    }
}
