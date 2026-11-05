package app.mise.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mise.TestFixtures
import app.mise.data.db.MiseDatabase
import app.mise.data.repo.RecipeRepository
import app.mise.data.repo.SourceProvider
import app.mise.data.repo.SyncResult
import app.mise.data.settings.Settings
import app.mise.data.settings.SourceKind
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

/** The library combines the PC, a folder and recipes kept on the phone. */
@RunWith(AndroidJUnit4::class)
class CombinedLibraryTest {
    private lateinit var db: MiseDatabase
    private lateinit var repo: RecipeRepository
    private val pc = FakeSource(SourceKind.LAN)
    private val folder = FakeSource(SourceKind.FOLDER, supportsAnnotations = false)
    private var enabled = listOf<FakeSource>(pc, folder)
    private val lamb = TestFixtures.lamb

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MiseDatabase::class.java)
            .allowMainThreadQueries().build()
        val provider = object : SourceProvider {
            override fun source(settings: Settings) = enabled.first()
            override fun sources(settings: Settings) = enabled
        }
        repo = RecipeRepository(
            db, db.recipes(), db.annotations(), db.viewState(), db.sourceTexts(), FakeSettings(),
            provider, Clock { 1_000L }, CoroutineScope(Dispatchers.Unconfined),
        )
        pc.put(lamb, "\"pc1\"")
        folder.put(lamb.copy(id = "folder-soup", title = "Folder soup"), "f1")
    }

    @After fun tearDown() = db.close()

    private suspend fun where() = repo.library().first().associate { it.id to it.location }

    @Test fun pcFolderAndPhoneRecipesLiveTogether() = runTest {
        val phoneId = repo.saveOnPhone(lamb.copy(id = "phone-pie", title = "Phone pie"), "typed on the phone")
        assertThat(repo.sync()).isInstanceOf(SyncResult.Success::class.java)
        assertThat(where()).containsExactly(
            lamb.id, SourceKind.LAN, "folder-soup", SourceKind.FOLDER, phoneId, SourceKind.PHONE,
        )
        // The folder going away takes only its recipes; the phone's own are never removed by a sync.
        enabled = listOf(pc)
        repo.sync()
        assertThat(where().keys).containsExactly(lamb.id, phoneId)
        assertThat(repo.sourceText(phoneId).getOrThrow()).isEqualTo("typed on the phone")
    }

    @Test fun aFolderCopyOfAPcRecipeDoesNotFightIt() = runTest {
        folder.put(lamb.copy(title = "Old copy in the folder"), "f2") // same id as the PC's
        repo.sync()
        repo.sync()
        assertThat(where()[lamb.id]).isEqualTo(SourceKind.LAN)
        assertThat(repo.library().first().single { it.id == lamb.id }.title).isEqualTo(lamb.title)
    }

    @Test fun thePcBeingOffStillLetsTheFolderSync() = runTest {
        repo.sync()
        pc.unreachable = true
        folder.put(lamb.copy(id = "new-soup", title = "New soup"), "n1")
        val r = repo.sync()
        assertThat(r).isInstanceOf(SyncResult.Failed::class.java) // reported…
        assertThat(where().keys).containsAtLeast(lamb.id, "new-soup") // …but the folder synced and the PC cache stayed
    }

    @Test fun notesOnAPhoneRecipeStayOnThePhone() = runTest {
        val id = repo.saveOnPhone(lamb.copy(id = "phone-pie"))
        repo.setFavorite(id, true)
        repo.sync()
        assertThat(pc.puts.map { it.first }).doesNotContain(id)
    }

    @Test fun saveAPhoneRecipeToThePc() = runTest {
        val id = repo.saveOnPhone(lamb.copy(id = "phone-pie", title = "Phone pie"), "typed on the phone")
        repo.setFavorite(id, true)
        assertThat(repo.saveToPc(id)).isEqualTo(RecipeRepository.SaveToPc.Saved)
        assertThat(where()[id]).isEqualTo(SourceKind.LAN)
        assertThat(pc.recipes.keys).contains(id)
        assertThat(pc.puts.last().first).isEqualTo(id) // my favourite went with it
        repo.sync()
        assertThat(where()[id]).isEqualTo(SourceKind.LAN)

        // A clash with what's already on the PC is asked about, not overwritten.
        val other = repo.saveOnPhone(lamb.copy(id = "clash", title = "Mine"))
        pc.put(lamb.copy(id = "clash", title = "The PC's"), "c1")
        repo.sync() // the phone's own copy keeps the id locally
        assertThat(where()[other]).isEqualTo(SourceKind.PHONE)
        assertThat(repo.saveToPc(other)).isEqualTo(RecipeRepository.SaveToPc.AlreadyThere)
        assertThat(repo.saveToPc(other, replace = true)).isEqualTo(RecipeRepository.SaveToPc.Saved)
        assertThat(pc.recipes.getValue("clash").first.title).isEqualTo("Mine")
    }

    @Test fun keepACopyOnThePhoneThenDeleteIt() = runTest {
        repo.sync()
        repo.editTitle(lamb.id, "My curry")
        repo.setFavorite(lamb.id, true)
        val copy = repo.keepCopyOnPhone(lamb.id)!!
        assertThat(copy).isEqualTo("${lamb.id}-phone")
        val d = repo.detail(copy).first()!!
        assertThat(d.sourceKind).isEqualTo(SourceKind.PHONE)
        assertThat(d.recipe.title).isEqualTo("My curry") // my edits are baked into the copy
        assertThat(d.edits.isEmpty).isTrue()
        assertThat(d.favorite).isTrue()
        // Independent of the PC: unpairing keeps it.
        enabled = listOf(folder)
        repo.sync()
        assertThat(where().keys).containsExactly(copy, "folder-soup")
        repo.deleteFromPhone(lamb.id) // not a phone recipe here any more: ignored
        repo.deleteFromPhone(copy)
        assertThat(where().keys).containsExactly("folder-soup")
    }

    @Test fun theDemoOnlyFillsAnEmptyLibrary() = runTest {
        val demo = FakeSource(SourceKind.SAMPLE, supportsAnnotations = false).apply { put(lamb.copy(id = "demo"), "d") }
        enabled = listOf(demo)
        repo.sync()
        assertThat(where().keys).containsExactly("demo")
        repo.saveOnPhone(lamb.copy(id = "mine"))
        repo.sync()
        assertThat(where().keys).containsExactly("mine")
    }

    @Test fun photosLiveWithTheRecipe() = runTest {
        repo.sync()
        val jpeg = byteArrayOf(-1, -40, -1, 1, 2, 3)
        // A PC recipe's photo is saved on the PC, and kept here for offline use.
        assertThat(repo.setPhoto(lamb.id, jpeg).isSuccess).isTrue()
        assertThat(pc.photos[lamb.id]).isEqualTo(jpeg)
        assertThat(repo.image(lamb.id)).isEqualTo(jpeg)
        pc.unreachable = true
        assertThat(repo.image(lamb.id)).isEqualTo(jpeg) // offline
        pc.unreachable = false

        // A phone recipe's photo stays on the phone… until it's saved to the PC, when it goes too.
        val id = repo.saveOnPhone(lamb.copy(id = "phone-pie"))
        repo.setPhoto(id, jpeg)
        assertThat(pc.photos).doesNotContainKey(id)
        repo.saveToPc(id)
        assertThat(pc.photos[id]).isEqualTo(jpeg)

        // A copy keeps the photo; deleting the copy removes it.
        val copy = repo.keepCopyOnPhone(lamb.id)!!
        assertThat(repo.image(copy)).isEqualTo(jpeg)
        repo.deleteFromPhone(copy)
        assertThat(repo.image(copy)).isNull()

        // Removing it.
        repo.setPhoto(lamb.id, null)
        assertThat(pc.photos).doesNotContainKey(lamb.id)
        assertThat(repo.image(lamb.id)).isNull()
    }

    @Test fun tipsCanBeKeptInMyNotes() = runTest {
        repo.sync()
        val tips = repo.tips(lamb.id).getOrThrow()
        assertThat(tips.map { it.title }).containsExactly("Brown in batches", "Finish with lime").inOrder()
        repo.addTipToNotes(lamb.id, tips[0])
        repo.addTipToNotes(lamb.id, tips[0]) // only once
        val notes = repo.detail(lamb.id).first()!!.cookNotes
        assertThat(notes.count { it.startsWith("Brown in batches:") }).isEqualTo(1)
        val phone = repo.saveOnPhone(lamb.copy(id = "phone-pie"))
        assertThat(repo.tips(phone).exceptionOrNull()?.message).contains("Save this one to your PC first")
    }
}
