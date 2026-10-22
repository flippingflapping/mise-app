package app.mise.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mise.core.model.Ingredient
import app.mise.core.model.Recipe
import app.mise.core.model.RecipeStatus
import app.mise.core.scale.AmountRenderer
import app.mise.core.scale.ScaleByIngredient
import app.mise.core.scale.Servings
import app.mise.core.timer.StepTimers
import app.mise.core.timer.StepTiming
import app.mise.core.units.UnitMode
import app.mise.ui.components.HeatBadge
import app.mise.ui.components.StatusBanner
import app.mise.ui.components.StepText
import app.mise.ui.timers.TimerActions
import app.mise.ui.timers.TimerTray
import app.mise.ui.timers.rememberTimerStarter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    viewModel: RecipeDetailViewModel,
    onBack: (() -> Unit)?,
    onCook: (stepIndex: Int) -> Unit,
    onOriginal: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit = {},
    onReview: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val image by viewModel.image.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var scaleTarget by remember { mutableStateOf<Ingredient?>(null) }
    var editingNotes by remember { mutableStateOf(false) }
    val startTimer = rememberTimerStarter(onDenied = {
        scope.launch { snackbar.showSnackbar("Notifications are off — timers will only ring while Mise is open.") }
    })
    val detail = state.detail
    val timerActions = remember(viewModel) { controllerActions(viewModel) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(detail?.recipe?.title.orEmpty(), maxLines = 1) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (detail != null) {
                        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit recipe") }
                        IconButton(onClick = viewModel::toggleFavorite) {
                            if (detail.favorite) Icon(Icons.Filled.Favorite, "Remove from favorites", tint = MaterialTheme.colorScheme.primary)
                            else Icon(Icons.Filled.FavoriteBorder, "Add to favorites")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (detail != null) {
                ExtendedFloatingActionButton(
                    onClick = { onCook(0) },
                    icon = { Icon(Icons.Filled.PlayArrow, null) },
                    text = { Text("Start cooking") },
                )
            }
        },
        bottomBar = { TimerTray(state.timers, timerActions) },
    ) { padding ->
        if (detail == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(if (state.loading) "Loading…" else "This recipe isn't in your library any more.")
            }
            return@Scaffold
        }
        val recipe = detail.recipe
        val factor = state.factor
        val mode = state.unitMode
        val groups = remember(recipe) { recipe.ingredientGroups() }

        // Indices of ingredient rows in the list, so a chip tap can scroll to its ingredient.
        val showFlags = !detail.flagsDismissed && recipe.reviewFlags.isNotEmpty() && detail.status == RecipeStatus.NEEDS_REVIEW
        val ingredientIndex = remember(recipe, showFlags, image != null) {
            buildIngredientIndex(recipe, groups, showFlags = showFlags, hasImage = image != null)
        }
        LaunchedEffect(state.highlighted) {
            state.highlighted?.let { id -> ingredientIndex[id]?.let { listState.animateScrollToItem(it, -200) } }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            image?.let { img ->
                item(key = "image") {
                    Image(img, contentDescription = "Photo of ${recipe.title}", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                }
            }
            item(key = "header") { Header(recipe, state.servings, factor) }
            if (recipe.advancePrep.isNotEmpty()) item(key = "advance") { AdvancePrepBanner(recipe.advancePrep) }
            if (showFlags) {
                item(key = "flags") {
                    ReviewFlagsCard(recipe, detail.edits.checkedFlags.size, onReview, viewModel::markReviewed, viewModel::dismissFlags)
                }
            }
            item(key = "controls") {
                Controls(
                    servings = state.servings,
                    scaled = kotlin.math.abs(factor - 1) > 1e-9,
                    mode = mode,
                    onIncrement = viewModel::increment,
                    onDecrement = viewModel::decrement,
                    onReset = viewModel::resetScale,
                    onMode = viewModel::setUnitMode,
                )
            }
            item(key = "ing-h") {
                SectionTitle("Ingredients", trailing = if (detail.checked.isNotEmpty()) ({
                    TextButton(onClick = viewModel::clearChecked) { Text("Clear ticks") }
                }) else null)
            }
            for ((group, ings) in groups) {
                if (group != null) item(key = "g-$group") {
                    Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
                }
                for (ing in ings) item(key = "i-${ing.id}") {
                    IngredientRow(
                        ingredient = ing,
                        factor = factor,
                        mode = mode,
                        checked = ing.id in detail.checked,
                        highlighted = state.highlighted == ing.id,
                        onToggle = { viewModel.toggleChecked(ing.id) },
                        onScaleBy = if (ScaleByIngredient.canScaleBy(ing)) ({ scaleTarget = ing }) else null,
                    )
                }
            }
            item(key = "steps-h") { SectionTitle("Method") }
            recipe.steps.forEachIndexed { index, step ->
                item(key = "s-${step.id}") {
                    StepCard(
                        number = index + 1,
                        step = step,
                        recipe = recipe,
                        factor = factor,
                        mode = mode,
                        onIngredient = viewModel::highlight,
                        onStartTimer = { spec -> startTimer { viewModel.timers.start(recipe, step, spec) } },
                        onCookFromHere = { onCook(index) },
                    )
                }
            }
            if (!recipe.notes.isNullOrBlank()) item(key = "notes") {
                SectionTitle("Notes")
                Text(recipe.notes!!, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
            }
            item(key = "mynotes") {
                MyNotes(detail.cookNotes, state.notesSyncToPc) { editingNotes = true }
            }
            item(key = "cooked") {
                CookLog(detail.lastCooked, detail.cookLog.size) {
                    viewModel.madeThis()
                    scope.launch { snackbar.showSnackbar("Logged today. Enjoy!") }
                }
            }
            if (state.related.isNotEmpty()) item(key = "related") {
                SectionTitle("Related recipes")
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.related.forEach { r ->
                        AssistChip(
                            onClick = { if (r.available) onOpenRecipe(r.id) },
                            enabled = r.available,
                            label = { Text(if (r.available) r.title else "${r.title} (not in your library)") },
                        )
                    }
                }
            }
            item(key = "source") { SourceSection(recipe, onOriginal) }
        }
    }

    scaleTarget?.let { ing ->
        ScaleByIngredientDialog(
            ingredient = ing,
            baseServings = detail?.recipe?.recipeYield?.baseServings ?: 1.0,
            onDismiss = { scaleTarget = null },
            onApply = { amount, unit ->
                viewModel.scaleBy(ing.id, amount, unit)
                scaleTarget = null
            },
        )
    }
    if (editingNotes && detail != null) {
        NotesEditor(detail.cookNotes, onDismiss = { editingNotes = false }) {
            viewModel.setCookNotes(it)
            editingNotes = false
        }
    }
}

private fun controllerActions(vm: RecipeDetailViewModel) = object : TimerActions {
    override fun pause(id: String) = vm.timers.pause(id)
    override fun resume(id: String) = vm.timers.resume(id)
    override fun addMinute(id: String) = vm.timers.addMinute(id)
    override fun toMax(id: String) = vm.timers.toMax(id)
    override fun dismiss(id: String) = vm.timers.dismiss(id)
}

/** Mirrors the LazyColumn's item order up to the ingredient rows. */
private fun buildIngredientIndex(
    recipe: Recipe,
    groups: List<Pair<String?, List<Ingredient>>>,
    showFlags: Boolean,
    hasImage: Boolean,
): Map<String, Int> {
    var i = 0
    if (hasImage) i++
    i++ // header
    if (recipe.advancePrep.isNotEmpty()) i++
    if (showFlags) i++
    i++ // controls
    i++ // ingredients title
    val out = mutableMapOf<String, Int>()
    for ((group, ings) in groups) {
        if (group != null) i++
        for (ing in ings) out[ing.id] = i++
    }
    return out
}

@Composable
private fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 24.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

@Composable
private fun Header(recipe: Recipe, servings: Double, factor: Double) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(recipe.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        recipe.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        val label = recipe.recipeYield.label?.takeIf { it.isNotBlank() } ?: if (servings == 1.0) "serving" else "servings"
        val scaled = if (kotlin.math.abs(factor - 1) > 1e-9) " (recipe makes ${Servings.format(recipe.recipeYield.baseServings)})" else ""
        Text("Serves ${Servings.format(servings)} $label$scaled", style = MaterialTheme.typography.titleMedium)
        recipe.times?.let { t ->
            val parts = listOfNotNull(
                t.prepMinutes?.let { "Prep ${StepTimers.formatDuration(it * 60)}" },
                t.cookMinutes?.let { "Cook ${StepTimers.formatDuration(it * 60)}" },
                t.totalMinutes?.let { "Total ${StepTimers.formatDuration(it * 60)}" },
            )
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
        if (recipe.tags.isNotEmpty()) {
            Text(recipe.tags.joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AdvancePrepBanner(items: List<String>, modifier: Modifier = Modifier, large: Boolean = false) {
    val overnight = items.any { it.contains("overnight", ignoreCase = true) || it.contains("day before", ignoreCase = true) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.Alarm, null, Modifier.size(if (large) 36.dp else 28.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    if (overnight) "Start the day before" else "Plan ahead",
                    style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                items.forEach {
                    Text(it, style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun ReviewFlagsCard(recipe: Recipe, checked: Int, onReview: () -> Unit, onReviewed: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.RateReview, null)
                Spacer(Modifier.width(8.dp))
                Text("Check the conversion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            val n = recipe.reviewFlags.size
            Text(
                "The converter wasn't sure about $n thing${if (n == 1) "" else "s"}" +
                    (if (checked in 1 until n) " ($checked checked so far)" else "") +
                    (if (n == 1) ". Check it, and fix it if it's off:" else ". Go through them one at a time and fix anything that's off:"),
                style = MaterialTheme.typography.bodyMedium,
            )
            recipe.reviewFlags.take(3).forEach { f ->
                Text("• ${f.message}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (n > 3) Text("…and ${n - 3} more", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onReview) { Text(if (checked in 1 until n) "Carry on" else if (n == 1) "Check it" else "Check them") }
                TextButton(onClick = onReviewed) { Text("All fine") }
                TextButton(onClick = onDismiss) { Text("Hide") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Controls(
    servings: Double,
    scaled: Boolean,
    mode: UnitMode,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onReset: () -> Unit,
    onMode: (UnitMode) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Servings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilledTonalIconButton(onClick = onDecrement, enabled = Servings.canDecrement(servings)) {
                Icon(Icons.Filled.Remove, "Fewer servings")
            }
            Text(
                Servings.format(servings),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.widthIn(min = 56.dp).padding(horizontal = 8.dp)
                    .semantics { contentDescription = "${Servings.format(servings)} servings" },
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            FilledTonalIconButton(onClick = onIncrement) { Icon(Icons.Filled.Add, "More servings") }
            if (scaled) TextButton(onClick = onReset) { Text("Reset") }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            UnitMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = m == mode,
                    onClick = { onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, UnitMode.entries.size),
                ) { Text(m.label) }
            }
        }
    }
}

@Composable
private fun IngredientRow(
    ingredient: Ingredient,
    factor: Double,
    mode: UnitMode,
    checked: Boolean,
    highlighted: Boolean,
    onToggle: () -> Unit,
    onScaleBy: (() -> Unit)?,
) {
    val amount = remember(ingredient, factor, mode) { AmountRenderer.render(ingredient, factor, mode) }
    val bg by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, label = "highlight",
    )
    val dim = if (checked) 0.5f else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val amountText = amount.text
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (onScaleBy != null) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                    modifier = if (onScaleBy != null) Modifier.clickable(
                        onClickLabel = "scale the recipe to the amount of ${ingredient.shortName} you have",
                        onClick = onScaleBy,
                    ) else Modifier,
                ) {
                    Text(
                        amountText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            .semantics { contentDescription = amount.spoken },
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    ingredient.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            val extra = listOfNotNull(
                ingredient.prep,
                "optional".takeIf { ingredient.optional },
                "doesn't scale".takeIf { !ingredient.scales && kotlin.math.abs(factor - 1) > 1e-9 },
            )
            if (extra.isNotEmpty()) {
                Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim))
            }
            ingredient.substitutes?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim))
            }
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    step: app.mise.core.model.Step,
    recipe: Recipe,
    factor: Double,
    mode: UnitMode,
    onIngredient: (String) -> Unit,
    onStartTimer: (app.mise.core.timer.TimerSpec) -> Unit,
    onCookFromHere: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$number", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleSmall)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    step.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false).clickable(onClickLabel = "cook from this step", onClick = onCookFromHere)
                        .semantics { heading() },
                )
                step.heat?.let { Spacer(Modifier.width(8.dp)); HeatBadge(it) }
            }
            StepText(step.content, recipe, factor, mode, onIngredient)
            when (val timing = StepTimers.timing(step, recipe)) {
                is StepTiming.Countdown -> OutlinedButton(onClick = { onStartTimer(timing.spec) }) {
                    Icon(Icons.Filled.Timer, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Timer · ${StepTimers.formatRange(timing.spec)}")
                }
                is StepTiming.AdvancePrep -> if (timing.notes.isNotEmpty()) {
                    Text("⏰ Long wait — see “Start the day before” above", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary)
                }
                StepTiming.None -> {}
            }
        }
    }
}

@Composable
private fun MyNotes(notes: List<String>, syncsToPc: Boolean, onEdit: () -> Unit) {
    SectionTitle("My notes", trailing = {
        TextButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, null)
            Spacer(Modifier.width(4.dp))
            Text(if (notes.isEmpty()) "Add" else "Edit")
        }
    })
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (notes.isEmpty()) {
            Text("Your own tweaks and reminders for next time.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
        Text(
            if (syncsToPc) "Saved on this phone and synced to your PC." else "Saved on this phone.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotesEditor(notes: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(notes.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My notes") },
        text = {
            Column {
                Text("One note per line.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 5, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.lines()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CookLog(lastCooked: String?, times: Int, onMadeThis: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 24.dp, 16.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                lastCooked?.let { "Last cooked $it" } ?: "Not cooked yet",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (times > 1) Text("Made $times times", style = MaterialTheme.typography.bodyMedium)
        }
        FilledTonalButton(onClick = onMadeThis) {
            Icon(Icons.Outlined.Restaurant, null)
            Spacer(Modifier.width(8.dp))
            Text("I made this")
        }
    }
}

@Composable
private fun SourceSection(recipe: Recipe, onOriginal: () -> Unit) {
    val s = recipe.source
    Column(Modifier.padding(16.dp, 24.dp, 16.dp, 0.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        val bits = listOfNotNull(
            s?.title, s?.author?.let { "by $it" }, s?.page?.let { "p. $it" },
        )
        if (bits.isNotEmpty()) Text("Source: ${bits.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
        s?.url?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onOriginal) {
            Icon(Icons.Outlined.Description, null)
            Spacer(Modifier.width(8.dp))
            Text("View original text")
        }
    }
}
