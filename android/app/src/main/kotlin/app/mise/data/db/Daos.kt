package app.mise.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A library row: recipe columns joined with my annotations. */
data class LibraryRow(
    val id: String,
    val title: String,
    val tags: String,
    val ingredientNames: String,
    val status: String,
    val favorite: Boolean?,
    val reviewed: Boolean?,
    val lastCooked: String?,
)

@Dao
interface RecipeDao {
    @Query(
        """
        SELECT r.id, r.title, r.tags, r.ingredient_names AS ingredientNames, r.status,
               a.favorite, a.reviewed, a.last_cooked AS lastCooked
        FROM recipes r LEFT JOIN annotations a ON a.recipe_id = r.id
        ORDER BY r.title COLLATE NOCASE
        """,
    )
    fun library(): Flow<List<LibraryRow>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    fun observe(id: String): Flow<RecipeEntity?>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun get(id: String): RecipeEntity?

    @Query("SELECT id FROM recipes")
    suspend fun allIds(): List<String>

    @Query("SELECT * FROM recipes")
    suspend fun all(): List<RecipeEntity>

    @Upsert
    suspend fun upsert(entity: RecipeEntity)

    @Query("DELETE FROM recipes WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT COUNT(*) FROM recipes")
    suspend fun count(): Int
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE recipe_id = :id")
    fun observe(id: String): Flow<AnnotationEntity?>

    @Query("SELECT * FROM annotations WHERE recipe_id = :id")
    suspend fun get(id: String): AnnotationEntity?

    @Query("SELECT * FROM annotations WHERE dirty = 1")
    suspend fun dirty(): List<AnnotationEntity>

    @Upsert
    suspend fun upsert(entity: AnnotationEntity)

    /** Read-modify-write in one transaction. */
    @Transaction
    suspend fun update(id: String, change: (AnnotationEntity) -> AnnotationEntity) {
        upsert(change(get(id) ?: AnnotationEntity(recipeId = id)))
    }
}

@Dao
interface ViewStateDao {
    @Query("SELECT * FROM view_state WHERE recipe_id = :id")
    fun observe(id: String): Flow<ViewStateEntity?>

    @Query("SELECT * FROM view_state WHERE recipe_id = :id")
    suspend fun get(id: String): ViewStateEntity?

    @Upsert
    suspend fun upsert(entity: ViewStateEntity)

    @Transaction
    suspend fun update(id: String, change: (ViewStateEntity) -> ViewStateEntity) {
        upsert(change(get(id) ?: ViewStateEntity(recipeId = id)))
    }
}

@Dao
interface SourceTextDao {
    @Query("SELECT * FROM source_texts WHERE recipe_id = :id")
    suspend fun get(id: String): SourceTextEntity?

    @Upsert
    suspend fun upsert(entity: SourceTextEntity)

    @Query("DELETE FROM source_texts WHERE recipe_id IN (:ids)")
    suspend fun delete(ids: List<String>)
}
