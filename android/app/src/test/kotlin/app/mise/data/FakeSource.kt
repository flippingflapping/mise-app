package app.mise.data

import app.mise.core.model.Annotations
import app.mise.core.model.Recipe
import app.mise.core.model.RecipeSummary
import app.mise.data.settings.Settings
import app.mise.data.settings.SettingsStore
import app.mise.data.settings.SourceKind
import app.mise.data.source.RecipeSource
import app.mise.data.source.SourceCapabilities
import app.mise.data.source.SourceException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant

class FakeSource(
    override val kind: SourceKind = SourceKind.LAN,
    supportsAnnotations: Boolean = true,
) : RecipeSource {
    val recipes = linkedMapOf<String, Pair<Recipe, String>>() // id → (recipe, etag)
    val gets = mutableListOf<String>()
    val puts = mutableListOf<Pair<String, Annotations>>()
    var unreachable = false
    /** Recipe ids whose annotation PUTs the "server" refuses (422), like an older server seeing edits. */
    val rejecting = mutableSetOf<String>()

    override val capabilities = SourceCapabilities(
        readOnly = !supportsAnnotations, supportsAnnotations = supportsAnnotations,
        supportsSourceText = true, supportsImages = false,
    )

    fun put(recipe: Recipe, etag: String) { recipes[recipe.id] = recipe to etag }

    private fun check() {
        if (unreachable) throw SourceException(SourceException.Reason.UNREACHABLE, "Can't reach your PC")
    }

    override suspend fun listRecipes(since: Instant?): List<RecipeSummary> {
        check()
        return recipes.values.map { (r, e) -> RecipeSummary(r.id, r.title, r.tags, r.status, r.updatedAt, e) }
    }

    override suspend fun getRecipe(id: String): Recipe {
        check()
        gets += id
        return recipes[id]?.first ?: throw SourceException(SourceException.Reason.NOT_FOUND, "nope")
    }

    /** The original text; null = "raw text of <id>". */
    var sourceText: String? = null

    override suspend fun getSourceText(id: String): String? = sourceText ?: "raw text of $id"

    var features = app.mise.data.source.ServerFeatures(canImport = true, canConvert = true)
    val imports = mutableListOf<String>()
    /** Statuses handed out for successive importStatus calls. */
    var importStatuses = ArrayDeque(listOf("converting", "done"))

    override suspend fun features() = features

    override suspend fun importRecipe(text: String, title: String?, source: String?): app.mise.data.source.ImportJob {
        check()
        imports += text
        return app.mise.data.source.ImportJob("job1", "from-phone/x.txt", if (features.canConvert) "queued" else "saved")
    }

    override suspend fun importStatus(jobId: String): app.mise.data.source.ImportJob? {
        val status = importStatuses.removeFirstOrNull() ?: "done"
        return app.mise.data.source.ImportJob(jobId, "from-phone/x.txt", status, recipeId = if (status == "done") "sri-lankan-lamb-curry-for-kothu" else null)
    }

    override suspend fun putAnnotations(id: String, a: Annotations) {
        check()
        if (!capabilities.supportsAnnotations) throw UnsupportedOperationException()
        if (id in rejecting) throw SourceException(SourceException.Reason.REJECTED, "update the PC server")
        puts += id to a
    }
}

class FakeSettings(initial: Settings = Settings(serverUrl = "http://pc:8765", token = "t")) : SettingsStore {
    val state = MutableStateFlow(initial)
    override val settings = state
    override suspend fun current() = state.first()
    override suspend fun recordSync(at: Long, error: String?) {
        state.value = state.value.copy(lastSyncAt = if (error == null) at else state.value.lastSyncAt, lastSyncError = error)
    }
}
