package app.mise.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mise.di.Clock
import app.mise.diag.ConnectionDoctor
import app.mise.diag.DiagStep
import app.mise.ui.rememberLocalNetworkGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionCheckState(val running: Boolean = false, val steps: List<DiagStep> = emptyList(), val at: Long = 0)

@HiltViewModel
class ConnectionCheckViewModel @Inject constructor(
    private val doctor: ConnectionDoctor,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectionCheckState())
    val state: StateFlow<ConnectionCheckState> = _state

    fun run() {
        if (_state.value.running) return
        _state.value = ConnectionCheckState(running = true, at = clock.now())
        viewModelScope.launch {
            val steps = doctor.run { progress -> _state.update { it.copy(steps = progress) } }
            _state.update { it.copy(running = false, steps = steps) }
        }
    }

    fun report(): String = _state.value.let { ConnectionDoctor.report(it.steps, it.at) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionCheckScreen(viewModel: ConnectionCheckViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val gate = rememberLocalNetworkGate()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Connection check") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (state.steps.isNotEmpty() && !state.running) {
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, viewModel.report())
                        context.startActivity(Intent.createChooser(send, "Share the connection check"))
                    }) { Icon(Icons.Filled.Share, "Share the results") }
                }
            },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Tests each link between this phone and your PC, one at a time, to find where it breaks. " +
                            "Keep the server running on the PC. Its window (and logs/server.log) shows every request that arrives.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { gate { viewModel.run() } }, enabled = !state.running, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.running) "Checking…" else if (state.steps.isEmpty()) "Run the check" else "Run it again")
                    }
                }
            }
            items(state.steps, key = { it.title }) { step -> StepRow(step) }
            if (state.steps.isNotEmpty() && !state.running) item {
                Text(
                    "Tap share (top right) to send these results. They include the PC's address but never the access token.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StepRow(step: DiagStep) {
    val color = when (step.status) {
        DiagStep.Status.OK -> Color(0xFF2E7D32)
        DiagStep.Status.WARN -> Color(0xFFB26A00)
        DiagStep.Status.FAIL -> MaterialTheme.colorScheme.error
        DiagStep.Status.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column {
        Row {
            Text(step.status.mark, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.widthIn(min = 20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(step.title, style = MaterialTheme.typography.titleSmall)
                Text(step.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
        HorizontalDivider(Modifier.padding(top = 10.dp))
    }
}
