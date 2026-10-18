package app.mise.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.mise.ui.components.EmptyState
import app.mise.ui.cooking.CookingModeScreen
import app.mise.ui.cooking.CookingViewModel
import app.mise.ui.detail.RecipeDetailScreen
import app.mise.ui.detail.RecipeDetailViewModel
import app.mise.ui.library.LibraryScreen
import app.mise.ui.library.LibraryViewModel
import app.mise.ui.original.OriginalTextScreen
import app.mise.ui.original.OriginalTextViewModel
import app.mise.ui.settings.SettingsScreen
import app.mise.ui.add.AddRecipeScreen
import app.mise.ui.add.AddRecipeViewModel
import app.mise.ui.plans.MealPlanScreen
import app.mise.ui.plans.MealPlanViewModel
import app.mise.ui.plans.MealPlansScreen
import app.mise.ui.plans.NewPlanScreen
import app.mise.ui.plans.NewPlanViewModel
import app.mise.ui.plans.PlanKind
import app.mise.ui.plans.ShoppingListScreen
import app.mise.ui.plans.ShoppingListViewModel
import app.mise.ui.plans.ShoppingListsScreen
import kotlinx.serialization.Serializable

@Serializable data object LibraryRoute
@Serializable data class DetailRoute(val id: String)
@Serializable data class CookingRoute(val id: String, val step: Int = 0)
@Serializable data class OriginalRoute(val id: String)
@Serializable data class SettingsRoute(val pairingLink: String? = null)
@Serializable data class AddRecipeRoute(val text: String? = null, val subject: String? = null)
@Serializable data object ShoppingListsRoute
@Serializable data class ShoppingListRoute(val id: Long)
@Serializable data object MealPlansRoute
@Serializable data class MealPlanRoute(val id: Long)
/** kind = "shopping" | "meal"; ids comma-separated in the order picked. */
@Serializable data class NewPlanRoute(val kind: String, val ids: String)

/** What the library's buttons lead to. */
data class LibraryActions(
    val onAddRecipe: () -> Unit = {},
    val onShoppingLists: () -> Unit = {},
    val onMeals: () -> Unit = {},
    val onNewPlan: (kind: String, ids: List<String>) -> Unit = { _, _ -> },
)

/** Two panes (list + detail) at this width and above: tablets, unfolded foldables, landscape. */
private val TWO_PANE_MIN_WIDTH = 720.dp

/** Something the activity was asked to show (deep link, notification tap). */
sealed interface LaunchRequest {
    data class Pair(val link: String) : LaunchRequest
    data class Cook(val recipeId: String) : LaunchRequest
    data class AddRecipe(val text: String?, val subject: String?) : LaunchRequest
    data class Meal(val feastId: Long) : LaunchRequest
}

@Composable
fun MiseNavHost(launch: LaunchRequest?, onLaunchHandled: () -> Unit, nav: NavHostController = rememberNavController()) {
    LaunchedEffect(launch) {
        when (launch) {
            is LaunchRequest.Pair -> nav.navigate(SettingsRoute(launch.link)) { launchSingleTop = true }
            is LaunchRequest.Cook -> nav.navigate(CookingRoute(launch.recipeId)) { launchSingleTop = true }
            is LaunchRequest.AddRecipe -> nav.navigate(AddRecipeRoute(launch.text, launch.subject)) { launchSingleTop = true }
            is LaunchRequest.Meal -> nav.navigate(MealPlanRoute(launch.feastId)) { launchSingleTop = true }
            null -> return@LaunchedEffect
        }
        onLaunchHandled()
    }

    NavHost(nav, startDestination = LibraryRoute) {
        composable<LibraryRoute> {
            LibraryWithDetail(
                libraryVm = hiltViewModel(),
                onOpenFullScreen = { nav.navigate(DetailRoute(it)) },
                onSettings = { nav.navigate(SettingsRoute()) },
                actions = LibraryActions(
                    onAddRecipe = { nav.navigate(AddRecipeRoute()) },
                    onShoppingLists = { nav.navigate(ShoppingListsRoute) },
                    onMeals = { nav.navigate(MealPlansRoute) },
                    onNewPlan = { kind, ids -> nav.navigate(NewPlanRoute(kind, ids.joinToString(","))) },
                ),
            ) { id, onSelect ->
                val vm = hiltViewModel<RecipeDetailViewModel, RecipeDetailViewModel.Factory>(key = "pane-$id") { it.create(id) }
                RecipeDetailScreen(
                    vm, onBack = null,
                    onCook = { step -> nav.navigate(CookingRoute(id, step)) },
                    onOriginal = { nav.navigate(OriginalRoute(id)) },
                    onOpenRecipe = onSelect,
                )
            }
        }
        composable<DetailRoute> { entry ->
            val id = entry.toRoute<DetailRoute>().id
            val vm = hiltViewModel<RecipeDetailViewModel, RecipeDetailViewModel.Factory> { it.create(id) }
            RecipeDetailScreen(
                vm,
                onBack = { nav.popBackStack() },
                onCook = { step -> nav.navigate(CookingRoute(id, step)) },
                onOriginal = { nav.navigate(OriginalRoute(id)) },
                onOpenRecipe = { nav.navigate(DetailRoute(it)) },
            )
        }
        composable<CookingRoute> { entry ->
            val r = entry.toRoute<CookingRoute>()
            val vm = hiltViewModel<CookingViewModel, CookingViewModel.Factory> { it.create(r.id) }
            CookingModeScreen(vm, startStep = r.step, onClose = { nav.popBackStack() })
        }
        composable<OriginalRoute> { entry ->
            val id = entry.toRoute<OriginalRoute>().id
            val vm = hiltViewModel<OriginalTextViewModel, OriginalTextViewModel.Factory> { it.create(id) }
            OriginalTextScreen(vm, onBack = { nav.popBackStack() })
        }
        composable<AddRecipeRoute> { entry ->
            val r = entry.toRoute<AddRecipeRoute>()
            val vm = hiltViewModel<AddRecipeViewModel, AddRecipeViewModel.Factory> { it.create(r.text, r.subject) }
            AddRecipeScreen(vm, onBack = { nav.popBackStack() }, onOpenRecipe = { id ->
                nav.navigate(DetailRoute(id)) { popUpTo<AddRecipeRoute> { inclusive = true } }
            })
        }
        composable<NewPlanRoute> { entry ->
            val r = entry.toRoute<NewPlanRoute>()
            val kind = if (r.kind == "meal") PlanKind.MEAL else PlanKind.SHOPPING
            val vm = hiltViewModel<NewPlanViewModel, NewPlanViewModel.Factory> { it.create(kind, r.ids.split(',').filter(String::isNotBlank)) }
            NewPlanScreen(vm, onBack = { nav.popBackStack() }, onCreated = { id ->
                val target: Any = if (kind == PlanKind.MEAL) MealPlanRoute(id) else ShoppingListRoute(id)
                nav.navigate(target) { popUpTo<NewPlanRoute> { inclusive = true } }
            })
        }
        composable<ShoppingListsRoute> {
            ShoppingListsScreen(hiltViewModel(), onBack = { nav.popBackStack() }, onOpen = { nav.navigate(ShoppingListRoute(it)) })
        }
        composable<ShoppingListRoute> { entry ->
            val id = entry.toRoute<ShoppingListRoute>().id
            ShoppingListScreen(hiltViewModel<ShoppingListViewModel, ShoppingListViewModel.Factory> { it.create(id) }, onBack = { nav.popBackStack() })
        }
        composable<MealPlansRoute> {
            MealPlansScreen(hiltViewModel(), onBack = { nav.popBackStack() }, onOpen = { nav.navigate(MealPlanRoute(it)) })
        }
        composable<MealPlanRoute> { entry ->
            val id = entry.toRoute<MealPlanRoute>().id
            MealPlanScreen(
                hiltViewModel<MealPlanViewModel, MealPlanViewModel.Factory> { it.create(id) },
                onBack = { nav.popBackStack() },
                onOpenStep = { recipeId, step -> nav.navigate(CookingRoute(recipeId, step)) },
            )
        }
        composable<SettingsRoute> { entry ->
            SettingsScreen(hiltViewModel(), entry.toRoute<SettingsRoute>().pairingLink, onBack = { nav.popBackStack() })
        }
    }
}

/**
 * The library. On wide windows (tablets, unfolded foldables) it becomes two panes — list and the
 * selected recipe side by side; on phones tapping a recipe opens it full screen.
 */
@Composable
fun LibraryWithDetail(
    libraryVm: LibraryViewModel,
    onOpenFullScreen: (String) -> Unit,
    onSettings: () -> Unit,
    actions: LibraryActions = LibraryActions(),
    detailPane: @Composable (id: String, onSelect: (String) -> Unit) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        if (width < TWO_PANE_MIN_WIDTH) {
            LibraryScreen(
                libraryVm, selectedId = null, onOpen = onOpenFullScreen, onSettings = onSettings,
                onAddRecipe = actions.onAddRecipe, onShoppingLists = actions.onShoppingLists,
                onMeals = actions.onMeals, onNewPlan = actions.onNewPlan,
            )
        } else {
            Row(Modifier.fillMaxSize()) {
                LibraryScreen(
                    libraryVm, selectedId = selected,
                    onOpen = { selected = it },
                    onSettings = onSettings,
                    modifier = Modifier.width((width * 0.38f).coerceAtLeast(320.dp)).fillMaxHeight(),
                    onAddRecipe = actions.onAddRecipe, onShoppingLists = actions.onShoppingLists,
                    onMeals = actions.onMeals, onNewPlan = actions.onNewPlan,
                )
                VerticalDivider()
                val id = selected
                if (id == null) {
                    EmptyState(Icons.AutoMirrored.Outlined.MenuBook, "Pick a recipe", "Choose a recipe from the list to see it here.")
                } else {
                    detailPane(id) { selected = it }
                }
            }
        }
    }
}
