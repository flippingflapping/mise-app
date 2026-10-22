package app.mise.ui.settings

import androidx.core.net.toUri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mise.BuildConfig
import app.mise.ui.LocalNetworkPermission
import app.mise.ui.rememberLocalNetworkGate
import app.mise.core.units.UnitMode
import app.mise.data.settings.SourceKind
import app.mise.data.settings.ThemeMode
import app.mise.data.source.IMPORTED_FILES_ONLY
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    pairingLink: String?,
    onBack: () -> Unit,
    onConnectionCheck: () -> Unit = {},
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val sync by viewModel.syncStatus.collectAsStateWithLifecycle()
    val pending by viewModel.pendingPairing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    var localNetworkDenied by remember { mutableStateOf(false) }
    val gate = rememberLocalNetworkGate(onDenied = { localNetworkDenied = true })

    LaunchedEffect(pairingLink) {
        if (pairingLink != null && !viewModel.offerPairing(pairingLink)) toast("That pairing link isn't valid.")
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setFolder)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importFiles(uris)
    }
    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code ->
                if (!viewModel.offerPairing(code.rawValue.orEmpty())) toast("That QR code isn't a Mise pairing code.")
            }
            .addOnFailureListener { toast("Scanner unavailable (${it.message}). Enter the address and token below instead.") }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        ) {
            Section("Recipes come from")
            Column(Modifier.selectableGroup()) {
                SourceKind.entries.forEach { kind ->
                    val enabled = kind != SourceKind.CLOUD
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = s.sourceKind == kind, enabled = enabled, role = Role.RadioButton,
                                onClick = { viewModel.setSource(kind) })
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.sourceKind == kind, onClick = null, enabled = enabled)
                        Spacer(Modifier.width(12.dp))
                        Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            when (s.sourceKind) {
                SourceKind.LAN -> LanSettings(
                    serverUrl = s.serverUrl,
                    token = s.token,
                    onScan = ::scan,
                    onSave = { url, token -> gate { viewModel.saveServer(url, token)?.let { toast(it) } } },
                )
                SourceKind.FOLDER -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Pick a folder that holds your recipe JSON files — e.g. a Syncthing or Google Drive folder, or a copy " +
                            "of the PC's recipes folder. Works without the PC.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val folderLabel = when (val f = s.folderUri) {
                        null -> "No folder chosen"
                        IMPORTED_FILES_ONLY -> "Imported files only"
                        else -> f.toUri().lastPathSegment ?: f
                    }
                    Text(folderLabel, style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickFolder.launch(null) }) {
                            Icon(Icons.Outlined.Folder, null); Spacer(Modifier.width(8.dp)); Text("Choose folder")
                        }
                        OutlinedButton(onClick = { pickFiles.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                            Icon(Icons.Outlined.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Import files")
                        }
                    }
                }
                SourceKind.SAMPLE -> Text(
                    "Shows the built-in demo recipe. Nothing leaves the phone.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp),
                )
                SourceKind.CLOUD -> {}
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { gate { viewModel.testConnection() } }, enabled = check != ConnectionCheck.Running) {
                        Text("Test connection")
                    }
                    OutlinedButton(onClick = { gate { viewModel.syncNow() } }, enabled = !sync.running) {
                        Text(if (sync.running) "Syncing…" else "Sync now")
                    }
                }
                when (val c = check) {
                    ConnectionCheck.Running -> Text("Checking…")
                    is ConnectionCheck.Ok -> Text("✓ ${c.message}", color = MaterialTheme.colorScheme.primary)
                    is ConnectionCheck.Failed -> {
                        Text("✗ ${c.message}", color = MaterialTheme.colorScheme.error)
                        if (s.sourceKind == SourceKind.LAN && (localNetworkDenied || LocalNetworkPermission.needed(context))) {
                            Text(LocalNetworkPermission.EXPLANATION, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { context.startActivity(LocalNetworkPermission.appSettingsIntent(context)) }) {
                                Text("Open app settings")
                            }
                        }
                        if (s.sourceKind == SourceKind.LAN && c.message.startsWith("Can't reach")) {
                            Text(
                                "Check that the phone is on the same Wi-Fi, and that the address is your PC's Wi-Fi/Ethernet " +
                                    "address (usually 192.168.x.x). 172.x addresses are often virtual adapters (WSL, Docker) " +
                                    "the phone can't reach — open http://localhost:8765 on the PC for QR codes of every address.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    ConnectionCheck.Idle -> {}
                }
                if (s.sourceKind == SourceKind.LAN) {
                    TextButton(onClick = onConnectionCheck) { Text("Can't connect? Run a step-by-step connection check") }
                }
                Text(
                    s.lastSyncAt?.let { "Last synced " + DateUtils.getRelativeTimeSpanString(it).toString().lowercase() }
                        ?: "Not synced yet",
                    style = MaterialTheme.typography.bodyMedium,
                )
                viewModel.describe(sync.lastResult)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                ToggleRow("Sync in the background", "About every 6 hours on Wi-Fi", s.periodicSync, viewModel::setPeriodicSync)
            }

            HorizontalDivider()
            Section("Display")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Default units", style = MaterialTheme.typography.bodyLarge)
                Segmented(UnitMode.entries, s.defaultUnitMode, { it.label }, viewModel::setDefaultUnitMode)
                Text("Theme", style = MaterialTheme.typography.bodyLarge)
                Segmented(ThemeMode.entries, s.theme, { it.label }, viewModel::setTheme)
                var scale by remember(s.textScale) { mutableFloatStateOf(s.textScale) }
                Text("Text size: ${(scale * 100).toInt()}% (on top of the system font size)", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = scale,
                    onValueChange = { scale = it },
                    onValueChangeFinished = { viewModel.setTextScale(scale) },
                    valueRange = 0.85f..1.6f,
                    steps = 14,
                )
            }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            Section("Cooking")
            Column(Modifier.padding(horizontal = 16.dp)) {
                ToggleRow("Keep the screen on in cooking mode", null, s.keepScreenOn, viewModel::setKeepScreenOn)
                ToggleRow("Timer sound", "Off = vibrate only", s.timerSound, viewModel::setTimerSound)
                if (!viewModel.exactAlarms) {
                    Text(
                        "Exact alarms are off for Mise, so a timer may ring a little late if the phone is asleep and Mise was closed.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            Section("About")
            Text(
                "Mise ${BuildConfig.VERSION_NAME}. Recipes are converted on your PC; this app never holds an AI key " +
                    "and only talks to the server or folder you choose.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    pending?.let { info ->
        AlertDialog(
            onDismissRequest = viewModel::cancelPairing,
            title = { Text("Pair with this PC?") },
            text = { Text("Mise will load recipes from ${info.serverUrl}.") },
            confirmButton = { TextButton(onClick = { gate { viewModel.confirmPairing() } }) { Text("Pair") } },
            dismissButton = { TextButton(onClick = viewModel::cancelPairing) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LanSettings(serverUrl: String, token: String, onScan: () -> Unit, onSave: (String, String) -> Unit) {
    var url by remember(serverUrl) { mutableStateOf(serverUrl) }
    var tok by remember(token) { mutableStateOf(token) }
    var showManual by remember { mutableStateOf(serverUrl.isBlank()) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (serverUrl.isBlank()) "Start the Mise server on your PC (scripts/server.ps1 or scripts/server.sh), then scan the QR code it shows."
            else "Paired with $serverUrl",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onScan) {
                Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Pair with PC")
            }
            TextButton(onClick = { showManual = !showManual }) { Text(if (showManual) "Hide manual entry" else "Enter manually") }
        }
        if (showManual) {
            OutlinedTextField(
                value = url, onValueChange = { url = it }, label = { Text("Server address") },
                placeholder = { Text("192.168.1.20:8765") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tok, onValueChange = { tok = it }, label = { Text("Access token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onSave(url, tok) }) { Text("Save") }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label(o)) }
        }
    }
}
