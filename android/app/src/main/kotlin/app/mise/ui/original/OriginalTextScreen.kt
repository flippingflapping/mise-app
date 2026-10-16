package app.mise.ui.original

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mise.data.repo.RecipeRepository
import app.mise.ui.components.EmptyState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface OriginalState {
    data object Loading : OriginalState
    data class Loaded(val text: String) : OriginalState
    data class Unavailable(val message: String) : OriginalState
}

@HiltViewModel(assistedFactory = OriginalTextViewModel.Factory::class)
class OriginalTextViewModel @AssistedInject constructor(
    @Assisted recipeId: String,
    repository: RecipeRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(recipeId: String): OriginalTextViewModel
    }

    private val _state = MutableStateFlow<OriginalState>(OriginalState.Loading)
    val state: StateFlow<OriginalState> = _state

    init {
        viewModelScope.launch {
            _state.value = repository.sourceText(recipeId).fold(
                onSuccess = { it?.let(OriginalState::Loaded) ?: OriginalState.Unavailable("No original text was stored with this recipe.") },
                onFailure = { OriginalState.Unavailable("Can't load the original text right now (${it.message}). It's cached once you've viewed it.") },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OriginalTextScreen(viewModel: OriginalTextViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Original text") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                OriginalState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is OriginalState.Unavailable -> EmptyState(Icons.Outlined.CloudOff, "Not available", s.message)
                is OriginalState.Loaded -> SelectionContainer {
                    Text(
                        s.text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    )
                }
            }
        }
    }
}
