package app.mise.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mise.core.model.Ingredient
import app.mise.data.repo.RecipeDetail
import app.mise.data.repo.RecipeRepository
import app.mise.data.settings.SettingsStore
import app.mise.data.settings.SourceKind
import app.mise.data.source.Substitute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubstitutesState(
    val usesPc: Boolean = true,
    val asking: Boolean = false,
    /** null = not asked yet. */
    val ideas: List<Substitute>? = null,
    val error: String? = null,
)

@HiltViewModel(assistedFactory = SubstitutesViewModel.Factory::class)
class SubstitutesViewModel @AssistedInject constructor(
    @Assisted("recipe") val recipeId: String,
    @Assisted("ingredient") val ingredientId: String,
    private val repository: RecipeRepository,
    private val settings: SettingsStore,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(@Assisted("recipe") recipeId: String, @Assisted("ingredient") ingredientId: String): SubstitutesViewModel
    }

    val detail: StateFlow<RecipeDetail?> =
        repository.detail(recipeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _state = MutableStateFlow(SubstitutesState())
    val state: StateFlow<SubstitutesState> = _state

    init {
        viewModelScope.launch {
            _state.update { it.copy(usesPc = settings.settings.first().effectiveSource == SourceKind.LAN) }
        }
    }

    fun ask() = viewModelScope.launch {
        _state.update { it.copy(asking = true, error = null) }
        repository.substitutes(recipeId, ingredientId)
            .onSuccess { ideas -> _state.update { it.copy(asking = false, ideas = ideas) } }
            .onFailure { e -> _state.update { it.copy(asking = false, error = e.message ?: "Couldn't ask your PC") } }
    }

    fun use(s: Substitute, then: () -> Unit) = viewModelScope.launch {
        repository.useSubstitute(recipeId, ingredientId, s)
        then()
    }

    fun putBack(then: () -> Unit) = viewModelScope.launch {
        repository.editIngredient(recipeId, ingredientId, null)
        then()
    }
}

/** "1½ tsp garlic paste" for a suggestion, as written in the recipe (before scaling). */
fun substituteLine(s: Substitute): String = ingredientLine(Ingredient(id = "x", name = s.name, amount = s.amount, unit = s.unit))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubstitutesScreen(viewModel: SubstitutesViewModel, onBack: () -> Unit) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = app.mise.ui.rememberLocalNetworkGate()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Swap an ingredient") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        val d = detail ?: return@Scaffold
        val ing = d.recipe.ingredient(viewModel.ingredientId) ?: run {
            Text("That ingredient isn't in the recipe any more.", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        val original = d.original.ingredient(ing.id)
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Instead of", style = MaterialTheme.typography.labelLarge)
            Text(ingredientLine(ing), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("in ${d.recipe.title}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (original != null && ing.id in d.edits.ingredients) {
                OutlinedButton(onClick = { viewModel.putBack(onBack) }) { Text("Back to ${ingredientLine(original)}") }
            }
            (original?.substitutes ?: ing.substitutes)?.let { own ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(16.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("The recipe suggests", style = MaterialTheme.typography.labelLarge)
                            Text(own, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            when {
                !state.usesPc -> Text(
                    "More ideas come from your PC: pair with it in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.asking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text("Asking your PC… this usually takes 10–30 seconds.")
                }
                state.ideas == null -> Button(onClick = { gate { viewModel.ask() } }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ask for ideas")
                }
                state.ideas!!.isEmpty() -> Text("No good swaps came back for this one.")
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.ideas?.forEach { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val leaveOut = s.name.equals("leave it out", ignoreCase = true)
                        Text(if (leaveOut) "Leave it out" else substituteLine(s), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        if (s.note.isNotBlank()) Text(s.note, style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(onClick = { viewModel.use(s, onBack) }) { Text(if (leaveOut) "Remove it" else "Use this") }
                    }
                }
            }
            if (state.ideas != null) {
                Text(
                    "Using one changes the recipe for you (amounts scale as usual). Edit recipe → Back to original undoes it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
