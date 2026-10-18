package app.mise.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mise.core.model.RecipeStatus
import app.mise.data.repo.LibraryItem
import app.mise.data.settings.SourceKind
import app.mise.ui.LocalNetworkPermission
import app.mise.ui.components.EmptyState
import app.mise.ui.components.StatusBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    selectedId: String?,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Recipes") },
                actions = {
                    IconButton(onClick = viewModel::toggleFavorites) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = if (state.filters.favoritesOnly) "Show all recipes" else "Show favorites only",
                            tint = if (state.filters.favoritesOnly) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "search") {
                    OutlinedTextField(
                        value = state.filters.query,
                        onValueChange = viewModel::setQuery,
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        placeholder = { Text("Search titles, tags, ingredients") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (state.tags.isNotEmpty()) item(key = "tags") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.tags, key = { it }) { tag ->
                            FilterChip(
                                selected = tag in state.filters.tags,
                                onClick = { viewModel.toggleTag(tag) },
                                label = { Text(tag) },
                            )
                        }
                    }
                }
                state.banner?.let { b ->
                    item(key = "banner") {
                        val context = LocalContext.current
                        val permissionMissing = state.source == SourceKind.LAN && LocalNetworkPermission.needed(context)
                        StatusBanner(
                            if (permissionMissing) "$b. ${LocalNetworkPermission.EXPLANATION}" else b,
                            Modifier.padding(16.dp, 8.dp).then(
                                if (permissionMissing) Modifier.clickable {
                                    context.startActivity(LocalNetworkPermission.appSettingsIntent(context))
                                } else Modifier,
                            ),
                        )
                    }
                }
                if (state.source == SourceKind.SAMPLE && !state.loading) item(key = "demo") {
                    StatusBanner(
                        "This is the built-in demo recipe. Pair with your PC in Settings to see your own library.",
                        Modifier.padding(16.dp, 8.dp).clickable(onClick = onSettings),
                        icon = Icons.Outlined.Info,
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                }
                if (state.recentlyCooked.isNotEmpty()) {
                    item(key = "recent-h") { SectionHeader("Recently cooked") }
                    item(key = "recent") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.recentlyCooked, key = { "r-" + it.id }) { r ->
                                AssistChip(onClick = { onOpen(r.id) }, label = { Text(r.title, maxLines = 1) })
                            }
                        }
                    }
                    item(key = "all-h") { SectionHeader(if (state.filters.active) "Results" else "All recipes") }
                }
                when {
                    state.loading -> item(key = "loading") {
                        Row(Modifier.fillMaxWidth().padding(48.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    state.total == 0 -> item(key = "empty") {
                        EmptyState(
                            Icons.AutoMirrored.Outlined.MenuBook,
                            "No recipes yet",
                            if (state.refreshing) "Syncing…" else "Pair with your PC or pick a recipes folder in Settings, then pull down to refresh.",
                            action = "Open Settings",
                            onAction = onSettings,
                        )
                    }
                    state.items.isEmpty() -> item(key = "nomatch") {
                        EmptyState(Icons.Outlined.SearchOff, "No matches", "Try fewer words or clear the filters.",
                            action = "Clear filters", onAction = viewModel::clearFilters)
                    }
                    else -> items(state.items, key = { it.id }) { item ->
                        RecipeRow(item, selected = item.id == selectedId, onClick = { onOpen(item.id) })
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { contentDescription = "$text section" },
    )
}

@Composable
private fun RecipeRow(item: LibraryItem, selected: Boolean, onClick: () -> Unit) {
    val needsReview = item.status == RecipeStatus.NEEDS_REVIEW
    ListItem(
        headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (item.tags.isEmpty()) null else ({ Text(item.tags.joinToString(" · "), maxLines = 1) }),
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (needsReview) {
                    Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                        Icon(Icons.Outlined.RateReview, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Needs review", Modifier.padding(vertical = 2.dp))
                    }
                }
                if (item.favorite) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Favorite, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else ListItemDefaults.colors(),
        modifier = Modifier
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                stateDescription = listOfNotNull(
                    "needs review".takeIf { needsReview },
                    "favorite".takeIf { item.favorite },
                    "selected".takeIf { selected },
                ).joinToString(", ")
            },
    )
}
