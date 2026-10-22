package app.mise.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mise.TestFixtures
import app.mise.data.FakeSettings
import app.mise.data.FakeSource
import app.mise.data.db.MiseDatabase
import app.mise.data.repo.RecipeRepository
import app.mise.data.repo.SourceProvider
import app.mise.data.settings.ThemeMode
import app.mise.di.Clock
import app.mise.timers.TimerController
import app.mise.timers.TimerStore
import app.mise.ui.cooking.CookingModeScreen
import app.mise.ui.cooking.CookingViewModel
import app.mise.ui.detail.RecipeDetailScreen
import app.mise.ui.detail.RecipeDetailViewModel
import app.mise.ui.library.LibraryViewModel
import app.mise.ui.theme.MiseTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens (Robolectric, native graphics) against the fixture recipe, checks the
 * behaviour, and — with -Pmise.screenshots=true — writes the screenshots used in the README.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var db: MiseDatabase
    private lateinit var repo: RecipeRepository
    private lateinit var timers: TimerController
    private val settings = FakeSettings()
    private val id = "sri-lankan-lamb-curry-for-kothu"

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val direct = java.util.concurrent.Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, MiseDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor(direct).setTransactionExecutor(direct).build()
        // Android 13+: as if the user already allowed notifications (timers ask on first use).
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val source = FakeSource().apply { put(TestFixtures.lamb, "\"v1\"") }
        repo = RecipeRepository(
            db, db.recipes(), db.annotations(), db.viewState(), db.sourceTexts(), settings,
            SourceProvider { source }, Clock { System.currentTimeMillis() }, CoroutineScope(Dispatchers.Unconfined),
        )
        File(context.filesDir, "timers.json").delete()
        timers = TimerController(context, TimerStore(context) { System.currentTimeMillis() })
        runBlocking { repo.sync() }
    }

    @After fun tearDown() = db.close()

    private fun detailVm() = RecipeDetailViewModel(id, repo, settings, timers)
    private val paneVms = mutableMapOf<String, RecipeDetailViewModel>()
    private fun paneVm(rid: String) = paneVms.getOrPut(rid) { RecipeDetailViewModel(rid, repo, settings, timers) }

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) = compose.setContent {
        MiseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { content() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        if (System.getProperty("mise.writeScreenshots") != "true") return
        val dir = File(TestFixtures.repoRoot, "docs/screenshots").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Screens update from Room flows; wait for the text instead of assuming the next frame has it. */
    private fun waitFor(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitGone(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    @Composable
    private fun Detail(vm: RecipeDetailViewModel) =
        RecipeDetailScreen(vm, onBack = {}, onCook = {}, onOriginal = {}, onOpenRecipe = {})

    @Test fun detailHeaderBannerAndScaling() {
        show { Detail(detailVm()) }
        waitFor("Serves 2 portions of kothu")
        compose.onNodeWithText("Start the day before").assertIsDisplayed()
        compose.onNodeWithText("Check the conversion").assertIsDisplayed()
        screenshot("detail")

        compose.onNodeWithContentDescription("More servings").performClick()
        waitFor("Serves 3 portions of kothu (recipe makes 2)")
        compose.onNodeWithText("US").performClick()
        compose.waitUntil(10_000) {
            runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText("1⅝ lb")) }.isSuccess
        }
        compose.onNodeWithText("1⅝ lb").assertIsDisplayed() // 750 g lamb
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Metric"))
        compose.onNodeWithText("Metric").performClick()
        compose.waitUntil(10_000) {
            runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText("750 g")) }.isSuccess
        }
        screenshot("detail-ingredients")
    }

    @Test fun stepsShowInlineAmountsAndTimers() {
        show { Detail(detailVm()) }
        waitFor("Serves 2 portions of kothu")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Brown the lamb"))
        compose.onNodeWithText("Timer · 5–7 min").assertExists()
        compose.onNodeWithText("Timer · 5–7 min").performClick()
        compose.waitUntil(10_000) { timers.store.timers.value.isNotEmpty() }
        assertThat(timers.store.timers.value.single().label).isEqualTo("Brown the lamb")
        waitFor("Brown the lamb") // and it shows in the tray
        screenshot("detail-steps")
    }

    @Test fun markAsReviewed() {
        show { Detail(detailVm()) }
        waitFor("All fine")
        compose.onNodeWithText("All fine").performClick()
        waitGone("Check the conversion")
        assertThat(runBlocking { db.annotations().get(id)?.reviewed }).isTrue()
    }

    /**
     * The dialog itself is covered by [ScaleDialogTest]. (Under Robolectric, any text field in a
     * dialog window over this screen keeps Compose from idling, so the two halves are tested apart.)
     */
    @Test fun scaleByWhatIHave() {
        val vm = detailVm()
        show { Detail(vm) }
        waitFor("Serves 2 portions of kothu")
        assertThat(vm.scaleBy("0001", 400.0, app.mise.core.model.MeasureUnit.G)).isTrue()
        waitFor("Serves 1.6 portions of kothu (recipe makes 2)")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Reset"))
        compose.onNodeWithText("Reset").performClick()
        waitFor("Serves 2 portions of kothu")
    }

    @Test fun darkMode() {
        show(dark = true) { Detail(detailVm()) }
        waitFor("Serves 2 portions of kothu")
        screenshot("detail-dark")
    }

    @Test fun cookingModeOneStepPerPage() {
        val vm = CookingViewModel(id, repo, settings, timers)
        show { CookingModeScreen(vm, startStep = 0, onClose = {}) }
        waitFor("Step 1 of 13")
        compose.onNodeWithText("Marinate overnight").assertIsDisplayed()
        compose.onNodeWithText("No timer for this one — it's a long wait.").assertExists()
        compose.onNodeWithText("Next").performClick()
        waitFor("Step 2 of 13")
        compose.onNodeWithText("You'll need").assertIsDisplayed()
        compose.onNodeWithText("Start 5–7 min timer").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Pause Brown the lamb")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Start another 5–7 min timer").assertExists()
        screenshot("cooking")
    }

    @Test fun libraryWithBadgeAndSearch() {
        val vm = LibraryViewModel(repo, settings)
        show { LibraryWithDetail(vm, onOpenFullScreen = {}, onSettings = {}) { _, _ -> } }
        waitFor("Sri Lankan Lamb Curry for Kothu")
        compose.onNodeWithText("Needs review").assertIsDisplayed()
        screenshot("library")
        compose.onNodeWithText("Search titles, tags, ingredients").performTextInput("pizza")
        waitFor("No matches")
    }

    @Test fun settingsAndPairingConfirmation() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = app.mise.ui.settings.SettingsViewModel(context, app.mise.data.settings.SettingsRepository(context), repo, timers)
        show { app.mise.ui.settings.SettingsScreen(vm, pairingLink = null, onBack = {}) }
        waitFor("Recipes come from")
        compose.onNodeWithText("Pair with PC").assertIsDisplayed()
        compose.onNodeWithText("Server address").assertIsDisplayed() // manual entry until paired
        screenshot("settings")
        assertThat(vm.offerPairing("mise://pair?url=http%3A%2F%2F192.168.1.20%3A8765&token=abc")).isTrue()
        waitFor("Pair with this PC?")
        compose.onNodeWithText("Mise will load recipes from http://192.168.1.20:8765.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        waitGone("Pair with this PC?")
        assertThat(vm.offerPairing("https://example.com")).isFalse()
    }

    @Test
    @Config(qualifiers = "w1000dp-h700dp-mdpi")
    fun tabletShowsTwoPanes() {
        val vm = LibraryViewModel(repo, settings)
        show {
            LibraryWithDetail(vm, onOpenFullScreen = { error("should stay in-pane") }, onSettings = {}) { rid, _ -> Detail(paneVm(rid)) }
        }
        compose.onNodeWithText("Pick a recipe").assertIsDisplayed()
        waitFor("Sri Lankan Lamb Curry for Kothu")
        compose.onNodeWithText("Sri Lankan Lamb Curry for Kothu").performClick()
        waitFor("Serves 2 portions of kothu")
        screenshot("tablet")
    }
}
