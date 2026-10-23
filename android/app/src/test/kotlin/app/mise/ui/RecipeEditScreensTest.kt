package app.mise.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mise.TestFixtures
import app.mise.core.model.RecipeStatus
import app.mise.data.FakeSettings
import app.mise.data.FakeSource
import app.mise.data.db.MiseDatabase
import app.mise.data.repo.RecipeRepository
import app.mise.data.repo.SourceProvider
import app.mise.data.settings.ThemeMode
import app.mise.di.Clock
import app.mise.ui.edit.EditRecipeScreen
import app.mise.ui.edit.EditRecipeViewModel
import app.mise.ui.edit.ReviewScreen
import app.mise.ui.edit.ReviewViewModel
import app.mise.ui.edit.SubstitutesScreen
import app.mise.ui.edit.SubstitutesViewModel
import app.mise.ui.edit.excerpt
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
class RecipeEditScreensTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var db: MiseDatabase
    private lateinit var repo: RecipeRepository
    private val source = FakeSource()
    private val id = "sri-lankan-lamb-curry-for-kothu"

    @Before fun setUp() {
        val direct = java.util.concurrent.Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MiseDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
        source.put(TestFixtures.lamb, "\"v1\"")
        source.sourceText = TestFixtures.lambRaw
        repo = RecipeRepository(
            db, db.recipes(), db.annotations(), db.viewState(), db.sourceTexts(), FakeSettings(),
            SourceProvider { source }, Clock { System.currentTimeMillis() }, CoroutineScope(Dispatchers.Unconfined),
        )
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

    /** The screen's vertical list (the ingredient chips scroll sideways too). */
    private fun list() = compose.onNode(
        hasScrollAction() and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange,
        ),
    )

    private fun recipe() = runBlocking { repo.detail(id).first()!! }

    @Test fun editAnIngredientAndTheServings() {
        val vm = EditRecipeViewModel(id, repo)
        show { EditRecipeScreen(vm, onBack = {}) }
        waitFor("Makes")
        compose.onNodeWithText("2 portions of kothu").performClick()
        compose.onNode(hasSetTextAction() and hasText("Makes")).performTextReplacement("4")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { recipe().recipe.recipeYield.baseServings == 4.0 }

        list().performScrollToNode(hasText("minced ginger", substring = true))
        compose.onNodeWithText("1 tsp minced ginger", substring = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("Amount")).performTextReplacement("1½")
        compose.onNode(hasSetTextAction() and hasText("up to")).performTextReplacement("1")
        compose.onNodeWithText("Save").performClick()
        waitFor("The range must end above where it starts.") // explained, not saved
        assertThat(recipe().edits.ingredients).isEmpty()
        compose.onNode(hasSetTextAction() and hasText("up to")).performTextReplacement("2")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("The range must end above where it starts.").fetchSemanticsNodes().isEmpty() }
        screenshot("edit-recipe")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { recipe().recipe.ingredient("0002")!!.amountMax == 2.0 }
        assertThat(recipe().recipe.ingredient("0002")!!.amount).isEqualTo(1.5)
        assertThat(recipe().original.ingredient("0002")!!.amount).isEqualTo(1.0)
        waitFor("Edited")
    }

    @Test fun guidedReviewFixesTheFlaggedAmount() {
        val vm = ReviewViewModel(id, repo)
        var back = false
        show { ReviewScreen(vm, onBack = { back = true }, onOriginal = {}, onEditAll = {}) }
        waitFor("The converter wasn't sure:")
        compose.onNodeWithText("1 of 1").assertExists()
        compose.onNodeWithText("Looks right").assertExists()
        waitFor("“2 9cm (3½in) cinnamon stick”") // what the original said, right there
        compose.onNode(hasSetTextAction() and hasText("Amount")).performTextReplacement("2")
        waitFor("Save & next")
        screenshot("review")
        compose.onNodeWithText("Save & next").performClick()
        waitFor("All checked")
        val d = recipe()
        assertThat(d.recipe.ingredient("0009")!!.amount).isEqualTo(2.0)
        assertThat(d.status).isEqualTo(RecipeStatus.READY)
        compose.onNodeWithText("Back to the recipe").performClick()
        assertThat(back).isTrue()
    }

    @Test fun excerptPrefersTheIngredientLine() {
        val lines = excerpt(TestFixtures.lambRaw, listOf("cinnamon stick, 9cm (3½in)"))
        assertThat(lines.first()).isEqualTo("2 9cm (3½in) cinnamon stick")
        assertThat(lines.drop(1).all { it.length < 140 }).isTrue() // long method lines are cut down
    }

    @Test fun rewordAStepAddAndRemoveIngredients() {
        val vm = EditRecipeViewModel(id, repo)
        show { EditRecipeScreen(vm, onBack = {}) }
        waitFor("Makes")
        // Reword step 2, keeping the scaling amount and adding a new mention.
        list().performScrollToNode(hasText("Brown the lamb", substring = true))
        compose.onNodeWithText("Brown the lamb", substring = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("What to do")).performTextReplacement("Heat {coconut oil, for browning} and brown the [lamb] well.")
        screenshot("edit-step")
        list().performScrollToNode(hasText("Save"))
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { recipe().recipe.steps.single { it.id == "s2" }.content == "Heat {0007} and brown the {0001:name} well." }

        // A typo in a name is explained instead of saved.
        compose.onNodeWithText("Brown the lamb", substring = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("What to do")).performTextReplacement("Brown the {lamm}.")
        list().performScrollToNode(hasText("Save"))
        compose.onNodeWithText("Save").performClick()
        waitFor("No ingredient called “lamm”", substring = true)
        compose.onNodeWithText("Cancel").performClick()

        // Add an ingredient.
        list().performScrollToNode(hasText("+ Add an ingredient"))
        compose.onNodeWithText("+ Add an ingredient").performClick()
        compose.onNode(hasSetTextAction() and hasText("Amount")).performTextReplacement("1")
        compose.onNode(hasSetTextAction() and hasText("Ingredient")).performTextReplacement("lime")
        list().performScrollToNode(hasText("Add"))
        compose.onNodeWithText("Add").performClick()
        compose.waitUntil(10_000) { recipe().recipe.ingredients.any { it.name == "lime" } }

        // Remove the curry leaves; the step that used them still reads.
        list().performScrollToNode(hasText("curry leaves", substring = true))
        compose.onNodeWithText("curry leaves", substring = true).performClick()
        list().performScrollToNode(hasText("Remove"))
        compose.onNodeWithText("Remove").performClick()
        waitFor("Remove curry leaves?")
        compose.onAllNodesWithText("Remove")[compose.onAllNodesWithText("Remove").fetchSemanticsNodes().size - 1].performClick()
        compose.waitUntil(10_000) { recipe().recipe.ingredients.none { it.name == "curry leaves" } }
        list().performScrollToNode(hasText("Put back"))
        screenshot("edit-removed")
    }

    @Test fun swapAnIngredientForASuggestion() {
        val vm = SubstitutesViewModel(id, "0003", repo, FakeSettings()) // minced garlic (Marinade)
        var back = false
        show { SubstitutesScreen(vm, onBack = { back = true }) }
        waitFor("Instead of")
        compose.onNodeWithText("1 tsp minced garlic").assertExists()
        compose.onNodeWithText("Ask for ideas").performClick()
        waitFor("1 tsp garlic paste")
        compose.onNodeWithText("Leave it out").assertExists()
        screenshot("substitutes")
        compose.onAllNodesWithText("Use this")[0].performClick()
        compose.waitUntil(10_000) { back }
        val garlic = recipe().recipe.ingredient("0003")!!
        assertThat(garlic.name).isEqualTo("garlic paste")
        assertThat(garlic.shortName).isEqualTo("garlic paste") // so the steps say it too
        assertThat(source.substituteAsks.single()).isEqualTo(id to "0003")
    }
}
