package app.mise.ui.plans

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mise.core.feast.AdvanceTask
import app.mise.core.feast.FeastBlock
import app.mise.core.feast.FeastDish
import app.mise.core.feast.FeastPlanner
import app.mise.core.feast.FeastTask
import app.mise.core.timer.StepTimers
import app.mise.data.db.FeastSummary
import app.mise.data.repo.FeastDetail
import app.mise.data.repo.PlansRepository
import app.mise.plans.FeastReminders
import app.mise.timers.TimerController
import app.mise.ui.components.EmptyState
import app.mise.ui.timers.TimerActions
import app.mise.ui.timers.TimerTray
import app.mise.ui.timers.rememberNow
import app.mise.ui.timers.rememberTimerStarter
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

// ---------------------------------------------------------------------------------------- all plans

@HiltViewModel
class MealPlansViewModel @Inject constructor(plans: PlansRepository) : ViewModel() {
    val plans: StateFlow<List<FeastSummary>?> = plans.feastList().map<List<FeastSummary>, List<FeastSummary>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealPlansScreen(viewModel: MealPlansViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Meal plans") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        val all = plans
        when {
            all == null -> {}
            all.isEmpty() -> EmptyState(
                Icons.Filled.Schedule, "No meal plans yet",
                "In your recipes, long-press one recipe, tap the others you're making, then tap Plan a meal and pick a serve time.",
                Modifier.padding(padding),
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(all, key = { it.id }) { f ->
                    ListItem(
                        headlineContent = { Text(f.name) },
                        supportingContent = { Text("Serve ${formatDayTime(f.serveAt)} · ${f.dishes} dish${if (f.dishes == 1) "" else "es"}") },
                        modifier = Modifier.clickable { onOpen(f.id) }.heightIn(min = 64.dp),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- one plan

@HiltViewModel(assistedFactory = MealPlanViewModel.Factory::class)
class MealPlanViewModel @AssistedInject constructor(
    @Assisted val feastId: Long,
    private val plans: PlansRepository,
    private val reminders: FeastReminders,
    val timers: TimerController,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(feastId: Long): MealPlanViewModel
    }

    val detail: StateFlow<FeastDetail?> = plans.feast(feastId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun changed() = viewModelScope.launch { reminders.reschedule(feastId) }

    fun setDone(taskId: String, done: Boolean) = viewModelScope.launch {
        plans.setDone(feastId, taskId, done)
        changed()
    }

    /** The serve time pushed back by [minutes], landing on a round 5 minutes. */
    fun shiftedServeAt(serveAt: Long, minutes: Int): Long {
        val five = 5 * 60_000L
        return Math.floorDiv(serveAt + minutes * 60_000L + five - 1, five) * five
    }

    /** Running late: push the serve time back so the plan fits from now. */
    fun shiftBy(minutes: Int) = viewModelScope.launch {
        val f = detail.value?.feast ?: return@launch
        plans.setServeAt(feastId, shiftedServeAt(f.serveAt, minutes))
        changed()
    }

    fun setRemind(on: Boolean) = viewModelScope.launch {
        plans.setRemind(feastId, on)
        changed()
    }

    fun delete(then: () -> Unit) = viewModelScope.launch {
        reminders.cancel(feastId)
        plans.deleteFeast(feastId)
        then()
    }

    fun startTimer(task: FeastTask) {
        val d = detail.value ?: return
        val recipe = d.dishes.firstOrNull { it.first.id == task.recipeId }?.first ?: return
        val step = recipe.steps.firstOrNull { it.id == task.stepId } ?: return
        val spec = task.timer ?: return
        timers.start(recipe, step, spec)
    }

    init {
        changed() // make sure reminders match the current plan
    }
}

private val DISH_COLORS = listOf(
    Color(0xFFE07A5F), Color(0xFF3D85C6), Color(0xFF81B29A), Color(0xFFF2CC8F), Color(0xFF9C6ADE), Color(0xFF4DB6AC),
)

/** "Prep everything", "Beef stew", "Curry + rice", "Free time". */
fun FeastBlock.headline(titles: Map<String, String>): String = when (kind) {
    FeastBlock.Kind.PREP -> if (recipeIds.size > 1) "Prep everything" else "Prep"
    FeastBlock.Kind.COOK -> if (recipeIds.size > 2) "Bring it all together" else recipeIds.joinToString(" + ") { titles[it] ?: it }
    FeastBlock.Kind.BREAK -> "Free time"
}

fun FeastBlock.blurb(): String = when (kind) {
    FeastBlock.Kind.PREP -> "Chop, measure and get everything out, so the cooking is calm."
    FeastBlock.Kind.COOK -> if (recipeIds.size > 1) "A few dishes at once. Go down the list in order." else ""
    FeastBlock.Kind.BREAK -> "Things are cooking by themselves. Tidy up, or sit down for a bit."
}

/** "about 45 min", "about 1 hour", "about 1½ hours". */
fun approxDuration(minutes: Int): String {
    if (minutes < 60) return "about $minutes min"
    val h = minutes / 60
    val frac = when (minutes % 60) { 15 -> "¼"; 30 -> "½"; 45 -> "¾"; else -> "" }
    return if (h == 1 && frac.isEmpty()) "about 1 hour" else "about $h$frac hours"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealPlanScreen(
    viewModel: MealPlanViewModel,
    onBack: () -> Unit,
    onOpenStep: (recipeId: String, stepIndex: Int) -> Unit,
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val timers by viewModel.timers.store.timers.collectAsStateWithLifecycle()
    val now = rememberNow(15_000)
    var confirmDelete by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(setOf<String>()) }
    val startTimer = rememberTimerStarter()
    val actions = remember(viewModel) {
        object : TimerActions {
            override fun pause(id: String) = viewModel.timers.pause(id)
            override fun resume(id: String) = viewModel.timers.resume(id)
            override fun addMinute(id: String) = viewModel.timers.addMinute(id)
            override fun toMax(id: String) = viewModel.timers.toMax(id)
            override fun dismiss(id: String) = viewModel.timers.dismiss(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.feast?.name.orEmpty(), maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete plan") } },
            )
        },
        bottomBar = { TimerTray(timers, actions) },
    ) { padding ->
        val d = detail ?: return@Scaffold
        val plan = d.plan
        val all = remember(d.dishes, d.feast.serveAt) {
            FeastPlanner.plan(d.dishes.map { (r, f) -> FeastDish(r, f) }, d.feast.serveAt)
        }
        val color = d.dishes.mapIndexed { i, (r, _) -> r.id to DISH_COLORS[i % DISH_COLORS.size] }.toMap()
        val titles = d.dishes.associate { (r, _) -> r.id to r.title }
        val behind = plan.minutesBehind(now)
        val current = plan.currentBlock
        val doneTasks = all.tasks.filter { it.id in d.done }
        val doneAdvance = all.advance.filter { it.id in d.done }
        val taskActions = TaskActions(
            done = { viewModel.setDone(it.id, true) },
            timer = { t -> startTimer { viewModel.startTimer(t) } },
            open = { t -> stepOpener(d, t, onOpenStep) },
        )

        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "head") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Eat at ${formatDayTime(d.feast.serveAt)}", style = MaterialTheme.typography.titleLarge)
                    if (plan.tasks.isNotEmpty()) {
                        Text("Start at about ${formatTime(plan.startAtMillis)}", style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.dishes.forEach { (r, _) -> DishChip(r.title, color[r.id] ?: Color.Gray) }
                    }
                    if (d.missing.isNotEmpty()) {
                        Text("Not in your library any more: ${d.missing.joinToString()}", color = MaterialTheme.colorScheme.error)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Alarm, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Remind me when each part starts", Modifier.weight(1f))
                        Switch(checked = d.feast.remind, onCheckedChange = viewModel::setRemind)
                    }
                }
            }
            if (behind > 0 && plan.tasks.isNotEmpty()) item(key = "late") {
                val later = formatTime(viewModel.shiftedServeAt(d.feast.serveAt, behind))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Running about $behind min late", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("That's fine. Eat at $later instead and everything below moves with it.")
                        Button(onClick = { viewModel.shiftBy(behind) }) { Text("Eat at $later") }
                    }
                }
            }
            val pendingAdvance = plan.advance
            if (pendingAdvance.isNotEmpty()) {
                item(key = "adv-h") { Section("Ahead of time") }
                items(pendingAdvance, key = { "adv-" + it.id }) { a -> AdvanceRow(a, false, color[a.recipeId]) { viewModel.setDone(a.id, true) } }
            }
            if (plan.blocks.isNotEmpty()) {
                item(key = "plan-h") { Section("The plan") }
                items(plan.blocks, key = { "block-" + it.id }) { b ->
                    if (b == current) {
                        CurrentBlock(b, started = b.startMillis <= now, titles, color, taskActions)
                    } else {
                        BlockRow(
                            b, titles, color,
                            expanded = b.id in opened, actions = taskActions,
                            onToggle = { opened = if (b.id in opened) opened - b.id else opened + b.id },
                        )
                    }
                }
                item(key = "serve") { ServeRow(d.feast.serveAt) }
            }
            if (plan.tasks.isEmpty() && pendingAdvance.isEmpty()) item(key = "fin") {
                Text("Everything's done — enjoy the meal!", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            }
            if (doneTasks.isNotEmpty() || doneAdvance.isNotEmpty()) {
                item(key = "done-h") { Section("Done") }
                items(doneAdvance, key = { "dadv-" + it.id }) { a -> AdvanceRow(a, true, color[a.recipeId]) { viewModel.setDone(a.id, false) } }
                items(doneTasks, key = { "done-" + it.id }) { t ->
                    DoneRow(t, color[t.recipeId]) { viewModel.setDone(t.id, false) }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this plan?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private class TaskActions(
    val done: (FeastTask) -> Unit,
    val timer: (FeastTask) -> Unit,
    val open: (FeastTask) -> (() -> Unit)?,
)

private fun stepOpener(d: FeastDetail, t: FeastTask, onOpenStep: (String, Int) -> Unit): (() -> Unit)? {
    if (t.stepId == null) return null
    val recipe = d.dishes.firstOrNull { it.first.id == t.recipeId }?.first ?: return null
    val index = recipe.steps.indexOfFirst { it.id == t.stepId }.takeIf { it >= 0 } ?: return null
    return { onOpenStep(recipe.id, index) }
}

@Composable
private fun Section(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp).semantics { heading() },
    )
}

@Composable
private fun DishChip(title: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun Dots(ids: List<String>, color: Map<String, Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ids.forEach { Box(Modifier.size(10.dp).background(color[it] ?: Color.Gray, CircleShape)) }
    }
}

/** The block to work on: big, with its checklist. No clock times inside — just the order. */
@Composable
private fun CurrentBlock(
    b: FeastBlock, started: Boolean, titles: Map<String, String>, color: Map<String, Color>, actions: TaskActions,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (started) "Now" else "First, at about ${formatTime(b.startMillis)}") + " · ${approxDuration(b.approxMinutes)}",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dots(b.recipeIds, color)
                Spacer(Modifier.width(8.dp))
                Text(b.headline(titles), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            b.blurb().takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val multi = b.recipeIds.size > 1
            b.tasks.forEach { t -> TaskCheck(t, if (multi || t.kind == FeastTask.Kind.MISE_EN_PLACE) titles[t.recipeId] else null, color[t.recipeId], actions) }
        }
    }
}

/** One thing on the checklist. */
@Composable
private fun TaskCheck(t: FeastTask, dish: String?, color: Color?, actions: TaskActions) {
    val open = actions.open(t)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (open != null) Modifier.clickable(onClickLabel = "show this step", onClick = open) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = false, onCheckedChange = { actions.done(t) })
        Column(Modifier.weight(1f)) {
            if (t.kind == FeastTask.Kind.MISE_EN_PLACE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(color ?: Color.Gray, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(dish.orEmpty(), style = MaterialTheme.typography.titleMedium)
                }
                if (t.prepList.isEmpty()) Text("Get the ingredients out and measured", style = MaterialTheme.typography.bodyMedium)
                t.prepList.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
            } else {
                Text(t.title, style = MaterialTheme.typography.bodyLarge)
                if (dish != null) {
                    Text(dish, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        t.timer?.let { spec ->
            FilledTonalButton(onClick = { actions.timer(t) }) {
                Icon(Icons.Filled.Timer, null); Spacer(Modifier.width(4.dp)); Text(StepTimers.formatRange(spec))
            }
        }
    }
}

/** A block that isn't now: one calm line ("about 6:30 · Beef stew · about 20 min"), tap to peek inside. */
@Composable
private fun BlockRow(
    b: FeastBlock, titles: Map<String, String>, color: Map<String, Color>,
    expanded: Boolean, actions: TaskActions, onToggle: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth()
            .then(if (b.tasks.isNotEmpty()) Modifier.clickable(onClickLabel = if (expanded) "hide steps" else "show steps", onClick = onToggle) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTime(b.startMillis), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.widthIn(min = 84.dp),
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (b.kind != FeastBlock.Kind.BREAK) { Dots(b.recipeIds, color); Spacer(Modifier.width(8.dp)) }
                    Text(
                        b.headline(titles), style = MaterialTheme.typography.titleMedium,
                        color = if (b.kind == FeastBlock.Kind.BREAK) muted else Color.Unspecified,
                    )
                }
                val sub = approxDuration(b.approxMinutes) + when (b.kind) {
                    FeastBlock.Kind.BREAK -> " · things are cooking"
                    else -> " · ${b.tasks.size} step${if (b.tasks.size == 1) "" else "s"}"
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        if (expanded) {
            val multi = b.recipeIds.size > 1
            b.tasks.forEach { t -> TaskCheck(t, if (multi || t.kind == FeastTask.Kind.MISE_EN_PLACE) titles[t.recipeId] else null, color[t.recipeId], actions) }
        }
    }
}

@Composable
private fun ServeRow(serveAt: Long) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(serveAt), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 84.dp))
        Text("Eat!", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DoneRow(task: FeastTask, color: Color?, onUndo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color ?: Color.Gray, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (task.kind == FeastTask.Kind.MISE_EN_PLACE) "Prep" else task.title,
                style = MaterialTheme.typography.bodyLarge, textDecoration = TextDecoration.LineThrough,
            )
            Text(task.recipeTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Checkbox(checked = true, onCheckedChange = { onUndo() })
    }
}

@Composable
private fun AdvanceRow(a: AdvanceTask, done: Boolean, color: Color?, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color ?: Color.Gray, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (a.overnight) "The day before" else "By ${formatDayTime(a.byMillis)}") + ": ${a.title}",
                style = MaterialTheme.typography.bodyLarge, textDecoration = if (done) TextDecoration.LineThrough else null,
            )
            Text(
                a.recipeTitle + (a.notes.firstOrNull()?.let { " · $it" } ?: "") +
                    if (a.overnight) " · start by about ${formatDayTime(a.byMillis)}" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = done, onCheckedChange = { onToggle() })
    }
}
