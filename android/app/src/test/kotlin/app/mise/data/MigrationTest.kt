package app.mise.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mise.data.db.MIGRATION_1_2
import app.mise.data.db.MiseDatabase
import app.mise.data.db.SourceTextEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Room schema evolution: v1 (exported schema in app/schemas) → v2 keeps every row. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MiseDatabase::class.java)

    @Test
    fun migrate1To2KeepsDataAndAddsSourceTexts() = runTest {
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO recipes (id, source_kind, json, title, tags, ingredient_names, status, updated_at, etag, cached_at) " +
                    "VALUES ('r1', 'LAN', '{}', 'Curry', 'curry', 'lamb', 'READY', '2026-10-15T00:00:00Z', '\"e1\"', 5)",
            )
            execSQL(
                "INSERT INTO annotations (recipe_id, favorite, reviewed, cook_notes_json, last_cooked, cook_log_json, " +
                    "flags_dismissed, dirty, updated_at) VALUES ('r1', 1, 1, '[\"less salt\"]', '2026-10-01', '[\"2026-10-01\"]', 0, 1, 9)",
            )
            execSQL("INSERT INTO view_state (recipe_id, factor, unit_mode, checked) VALUES ('r1', 1.5, 'US', '0001')")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)
        db.query("SELECT title, etag, image FROM recipes WHERE id = 'r1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Curry")
            assertThat(c.getString(1)).isEqualTo("\"e1\"")
            assertThat(c.isNull(2)).isTrue()
        }
        db.query("SELECT favorite, cook_notes_json, dirty FROM annotations WHERE recipe_id = 'r1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(1)
            assertThat(c.getString(1)).isEqualTo("[\"less salt\"]")
            assertThat(c.getInt(2)).isEqualTo(1)
        }
        db.close()

        // And the migrated file opens with the real Room database class.
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), MiseDatabase::class.java, dbName)
            .addMigrations(*MiseDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            assertThat(room.recipes().library().first().single().title).isEqualTo("Curry")
            room.sourceTexts().upsert(SourceTextEntity("r1", "raw text", 1))
            assertThat(room.sourceTexts().get("r1")?.text).isEqualTo("raw text")
            assertThat(room.viewState().get("r1")?.factor).isEqualTo(1.5)
        } finally {
            room.close()
        }
    }
}
