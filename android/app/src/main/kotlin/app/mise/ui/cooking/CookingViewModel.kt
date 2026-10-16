package app.mise.ui.cooking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mise.core.model.Recipe
import app.mise.core.timer.KitchenTimer
import app.mise.core.units.UnitMode
import app.mise.data.repo.RecipeRepository
import app.mise.data.settings.SettingsStore
import app.mise.timers.TimerController
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class CookingUiState(
    val loading: Boolean = true,
    val recipe: Recipe? = null,
    val factor: Double = 1.0,
    val unitMode: UnitMode = UnitMode.ORIGINAL,
    val timers: List<KitchenTimer> = emptyList(),
    val keepScreenOn: Boolean = true,
)

@HiltViewModel(assistedFactory = CookingViewModel.Factory::class)
class CookingViewModel @AssistedInject constructor(
    @Assisted val recipeId: String,
    repository: RecipeRepository,
    settings: SettingsStore,
    val timers: TimerController,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(recipeId: String): CookingViewModel
    }

    val state: StateFlow<CookingUiState> = combine(
        repository.detail(recipeId), settings.settings, timers.store.timers,
    ) { d, s, t ->
        CookingUiState(
            loading = false,
            recipe = d?.recipe,
            factor = d?.factor ?: 1.0,
            unitMode = d?.viewState?.unitMode?.let { m -> UnitMode.entries.firstOrNull { it.name == m } } ?: s.defaultUnitMode,
            timers = t,
            keepScreenOn = s.keepScreenOn,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CookingUiState())
}
