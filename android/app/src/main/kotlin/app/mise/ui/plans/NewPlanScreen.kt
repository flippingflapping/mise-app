package app.mise.ui.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mise.core.feast.FeastStyle
import app.mise.core.model.Recipe
import app.mise.core.scale.Servings
import app.mise.data.repo.Pick
import app.mise.data.repo.PlansRepository
import app.mise.data.repo.RecipeRepository
import app.mise.data.settings.SettingsStore
import app.mise.di.Clock
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class PlanKind { SHOPPING, MEAL }

data class NewPlanState(
    val loading: Boolean = true,
    val recipes: List<Recipe> = emptyList(),
    val servings: Map<String, Double> = emptyMap(),
    val name: String = "",
    val serveAt: Long = 0,
    val style: FeastStyle = FeastStyle.CALM,
)

@HiltViewModel(assistedFactory = NewPlanViewModel.Factory::class)
class NewPlanViewModel @AssistedInject constructor(
    @Assisted val kind: PlanKind,
    @Assisted ids: List<String>,
    private val recipes: RecipeRepository,
    private val plans: PlansRepository,
    private val settings: SettingsStore,
    private val clock: Clock,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(kind: PlanKind, ids: List<String>): NewPlanViewModel
    }

    private val _state = MutableStateFlow(NewPlanState())
    val state: StateFlow<NewPlanState> = _state

    init {
        viewModelScope.launch {
            val byId = recipes.recipesById(ids)
            val list = ids.mapNotNull { byId[it] }
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(clock.now()), zone)
            var serve = now.toLocalDate().atTime(19, 0).atZone(zone)
            if (serve.isBefore(now.plusMinutes(30))) serve = serve.plusDays(1)
            val day = serve.format(DateTimeFormatter.ofPattern("EEE d MMM"))
            _state.value = NewPlanState(
                loading = false,
                recipes = list,
                servings = list.associate { it.id to it.recipeYield.baseServings },
                name = if (kind == PlanKind.SHOPPING) "Shopping · $day" else "Dinner · $day",
                serveAt = serve.toInstant().toEpochMilli(),
                style = if (kind == PlanKind.MEAL) plans.defaultStyle() else FeastStyle.CALM,
            )
        }
    }

    fun setName(n: String) = _state.update { it.copy(name = n) }
    fun increment(id: String) = _state.update { s -> s.copy(servings = s.servings + (id to Servings.increment(s.servings[id] ?: 1.0))) }
    fun decrement(id: String) = _state.update { s -> s.copy(servings = s.servings + (id to Servings.decrement(s.servings[id] ?: 1.0))) }
    fun setServeAt(millis: Long) = _state.update { it.copy(serveAt = millis) }
    fun setStyle(style: FeastStyle) = _state.update { it.copy(style = style) }

    /** Creates the list / plan and hands its id to [onCreated]. */
    fun create(onCreated: (Long) -> Unit) = viewModelScope.launch {
        val s = _state.value
        val picks = s.recipes.map { Pick(it.id, s.servings[it.id] ?: it.recipeYield.baseServings) }
        val id = when (kind) {
            PlanKind.SHOPPING -> plans.createShoppingList(s.name, picks, settings.settings.first().defaultUnitMode)
            PlanKind.MEAL -> plans.createFeast(s.name, s.serveAt, picks, s.style)
        }
        onCreated(id)
    }
}

fun formatTime(millis: Long): String =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).toLocalTime()
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

fun formatDayTime(millis: Long): String =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE d MMM, ").withZone(ZoneId.systemDefault())) + formatTime(millis)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewPlanScreen(viewModel: NewPlanViewModel, onBack: () -> Unit, onCreated: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val meal = viewModel.kind == PlanKind.MEAL
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (meal) "Plan a meal" else "New shopping list") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Button(
                onClick = { viewModel.create(onCreated) },
                enabled = !state.loading && state.recipes.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp),
            ) { Text(if (meal) "Make the plan" else "Make the list") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(
                    value = state.name, onValueChange = viewModel::setName, label = { Text("Name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (meal) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Serve at", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickDate = true }) {
                            Text(ZonedDateTime.ofInstant(Instant.ofEpochMilli(state.serveAt), ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("EEE d MMM")))
                        }
                        OutlinedButton(onClick = { pickTime = true }) { Text(formatTime(state.serveAt)) }
                    }
                    Text(
                        "Mise works backwards from this time so every dish is ready together, with one cook.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (meal) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("How do you like to cook?", style = MaterialTheme.typography.titleMedium)
                    StyleChooser(state.style, viewModel::setStyle)
                }
            }
            item { Text("Servings", style = MaterialTheme.typography.titleMedium) }
            items(state.recipes, key = { it.id }) { r ->
                val servings = state.servings[r.id] ?: r.recipeYield.baseServings
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.bodyLarge)
                        r.recipeYield.label?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    FilledTonalIconButton(onClick = { viewModel.decrement(r.id) }, enabled = Servings.canDecrement(servings)) {
                        Icon(Icons.Filled.Remove, "Fewer servings of ${r.title}")
                    }
                    Text(
                        Servings.format(servings), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 48.dp).semantics { contentDescription = "${Servings.format(servings)} servings" },
                    )
                    FilledTonalIconButton(onClick = { viewModel.increment(r.id) }) { Icon(Icons.Filled.Add, "More servings of ${r.title}") }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
    }

    val zone = ZoneId.systemDefault()
    val current = ZonedDateTime.ofInstant(Instant.ofEpochMilli(state.serveAt.takeIf { it > 0 } ?: System.currentTimeMillis()), zone)
    if (pickDate) {
        val dp = rememberDatePickerState(initialSelectedDateMillis = current.toLocalDate().atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dp.selectedDateMillis?.let {
                        val date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                        viewModel.setServeAt(date.atTime(current.toLocalTime()).atZone(zone).toInstant().toEpochMilli())
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(dp) }
    }
    if (pickTime) {
        val tp = rememberTimePickerState(initialHour = current.hour, initialMinute = current.minute)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Serve at") },
            text = { TimePicker(tp) },
            confirmButton = {
                TextButton(onClick = {
                    val date: LocalDate = current.toLocalDate()
                    viewModel.setServeAt(date.atTime(LocalTime.of(tp.hour, tp.minute)).atZone(zone).toInstant().toEpochMilli())
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
        )
    }
}

