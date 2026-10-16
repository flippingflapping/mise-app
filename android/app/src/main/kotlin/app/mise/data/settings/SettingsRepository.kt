package app.mise.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.mise.core.units.UnitMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** The slice of settings the sync layer needs (lets tests run without DataStore). */
interface SettingsStore {
    val settings: Flow<Settings>
    suspend fun current(): Settings
    suspend fun recordSync(at: Long, error: String?)
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) : SettingsStore {
    private val store = context.settingsStore

    private object K {
        val source = stringPreferencesKey("source_kind")
        val url = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token")
        val folder = stringPreferencesKey("folder_uri")
        val unitMode = stringPreferencesKey("default_unit_mode")
        val keepOn = booleanPreferencesKey("keep_screen_on")
        val sound = booleanPreferencesKey("timer_sound")
        val theme = stringPreferencesKey("theme")
        val textScale = floatPreferencesKey("text_scale")
        val periodic = booleanPreferencesKey("periodic_sync")
        val lastSync = longPreferencesKey("last_sync_at")
        val lastError = stringPreferencesKey("last_sync_error")
    }

    override val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            sourceKind = p[K.source].toEnum(SourceKind.LAN),
            serverUrl = p[K.url].orEmpty(),
            token = p[K.token].orEmpty(),
            folderUri = p[K.folder],
            defaultUnitMode = p[K.unitMode].toEnum(UnitMode.ORIGINAL),
            keepScreenOn = p[K.keepOn] ?: true,
            timerSound = p[K.sound] ?: true,
            theme = p[K.theme].toEnum(ThemeMode.SYSTEM),
            textScale = p[K.textScale] ?: 1f,
            periodicSync = p[K.periodic] ?: false,
            lastSyncAt = p[K.lastSync],
            lastSyncError = p[K.lastError],
        )
    }

    override suspend fun current(): Settings = settings.first()

    suspend fun setServer(url: String, token: String) = store.edit {
        it[K.url] = url
        it[K.token] = token
        it[K.source] = SourceKind.LAN.name
        it.remove(K.lastError)
    }

    suspend fun setSourceKind(kind: SourceKind) = store.edit { it[K.source] = kind.name }
    suspend fun setFolderUri(uri: String?) = store.edit {
        if (uri == null) it.remove(K.folder) else it[K.folder] = uri
    }
    suspend fun setDefaultUnitMode(mode: UnitMode) = store.edit { it[K.unitMode] = mode.name }
    suspend fun setKeepScreenOn(on: Boolean) = store.edit { it[K.keepOn] = on }
    suspend fun setTimerSound(on: Boolean) = store.edit { it[K.sound] = on }
    suspend fun setTheme(mode: ThemeMode) = store.edit { it[K.theme] = mode.name }
    suspend fun setTextScale(scale: Float) = store.edit { it[K.textScale] = scale }
    suspend fun setPeriodicSync(on: Boolean) = store.edit { it[K.periodic] = on }

    override suspend fun recordSync(at: Long, error: String?) {
        store.edit {
        if (error == null) {
            it[K.lastSync] = at
            it.remove(K.lastError)
        } else {
            it[K.lastError] = error
        }
        }
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}
