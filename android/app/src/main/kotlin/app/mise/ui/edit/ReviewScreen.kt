package app.mise.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mise.core.edit.FlagTarget
import app.mise.core.edit.RecipeEditor
import app.mise.core.model.IngredientEdit
import app.mise.core.model.Recipe
import app.mise.core.model.RecipeYield
import app.mise.core.model.ReviewFlag
import app.mise.core.model.StepEdit
import app.mise.core.render.StepRenderer
import app.mise.core.units.UnitMode
import app.mise.data.repo.RecipeDetail
import app.mise.data.repo.RecipeRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = ReviewViewModel.Factory::class)
class ReviewViewModel @AssistedInject constructor(
    @Assisted val recipeId: String,
    private val repository: RecipeRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(recipeId: String): ReviewViewModel
    }

    val detail: StateFlow<RecipeDetail?> =
        repository.detail(recipeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The original recipe text, for showing what the source said (null until loaded / if unavailable). */
    val source = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { source.value = repository.sourceText(recipeId).getOrNull() }
    }

    private fun allPaths() = detail.value?.original?.reviewFlags?.map { it.path }.orEmpty()

    fun looksRight(flag: ReviewFlag) = viewModelScope.launch { repository.checkFlag(recipeId, flag.path, allPaths()) }

    fun saveIngredient(flag: ReviewFlag, id: String, edit: IngredientEdit) = viewModelScope.launch {
        val original = detail.value?.original?.ingredient(id)
        repository.editIngredient(recipeId, id, edit.takeUnless { original != null && it == RecipeEditor.editOf(original) })
        repository.checkFlag(recipeId, flag.path, allPaths())
    }

    fun saveStep(flag: ReviewFlag, id: String, edit: StepEdit) = viewModelScope.launch {
        val original = detail.value?.original?.steps?.firstOrNull { it.id == id }
        repository.editStep(recipeId, id, edit.takeUnless { original != null && it == RecipeEditor.editOf(original) })
        repository.checkFlag(recipeId, flag.path, allPaths())
    }

    fun saveServings(flag: ReviewFlag, y: RecipeYield) = viewModelScope.launch {
        repository.editServings(recipeId, y.takeUnless { it == detail.value?.original?.recipeYield })
        repository.checkFlag(recipeId, flag.path, allPaths())
    }

    fun saveTitle(flag: ReviewFlag, title: String) = viewModelScope.launch {
        repository.editTitle(recipeId, title.takeUnless { it.trim() == detail.value?.original?.title })
        repository.checkFlag(recipeId, flag.path, allPaths())
    }
}

/**
 * Lines of the original text that mention [words], best matches first (short lines, like an
 * ingredient line, beat a long method paragraph), with long lines cut down to the part that matters.
 */
fun excerpt(source: String?, words: List<String>, max: Int = 3): List<String> {
    if (source.isNullOrBlank()) return emptyList()
    val keys = words.flatMap { it.lowercase().split(Regex("[^\\p{L}]+")) }.filter { it.length >= 4 }.distinct()
    if (keys.isEmpty()) return emptyList()
    val scored = source.lines().map(String::trim).filter(String::isNotEmpty)
        .map { line -> line to keys.count { it in line.lowercase() } }
    val best = scored.maxOfOrNull { it.second } ?: 0
    if (best == 0) return emptyList()
    return scored.filter { it.second == best } // "1 lemongrass stick" doesn't count as a cinnamon stick
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
        .take(max)
        .map { (line, _) -> shorten(line, keys) }
}

private fun shorten(line: String, keys: List<String>, around: Int = 60): String {
    if (line.length <= 2 * around + 20) return line
    val at = keys.mapNotNull { k -> line.lowercase().indexOf(k).takeIf { it >= 0 } }.minOrNull() ?: 0
    val start = (at - around).coerceAtLeast(0)
    val end = (at + around).coerceAtMost(line.length)
    return (if (start > 0) "…" else "") + line.substring(start, end).trim() + (if (end < line.length) "…" else "")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(viewModel: ReviewViewModel, onBack: () -> Unit, onOriginal: () -> Unit, onEditAll: () -> Unit) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    var index by rememberSaveable { mutableIntStateOf(-1) } // -1 = start at the first unchecked flag

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Check the conversion") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        val d = detail ?: return@Scaffold
        val flags = d.original.reviewFlags
        val checked = d.edits.checkedFlags.toSet()
        LaunchedEffect(flags) {
            if (index == -1) index = flags.indexOfFirst { it.path !in checked }.let { if (it == -1) flags.size else it }
        }
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (flags.isEmpty() || index >= flags.size) {
                Finished(flags.size, flags.count { it.path in checked }, onBack, onEditAll) { index = flags.indexOfFirst { it.path !in checked } }
                return@Column
            }
            if (index < 0) return@Column
            val flag = flags[index]
            val next = {
                // The next one not checked yet, after this one; then any earlier one; then done.
                val after = (index + 1 until flags.size).firstOrNull { flags[it].path !in checked && flags[it] != flag }
                val before = (0 until index).firstOrNull { flags[it].path !in checked }
                index = after ?: before ?: flags.size
            }
            Text("${index + 1} of ${flags.size}", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress = { checked.size.toFloat() / flags.size }, modifier = Modifier.fillMaxWidth())
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(16.dp)) {
                    Icon(Icons.Outlined.RateReview, null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("The converter wasn't sure:", style = MaterialTheme.typography.labelLarge)
                        Text(flag.message, style = MaterialTheme.typography.bodyLarge)
                        if (flag.path in checked) Text("✓ Checked", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            FlagStep(d, flag, source, onOriginal, viewModel, next, key = "${flag.path}#$index")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (index > 0) TextButton(onClick = { index -= 1 }) { Text("Previous") }
                TextButton(onClick = { index = if (index + 1 < flags.size) index + 1 else flags.size }) { Text("Skip for now") }
            }
        }
    }
}

@Composable
private fun FlagStep(
    d: RecipeDetail,
    flag: ReviewFlag,
    source: String?,
    onOriginal: () -> Unit,
    vm: ReviewViewModel,
    next: () -> Unit,
    key: String,
) {
    val recipe: Recipe = d.recipe
    when (val target = RecipeEditor.target(d.original, flag)) {
        is FlagTarget.IngredientTarget -> {
            val current = recipe.ingredient(target.ingredient.id) ?: target.ingredient
            Original(excerpt(source, listOf(current.name, current.shortName)), onOriginal)
            Text("In the recipe now: ${ingredientLine(current)}", style = MaterialTheme.typography.titleSmall)
            val draft = remember(key, current) { IngredientDraft(RecipeEditor.editOf(current)) }
            var tried by remember(key) { mutableStateOf(false) }
            val problems = if (tried) draft.result().second else emptyList()
            IngredientEditor(draft, problems)
            Decide(changed = { draft.result().first != RecipeEditor.editOf(current) }, onLooksRight = { vm.looksRight(flag); next() }) {
                val edit = draft.result().first
                tried = true
                if (edit != null) { vm.saveIngredient(flag, current.id, edit); next() }
            }
        }
        is FlagTarget.StepTarget -> {
            val current = recipe.steps.firstOrNull { it.id == target.step.id } ?: target.step
            val text = StepRenderer.plainText(current.content, recipe, 1.0, UnitMode.ORIGINAL)
            Original(excerpt(source, listOf(current.title) + text.split(" ").take(8)), onOriginal)
            val draft = remember(key, current) { StepDraft(RecipeEditor.editOf(current)) }
            var tried by remember(key) { mutableStateOf(false) }
            val problems = if (tried) draft.result().second else emptyList()
            StepEditor(draft, problems, content = "Step ${target.index + 1}: $text")
            Decide(changed = { draft.result().first != RecipeEditor.editOf(current) }, onLooksRight = { vm.looksRight(flag); next() }) {
                val edit = draft.result().first
                tried = true
                if (edit != null) { vm.saveStep(flag, current.id, edit); next() }
            }
        }
        FlagTarget.Servings -> {
            Original(excerpt(source, listOf("serves", "makes", "portions", "servings")), onOriginal)
            val draft = remember(key, recipe.recipeYield) { ServingsDraft(recipe.recipeYield) }
            var tried by remember(key) { mutableStateOf(false) }
            val problems = if (tried) draft.result().second else emptyList()
            ServingsEditor(draft, problems)
            Decide(changed = { draft.result().first != recipe.recipeYield }, onLooksRight = { vm.looksRight(flag); next() }) {
                val y = draft.result().first
                tried = true
                if (y != null) { vm.saveServings(flag, y); next() }
            }
        }
        FlagTarget.Title -> {
            var text by remember(key) { mutableStateOf(recipe.title) }
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Title") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Decide(changed = { text.trim() != recipe.title }, onLooksRight = { vm.looksRight(flag); next() }) {
                if (text.isNotBlank()) { vm.saveTitle(flag, text); next() }
            }
        }
        FlagTarget.Other -> {
            Original(emptyList(), onOriginal)
            Text(
                "There's nothing to change in the app for this one: read the note, compare with the original if you " +
                    "like, and carry on.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { vm.looksRight(flag); next() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("OK, next")
            }
        }
    }
}

@Composable
private fun Original(lines: List<String>, onOriginal: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (lines.isNotEmpty()) {
            Text("Your original text says:", style = MaterialTheme.typography.labelLarge)
            lines.forEach { Text("“$it”", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
        }
        TextButton(onClick = onOriginal) { Text("See the whole original") }
    }
}

/** "Looks right" when nothing changed, "Save & next" once something has. */
@Composable
private fun Decide(changed: () -> Boolean, onLooksRight: () -> Unit, onSave: () -> Unit) {
    val isChanged = changed()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isChanged) {
            Button(onClick = onSave, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save & next") }
            OutlinedButton(onClick = onLooksRight) { Text("Keep as it was") }
        } else {
            Button(onClick = onLooksRight, modifier = Modifier.heightIn(min = 48.dp)) { Text("Looks right") }
        }
    }
}

@Composable
private fun Finished(total: Int, done: Int, onBack: () -> Unit, onEditAll: () -> Unit, onContinue: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
        if (done >= total) {
            Text("All checked", style = MaterialTheme.typography.headlineSmall)
            Text("The recipe is marked as reviewed. You can still change anything with Edit recipe.",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onBack) { Text("Back to the recipe") }
        } else {
            Text("${total - done} still to check", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onContinue) { Text("Continue checking") }
            TextButton(onClick = onBack) { Text("Later") }
        }
        TextButton(onClick = onEditAll) { Text("Edit anything else") }
    }
}
