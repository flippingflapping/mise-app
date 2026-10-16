package app.mise.data.source

import android.content.res.AssetManager
import app.mise.core.model.Annotations
import app.mise.core.model.Recipe
import app.mise.core.model.RecipeJson
import app.mise.core.model.RecipeSummary
import app.mise.data.settings.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * The shared fixture(s), packaged as assets (see app/build.gradle.kts): `expected/<id>.json` plus
 * `raw/<original_filename>` for "View original". Used on first run and for development.
 */
class BundledSampleSource(private val assets: AssetManager) : RecipeSource {
    override val kind = SourceKind.SAMPLE
    override val capabilities = SourceCapabilities(
        readOnly = true, supportsAnnotations = false, supportsSourceText = true, supportsImages = false,
    )

    private suspend fun load(): List<Recipe> = withContext(Dispatchers.IO) {
        (assets.list("expected") ?: emptyArray()).filter { it.endsWith(".json") }.sorted().map { name ->
            RecipeJson.decodeRecipe(assets.open("expected/$name").bufferedReader().use { it.readText() })
        }
    }

    override suspend fun listRecipes(since: Instant?): List<RecipeSummary> = load().map {
        RecipeSummary(it.id, it.title, it.tags, it.status, it.updatedAt, etag = "sample-${it.updatedAt}-${it.sourceTextSha256}")
    }

    override suspend fun getRecipe(id: String): Recipe =
        load().firstOrNull { it.id == id } ?: throw SourceException(SourceException.Reason.NOT_FOUND, "No sample $id")

    override suspend fun getSourceText(id: String): String? = withContext(Dispatchers.IO) {
        val file = getRecipe(id).source?.originalFilename ?: return@withContext null
        runCatching { assets.open("raw/$file").bufferedReader().use { it.readText() } }.getOrNull()
    }

    override suspend fun putAnnotations(id: String, a: Annotations) =
        throw UnsupportedOperationException("The demo recipe keeps notes on this phone only")

    override suspend fun testConnection() = "Showing the built-in demo recipe"
}
