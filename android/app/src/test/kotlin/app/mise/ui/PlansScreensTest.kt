package app.mise.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mise.TestFixtures
import app.mise.core.units.UnitMode
import app.mise.data.FakeSettings
import app.mise.data.FakeSource
import app.mise.data.db.MiseDatabase
import app.mise.data.repo.Pick
import app.mise.data.repo.PlansRepository
import app.mise.data.repo.RecipeRepository
import app.mise.data.repo.SourceProvider
import app.mise.data.settings.ThemeMode
import app.mise.di.Clock
import app.mise.plans.FeastReminders
import app.mise.timers.TimerController
import app.mise.timers.TimerStore
import app.mise.ui.add.AddRecipeScreen
import app.mise.ui.add.AddRecipeViewModel
import app.mise.ui.library.LibraryViewModel
import app.mise.ui.plans.MealPlanScreen
import app.mise.ui.plans.MealPlanViewModel
import app.mise.ui.plans.NewPlanScreen
import app.mise.ui.plans.NewPlanViewModel
import app.mise.ui.plans.PlanKind
import app.mise.ui.plans.ShoppingListScreen
import app.mise.ui.plans.ShoppingListViewModel
import app.mise.ui.theme.MiseTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class PlansScreensTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var db: MiseDatabase
    private lateinit var repo: RecipeRepository
    private lateinit var plans: PlansRepository
    private lateinit var timers: TimerController
    private lateinit var reminders: FeastReminders
    private val source = FakeSource()
    private val settings = FakeSettings()
    private val clock = Clock { System.currentTimeMillis() }
    private val id = "sri-lankan-lamb-curry-for-kothu"

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val direct = java.util.concurrent.Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, MiseDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor(direct).setTransactionExecutor(direct).build()
        source.put(TestFixtures.lamb, "\"v1\"")
        repo = RecipeRepository(
            db, db.recipes(), db.annotations(), db.viewState(), db.sourceTexts(), settings,
            SourceProvider { source }, clock, CoroutineScope(Dispatchers.Unconfined),
        )
        plans = PlansRepository(db.shopping(), db.feasts(), db.recipes(), repo, clock)
        File(context.filesDir, "timers.json").delete()
        timers = TimerController(context, TimerStore(context, clock))
        reminders = FeastReminders(context, plans, clock)
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { repo.sync() }
    }

    @After fun tearDown() = db.close()

    private fun show(content: @Composable () -> Unit) = compose.setContent { MiseTheme(ThemeMode.LIGHT) { content() } }

    private fun waitFor(text: String, substring: Boolean = false) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        if (System.getProperty("mise.writeScreenshots") != "true") return
        val dir = File(TestFixtures.repoRoot, "docs/screenshots").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun longPressPicksRecipesForAPlan() {
        val vm = LibraryViewModel(repo, settings)
        var planned: Pair<String, List<String>>? = null
        show {
            LibraryWithDetail(vm, onOpenFullScreen = {}, onSettings = {}, actions = LibraryActions(onNewPlan = { k, ids -> planned = k to ids })) { _, _ -> }
        }
        waitFor("Sri Lankan Lamb Curry for Kothu")
        compose.onNodeWithText("Sri Lankan Lamb Curry for Kothu").performTouchInput { longClick() }
        waitFor("1 selected")
        screenshot("library-select")
        compose.onNodeWithText("Plan a meal").performClick()
        assertThat(planned).isEqualTo("meal" to listOf(id))
        waitFor("Recipes") // selection cleared afterwards
    }

    @Test fun newShoppingListWithServings() {
        val vm = NewPlanViewModel(PlanKind.SHOPPING, listOf(id), repo, plans, settings, clock)
        var created: Long? = null
        show { NewPlanScreen(vm, onBack = {}, onCreated = { created = it }) }
        waitFor("Sri Lankan Lamb Curry for Kothu")
        compose.onNodeWithContentDescription("More servings of Sri Lankan Lamb Curry for Kothu").performClick()
        compose.onNodeWithContentDescription("3 servings").assertExists()
        compose.onNodeWithText("Make the list").performClick()
        compose.waitUntil(10_000) { created != null }
        val d = runBlocking { plans.shoppingList(created!!).first()!! }
        assertThat(d.recipes.single().servings).isEqualTo(3.0)
        assertThat(d.items.map { it.label }).contains("750 g lamb neck or shoulder fillets")
    }

    @Test fun shoppingListTicksOff() {
        val listId = runBlocking { plans.createShoppingList("Saturday's feast", listOf(Pick(id, 4.0)), UnitMode.ORIGINAL) }
        val vm = ShoppingListViewModel(listId, plans)
        show { ShoppingListScreen(vm, onBack = {}) }
        waitFor("Fruit & veg")
        compose.onNodeWithText("Meat & fish").assertExists()
        compose.onNodeWithText("300 g onion").performClick()
        waitFor("1 of", substring = true)
        screenshot("shopping-list")
    }

    @Test fun mealPlanWellAhead() {
        val serve = System.currentTimeMillis() + 5 * 3_600_000L
        val feastId = runBlocking { plans.createFeast("Dinner", serve, listOf(Pick(id, 4.0))) }
        val vm = MealPlanViewModel(feastId, plans, reminders, timers)
        show { MealPlanScreen(vm, onBack = {}, onOpenStep = { _, _ -> }) }
        waitFor("Start cooking at", substring = true)
        compose.onNodeWithText("Ahead of time").assertExists()
        compose.onNodeWithText("The day before: Marinate overnight").assertExists()
        compose.onNodeWithText("Next").assertExists()
        compose.onNodeWithText("Mise en place").assertExists()
        screenshot("meal-plan")
    }

    @Test fun mealPlanInProgressWithLateness() {
        val serve = System.currentTimeMillis() + 20 * 60_000L // too soon: the plan should already have started
        val feastId = runBlocking { plans.createFeast("Tonight", serve, listOf(Pick(id, 2.0))) }
        val vm = MealPlanViewModel(feastId, plans, reminders, timers)
        show { MealPlanScreen(vm, onBack = {}, onOpenStep = { _, _ -> }) }
        waitFor("Now")
        waitFor("behind", substring = true)
        screenshot("meal-plan-now")
        compose.onAllNodesWithText("Done")[0].performClick()
        compose.waitUntil(10_000) { runBlocking { plans.feast(feastId).first()!!.done.isNotEmpty() } }
        compose.onNode(hasText("Move serve time to", substring = true)).performClick()
        compose.waitUntil(10_000) { runBlocking { plans.feast(feastId).first()!!.feast.serveAt > serve } }
    }

    @Test fun addRecipeSendsToThePcAndFollowsTheJob() {
        val vm = AddRecipeViewModel(null, null, repo, settings)
        var opened: String? = null
        show { AddRecipeScreen(vm, onBack = {}, onOpenRecipe = { opened = it }) }
        waitFor("Send to my PC")
        compose.onNodeWithText("Recipe text").performTextInput("Toast\n2 slices bread\nToast them.")
        screenshot("add-recipe")
        compose.onNodeWithText("Send to my PC").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Added to your library").fetchSemanticsNodes().isNotEmpty() }
        assertThat(source.imports.single()).startsWith("Toast")
        compose.onNodeWithText("Open recipe").performClick()
        assertThat(opened).isEqualTo(id)
    }

    @Test fun addRecipeWithoutKeyWaitsInInbox() {
        source.features = app.mise.data.source.ServerFeatures(canImport = true, canConvert = false)
        val vm = AddRecipeViewModel("shared text from Chrome", "Shared title", repo, settings)
        show { AddRecipeScreen(vm, onBack = {}, onOpenRecipe = {}) }
        waitFor("It'll wait in the inbox")
        compose.onNodeWithText("shared text from Chrome").assertExists()
        compose.onNodeWithText("Send to my PC").performClick()
        waitFor("Saved to your PC's inbox")
    }
}
