package app.mise.ui.add

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mise.data.repo.RecipeRepository
import app.mise.data.settings.SettingsStore
import app.mise.data.settings.SourceKind
import app.mise.data.source.ImportJob
import app.mise.data.source.ServerFeatures
import app.mise.ui.rememberLocalNetworkGate
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AddMode { PASTE, DESCRIBE }

data class AddRecipeState(
    val mode: AddMode = AddMode.PASTE,
    /** For [AddMode.DESCRIBE]: what to cook, in a few words. */
    val request: String = "",
    val text: String = "",
    val title: String = "",
    val source: String = "",
    /** null = still checking; otherwise what the PC server can do. */
    val features: ServerFeatures? = null,
    val usesPc: Boolean = true,
    val sending: Boolean = false,
    val job: ImportJob? = null,
    val error: String? = null,
) {
    val canSend: Boolean
        get() = usesPc && !sending && (job == null || job.status == "failed") &&
            if (mode == AddMode.PASTE) text.isNotBlank() else request.trim().length >= 3 && features?.canRequest != false
}

@HiltViewModel(assistedFactory = AddRecipeViewModel.Factory::class)
class AddRecipeViewModel @AssistedInject constructor(
    @Assisted("text") sharedText: String?,
    @Assisted("subject") sharedSubject: String?,
    private val repository: RecipeRepository,
    private val settings: SettingsStore,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(@Assisted("text") text: String?, @Assisted("subject") subject: String?): AddRecipeViewModel
    }

    private val _state = MutableStateFlow(AddRecipeState(text = sharedText.orEmpty(), title = sharedSubject.orEmpty()))
    val state: StateFlow<AddRecipeState> = _state

    init { refreshFeatures() }

    fun refreshFeatures() = viewModelScope.launch {
        val usesPc = settings.settings.first().effectiveSource == SourceKind.LAN
        _state.update { it.copy(usesPc = usesPc) }
        if (!usesPc) return@launch
        repository.serverFeatures().onSuccess { f -> _state.update { it.copy(features = f, error = null) } }
            .onFailure { e -> _state.update { it.copy(error = e.message ?: "Can't reach your PC") } }
    }

    fun setMode(m: AddMode) = _state.update { it.copy(mode = m, error = null) }
    fun setRequest(t: String) = _state.update { it.copy(request = t) }
    fun setText(t: String) = _state.update { it.copy(text = t) }
    fun setTitle(t: String) = _state.update { it.copy(title = t) }
    fun setSource(t: String) = _state.update { it.copy(source = t) }

    fun send() = viewModelScope.launch {
        val s = _state.value
        _state.update { it.copy(sending = true, error = null) }
        val sent = if (s.mode == AddMode.DESCRIBE) repository.requestRecipe(s.request.trim())
        else repository.importRecipe(s.text, s.title.ifBlank { null }, s.source.ifBlank { null })
        sent.onSuccess { job ->
                _state.update { it.copy(sending = false, job = job) }
                follow(job)
            }
            .onFailure { e -> _state.update { it.copy(sending = false, error = e.message ?: "Couldn't send it to your PC") } }
    }

    /** Poll the PC until the conversion finishes, then sync so the new recipe appears. */
    private suspend fun follow(start: ImportJob) {
        var job = start
        while (!job.finished) {
            delay(3_000)
            job = repository.importStatus(job.id).getOrNull() ?: break
            _state.update { it.copy(job = job) }
        }
        if (job.recipeId != null) repository.sync()
    }

    fun reset() = _state.update { AddRecipeState(mode = it.mode, features = it.features, usesPc = it.usesPc) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRecipeScreen(viewModel: AddRecipeViewModel, onBack: () -> Unit, onOpenRecipe: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val gate = rememberLocalNetworkGate()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Add a recipe") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding() // stay above the keyboard
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.usesPc) {
                Notice(Icons.Outlined.ErrorOutline, "Adding recipes needs your PC",
                    "Recipes are converted on your PC. Switch Settings → Recipes come from → PC server, and pair with it.")
                return@Column
            }
            val job = state.job
            if (job != null) {
                JobStatus(job, onOpen = { job.recipeId?.let(onOpenRecipe) }, onAnother = viewModel::reset)
                if (job.status != "failed") return@Column
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AddMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = state.mode == m, onClick = { viewModel.setMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, AddMode.entries.size),
                    ) { Text(if (m == AddMode.PASTE) "Paste a recipe" else "Describe one") }
                }
            }
            if (state.mode == AddMode.DESCRIBE) {
                DescribeForm(state, viewModel::setRequest)
                state.error?.let { Notice(Icons.Outlined.ErrorOutline, "Couldn't ask your PC", it) }
                Button(
                    onClick = { gate { viewModel.send() } }, enabled = state.canSend,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    if (state.sending) CircularProgressIndicator(Modifier.padding(end = 8.dp)) else Text("Ask my PC for a recipe")
                }
                return@Column
            }
            Text(
                "Paste a recipe — straight from a website, a scan or a note. It doesn't need to be tidy: your PC turns it " +
                    "into a proper recipe and it appears in your library.",
                style = MaterialTheme.typography.bodyMedium,
            )
            when {
                state.error != null -> Notice(Icons.Outlined.ErrorOutline, "Can't reach your PC", state.error!!)
                state.features?.canImport == false -> Notice(Icons.Outlined.ErrorOutline, "Update your PC server",
                    "This server is too old to accept recipes from the app. Download the latest version and restart it.")
                state.features?.canConvert == false -> Notice(Icons.Outlined.Inbox, "It'll wait in the inbox",
                    "Your PC can't convert right now, so the text is saved to its inbox and converted next time you run " +
                        "convert. (Log in to Claude Code on the PC, or set ANTHROPIC_API_KEY, then restart the server to " +
                        "convert straight away.)")
            }
            OutlinedTextField(
                value = state.text, onValueChange = viewModel::setText,
                label = { Text("Recipe text") },
                // Long recipes scroll inside the box, so the fields and button below stay reachable.
                minLines = 8, maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.text.isBlank()) {
                TextButton(onClick = { clipboard.getText()?.text?.let(viewModel::setText) }) {
                    Icon(Icons.Filled.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("Paste from clipboard")
                }
            }
            OutlinedTextField(value = state.title, onValueChange = viewModel::setTitle, label = { Text("Title (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = state.source, onValueChange = viewModel::setSource,
                label = { Text("Where it's from (optional)") }, placeholder = { Text("Book and page, website, Mum…") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { gate { viewModel.send() } }, enabled = state.canSend,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (state.sending) CircularProgressIndicator(Modifier.padding(end = 8.dp)) else Text("Send to my PC")
            }
        }
    }
}

private val EXAMPLES = listOf(
    "A quick weeknight dal for 4",
    "Something with leftover roast chicken, ready in 30 minutes",
    "A vegetarian shepherd's pie",
    "Banana bread without eggs",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DescribeForm(state: AddRecipeState, onRequest: (String) -> Unit) {
    Text(
        "Say what you'd like to cook. Your PC asks Claude to write a recipe in the same format as the rest of your " +
            "library. It's marked Needs review so you can check it before you cook.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (state.features?.canImport == true && !state.features.canRequest) {
        Notice(Icons.Outlined.ErrorOutline, "Your PC can't write recipes yet",
            "Update the PC server, and log in to Claude Code on the PC (or set ANTHROPIC_API_KEY), then restart the server.")
    }
    OutlinedTextField(
        value = state.request, onValueChange = onRequest, label = { Text("What would you like to cook?") },
        placeholder = { Text("A quick weeknight dal for 4") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
    )
    Text("Or start from one of these:", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EXAMPLES.forEach { e -> SuggestionChip(onClick = { onRequest(e) }, label = { Text(e) }) }
    }
}

@Composable
private fun JobStatus(job: ImportJob, onOpen: () -> Unit, onAnother: () -> Unit) {
    val asked = job.kind == "request"
    val (icon, title, body) = if (asked) when (job.status) {
        "queued", "converting" -> Triple(null, "Writing your recipe on your PC…", "This takes a minute or two. You can leave this screen; it'll appear in your library.")
        "done", "needs_review" -> Triple(Icons.Filled.CheckCircle, "Your recipe is ready",
            "It's marked Needs review: have a read (and go through the notes) before you cook it.")
        else -> Triple(Icons.Outlined.ErrorOutline, "Couldn't write it", job.error ?: "Try saying it a different way.")
    } else when (job.status) {
        "queued", "converting" -> Triple(null, "Converting on your PC…", "This usually takes under a minute. You can leave this screen; it'll appear in your library.")
        "done" -> Triple(Icons.Filled.CheckCircle, "Added to your library", "")
        "needs_review" -> Triple(Icons.Filled.CheckCircle, "Added — please give it a quick check",
            "The converter flagged something it wasn't sure about. Open it to see what.")
        "saved" -> Triple(Icons.Outlined.Inbox, "Saved to your PC's inbox", "Run convert on the PC to turn it into a recipe (or start the server with an API key).")
        else -> Triple(Icons.Outlined.ErrorOutline, "Conversion failed", job.error ?: "Try tidying the text and sending it again.")
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon == null) CircularProgressIndicator(Modifier.padding(end = 12.dp)) else Icon(icon, null, Modifier.padding(end = 12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (job.recipeId != null) Button(onClick = onOpen) { Text("Open recipe") }
                if (job.finished) {
                    OutlinedButton(onClick = onAnother) {
                        Text(if (job.status == "failed") "Edit and retry" else if (asked) "Ask for another" else "Add another")
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
