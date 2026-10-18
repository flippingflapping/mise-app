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

    /** Running late: push the serve time back so the plan fits from now. */
    fun shiftBy(minutes: Int) = viewModelScope.launch {
        val f = detail.value?.feast ?: return@launch
        plans.setServeAt(feastId, f.serveAt + minutes * 60_000L)
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
        val behind = plan.minutesBehind(now)
        val current = plan.current(now)
        val upcoming = plan.upcoming(now)
        val doneTasks = all.tasks.filter { it.id in d.done }
        val doneAdvance = all.advance.filter { it.id in d.done }

        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "head") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Serve at ${formatDayTime(d.feast.serveAt)}", style = MaterialTheme.typography.titleLarge)
                    if (plan.tasks.isNotEmpty()) {
                        Text("Start cooking at ${formatTime(plan.startAtMillis)}", style = MaterialTheme.typography.bodyLarge)
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
                        Text("Remind me when each step is due", Modifier.weight(1f))
                        Switch(checked = d.feast.remind, onCheckedChange = viewModel::setRemind)
                    }
                }
            }
            if (behind > 0 && plan.tasks.isNotEmpty()) item(key = "late") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("You're about $behind min behind", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Serve at ${formatTime(d.feast.serveAt + behind * 60_000L)} instead, and every time below moves with it.")
                        Button(onClick = { viewModel.shiftBy(behind) }) { Text("Move serve time to ${formatTime(d.feast.serveAt + behind * 60_000L)}") }
                    }
                }
            }
            val pendingAdvance = plan.advance
            if (pendingAdvance.isNotEmpty()) {
                item(key = "adv-h") { Section("Ahead of time") }
                items(pendingAdvance, key = { "adv-" + it.id }) { a -> AdvanceRow(a, false, color[a.recipeId]) { viewModel.setDone(a.id, true) } }
            }
            if (current.isNotEmpty()) {
                item(key = "now-h") { Section("Now") }
                items(current, key = { "now-" + it.id }) { t ->
                    NowCard(
                        task = t, now = now, color = color[t.recipeId],
                        onDone = { viewModel.setDone(t.id, true) },
                        onTimer = { startTimer { viewModel.startTimer(t) } },
                        onOpen = stepOpener(d, t, onOpenStep),
                    )
                }
            }
            if (upcoming.isNotEmpty()) {
                item(key = "next-h") { Section(if (current.isEmpty()) "Next" else "Coming up") }
                items(upcoming, key = { "up-" + it.id }) { t ->
                    TaskRow(t, now, color[t.recipeId], done = false, onToggle = { viewModel.setDone(t.id, true) }, onOpen = stepOpener(d, t, onOpenStep))
                }
            }
            if (plan.tasks.isEmpty() && pendingAdvance.isEmpty()) item(key = "fin") {
                Text("Everything's done — enjoy the meal!", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            }
            if (doneTasks.isNotEmpty() || doneAdvance.isNotEmpty()) {
                item(key = "done-h") { Section("Done") }
                items(doneAdvance, key = { "dadv-" + it.id }) { a -> AdvanceRow(a, true, color[a.recipeId]) { viewModel.setDone(a.id, false) } }
                items(doneTasks, key = { "done-" + it.id }) { t ->
                    TaskRow(t, now, color[t.recipeId], done = true, onToggle = { viewModel.setDone(t.id, false) }, onOpen = null)
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

private fun stepOpener(d: FeastDetail, t: FeastTask, onOpenStep: (String, Int) -> Unit): (() -> Unit)? {
    val recipe = d.dishes.firstOrNull { it.first.id == t.recipeId }?.first ?: return null
    val index = recipe.steps.indexOfFirst { it.id == t.stepId }.takeIf { it >= 0 } ?: 0
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

private fun duration(t: FeastTask): String {
    val total = ((t.endMillis - t.startMillis) / 1000).toInt()
    val hands = (t.activeMillis / 1000).toInt()
    return if (t.handsOff) "${StepTimers.formatDuration(total)} · hands-on ${StepTimers.formatDuration(hands)}"
    else StepTimers.formatDuration(total)
}

@Composable
private fun NowCard(task: FeastTask, now: Long, color: Color?, onDone: () -> Unit, onTimer: () -> Unit, onOpen: (() -> Unit)?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DishChip(task.recipeTitle, color ?: Color.Gray)
            Text(task.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${formatTime(task.startMillis)}–${formatTime(task.endMillis)} · ${duration(task)}", style = MaterialTheme.typography.bodyMedium)
            if (task.prepList.isNotEmpty()) task.prepList.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDone) { Icon(Icons.Filled.Check, null); Spacer(Modifier.width(6.dp)); Text("Done") }
                if (task.timer != null) {
                    FilledTonalButton(onClick = onTimer) {
                        Icon(Icons.Filled.Timer, null); Spacer(Modifier.width(6.dp)); Text(StepTimers.formatRange(task.timer!!))
                    }
                }
                if (onOpen != null && task.stepId != null) OutlinedButton(onClick = onOpen) { Text("Show step") }
            }
        }
    }
}

@Composable
private fun TaskRow(task: FeastTask, now: Long, color: Color?, done: Boolean, onToggle: () -> Unit, onOpen: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = "show this step", onClick = onOpen) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatTime(task.startMillis), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.widthIn(min = 64.dp))
        Box(Modifier.size(10.dp).background(color ?: Color.Gray, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge, textDecoration = if (done) TextDecoration.LineThrough else null)
            val inMin = ((task.startMillis - now) / 60_000).toInt()
            Text(
                "${task.recipeTitle} · ${duration(task)}" + if (!done && inMin in 1..120) " · in $inMin min" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = done, onCheckedChange = { onToggle() })
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
                    if (a.overnight) " · start by ${formatDayTime(a.byMillis)}" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = done, onCheckedChange = { onToggle() })
    }
}
