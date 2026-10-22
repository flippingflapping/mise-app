package app.mise.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mise.TestFixtures
import app.mise.core.edit.RecipeEditor
import app.mise.core.model.RecipeEdits
import app.mise.core.model.RecipeStatus
import app.mise.core.model.RecipeYield
import app.mise.core.model.UserData
import app.mise.data.db.MiseDatabase
import app.mise.data.repo.RecipeRepository
import app.mise.data.repo.SourceProvider
import app.mise.data.repo.SyncResult
import app.mise.data.settings.SourceKind
import app.mise.data.source.SourceException
import app.mise.di.Clock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecipeRepositoryTest {
    private lateinit var db: MiseDatabase
    private lateinit var repo: RecipeRepository
    private val settings = FakeSettings()
    private var source = FakeSource()
    private var now = 1_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MiseDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = RecipeRepository(
            db, db.recipes(), db.annotations(), db.viewState(), db.sourceTexts(), settings,
            SourceProvider { source }, Clock { now }, CoroutineScope(Dispatchers.Unconfined),
        )
        source.put(TestFixtures.lamb, "\"v1\"")
    }

    @After fun tearDown() = db.close()

    private val id = "sri-lankan-lamb-curry-for-kothu"

    @Test fun firstSyncCachesEverything() = runTest {
        val r = repo.sync() as SyncResult.Success
        assertThat(r.added).isEqualTo(1)
        val lib = repo.library().first()
        assertThat(lib.single().title).isEqualTo("Sri Lankan Lamb Curry for Kothu")
        assertThat(lib.single().ingredientNames).contains("green cardamom pods")
        assertThat(lib.single().status).isEqualTo(RecipeStatus.NEEDS_REVIEW)
        val d = repo.detail(id).first()!!
        assertThat(d.recipe.ingredients).hasSize(26)
        assertThat(d.sourceKind).isEqualTo(SourceKind.LAN)
        assertThat(settings.state.value.lastSyncAt).isEqualTo(1_000L)
    }

    @Test fun unchangedEtagsAreNotRefetched() = runTest {
        repo.sync()
        repo.sync()
        assertThat(source.gets).containsExactly(id)
        source.put(TestFixtures.lamb.copy(title = "Renamed"), "\"v2\"")
        val r = repo.sync() as SyncResult.Success
        assertThat(r.updated).isEqualTo(1)
        assertThat(repo.library().first().single().title).isEqualTo("Renamed")
    }

    @Test fun recipesRemovedFromTheSourceAreRemovedLocally() = runTest {
        repo.sync()
        source.recipes.clear()
        val r = repo.sync() as SyncResult.Success
        assertThat(r.removed).isEqualTo(1)
        assertThat(repo.library().first()).isEmpty()
    }

    @Test fun offlineKeepsTheCacheAndReportsWhy() = runTest {
        repo.sync()
        source.unreachable = true
        val r = repo.sync() as SyncResult.Failed
        assertThat(r.reason).isEqualTo(SourceException.Reason.UNREACHABLE)
        assertThat(repo.library().first()).hasSize(1)
        assertThat(settings.state.value.lastSyncError).isEqualTo("Can't reach your PC")
        assertThat(repo.status.value.lastResult).isInstanceOf(SyncResult.Failed::class.java)
    }

    @Test fun annotationsAreSavedLocallyThenPushed() = runTest {
        repo.sync()
        source.unreachable = true
        repo.setFavorite(id, true)
        repo.setCookNotes(id, listOf(" less chilli ", ""))
        repo.markReviewed(id)
        val d = repo.detail(id).first()!!
        assertThat(d.favorite).isTrue()
        assertThat(d.cookNotes).containsExactly("less chilli")
        assertThat(d.status).isEqualTo(RecipeStatus.READY) // reviewed locally, even offline
        assertThat(db.annotations().dirty()).hasSize(1)
        assertThat(source.puts).isEmpty()

        source.unreachable = false
        val r = repo.sync() as SyncResult.Success
        assertThat(r.pushed).isEqualTo(1)
        val sent = source.puts.last().second
        assertThat(sent.favorite).isTrue()
        assertThat(sent.reviewed).isTrue()
        assertThat(sent.cookNotes).containsExactly("less chilli")
        assertThat(db.annotations().dirty()).isEmpty()
    }

    @Test fun serverAnnotationsSeedLocalButNeverOverwriteUnpushedChanges() = runTest {
        source.put(TestFixtures.lamb.copy(user = UserData(favorite = true, lastCooked = "2026-10-01", cookLog = listOf("2026-10-01"))), "\"v1\"")
        repo.sync()
        var d = repo.detail(id).first()!!
        assertThat(d.favorite).isTrue()
        assertThat(d.lastCooked).isEqualTo("2026-10-01")

        source.unreachable = true
        repo.setFavorite(id, false) // dirty, can't push
        source.unreachable = false
        source.put(TestFixtures.lamb.copy(user = UserData(favorite = true)), "\"v2\"")
        // push happens first in sync, so the server gets false; the refetch then agrees
        repo.sync()
        d = repo.detail(id).first()!!
        assertThat(source.puts.last().second.favorite).isFalse()
    }

    @Test fun madeThisLogsTheDay() = runTest {
        repo.sync()
        repo.madeThis(id, java.time.LocalDate.of(2026, 10, 15))
        repo.madeThis(id, java.time.LocalDate.of(2026, 10, 1))
        val d = repo.detail(id).first()!!
        assertThat(d.lastCooked).isEqualTo("2026-10-15")
        assertThat(d.cookLog).containsExactly("2026-10-01", "2026-10-15").inOrder()
        assertThat(repo.library().first().single().lastCooked).isEqualTo("2026-10-15")
    }

    @Test fun readOnlySourcesKeepAnnotationsOnThePhone() = runTest {
        source = FakeSource(SourceKind.FOLDER, supportsAnnotations = false).apply { put(TestFixtures.lamb, "1") }
        repo.sync()
        repo.setFavorite(id, true)
        val r = repo.sync() as SyncResult.Success
        assertThat(r.pushed).isEqualTo(0)
        assertThat(repo.detail(id).first()!!.favorite).isTrue()
    }

    @Test fun switchingSourceReplacesTheLibrary() = runTest {
        repo.sync()
        source = FakeSource(SourceKind.FOLDER, supportsAnnotations = false).apply {
            put(TestFixtures.lamb.copy(id = "other", title = "Other"), "1")
        }
        repo.sync()
        assertThat(repo.library().first().map { it.id }).containsExactly("other")
    }

    @Test fun viewStateIsRememberedPerRecipe() = runTest {
        repo.sync()
        repo.setFactor(id, 2.0)
        repo.setUnitMode(id, app.mise.core.units.UnitMode.US)
        repo.toggleChecked(id, "0001")
        repo.toggleChecked(id, "0002")
        repo.toggleChecked(id, "0001")
        val d = repo.detail(id).first()!!
        assertThat(d.factor).isEqualTo(2.0)
        assertThat(d.viewState?.unitMode).isEqualTo("US")
        assertThat(d.checked).containsExactly("0002")
    }

    @Test fun sourceTextIsCachedForOfflineUse() = runTest {
        repo.sync()
        assertThat(repo.sourceText(id).getOrThrow()).isEqualTo("raw text of $id")
        source.unreachable = true
        assertThat(repo.sourceText(id).getOrThrow()).isEqualTo("raw text of $id")
    }

    @Test fun syncIfStaleRespectsInterval() = runTest {
        assertThat(repo.syncIfStale()).isNotNull() // empty library: always
        now += 10_000
        assertThat(repo.syncIfStale()).isNull()
        now += 120_000
        assertThat(repo.syncIfStale()).isNotNull()
    }

    @Test fun editsApplyEverywhereAndSyncBothWays() = runTest {
        repo.sync()
        val cinnamon = TestFixtures.lamb.ingredient("0009")!!
        repo.editIngredient(id, "0009", RecipeEditor.editOf(cinnamon).copy(amount = 2.0, name = "cinnamon sticks"))
        repo.editServings(id, RecipeYield(4.0, "people"))

        val d = repo.detail(id).first()!!
        assertThat(d.original.ingredient("0009")!!.amount).isEqualTo(1.0) // the conversion is kept
        assertThat(d.recipe.ingredient("0009")!!.amount).isEqualTo(2.0)
        assertThat(d.recipe.recipeYield.baseServings).isEqualTo(4.0)
        assertThat(repo.recipesById(listOf(id))[id]!!.ingredient("0009")!!.name).isEqualTo("cinnamon sticks") // shopping & plans
        repo.sync()
        val pushed = source.puts.last().second.edits!!
        assertThat(pushed.ingredients["0009"]!!.amount).isEqualTo(2.0)

        // Undo one, then all: "{}" goes to the PC so it forgets them too.
        repo.editIngredient(id, "0009", null)
        assertThat(repo.detail(id).first()!!.recipe.ingredient("0009")!!.amount).isEqualTo(1.0)
        repo.resetEdits(id)
        repo.sync() // pushes run in the background; a sync sends anything still pending
        assertThat(source.puts.last().second.edits).isEqualTo(RecipeEdits())

        // Edits made on another phone arrive with the recipe.
        val fromPc = RecipeEdits(title = "Kothu curry")
        source.put(TestFixtures.lamb.copy(user = UserData(edits = fromPc)), "\"v2\"")
        repo.sync()
        assertThat(repo.detail(id).first()!!.recipe.title).isEqualTo("Kothu curry")
        assertThat(repo.library().first().single().title).isEqualTo("Kothu curry")
    }

    @Test fun checkingEveryFlagMarksTheRecipeReviewed() = runTest {
        repo.sync()
        val paths = TestFixtures.lamb.reviewFlags.map { it.path }
        repo.checkFlag(id, paths.single(), paths)
        val d = repo.detail(id).first()!!
        assertThat(d.edits.checkedFlags).containsExactlyElementsIn(paths)
        assertThat(d.status).isEqualTo(RecipeStatus.READY)
    }

    @Test fun aRejectedChangeDoesNotBlockSyncing() = runTest {
        repo.sync()
        source.rejecting += id
        repo.editTitle(id, "Mine")
        val result = repo.sync()
        assertThat(result).isInstanceOf(SyncResult.Success::class.java)
        assertThat(repo.pushProblem.value).contains("update the PC server")
        assertThat(repo.detail(id).first()!!.recipe.title).isEqualTo("Mine") // still saved on the phone
        assertThat(db.annotations().get(id)!!.dirty).isTrue() // and retried next time
    }
}
