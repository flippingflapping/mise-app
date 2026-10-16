package app.mise.ui.timers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mise.core.timer.KitchenTimer
import app.mise.core.timer.KitchenTimer.Phase
import app.mise.core.timer.StepTimers
import kotlinx.coroutines.delay

/** Actions the tray can take on a timer. */
interface TimerActions {
    fun pause(id: String)
    fun resume(id: String)
    fun addMinute(id: String)
    fun toMax(id: String)
    fun dismiss(id: String)
}

/** A ticking clock for countdown text (the real timing lives in the service). */
@Composable
fun rememberNow(intervalMs: Long = 250): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(intervalMs)
        }
    }
    return now
}

/** Persistent tray of every running timer, each labelled with its step title. */
@Composable
fun TimerTray(timers: List<KitchenTimer>, actions: TimerActions, modifier: Modifier = Modifier, large: Boolean = false) {
    AnimatedVisibility(visible = timers.isNotEmpty(), modifier = modifier) {
        val now = rememberNow()
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            timers.sortedBy { it.changedAtMillis }.forEach { TimerCard(it, now, actions, large) }
        }
    }
}

@Composable
private fun TimerCard(t: KitchenTimer, now: Long, actions: TimerActions, large: Boolean) {
    val colors = MaterialTheme.colorScheme
    val alerting = t.isAlerting
    val container = when (t.phase) {
        Phase.CHECK -> colors.tertiaryContainer
        Phase.DONE -> colors.errorContainer
        Phase.PAUSED -> colors.surfaceVariant
        Phase.RUNNING -> colors.secondaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.width(if (large) 280.dp else 248.dp)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        t.label,
                        style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val status = when (t.phase) {
                        Phase.RUNNING -> StepTimers.clock(t.remaining(now))
                        Phase.PAUSED -> "${StepTimers.clock(t.remaining(now))} paused"
                        Phase.CHECK -> "Check now!"
                        Phase.DONE -> "Done!"
                    }
                    Text(
                        status,
                        style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = if (alerting) Modifier.semantics { liveRegion = LiveRegionMode.Assertive } else Modifier,
                    )
                }
                when (t.phase) {
                    Phase.RUNNING -> IconButton(onClick = { actions.pause(t.id) }) { Icon(Icons.Filled.Pause, "Pause ${t.label}") }
                    Phase.PAUSED -> IconButton(onClick = { actions.resume(t.id) }) { Icon(Icons.Filled.PlayArrow, "Resume ${t.label}") }
                    else -> {}
                }
                IconButton(onClick = { actions.dismiss(t.id) }) {
                    Icon(Icons.Filled.Close, if (alerting) "Dismiss ${t.label}" else "Cancel ${t.label}")
                }
            }
            if (alerting || t.phase == Phase.RUNNING) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { actions.addMinute(t.id) }) { Text("+1 min") }
                    if (t.phase == Phase.CHECK) {
                        val extra = (t.maxSeconds ?: t.minSeconds) - t.minSeconds
                        FilledTonalButton(onClick = { actions.toMax(t.id) }) { Text("To max (+${StepTimers.formatDuration(extra)})") }
                    }
                }
            }
        }
    }
}
