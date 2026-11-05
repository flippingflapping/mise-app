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

    private val cache = HashMap<String, RecipeSource>()

    /** The main source (see [Settings.effectiveSource]). */
    fun create(settings: Settings): RecipeSource = of(settings.effectiveSource, settings)

    /** Everything the library combines: the PC if paired, the folder if chosen; the demo if neither. */
    fun all(settings: Settings): List<RecipeSource> = buildList {
        if (settings.usesPc) add(of(SourceKind.LAN, settings))
        if (settings.usesFolder) add(of(SourceKind.FOLDER, settings))
        if (isEmpty()) add(of(SourceKind.SAMPLE, settings))
    }

    @Synchronized
    private fun of(kind: SourceKind, settings: Settings): RecipeSource {
        val key = when (kind) {
            SourceKind.LAN -> "LAN|${settings.serverUrl}|${settings.token}"
            SourceKind.FOLDER -> "FOLDER|${settings.folderUri}"
            else -> kind.name
        }
        cache[key]?.let { return it }
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
            SourceKind.PHONE -> error("recipes on the phone live in the database, not a source")
        }
        cache.keys.removeAll { it.startsWith(kind.name + "|") } // drop the old PC/folder when it changes
        cache[key] = source
        return source
    }
}
