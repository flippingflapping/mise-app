package app.mise.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RecipeEntity::class, AnnotationEntity::class, ViewStateEntity::class, SourceTextEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class MiseDatabase : RoomDatabase() {
    abstract fun recipes(): RecipeDao
    abstract fun annotations(): AnnotationDao
    abstract fun viewState(): ViewStateDao
    abstract fun sourceTexts(): SourceTextDao

    companion object {
        const val NAME = "mise.db"
        val MIGRATIONS = arrayOf(MIGRATION_1_2)
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
