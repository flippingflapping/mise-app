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

    override suspend fun getSourceText(id: String): String? = "raw text of $id"

    override suspend fun putAnnotations(id: String, a: Annotations) {
        check()
        if (!capabilities.supportsAnnotations) throw UnsupportedOperationException()
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
