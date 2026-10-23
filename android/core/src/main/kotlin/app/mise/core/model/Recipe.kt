@file:OptIn(ExperimentalSerializationApi::class)

package app.mise.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kotlin mirror of `schema/recipe.schema.json` (schema_version 1).
 *
 * Decoding is lenient (see [RecipeJson.lenient]): unknown fields are ignored and unknown enum
 * values fall back to their defaults, so a newer recipe file never crashes an older app.
 * Encoding (see [RecipeJson.canonical]) produces schema-valid JSON.
 */
@Serializable
data class Recipe(
    @EncodeDefault @SerialName("schema_version") val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val title: String,
    val description: String? = null,
    @SerialName("yield") val recipeYield: RecipeYield,
    val times: Times? = null,
    @SerialName("advance_prep") val advancePrep: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @EncodeDefault val ingredients: List<Ingredient> = emptyList(),
    @EncodeDefault val steps: List<Step> = emptyList(),
    val notes: String? = null,
    @SerialName("cook_notes") val cookNotes: List<String> = emptyList(),
    @SerialName("related_recipes") val relatedRecipes: List<String> = emptyList(),
    val source: Source? = null,
    @SerialName("source_text_sha256") val sourceTextSha256: String? = null,
    @SerialName("review_flags") val reviewFlags: List<ReviewFlag> = emptyList(),
    @EncodeDefault val status: RecipeStatus = RecipeStatus.NEEDS_REVIEW,
    val image: String? = null,
    val user: UserData? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun ingredient(id: String): Ingredient? = ingredients.firstOrNull { it.id == id }

    /** Ingredients in display order, grouped by [Ingredient.group] (null group first). */
    fun ingredientGroups(): List<Pair<String?, List<Ingredient>>> =
        ingredients.groupBy { it.group?.takeIf(String::isNotBlank) }.toList()

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class RecipeYield(
    @SerialName("base_servings") val baseServings: Double,
    val label: String? = null,
)

@Serializable
data class Times(
    @SerialName("prep_minutes") val prepMinutes: Int? = null,
    @SerialName("cook_minutes") val cookMinutes: Int? = null,
    @SerialName("total_minutes") val totalMinutes: Int? = null,
)

@Serializable
data class Ingredient(
    val id: String,
    val group: String? = null,
    val name: String,
    @EncodeDefault @SerialName("short_name") val shortName: String = name,
    val prep: String? = null,
    /** null = "to taste" / unquantified. Always encoded (required-but-nullable in the schema). */
    @EncodeDefault val amount: Double? = null,
    @SerialName("amount_max") val amountMax: Double? = null,
    /** null = countable; the counting noun lives in [name]. Always encoded. */
    @EncodeDefault val unit: MeasureUnit? = null,
    val optional: Boolean = false,
    val substitutes: String? = null,
    val scales: Boolean = true,
    val aisle: String? = null,
)

@Serializable
enum class MeasureUnit {
    @SerialName("g") G,
    @SerialName("kg") KG,
    @SerialName("ml") ML,
    @SerialName("l") L,
    @SerialName("tsp") TSP,
    @SerialName("tbsp") TBSP,
    @SerialName("cup") CUP,
    @SerialName("fl_oz") FL_OZ,
    @SerialName("oz") OZ,
    @SerialName("lb") LB,
    @SerialName("pinch") PINCH,
}

@Serializable
data class Step(
    val id: String,
    val title: String,
    val content: String,
    @SerialName("timer_seconds") val timerSeconds: Int? = null,
    @SerialName("timer_max_seconds") val timerMaxSeconds: Int? = null,
    val heat: Heat? = null,
)

@Serializable
enum class Heat(val label: String) {
    @SerialName("low") LOW("Low"),
    @SerialName("medium_low") MEDIUM_LOW("Medium-low"),
    @SerialName("medium") MEDIUM("Medium"),
    @SerialName("medium_high") MEDIUM_HIGH("Medium-high"),
    @SerialName("high") HIGH("High"),
}

@Serializable
enum class RecipeStatus {
    @SerialName("needs_review") NEEDS_REVIEW,
    @SerialName("ready") READY,
}

@Serializable
data class Source(
    @EncodeDefault val type: SourceType = SourceType.OTHER,
    val title: String? = null,
    val author: String? = null,
    val page: String? = null,
    val url: String? = null,
    @SerialName("original_filename") val originalFilename: String? = null,
)

@Serializable
enum class SourceType {
    @SerialName("book") BOOK,
    @SerialName("web") WEB,
    @SerialName("family") FAMILY,
    @SerialName("other") OTHER,
}

@Serializable
data class ReviewFlag(val path: String, val message: String)

/** Annotations merged in by the server from `<id>.user.json`. */
@Serializable
data class UserData(
    val favorite: Boolean? = null,
    val reviewed: Boolean? = null,
    @SerialName("last_cooked") val lastCooked: String? = null,
    @SerialName("cook_log") val cookLog: List<String>? = null,
    val edits: RecipeEdits? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/**
 * Corrections made in the app (schema `$defs.edits`). Applied on top of the converted recipe by
 * [app.mise.core.edit.RecipeEditor], never written into it, so they can be undone. Ingredients and
 * steps are keyed by id; each entry is the complete editable state of that item.
 */
@Serializable
data class RecipeEdits(
    val title: String? = null,
    @SerialName("yield") val recipeYield: RecipeYield? = null,
    val ingredients: Map<String, IngredientEdit> = emptyMap(),
    val steps: Map<String, StepEdit> = emptyMap(),
    /** Paths of review flags already checked in the guided review. */
    @SerialName("checked_flags") val checkedFlags: List<String> = emptyList(),
    /** Ingredients I added (ids from 9001 up, so they never clash with converted ones). */
    @SerialName("added_ingredients") val addedIngredients: List<Ingredient> = emptyList(),
    /** Ids of converted ingredients I removed. */
    @SerialName("removed_ingredients") val removedIngredients: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = title == null && recipeYield == null && ingredients.isEmpty() && steps.isEmpty() && checkedFlags.isEmpty() &&
            addedIngredients.isEmpty() && removedIngredients.isEmpty()
}

@Serializable
data class IngredientEdit(
    val name: String,
    val prep: String? = null,
    val amount: Double? = null,
    @SerialName("amount_max") val amountMax: Double? = null,
    val unit: MeasureUnit? = null,
    val optional: Boolean = false,
)

@Serializable
data class StepEdit(
    val title: String,
    @SerialName("timer_seconds") val timerSeconds: Int? = null,
    @SerialName("timer_max_seconds") val timerMaxSeconds: Int? = null,
    /** New wording (stored form, with `{0003}` placeholders); null = as converted. */
    val content: String? = null,
)

/** Body of `PUT /recipes/{id}/annotations`, and the sidecar format. */
@Serializable
data class Annotations(
    val favorite: Boolean? = null,
    val reviewed: Boolean? = null,
    @SerialName("cook_notes") val cookNotes: List<String>? = null,
    @SerialName("last_cooked") val lastCooked: String? = null,
    @SerialName("cook_log") val cookLog: List<String>? = null,
    /** Replaces all earlier edits when sent; an empty object undoes them. null = leave as they are. */
    val edits: RecipeEdits? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** One row of `GET /recipes`. */
@Serializable
data class RecipeSummary(
    val id: String,
    val title: String,
    val tags: List<String> = emptyList(),
    val status: RecipeStatus = RecipeStatus.NEEDS_REVIEW,
    @SerialName("updated_at") val updatedAt: String? = null,
    val etag: String? = null,
)
