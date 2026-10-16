package app.mise.data.settings

import app.mise.core.units.UnitMode

enum class SourceKind(val label: String) {
    LAN("PC server"),
    FOLDER("Folder on this phone"),
    SAMPLE("Demo recipe"),
    CLOUD("Cloud (coming later)"),
}

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

data class Settings(
    val sourceKind: SourceKind = SourceKind.LAN,
    val serverUrl: String = "",
    val token: String = "",
    val folderUri: String? = null,
    val defaultUnitMode: UnitMode = UnitMode.ORIGINAL,
    val keepScreenOn: Boolean = true,
    val timerSound: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val textScale: Float = 1f,
    val periodicSync: Boolean = false,
    val lastSyncAt: Long? = null,
    val lastSyncError: String? = null,
) {
    val lanConfigured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()

    /**
     * The source that actually feeds the library. Until the PC is paired (or a folder chosen),
     * the bundled demo recipe is shown so the app is never empty.
     */
    val effectiveSource: SourceKind
        get() = when (sourceKind) {
            SourceKind.LAN -> if (lanConfigured) SourceKind.LAN else SourceKind.SAMPLE
            SourceKind.FOLDER -> if (folderUri != null) SourceKind.FOLDER else SourceKind.SAMPLE
            else -> sourceKind
        }
}
