package app.mise.ui.timers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.mise.core.timer.StepTimers
import app.mise.core.timer.TimerSpec
import kotlin.math.roundToInt

/** Starts a step's timer; for a ranged one ("4–6 min") it first asks how long, on a slider. */
fun interface TimerLengthPicker {
    fun pick(spec: TimerSpec, label: String, start: (TimerSpec) -> Unit)
}

@Composable
fun rememberTimerLengthPicker(): TimerLengthPicker {
    var asking by remember { mutableStateOf<Ask?>(null) }
    asking?.let { ask -> TimerLengthDialog(ask, onDismiss = { asking = null }) }
    return remember {
        TimerLengthPicker { spec, label, start ->
            if (spec.isRange) asking = Ask(spec, label, start) else start(spec)
        }
    }
}

private class Ask(val spec: TimerSpec, val label: String, val start: (TimerSpec) -> Unit)

@Composable
private fun TimerLengthDialog(ask: Ask, onDismiss: () -> Unit) {
    val choices = remember(ask) { StepTimers.choices(ask.spec) }
    var position by remember(ask) { mutableFloatStateOf(0f) } // the shortest: what a plain tap always did
    val seconds = choices[position.roundToInt().coerceIn(choices.indices)]
    val chosen = StepTimers.chosen(ask.spec, seconds)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ask.label) },
        text = {
            Column {
                Text(
                    StepTimers.formatDuration(seconds),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Slider(
                    value = position,
                    onValueChange = { position = it },
                    valueRange = 0f..(choices.size - 1).toFloat(),
                    steps = (choices.size - 2).coerceAtLeast(0),
                    modifier = Modifier.semantics { stateDescription = StepTimers.formatDuration(seconds) },
                )
                Row {
                    Text(StepTimers.formatDuration(choices.first()), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text(StepTimers.formatDuration(choices.last()), style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    if (chosen.isRange) "It'll say “check now” at ${StepTimers.formatDuration(seconds)}; you can then let it run on to " +
                        "${StepTimers.formatDuration(chosen.maxSeconds!!)}."
                    else "A plain countdown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); ask.start(chosen) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
