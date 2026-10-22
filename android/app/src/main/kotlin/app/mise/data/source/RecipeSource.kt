package app.mise.data.source

import app.mise.core.model.Annotations
import app.mise.core.model.Recipe
import app.mise.core.model.RecipeSummary
import app.mise.data.settings.SourceKind
import java.time.Instant

/**
 * Where recipes come from. Implementations: [LanServerSource] (default), [FolderImportSource],
 * [BundledSampleSource], and the [CloudApiSource] stub. The app never reads a source directly for
 * display — [app.mise.data.repo.RecipeRepository] syncs sources into Room and the UI reads Room.
 */
interface RecipeSource {
    val kind: SourceKind
    val capabilities: SourceCapabilities

    /** Summaries of every recipe (or only those changed after [since], if supported). */
    suspend fun listRecipes(since: Instant?): List<RecipeSummary>

    suspend fun getRecipe(id: String): Recipe

    /** The raw text the recipe was converted from, for "View original". */
    suspend fun getSourceText(id: String): String? = null

    /** Image bytes, if the source serves them. */
    suspend fun getImage(id: String): ByteArray? = null

    /** Save my annotations. Throws [UnsupportedOperationException] if not supported. */
    suspend fun putAnnotations(id: String, a: Annotations)

    /** What the server can do beyond reading recipes. */
    suspend fun features(): ServerFeatures = ServerFeatures(canImport = false, canConvert = false)

    /** Send pasted recipe text to be converted (PC server only). */
    suspend fun importRecipe(text: String, title: String?, source: String?): ImportJob =
        throw UnsupportedOperationException("Adding recipes needs the PC server")

    /** Progress of an import; null if the server no longer knows it (e.g. it restarted). */
    suspend fun importStatus(jobId: String): ImportJob? = null

    /** A friendly one-line description of the connection, for Settings → Test connection. */
    suspend fun testConnection(): String {
        val n = listRecipes(null).size
        return "Connected — $n recipe${if (n == 1) "" else "s"} available"
    }
}

data class ServerFeatures(val canImport: Boolean, val canConvert: Boolean)

@kotlinx.serialization.Serializable
data class ImportJob(
    val id: String,
    val filename: String,
    /** saved | queued | converting | done | needs_review | failed */
    val status: String,
    @kotlinx.serialization.SerialName("recipe_id") val recipeId: String? = null,
    val error: String? = null,
) {
    val finished: Boolean get() = status in setOf("saved", "done", "needs_review", "failed")
}

data class SourceCapabilities(
    val readOnly: Boolean,
    val supportsAnnotations: Boolean,
    val supportsSourceText: Boolean,
    val supportsImages: Boolean,
    /** listRecipes(since) is honoured (otherwise always returns everything). */
    val supportsSince: Boolean = false,
)

/** A source failure, phrased for people. */
class SourceException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { UNREACHABLE, UNAUTHORIZED, NOT_FOUND, BAD_DATA, NOT_CONFIGURED, REJECTED, OTHER }
}
