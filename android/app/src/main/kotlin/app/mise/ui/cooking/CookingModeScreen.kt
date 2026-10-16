package app.mise.ui.cooking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mise.core.model.Recipe
import app.mise.core.model.Step
import app.mise.core.render.StepRenderer
import app.mise.core.scale.AmountRenderer
import app.mise.core.timer.KitchenTimer
import app.mise.core.timer.StepTimers
import app.mise.core.timer.StepTiming
import app.mise.core.timer.TimerSpec
import app.mise.core.units.UnitMode
import app.mise.ui.components.HeatBadge
import app.mise.ui.components.StepText
import app.mise.ui.detail.AdvancePrepBanner
import app.mise.ui.timers.TimerActions
import app.mise.ui.timers.TimerTray
import app.mise.ui.timers.rememberTimerStarter
import kotlinx.coroutines.launch

/**
 * Full-screen, one step per page, for messy hands at arm's length: very large text, swipe or big
 * Back/Next buttons, the step's ingredients (scaled) above the instruction, and a timer tray.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookingModeScreen(viewModel: CookingViewModel, startStep: Int, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recipe = state.recipe
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val startTimer = rememberTimerStarter(onDenied = {
        scope.launch { snackbar.showSnackbar("Notifications are off — timers will only ring while Mise is open.") }
    })

    KeepScreenOn(state.keepScreenOn)

    if (recipe == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (state.loading) "Loading…" else "Recipe not found")
        }
        return
    }
    val pages = recipe.steps.size
    val pager = rememberPagerState(initialPage = startStep.coerceIn(0, (pages - 1).coerceAtLeast(0))) { pages }
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
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(recipe.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text("Step ${pager.currentPage + 1} of $pages", style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.Close, "Leave cooking mode") }
                    },
                )
                LinearProgressIndicator(
                    progress = { (pager.currentPage + 1f) / pages },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Step ${pager.currentPage + 1} of $pages" },
                )
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                TimerTray(state.timers, actions, large = true)
                NavButtons(pager, pages)
            }
        },
    ) { padding ->
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(padding)) { page ->
            StepPage(
                recipe = recipe,
                step = recipe.steps[page],
                factor = state.factor,
                mode = state.unitMode,
                timers = state.timers,
                onStartTimer = { step, spec -> startTimer { viewModel.timers.start(recipe, step, spec) } },
            )
        }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun NavButtons(pager: PagerState, pages: Int) {
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(
            onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
            enabled = pager.currentPage > 0,
            modifier = Modifier.weight(1f).heightIn(min = 72.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text("Back", fontSize = 22.sp)
        }
        Button(
            onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            enabled = pager.currentPage < pages - 1,
            modifier = Modifier.weight(1f).heightIn(min = 72.dp),
        ) {
            Text(if (pager.currentPage < pages - 1) "Next" else "Last step", fontSize = 22.sp)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(28.dp))
        }
    }
}

@Composable
private fun StepPage(
    recipe: Recipe,
    step: Step,
    factor: Double,
    mode: UnitMode,
    timers: List<KitchenTimer>,
    onStartTimer: (Step, TimerSpec) -> Unit,
) {
    val used = remember(step, recipe) { StepRenderer.ingredientsUsed(step, recipe) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                step.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f, fill = false).semantics { heading() },
            )
            step.heat?.let { Spacer(Modifier.width(12.dp)); HeatBadge(it, large = true) }
        }
        if (used.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("You'll need", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                used.forEach { ing ->
                    val a = AmountRenderer.render(ing, factor, mode)
                    Row(Modifier.semantics(mergeDescendants = true) {
                        contentDescription = AmountRenderer.chipSpoken(ing, factor, mode)
                    }) {
                        Text(a.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(140.dp))
                        Text(ing.name + (ing.prep?.let { ", $it" } ?: ""), style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
        StepText(
            step.content, recipe, factor, mode,
            onIngredientClick = {},
            style = MaterialTheme.typography.headlineMedium.copy(lineHeight = 44.sp),
        )
        when (val timing = StepTimers.timing(step, recipe)) {
            is StepTiming.Countdown -> {
                val running = timers.any { it.stepId == step.id && it.recipeId == recipe.id }
                Button(
                    onClick = { onStartTimer(step, timing.spec) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    colors = if (running) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
                ) {
                    Icon(Icons.Filled.Timer, null, Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        (if (running) "Start another " else "Start ") + StepTimers.formatRange(timing.spec) + " timer",
                        fontSize = 24.sp, textAlign = TextAlign.Center,
                    )
                }
                if (timing.spec.isRange) {
                    Text(
                        "Counts to ${StepTimers.formatDuration(timing.spec.minSeconds)}, then asks you to check — " +
                            "add a minute or run on to ${StepTimers.formatDuration(timing.spec.maxSeconds!!)}.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            is StepTiming.AdvancePrep -> {
                Text("No timer for this one — it's a long wait.", style = MaterialTheme.typography.titleLarge)
                if (timing.notes.isNotEmpty()) AdvancePrepBanner(timing.notes, large = true)
            }
            StepTiming.None -> Spacer(Modifier.height(8.dp))
        }
    }
}
