package app.mise.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mise.TestFixtures
import app.mise.core.feast.FeastBlock
import app.mise.core.feast.FeastStyle
import app.mise.core.units.UnitMode
import app.mise.data.db.MiseDatabase
import app.mise.data.repo.Pick
import app.mise.data.repo.PlansRepository
import app.mise.data.repo.RecipeRepository
import app.mise.data.repo.SourceProvider
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
class PlansRepositoryTest {
    private lateinit var db: MiseDatabase
    private lateinit var plans: PlansRepository
    private val id = "sri-lankan-lamb-curry-for-kothu"
    private val serve = 1_800_000_000_000L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MiseDatabase::class.java)
            .allowMainThreadQueries().build()
        val source = FakeSource().apply { put(TestFixtures.lamb, "\"v1\"") }
        val repo = RecipeRepository(
            db, db.recipes(), db.annotations(), db.viewState(), db.sourceTexts(), FakeSettings(),
            SourceProvider { source }, Clock { 5 }, CoroutineScope(Dispatchers.Unconfined),
        )
        repo.sync()
        plans = PlansRepository(db.shopping(), db.feasts(), db.recipes(), repo, Clock { 5 })
    }

    @After fun tearDown() = db.close()

    @Test fun shoppingListIsCombinedScaledAndGrouped() = runTest {
        val listId = plans.createShoppingList("Saturday", listOf(Pick(id, 4.0)), UnitMode.ORIGINAL)
        val d = plans.shoppingList(listId).first()!!
        assertThat(d.list.name).isEqualTo("Saturday")
        assertThat(d.recipes.single().servings).isEqualTo(4.0)
        val labels = d.items.map { it.label }
        assertThat(labels).contains("1 kg lamb neck or shoulder fillets") // doubled
        assertThat(labels).contains("2⅔ tbsp minced ginger") // (1 tsp + 1 tbsp) × 2 = 8 tsp
        assertThat(d.byAisle.first().first).isEqualTo("Fruit & veg")
    }

    @Test fun tickingExtrasAndSharing() = runTest {
        val listId = plans.createShoppingList("", listOf(Pick(id, 2.0)), UnitMode.ORIGINAL)
        val first = plans.shoppingList(listId).first()!!.items.first()
        plans.setChecked(first.id, true)
        plans.addExtra(listId, " kitchen roll ")
        plans.addExtra(listId, "   ")
        val d = plans.shoppingList(listId).first()!!
        assertThat(d.list.name).isEqualTo("Shopping list")
        assertThat(d.items.single { it.id == first.id }.checked).isTrue()
        assertThat(d.byAisle.last()).isEqualTo("Also" to listOf(d.items.single { it.extra }))
        val text = plans.shareText(listId)!!
        assertThat(text).startsWith("Shopping list\nFor: Sri Lankan Lamb Curry for Kothu")
        assertThat(text).contains("☑ ${first.label}")
        assertThat(text).endsWith("Also\n☐ kitchen roll")
        assertThat(plans.shoppingLists().first().single().done).isEqualTo(1)
        plans.deleteList(listId)
        assertThat(plans.shoppingLists().first()).isEmpty()
    }

    @Test fun newPlansStartInTheLastStyleUsed() = runTest {
        assertThat(plans.defaultStyle()).isEqualTo(FeastStyle.CALM)
        val feastId = plans.createFeast("Dinner", serve, listOf(Pick(id, 4.0)), FeastStyle.PRO)
        assertThat(plans.feast(feastId).first()!!.plan.style).isEqualTo(FeastStyle.PRO)
        assertThat(plans.defaultStyle()).isEqualTo(FeastStyle.PRO)
        plans.setStyle(feastId, FeastStyle.CALM)
        assertThat(plans.feast(feastId).first()!!.plan.blocks.first().kind).isEqualTo(FeastBlock.Kind.PREP)
        assertThat(plans.defaultStyle()).isEqualTo(FeastStyle.CALM)
    }

    @Test fun feastPlansAndTicksOff() = runTest {
        val feastId = plans.createFeast("Dinner", serve, listOf(Pick(id, 4.0)))
        val d = plans.feast(feastId).first()!!
        assertThat(d.dishes.single().second).isEqualTo(2.0) // factor from 4 of 2 servings
        val plan = d.plan
        assertThat(plan.tasks.last().endMillis).isEqualTo(serve)
        assertThat(plan.advance.single().title).isEqualTo("Marinate overnight")
        val first = plan.tasks.first()
        plans.setDone(feastId, first.id, true)
        val after = plans.feast(feastId).first()!!
        assertThat(after.done).containsExactly(first.id)
        assertThat(after.plan.tasks.map { it.id }).doesNotContain(first.id)
        plans.setServeAt(feastId, serve + 600_000)
        assertThat(plans.planNow(feastId)!!.second.serveAtMillis).isEqualTo(serve + 600_000)
        assertThat(plans.feastList().first().single().dishes).isEqualTo(1)
    }
}
