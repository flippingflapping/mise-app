package app.mise.data.source

import androidx.core.net.toUri
import android.content.Context
import android.net.Uri
import app.mise.data.settings.Settings
import app.mise.data.settings.SourceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Value of [Settings.folderUri] when only individually imported files are used (no SAF folder). */
const val IMPORTED_FILES_ONLY = "app:imported"

/** Builds the active [RecipeSource] from settings (cached while the settings don't change). */
@Singleton
class SourceFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: HttpClient,
) {
    private var cached: Pair<String, RecipeSource>? = null

    @Synchronized
    fun create(settings: Settings): RecipeSource {
        val kind = settings.effectiveSource
        val key = "$kind|${settings.serverUrl}|${settings.token}|${settings.folderUri}"
        cached?.takeIf { it.first == key }?.let { return it.second }
        val source = when (kind) {
            SourceKind.LAN -> LanServerSource(http, settings.serverUrl, settings.token)
            SourceKind.FOLDER -> FolderImportSource(
                listOfNotNull(
                    settings.folderUri?.takeIf { it.startsWith("content:") }?.let { DocumentFileTree(context, it.toUri()) },
                    JavaFileTree(FolderImportSource.importedDir(context)),
                ),
            )
            SourceKind.SAMPLE -> BundledSampleSource(context.assets)
            SourceKind.CLOUD -> CloudApiSource()
        }
        cached = key to source
        return source
    }
}
