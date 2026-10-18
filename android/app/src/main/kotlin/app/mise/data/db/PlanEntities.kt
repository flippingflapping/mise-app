package app.mise.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A saved shopping list. Items are a snapshot taken when it was made (edits to recipes don't change it). */
@Entity(tableName = "shopping_lists")
data class ShoppingListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "shopping_list_recipes",
    primaryKeys = ["list_id", "recipe_id"],
    foreignKeys = [ForeignKey(ShoppingListEntity::class, ["id"], ["list_id"], onDelete = ForeignKey.CASCADE)],
)
data class ShoppingListRecipeEntity(
    @ColumnInfo(name = "list_id") val listId: Long,
    @ColumnInfo(name = "recipe_id") val recipeId: String,
    val title: String,
    val servings: Double,
)

@Entity(
    tableName = "shopping_items",
    foreignKeys = [ForeignKey(ShoppingListEntity::class, ["id"], ["list_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("list_id")],
)
data class ShoppingItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "list_id") val listId: Long,
    /** "4 tsp minced ginger", or free text for items you added yourself. */
    val label: String,
    val aisle: String,
    /** Recipe titles joined with '\n'; empty for your own items. */
    val recipes: String = "",
    val checked: Boolean = false,
    /** Added by hand rather than from a recipe. */
    val extra: Boolean = false,
    val position: Int = 0,
)

/** A planned meal: dishes plus a serve time. The timeline is recomputed from the recipes. */
@Entity(tableName = "feasts")
data class FeastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "serve_at") val serveAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Notify at the start of each block. */
    val remind: Boolean = true,
    /** A [app.mise.core.feast.FeastStyle] name: CALM (prep first) or PRO (multitask). */
    @ColumnInfo(defaultValue = "CALM") val style: String = "CALM",
)

@Entity(
    tableName = "feast_dishes",
    primaryKeys = ["feast_id", "recipe_id"],
    foreignKeys = [ForeignKey(FeastEntity::class, ["id"], ["feast_id"], onDelete = ForeignKey.CASCADE)],
)
data class FeastDishEntity(
    @ColumnInfo(name = "feast_id") val feastId: Long,
    @ColumnInfo(name = "recipe_id") val recipeId: String,
    val factor: Double,
    val position: Int = 0,
)

/** Tasks ticked off in a feast (task ids are "<recipeId>/<stepId>" or "<recipeId>/mise"). */
@Entity(
    tableName = "feast_done",
    primaryKeys = ["feast_id", "task_id"],
    foreignKeys = [ForeignKey(FeastEntity::class, ["id"], ["feast_id"], onDelete = ForeignKey.CASCADE)],
)
data class FeastDoneEntity(
    @ColumnInfo(name = "feast_id") val feastId: Long,
    @ColumnInfo(name = "task_id") val taskId: String,
)

data class ShoppingListSummary(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val total: Int,
    val done: Int,
)

@Dao
interface ShoppingDao {
    @Query(
        """
        SELECT l.id, l.name, l.created_at AS createdAt,
               COUNT(i.id) AS total, COALESCE(SUM(CASE WHEN i.checked THEN 1 ELSE 0 END), 0) AS done
        FROM shopping_lists l LEFT JOIN shopping_items i ON i.list_id = l.id
        GROUP BY l.id ORDER BY l.created_at DESC
        """,
    )
    fun lists(): Flow<List<ShoppingListSummary>>

    @Query("SELECT * FROM shopping_lists WHERE id = :id")
    fun list(id: Long): Flow<ShoppingListEntity?>

    @Query("SELECT * FROM shopping_items WHERE list_id = :id ORDER BY extra, position, id")
    fun items(id: Long): Flow<List<ShoppingItemEntity>>

    @Query("SELECT * FROM shopping_list_recipes WHERE list_id = :id")
    fun recipes(id: Long): Flow<List<ShoppingListRecipeEntity>>

    @Insert suspend fun insertList(list: ShoppingListEntity): Long
    @Insert suspend fun insertRecipes(rows: List<ShoppingListRecipeEntity>)
    @Insert suspend fun insertItems(rows: List<ShoppingItemEntity>)
    @Insert suspend fun insertItem(row: ShoppingItemEntity): Long

    @Query("UPDATE shopping_items SET checked = :checked WHERE id = :itemId")
    suspend fun setChecked(itemId: Long, checked: Boolean)

    @Query("UPDATE shopping_items SET checked = 0 WHERE list_id = :listId")
    suspend fun uncheckAll(listId: Long)

    @Query("DELETE FROM shopping_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    @Query("UPDATE shopping_lists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM shopping_lists WHERE id = :id")
    suspend fun deleteList(id: Long)

    @Transaction
    suspend fun create(list: ShoppingListEntity, recipes: List<ShoppingListRecipeEntity>, items: List<ShoppingItemEntity>): Long {
        val id = insertList(list)
        insertRecipes(recipes.map { it.copy(listId = id) })
        insertItems(items.map { it.copy(listId = id) })
        return id
    }
}

data class FeastSummary(
    val id: Long,
    val name: String,
    val serveAt: Long,
    val dishes: Int,
)

@Dao
interface FeastDao {
    @Query(
        """
        SELECT f.id, f.name, f.serve_at AS serveAt, COUNT(d.recipe_id) AS dishes
        FROM feasts f LEFT JOIN feast_dishes d ON d.feast_id = f.id
        GROUP BY f.id ORDER BY f.serve_at DESC
        """,
    )
    fun feasts(): Flow<List<FeastSummary>>

    @Query("SELECT * FROM feasts WHERE id = :id")
    fun feast(id: Long): Flow<FeastEntity?>

    @Query("SELECT * FROM feasts WHERE id = :id")
    suspend fun get(id: Long): FeastEntity?

    @Query("SELECT * FROM feasts WHERE remind = 1 AND serve_at > :after")
    suspend fun upcomingWithReminders(after: Long): List<FeastEntity>

    @Query("SELECT * FROM feast_dishes WHERE feast_id = :id ORDER BY position")
    fun dishes(id: Long): Flow<List<FeastDishEntity>>

    @Query("SELECT * FROM feast_dishes WHERE feast_id = :id ORDER BY position")
    suspend fun dishesNow(id: Long): List<FeastDishEntity>

    @Query("SELECT task_id FROM feast_done WHERE feast_id = :id")
    fun done(id: Long): Flow<List<String>>

    @Query("SELECT task_id FROM feast_done WHERE feast_id = :id")
    suspend fun doneNow(id: Long): List<String>

    @Insert suspend fun insertFeast(feast: FeastEntity): Long
    @Insert suspend fun insertDishes(rows: List<FeastDishEntity>)
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) suspend fun markDone(row: FeastDoneEntity)

    @Query("DELETE FROM feast_done WHERE feast_id = :feastId AND task_id = :taskId")
    suspend fun unmarkDone(feastId: Long, taskId: String)

    @Query("UPDATE feasts SET serve_at = :serveAt WHERE id = :id")
    suspend fun setServeAt(id: Long, serveAt: Long)

    @Query("UPDATE feasts SET remind = :remind WHERE id = :id")
    suspend fun setRemind(id: Long, remind: Boolean)

    @Query("UPDATE feasts SET style = :style WHERE id = :id")
    suspend fun setStyle(id: Long, style: String)

    /** The style of the most recently made plan: new plans start with it. */
    @Query("SELECT style FROM feasts ORDER BY created_at DESC, id DESC LIMIT 1")
    suspend fun lastStyle(): String?

    @Query("DELETE FROM feasts WHERE id = :id")
    suspend fun delete(id: Long)

    @Transaction
    suspend fun create(feast: FeastEntity, dishes: List<FeastDishEntity>): Long {
        val id = insertFeast(feast)
        insertDishes(dishes.map { it.copy(feastId = id) })
        return id
    }
}
