package app.mise.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mise.core.search.RecipeSearch
import app.mise.core.search.SearchDoc
import app.mise.data.repo.LibraryItem
import app.mise.data.repo.RecipeRepository
import app.mise.data.repo.SyncResult
import app.mise.data.settings.Settings
import app.mise.data.settings.SettingsStore
import app.mise.data.settings.SourceKind
import app.mise.data.source.SourceException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryFilters(val query: String = "", val tags: Set<String> = emptySet(), val favoritesOnly: Boolean = false) {
    val active: Boolean get() = query.isNotBlank() || tags.isNotEmpty() || favoritesOnly
}

data class LibraryUiState(
    val loading: Boolean = true,
    val items: List<LibraryItem> = emptyList(),
    val recentlyCooked: List<LibraryItem> = emptyList(),
    val tags: List<String> = emptyList(),
    val filters: LibraryFilters = LibraryFilters(),
    val total: Int = 0,
    val refreshing: Boolean = false,
    val banner: String? = null,
    val source: SourceKind = SourceKind.SAMPLE,
    /** Recipes picked (long-press) for a shopping list or meal plan, in the order picked. */
    val picked: List<String> = emptyList(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: RecipeRepository,
    settings: SettingsStore,
) : ViewModel() {
    private val filters = MutableStateFlow(LibraryFilters())
    private val picked = MutableStateFlow<List<String>>(emptyList())

    val state: StateFlow<LibraryUiState> = combine(
        repository.library(), filters, repository.status, settings.settings, picked,
    ) { all, f, status, s, pick ->
        val docs = all.associateWith { SearchDoc(it.title, it.tags, it.ingredientNames) }
        val filtered = all
            .filter { !f.favoritesOnly || it.favorite }
            .filter { item -> f.tags.all { t -> t in item.tags } }
            .map { it to RecipeSearch.score(docs.getValue(it), f.query) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<LibraryItem, Int>> { it.second }.thenBy { it.first.title.lowercase() })
            .map { it.first }
        LibraryUiState(
            loading = false,
            items = filtered,
            recentlyCooked = if (f.active) emptyList()
            else all.filter { it.lastCooked != null }.sortedByDescending { it.lastCooked }.take(6),
            tags = all.flatMap { it.tags }.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key }.take(20),
            filters = f,
            total = all.size,
            refreshing = status.running,
            banner = bannerFor(status.lastResult, s),
            source = s.effectiveSource,
            picked = pick.filter { id -> all.any { it.id == id } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        viewModelScope.launch { repository.syncIfStale() } // sync on app open
    }

    fun refresh() = viewModelScope.launch { repository.sync() }
    fun setQuery(q: String) = filters.update { it.copy(query = q) }
    fun toggleTag(tag: String) = filters.update { it.copy(tags = if (tag in it.tags) it.tags - tag else it.tags + tag) }
    fun toggleFavorites() = filters.update { it.copy(favoritesOnly = !it.favoritesOnly) }
    fun clearFilters() = filters.update { LibraryFilters() }
    fun togglePick(id: String) = picked.update { if (id in it) it - id else it + id }
    fun clearPicks() = picked.update { emptyList() }

    private fun bannerFor(result: SyncResult?, s: Settings): String? {
        val failed = result as? SyncResult.Failed ?: return null
        return when (failed.reason) {
            SourceException.Reason.UNREACHABLE ->
                if (s.effectiveSource == SourceKind.LAN) "Can't reach your PC — showing cached recipes"
                else "${failed.message} — showing cached recipes"
            SourceException.Reason.UNAUTHORIZED -> "Your PC rejected the access token. Pair again in Settings."
            else -> "${failed.message}. Showing cached recipes."
        }
    }
}
