package app.mise.ui.detail

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mise.core.model.MeasureUnit
import app.mise.core.scale.ScaleByIngredient
import app.mise.core.scale.Servings
import app.mise.core.timer.KitchenTimer
import app.mise.core.units.UnitMode
import app.mise.data.repo.RecipeDetail
import app.mise.data.repo.RecipeRepository
import app.mise.data.settings.SettingsStore
import app.mise.data.settings.SourceKind
import app.mise.timers.TimerController
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RelatedRecipe(val id: String, val title: String, val available: Boolean)

data class DetailUiState(
    val loading: Boolean = true,
    val detail: RecipeDetail? = null,
    val unitMode: UnitMode = UnitMode.ORIGINAL,
    val highlighted: String? = null,
    val related: List<RelatedRecipe> = emptyList(),
    val timers: List<KitchenTimer> = emptyList(),
    val notesSyncToPc: Boolean = false,
) {
    val factor: Double get() = detail?.factor ?: 1.0
    val servings: Double get() = (detail?.recipe?.recipeYield?.baseServings ?: 1.0) * factor
}

@HiltViewModel(assistedFactory = RecipeDetailViewModel.Factory::class)
class RecipeDetailViewModel @AssistedInject constructor(
    @Assisted val recipeId: String,
    private val repository: RecipeRepository,
    settings: SettingsStore,
    val timers: TimerController,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(recipeId: String): RecipeDetailViewModel
    }

    private val highlighted = MutableStateFlow<String?>(null)
    private var highlightJob: Job? = null

    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image: StateFlow<ImageBitmap?> = _image

    val state: StateFlow<DetailUiState> = combine(
        repository.detail(recipeId),
        settings.settings,
        highlighted,
        repository.library(),
        timers.store.timers,
    ) { detail, s, hl, library, allTimers ->
        val titles = library.associate { it.id to it.title }
        DetailUiState(
            loading = false,
            detail = detail,
            unitMode = detail?.viewState?.unitMode?.let { m -> UnitMode.entries.firstOrNull { it.name == m } } ?: s.defaultUnitMode,
            highlighted = hl,
            related = detail?.recipe?.relatedRecipes.orEmpty().map { id ->
                RelatedRecipe(id, titles[id] ?: humanize(id), id in titles)
            },
            timers = allTimers,
            notesSyncToPc = detail?.sourceKind == SourceKind.LAN,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init {
        viewModelScope.launch {
            val d = repository.detail(recipeId).filterNotNull().first()
            if (d.recipe.image != null) {
                val bytes = repository.image(recipeId) ?: return@launch
                _image.value = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
        }
    }

    private fun setServings(target: Double) {
        val base = state.value.detail?.recipe?.recipeYield?.baseServings ?: return
        val f = Servings.factor(base, target)
        viewModelScope.launch { repository.setFactor(recipeId, if (kotlin.math.abs(f - 1) < 1e-9) null else f) }
    }

    fun increment() = setServings(Servings.increment(state.value.servings))
    fun decrement() = setServings(Servings.decrement(state.value.servings))
    fun resetScale() = viewModelScope.launch { repository.setFactor(recipeId, null) }
    fun setUnitMode(mode: UnitMode) = viewModelScope.launch { repository.setUnitMode(recipeId, mode) }

    /** "I've only got 400 g lamb": returns false if the amount can't be used. */
    fun scaleBy(ingredientId: String, amount: Double, unit: MeasureUnit?): Boolean {
        val ing = state.value.detail?.recipe?.ingredient(ingredientId) ?: return false
        val f = ScaleByIngredient.factor(ing, amount, unit) ?: return false
        viewModelScope.launch { repository.setFactor(recipeId, f) }
        return true
    }

    fun toggleChecked(ingredientId: String) = viewModelScope.launch { repository.toggleChecked(recipeId, ingredientId) }
    fun clearChecked() = viewModelScope.launch { repository.clearChecked(recipeId) }

    /** Highlight an ingredient for a few seconds (after tapping its chip in a step). */
    fun highlight(ingredientId: String) {
        highlighted.value = ingredientId
        highlightJob?.cancel()
        highlightJob = viewModelScope.launch {
            delay(3_000)
            highlighted.value = null
        }
    }

    fun toggleFavorite() = viewModelScope.launch {
        repository.setFavorite(recipeId, !(state.value.detail?.favorite ?: false))
    }

    fun markReviewed() = viewModelScope.launch { repository.markReviewed(recipeId) }
    fun dismissFlags() = viewModelScope.launch { repository.dismissFlags(recipeId) }
    fun setCookNotes(notes: List<String>) = viewModelScope.launch { repository.setCookNotes(recipeId, notes) }
    fun madeThis() = viewModelScope.launch { repository.madeThis(recipeId) }

    companion object {
        fun humanize(slug: String) = slug.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}
