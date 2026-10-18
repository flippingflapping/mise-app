package app.mise.data.repo

import app.mise.core.feast.FeastDish
import app.mise.core.feast.FeastPlan
import app.mise.core.feast.FeastPlanner
import app.mise.core.feast.FeastStyle
import app.mise.core.model.Recipe
import app.mise.core.shopping.ShoppingLine
import app.mise.core.shopping.ShoppingList
import app.mise.core.shopping.ShoppingSource
import app.mise.core.units.UnitMode
import app.mise.data.db.FeastDao
import app.mise.data.db.FeastDishEntity
import app.mise.data.db.FeastDoneEntity
import app.mise.data.db.FeastEntity
import app.mise.data.db.RecipeDao
import app.mise.data.db.ShoppingDao
import app.mise.data.db.ShoppingItemEntity
import app.mise.data.db.ShoppingListEntity
import app.mise.data.db.ShoppingListRecipeEntity
import app.mise.di.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A recipe picked for a plan, with how many servings. */
data class Pick(val recipeId: String, val servings: Double)

data class ShoppingListDetail(
    val list: ShoppingListEntity,
    val recipes: List<ShoppingListRecipeEntity>,
    val items: List<ShoppingItemEntity>,
) {
    /** Recipe items grouped by aisle (shopping order), then your own items. */
    val byAisle: List<Pair<String, List<ShoppingItemEntity>>>
        get() {
            val fromRecipes = items.filter { !it.extra }.groupBy { it.aisle }
            val ordered = (ShoppingList.AISLES + fromRecipes.keys).distinct().mapNotNull { a -> fromRecipes[a]?.let { a to it } }
            val extras = items.filter { it.extra }
            return if (extras.isEmpty()) ordered else ordered + ("Also" to extras)
        }
}

data class FeastDetail(
    val feast: FeastEntity,
    val dishes: List<Pair<Recipe, Double>>, // recipe, factor
    val done: Set<String>,
    /** Dishes whose recipe is no longer in the library. */
    val missing: List<String>,
) {
    val style: FeastStyle get() = feast.feastStyle

    val plan: FeastPlan
        get() = FeastPlanner.plan(dishes.map { (r, f) -> FeastDish(r, f) }, feast.serveAt, done, style)
}

val FeastEntity.feastStyle: FeastStyle get() = runCatching { FeastStyle.valueOf(style) }.getOrDefault(FeastStyle.CALM)

/** Shopping lists and feast plans. Stored on the phone only. */
@Singleton
class PlansRepository @Inject constructor(
    private val shopping: ShoppingDao,
    private val feasts: FeastDao,
    private val recipeDao: RecipeDao,
    private val recipes: RecipeRepository,
    private val clock: Clock,
) {
    // ------------------------------------------------------------------ shopping lists

    fun shoppingLists() = shopping.lists()

    fun shoppingList(id: Long): Flow<ShoppingListDetail?> =
        combine(shopping.list(id), shopping.recipes(id), shopping.items(id)) { l, r, i -> l?.let { ShoppingListDetail(it, r, i) } }

    suspend fun createShoppingList(name: String, picks: List<Pick>, mode: UnitMode): Long {
        val byId = recipes.recipesById(picks.map { it.recipeId })
        val sources = picks.mapNotNull { p -> byId[p.recipeId]?.let { ShoppingSource(it, p.servings / it.recipeYield.baseServings) } }
        val lines: List<ShoppingLine> = ShoppingList.build(sources, mode)
        return shopping.create(
            ShoppingListEntity(name = name.ifBlank { "Shopping list" }, createdAt = clock.now()),
            picks.mapNotNull { p -> byId[p.recipeId]?.let { ShoppingListRecipeEntity(0, it.id, it.title, p.servings) } },
            lines.mapIndexed { i, l -> ShoppingItemEntity(listId = 0, label = l.label, aisle = l.aisle, recipes = l.recipes.joinToString("\n"), position = i) },
        )
    }

    suspend fun setChecked(itemId: Long, checked: Boolean) = shopping.setChecked(itemId, checked)
    suspend fun uncheckAll(listId: Long) = shopping.uncheckAll(listId)
    suspend fun addExtra(listId: Long, text: String) {
        if (text.isNotBlank()) shopping.insertItem(ShoppingItemEntity(listId = listId, label = text.trim(), aisle = "Also", extra = true, position = Int.MAX_VALUE))
    }
    suspend fun deleteItem(itemId: Long) = shopping.deleteItem(itemId)
    suspend fun renameList(id: Long, name: String) = shopping.rename(id, name.ifBlank { "Shopping list" })
    suspend fun deleteList(id: Long) = shopping.deleteList(id)

    suspend fun shareText(id: Long): String? {
        val d = shoppingList(id).first() ?: return null
        return buildString {
            appendLine(d.list.name)
            if (d.recipes.isNotEmpty()) appendLine("For: " + d.recipes.joinToString(", ") { it.title })
            for ((aisle, items) in d.byAisle) {
                appendLine()
                appendLine(aisle)
                items.forEach { appendLine("${if (it.checked) "☑" else "☐"} ${it.label}") }
            }
        }.trimEnd()
    }

    // ------------------------------------------------------------------ feasts

    fun feastList() = feasts.feasts()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun feast(id: Long): Flow<FeastDetail?> =
        combine(feasts.feast(id), feasts.dishes(id), feasts.done(id)) { f, d, done -> Triple(f, d, done) }
            .flatMapLatest { (f, dishes, done) ->
                if (f == null) return@flatMapLatest flowOf(null)
                // Re-plan when a dish's recipe changes in the library (e.g. after a sync).
                recipeDao.library().map {
                    val byId = recipes.recipesById(dishes.map { it.recipeId })
                    FeastDetail(
                        feast = f,
                        dishes = dishes.mapNotNull { d -> byId[d.recipeId]?.let { it to d.factor } },
                        done = done.toSet(),
                        missing = dishes.map { it.recipeId }.filter { it !in byId },
                    )
                }
            }

    suspend fun createFeast(name: String, serveAt: Long, picks: List<Pick>, style: FeastStyle = FeastStyle.CALM): Long {
        val byId = recipes.recipesById(picks.map { it.recipeId })
        return feasts.create(
            FeastEntity(name = name.ifBlank { "Feast" }, serveAt = serveAt, createdAt = clock.now(), style = style.name),
            picks.mapIndexedNotNull { i, p -> byId[p.recipeId]?.let { FeastDishEntity(0, it.id, p.servings / it.recipeYield.baseServings, i) } },
        )
    }

    suspend fun setDone(feastId: Long, taskId: String, done: Boolean) =
        if (done) feasts.markDone(FeastDoneEntity(feastId, taskId)) else feasts.unmarkDone(feastId, taskId)

    suspend fun setServeAt(feastId: Long, serveAt: Long) = feasts.setServeAt(feastId, serveAt)
    suspend fun setRemind(feastId: Long, remind: Boolean) = feasts.setRemind(feastId, remind)
    suspend fun setStyle(feastId: Long, style: FeastStyle) = feasts.setStyle(feastId, style.name)

    /** New meal plans start in the style you used last (calm the first time). */
    suspend fun defaultStyle(): FeastStyle =
        feasts.lastStyle()?.let { runCatching { FeastStyle.valueOf(it) }.getOrNull() } ?: FeastStyle.CALM
    suspend fun deleteFeast(feastId: Long) = feasts.delete(feastId)

    /** The current plan for reminders (computed outside of any UI). */
    suspend fun planNow(feastId: Long): Pair<FeastEntity, FeastPlan>? {
        val f = feasts.get(feastId) ?: return null
        val dishes = feasts.dishesNow(feastId)
        val byId = recipes.recipesById(dishes.map { it.recipeId })
        val plan = FeastPlanner.plan(
            dishes.mapNotNull { d -> byId[d.recipeId]?.let { FeastDish(it, d.factor) } }, f.serveAt, feasts.doneNow(feastId).toSet(), f.feastStyle,
        )
        return f to plan
    }

    suspend fun feastsWithReminders(): List<FeastEntity> = feasts.upcomingWithReminders(clock.now())
}
