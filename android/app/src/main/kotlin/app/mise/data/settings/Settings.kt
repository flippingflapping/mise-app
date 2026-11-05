package app.mise.data.settings

import app.mise.core.units.UnitMode

enum class SourceKind(val label: String) {
    LAN("PC server"),
    FOLDER("Folder on this phone"),
    SAMPLE("Demo recipe"),
    CLOUD("Cloud (coming later)"),
    /** Recipes that belong to the phone itself (imported files, copies). Never removed by a sync. */
    PHONE("On this phone"),
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

    /** Paired with the PC: its recipes are in the library, and PC-only features work. */
    val usesPc: Boolean get() = lanConfigured

    /** A folder on the phone (or legacy imported files) adds its recipes to the library. */
    val usesFolder: Boolean get() = folderUri != null

    /**
     * The main source: the PC when paired, else the folder, else the bundled demo (so the app is
     * never empty). The library combines the PC, the folder and recipes kept on the phone; see
     * [app.mise.data.repo.SourceProvider.sources]. ([sourceKind] is from when only one could be used.)
     */
    val effectiveSource: SourceKind
        get() = when {
            usesPc -> SourceKind.LAN
            usesFolder -> SourceKind.FOLDER
            else -> SourceKind.SAMPLE
        }
}
