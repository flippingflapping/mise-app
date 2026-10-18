package app.mise.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RecipeEntity::class, AnnotationEntity::class, ViewStateEntity::class, SourceTextEntity::class,
        ShoppingListEntity::class, ShoppingListRecipeEntity::class, ShoppingItemEntity::class,
        FeastEntity::class, FeastDishEntity::class, FeastDoneEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class MiseDatabase : RoomDatabase() {
    abstract fun recipes(): RecipeDao
    abstract fun annotations(): AnnotationDao
    abstract fun viewState(): ViewStateDao
    abstract fun sourceTexts(): SourceTextDao
    abstract fun shopping(): ShoppingDao
    abstract fun feasts(): FeastDao

    companion object {
        const val NAME = "mise.db"
        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}

/** v2: cache original text for "View original" offline, and remember the recipe image name. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recipes ADD COLUMN image TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS source_texts (" +
                "recipe_id TEXT NOT NULL, text TEXT NOT NULL, fetched_at INTEGER NOT NULL, PRIMARY KEY(recipe_id))",
        )
    }
}

/** v3: shopping lists and feast plans (statements copied from the exported schema 3.json). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `shopping_lists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `created_at` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `shopping_list_recipes` (`list_id` INTEGER NOT NULL, `recipe_id` TEXT NOT NULL, `title` TEXT NOT NULL, `servings` REAL NOT NULL, PRIMARY KEY(`list_id`, `recipe_id`), FOREIGN KEY(`list_id`) REFERENCES `shopping_lists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE TABLE IF NOT EXISTS `shopping_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `list_id` INTEGER NOT NULL, `label` TEXT NOT NULL, `aisle` TEXT NOT NULL, `recipes` TEXT NOT NULL, `checked` INTEGER NOT NULL, `extra` INTEGER NOT NULL, `position` INTEGER NOT NULL, FOREIGN KEY(`list_id`) REFERENCES `shopping_lists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_shopping_items_list_id` ON `shopping_items` (`list_id`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `feasts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `serve_at` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `remind` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `feast_dishes` (`feast_id` INTEGER NOT NULL, `recipe_id` TEXT NOT NULL, `factor` REAL NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`feast_id`, `recipe_id`), FOREIGN KEY(`feast_id`) REFERENCES `feasts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE TABLE IF NOT EXISTS `feast_done` (`feast_id` INTEGER NOT NULL, `task_id` TEXT NOT NULL, PRIMARY KEY(`feast_id`, `task_id`), FOREIGN KEY(`feast_id`) REFERENCES `feasts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    }
}
