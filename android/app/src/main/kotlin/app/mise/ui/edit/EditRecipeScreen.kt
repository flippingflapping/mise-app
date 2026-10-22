package app.mise.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mise.core.edit.RecipeEditor
import app.mise.core.model.IngredientEdit
import app.mise.core.model.RecipeYield
import app.mise.core.model.StepEdit
import app.mise.data.repo.RecipeDetail
import app.mise.data.repo.RecipeRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = EditRecipeViewModel.Factory::class)
class EditRecipeViewModel @AssistedInject constructor(
    @Assisted val recipeId: String,
    private val repository: RecipeRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(recipeId: String): EditRecipeViewModel
    }

    val detail: StateFlow<RecipeDetail?> =
        repository.detail(recipeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveIngredient(id: String, edit: IngredientEdit?) = viewModelScope.launch {
        val original = detail.value?.original?.ingredient(id)
        // Saving it exactly as converted is the same as undoing the edit.
        repository.editIngredient(recipeId, id, edit?.takeUnless { original != null && it == RecipeEditor.editOf(original) })
    }

    fun saveStep(id: String, edit: StepEdit?) = viewModelScope.launch {
        val original = detail.value?.original?.steps?.firstOrNull { it.id == id }
        repository.editStep(recipeId, id, edit?.takeUnless { original != null && it == RecipeEditor.editOf(original) })
    }

    fun saveServings(y: RecipeYield?) = viewModelScope.launch {
        repository.editServings(recipeId, y?.takeUnless { it == detail.value?.original?.recipeYield })
    }

    fun saveTitle(title: String) = viewModelScope.launch {
        repository.editTitle(recipeId, title.takeUnless { it.trim() == detail.value?.original?.title })
    }

    fun resetAll() = viewModelScope.launch { repository.resetEdits(recipeId) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditRecipeScreen(viewModel: EditRecipeViewModel, onBack: () -> Unit) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<String?>(null) } // "title", "servings", "i:0003", "s:s2"
    var confirmReset by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Edit recipe") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Done") } },
        )
    }) { padding ->
        val d = detail ?: return@Scaffold
        val recipe = d.recipe
        val edits = d.edits
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "intro") {
                Text(
                    "Tap anything to change it. Your changes are kept separately from the converted recipe, " +
                        "so you can always go back to the original.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp),
                )
            }
            item(key = "title") {
                EditableRow(
                    label = "Title", value = recipe.title, edited = edits.title != null, isOpen = open == "title",
                    onOpen = { open = "title" },
                ) {
                    var text by rememberSaveable { mutableStateOf(recipe.title) }
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Title") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    EditorButtons(
                        onSave = { if (text.isNotBlank()) { viewModel.saveTitle(text); open = null } },
                        onCancel = { open = null },
                        onUndo = if (edits.title != null) ({ viewModel.saveTitle(d.original.title); open = null }) else null,
                    )
                }
            }
            item(key = "servings") {
                val y = recipe.recipeYield
                EditableRow(
                    label = "Makes", value = "${RecipeEditor.formatAmount(y.baseServings)} ${y.label ?: "servings"}",
                    edited = edits.recipeYield != null, isOpen = open == "servings", onOpen = { open = "servings" },
                ) {
                    val draft = remember(y) { ServingsDraft(y) }
                    var tried by remember { mutableStateOf(false) }
                    val problems = if (tried) draft.result().second else emptyList()
                    ServingsEditor(draft, problems)
                    EditorButtons(
                        onSave = {
                            val result = draft.result().first
                            tried = true
                            if (result != null) { viewModel.saveServings(result); open = null }
                        },
                        onCancel = { open = null },
                        onUndo = if (edits.recipeYield != null) ({ viewModel.saveServings(null); open = null }) else null,
                    )
                }
            }
            item(key = "ing-h") { SectionTitle("Ingredients") }
            items(recipe.ingredients, key = { "i:" + it.id }) { ing ->
                val key = "i:" + ing.id
                val i = recipe.ingredients.indexOf(ing)
                // The group name ("Marinade") once, above its first ingredient.
                val group = ing.group?.takeIf { it != recipe.ingredients.getOrNull(i - 1)?.group }
                EditableRow(
                    label = group, value = ingredientLine(ing), edited = ing.id in edits.ingredients,
                    isOpen = open == key, onOpen = { open = key },
                ) {
                    val draft = remember(ing) { IngredientDraft(RecipeEditor.editOf(ing)) }
                    var tried by remember { mutableStateOf(false) }
                    val problems = if (tried) draft.result().second else emptyList()
                    IngredientEditor(draft, problems)
                    EditorButtons(
                        onSave = {
                            val result = draft.result().first
                            tried = true
                            if (result != null) { viewModel.saveIngredient(ing.id, result); open = null }
                        },
                        onCancel = { open = null },
                        onUndo = if (ing.id in edits.ingredients) ({ viewModel.saveIngredient(ing.id, null); open = null }) else null,
                    )
                }
            }
            item(key = "steps-h") { SectionTitle("Steps") }
            items(recipe.steps, key = { "s:" + it.id }) { step ->
                val key = "s:" + step.id
                val index = recipe.steps.indexOf(step) + 1
                EditableRow(
                    label = "Step $index", value = step.title + (stepTimerText(step)?.let { " · ⏱ $it" } ?: ""),
                    edited = step.id in edits.steps, isOpen = open == key, onOpen = { open = key },
                ) {
                    val draft = remember(step) { StepDraft(RecipeEditor.editOf(step)) }
                    var tried by remember { mutableStateOf(false) }
                    val problems = if (tried) draft.result().second else emptyList()
                    StepEditor(draft, problems, content = null)
                    EditorButtons(
                        onSave = {
                            val result = draft.result().first
                            tried = true
                            if (result != null) { viewModel.saveStep(step.id, result); open = null }
                        },
                        onCancel = { open = null },
                        onUndo = if (step.id in edits.steps) ({ viewModel.saveStep(step.id, null); open = null }) else null,
                    )
                }
            }
            if (!edits.isEmpty) item(key = "reset") {
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.padding(16.dp)) {
                    Text("Undo all my edits")
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Undo all your edits?") },
            text = { Text("The recipe goes back to exactly how it was converted.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; viewModel.resetAll() }) { Text("Undo all") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** One line ("2 tsp minced ginger"); tapped, it opens its editor in place. */
@Composable
private fun EditableRow(
    label: String?,
    value: String,
    edited: Boolean,
    isOpen: Boolean,
    onOpen: () -> Unit,
    editor: @Composable () -> Unit,
) {
    if (isOpen) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                label?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                editor()
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = "edit", onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = if (edited) FontWeight.SemiBold else null)
        }
        if (edited) AssistChip(onClick = onOpen, label = { Text("Edited") })
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}
