package app.mise.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mise.data.repo.RecipeDetail
import app.mise.data.repo.RecipeRepository
import app.mise.data.settings.SettingsStore
import app.mise.data.settings.SourceKind
import app.mise.data.source.Tip
import app.mise.ui.rememberLocalNetworkGate
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

data class TipsState(
    val usesPc: Boolean = true,
    val asking: Boolean = false,
    /** null = not asked yet. */
    val tips: List<Tip>? = null,
    val error: String? = null,
)

@HiltViewModel(assistedFactory = TipsViewModel.Factory::class)
class TipsViewModel @AssistedInject constructor(
    @Assisted val recipeId: String,
    private val repository: RecipeRepository,
    private val settings: SettingsStore,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(recipeId: String): TipsViewModel
    }

    val detail: StateFlow<RecipeDetail?> =
        repository.detail(recipeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _state = MutableStateFlow(TipsState())
    val state: StateFlow<TipsState> = _state

    init {
        viewModelScope.launch { _state.update { it.copy(usesPc = settings.settings.first().usesPc) } }
    }

    fun ask() = viewModelScope.launch {
        if (_state.value.asking) return@launch
        _state.update { it.copy(asking = true, error = null) }
        repository.tips(recipeId)
            .onSuccess { tips -> _state.update { it.copy(asking = false, tips = tips) } }
            .onFailure { e -> _state.update { it.copy(asking = false, error = e.message ?: "Couldn't ask your PC") } }
    }

    fun keep(tip: Tip) = viewModelScope.launch { repository.addTipToNotes(recipeId, tip) }
}

private fun kindLabel(kind: String) = when (kind) {
    "flavour" -> "Flavour"
    "time-saver" -> "Time-saver"
    "make-ahead" -> "Make ahead"
    "serving" -> "Serving"
    "equipment" -> "Equipment"
    else -> "Technique"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipsScreen(viewModel: TipsViewModel, onBack: () -> Unit) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = rememberLocalNetworkGate()
    val d = detail
    val onPc = d?.sourceKind == SourceKind.LAN
    // Opening the screen is the request: ask straight away (once), when it can work.
    LaunchedEffect(onPc, state.usesPc) {
        if (onPc && state.usesPc && state.tips == null && state.error == null) gate { viewModel.ask() }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Tips & ideas") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        if (d == null) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("For ${d.recipe.title}", style = MaterialTheme.typography.titleMedium)
            when {
                !state.usesPc -> Text("Tips come from Claude on your PC: pair with it in Settings.")
                !onPc -> Text("Tips work for recipes on your PC. Save this one to your PC first (⋮ → Save to PC).")
                state.asking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text("Asking your PC… this usually takes 10–30 seconds.")
                }
                state.error != null -> {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { gate { viewModel.ask() } }) { Text("Try again") }
                }
                state.tips?.isEmpty() == true -> Text("No tips came back for this one.")
            }
            val notes = d.cookNotes
            state.tips?.forEach { tip ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = {}, label = { Text(kindLabel(tip.kind)) })
                        Text(tip.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(tip.detail, style = MaterialTheme.typography.bodyLarge)
                        val kept = "${tip.title}: ${tip.detail}" in notes
                        if (kept) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text("In My notes", color = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            OutlinedButton(onClick = { viewModel.keep(tip) }) {
                                Icon(Icons.AutoMirrored.Outlined.NoteAdd, null); Spacer(Modifier.width(6.dp)); Text("Add to my notes")
                            }
                        }
                    }
                }
            }
            if (state.tips?.isNotEmpty() == true) {
                Text(
                    "Suggestions from Claude: trust your taste. Tips you keep go in My notes on the recipe (and sync to your PC).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onBack) { Text("Back to the recipe") }
            }
        }
    }
}
