package app.mise.ui.plans

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mise.data.db.ShoppingItemEntity
import app.mise.data.db.ShoppingListSummary
import app.mise.data.repo.PlansRepository
import app.mise.data.repo.ShoppingListDetail
import app.mise.ui.components.EmptyState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------------------- all lists

@HiltViewModel
class ShoppingListsViewModel @Inject constructor(plans: PlansRepository) : ViewModel() {
    val lists: StateFlow<List<ShoppingListSummary>?> =
        plans.shoppingLists().map<List<ShoppingListSummary>, List<ShoppingListSummary>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListsScreen(viewModel: ShoppingListsViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Shopping lists") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        val all = lists
        when {
            all == null -> {}
            all.isEmpty() -> EmptyState(
                Icons.Filled.ShoppingCart, "No shopping lists yet",
                "In your recipes, long-press one recipe, tap any others you're cooking, then tap Shopping list.",
                Modifier.padding(padding),
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(all, key = { it.id }) { l ->
                    ListItem(
                        headlineContent = { Text(l.name) },
                        supportingContent = { Text("${l.done} of ${l.total} ticked · made ${formatDayTime(l.createdAt)}") },
                        modifier = Modifier.clickable { onOpen(l.id) }.heightIn(min = 64.dp),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- one list

@HiltViewModel(assistedFactory = ShoppingListViewModel.Factory::class)
class ShoppingListViewModel @AssistedInject constructor(
    @Assisted val listId: Long,
    private val plans: PlansRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(listId: Long): ShoppingListViewModel
    }

    val detail: StateFlow<ShoppingListDetail?> =
        plans.shoppingList(listId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggle(item: ShoppingItemEntity) = viewModelScope.launch { plans.setChecked(item.id, !item.checked) }
    fun addExtra(text: String) = viewModelScope.launch { plans.addExtra(listId, text) }
    fun deleteItem(item: ShoppingItemEntity) = viewModelScope.launch { plans.deleteItem(item.id) }
    fun uncheckAll() = viewModelScope.launch { plans.uncheckAll(listId) }
    fun delete(then: () -> Unit) = viewModelScope.launch { plans.deleteList(listId); then() }
    suspend fun shareText(): String? = plans.shareText(listId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(viewModel: ShoppingListViewModel, onBack: () -> Unit) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var extra by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(detail?.list?.name.orEmpty(), maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = {
                    scope.launch {
                        val text = viewModel.shareText() ?: return@launch
                        context.startActivity(
                            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share shopping list"),
                        )
                    }
                }) { Icon(Icons.Filled.Share, "Share list") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete list") }
            },
        )
    }) { padding ->
        val d = detail ?: return@Scaffold
        val total = d.items.size
        val done = d.items.count { it.checked }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "head") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (d.recipes.isNotEmpty()) {
                        Text("For " + d.recipes.joinToString(", ") { "${it.title} (${app.mise.core.scale.Servings.format(it.servings)})" },
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$done of $total in the basket", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (done > 0) TextButton(onClick = viewModel::uncheckAll) { Text("Untick all") }
                    }
                }
            }
            for ((aisle, items) in d.byAisle) {
                item(key = "a-$aisle") {
                    Text(aisle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() })
                }
                items(items, key = { "i-${it.id}" }) { item -> ItemRow(item, viewModel) }
            }
            item(key = "add") {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = extra, onValueChange = { extra = it }, label = { Text("Add something else") },
                        singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel.addExtra(extra); extra = "" }),
                    )
                    IconButton(onClick = { viewModel.addExtra(extra); extra = "" }, enabled = extra.isNotBlank()) {
                        Icon(Icons.Filled.Add, "Add to list")
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this list?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ItemRow(item: ShoppingItemEntity, viewModel: ShoppingListViewModel) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(item.checked, role = Role.Checkbox, onValueChange = { viewModel.toggle(item) })
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.label,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (item.checked) 0.5f else 1f),
            )
            if (item.recipes.contains('\n')) {
                Text(item.recipes.replace("\n", " · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (item.extra) IconButton(onClick = { viewModel.deleteItem(item) }) { Icon(Icons.Filled.Close, "Remove ${item.label}") }
    }
}
