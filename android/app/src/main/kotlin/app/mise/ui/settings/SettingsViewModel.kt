package app.mise.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mise.core.pairing.PairingInfo
import app.mise.core.pairing.PairingUri
import app.mise.core.units.UnitMode
import app.mise.data.repo.RecipeRepository
import app.mise.data.repo.SyncResult
import app.mise.data.settings.Settings
import app.mise.data.settings.SettingsRepository
import app.mise.data.settings.SourceKind
import app.mise.data.settings.ThemeMode
import app.mise.data.source.FolderImportSource
import app.mise.data.source.IMPORTED_FILES_ONLY
import app.mise.data.sync.SyncWorker
import app.mise.timers.TimerController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

sealed interface ConnectionCheck {
    data object Idle : ConnectionCheck
    data object Running : ConnectionCheck
    data class Ok(val message: String) : ConnectionCheck
    data class Failed(val message: String) : ConnectionCheck
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val repository: RecipeRepository,
    timers: TimerController,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val syncStatus = repository.status

    private val _check = MutableStateFlow<ConnectionCheck>(ConnectionCheck.Idle)
    val check: StateFlow<ConnectionCheck> = _check.asStateFlow()

    /** A scanned / deep-linked pairing waiting for the user to confirm. */
    private val _pendingPairing = MutableStateFlow<PairingInfo?>(null)
    val pendingPairing: StateFlow<PairingInfo?> = _pendingPairing.asStateFlow()

    val exactAlarms: Boolean = timers.exactAlarmsAllowed

    fun offerPairing(raw: String): Boolean {
        val info = PairingUri.parse(raw) ?: return false
        _pendingPairing.value = info
        return true
    }

    fun cancelPairing() { _pendingPairing.value = null }

    fun confirmPairing() {
        val info = _pendingPairing.value ?: return
        _pendingPairing.value = null
        saveServer(info.serverUrl, info.token)
    }

    /** Manual entry. Returns an error message, or null if saved. */
    fun saveServer(url: String, token: String): String? {
        val normalized = PairingUri.normalizeServerUrl(url) ?: return "That doesn't look like a server address (e.g. 192.168.1.20:8765)"
        if (token.isBlank()) return "Enter the access token shown by the PC server"
        viewModelScope.launch {
            settingsRepo.setServer(normalized, token.trim())
            testConnection()
            repository.sync()
        }
        return null
    }

    fun testConnection() = viewModelScope.launch {
        _check.value = ConnectionCheck.Running
        val s = settingsRepo.current()
        _check.value = repository.testConnection(s).fold(
            onSuccess = { ConnectionCheck.Ok(it) },
            onFailure = { ConnectionCheck.Failed(it.message ?: "Couldn't connect") },
        )
    }

    fun syncNow() = viewModelScope.launch { repository.sync() }

    fun setSource(kind: SourceKind) = viewModelScope.launch {
        settingsRepo.setSourceKind(kind)
        _check.value = ConnectionCheck.Idle
        repository.sync()
    }

    fun setFolder(uri: Uri) = viewModelScope.launch {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        settingsRepo.setFolderUri(uri.toString())
        settingsRepo.setSourceKind(SourceKind.FOLDER)
        testConnection()
        repository.sync()
    }

    /** Copy picked JSON files (and matching .source.txt files) into the app's own import folder. */
    fun importFiles(uris: List<Uri>) = viewModelScope.launch {
        val dir = FolderImportSource.importedDir(context)
        val copied = withContext(Dispatchers.IO) {
            uris.count { uri ->
                runCatching {
                    val name = displayName(uri) ?: return@runCatching false
                    if (!(name.endsWith(".json") || name.endsWith(".txt"))) return@runCatching false
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        File(dir, name).outputStream().use { input.copyTo(it) }
                    } != null
                }.getOrDefault(false)
            }
        }
        if (settingsRepo.current().folderUri == null) settingsRepo.setFolderUri(IMPORTED_FILES_ONLY)
        settingsRepo.setSourceKind(SourceKind.FOLDER)
        _check.value = ConnectionCheck.Ok("Imported $copied file${if (copied == 1) "" else "s"}")
        repository.sync()
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun setDefaultUnitMode(mode: UnitMode) = viewModelScope.launch { settingsRepo.setDefaultUnitMode(mode) }
    fun setKeepScreenOn(on: Boolean) = viewModelScope.launch { settingsRepo.setKeepScreenOn(on) }
    fun setTimerSound(on: Boolean) = viewModelScope.launch { settingsRepo.setTimerSound(on) }
    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepo.setTheme(mode) }
    fun setTextScale(scale: Float) = viewModelScope.launch { settingsRepo.setTextScale(scale) }
    fun setPeriodicSync(on: Boolean) = viewModelScope.launch {
        settingsRepo.setPeriodicSync(on)
        SyncWorker.schedule(context, on)
    }

    fun describe(result: SyncResult?): String? = when (result) {
        null -> null
        is SyncResult.Success -> "Last sync: ${result.added} new, ${result.updated} updated, ${result.removed} removed" +
            if (result.pushed > 0) ", ${result.pushed} note change(s) sent" else ""
        is SyncResult.Failed -> "Last sync failed: ${result.message}"
    }
}
