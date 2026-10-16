package app.mise.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** One cached recipe: the full JSON plus a few columns for listing and searching. */
@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    /** Canonical recipe JSON (already migrated to the current schema version). */
    val json: String,
    val title: String,
    /** Tags joined with '\n'. */
    val tags: String,
    /** Ingredient names joined with '\n' (for search). */
    @ColumnInfo(name = "ingredient_names") val ingredientNames: String,
    val status: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String?,
    val etag: String?,
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
    /** Image file name from the recipe, if any (added in DB v2). */
    val image: String? = null,
)

/** Cached raw source text for "View original" (added in DB v2). */
@Entity(tableName = "source_texts")
data class SourceTextEntity(
    @PrimaryKey @ColumnInfo(name = "recipe_id") val recipeId: String,
    val text: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

/**
 * My annotations. Written locally first (works offline), pushed to sources that support them.
 * Null fields mean "no local value — use what the source says".
 */
@Entity(tableName = "annotations")
data class AnnotationEntity(
    @PrimaryKey @ColumnInfo(name = "recipe_id") val recipeId: String,
    val favorite: Boolean = false,
    val reviewed: Boolean? = null,
    /** JSON array of strings, or null to use the recipe's own cook_notes. */
    @ColumnInfo(name = "cook_notes_json") val cookNotesJson: String? = null,
    @ColumnInfo(name = "last_cooked") val lastCooked: String? = null,
    /** JSON array of ISO dates. */
    @ColumnInfo(name = "cook_log_json") val cookLogJson: String = "[]",
    /** Review-flag banner dismissed on this phone (not synced). */
    @ColumnInfo(name = "flags_dismissed") val flagsDismissed: Boolean = false,
    /** Has local changes not yet pushed to the source. */
    val dirty: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
)

/** Per-recipe view state: remembered servings, unit mode and mise-en-place ticks. */
@Entity(tableName = "view_state")
data class ViewStateEntity(
    @PrimaryKey @ColumnInfo(name = "recipe_id") val recipeId: String,
    /** Scale factor relative to the recipe's base servings; null = 1. */
    val factor: Double? = null,
    @ColumnInfo(name = "unit_mode") val unitMode: String? = null,
    /** Checked ingredient ids joined with ','. */
    val checked: String = "",
)
