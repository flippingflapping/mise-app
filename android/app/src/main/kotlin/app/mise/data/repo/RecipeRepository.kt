package app.mise.data.repo

import app.mise.core.edit.RecipeEditor
import app.mise.core.model.Annotations
import app.mise.core.model.IngredientEdit
import app.mise.core.model.Recipe
import app.mise.core.model.RecipeEdits
import app.mise.core.model.RecipeJson
import app.mise.core.model.RecipeStatus
import app.mise.core.model.RecipeYield
import app.mise.core.model.StepEdit
import app.mise.core.units.UnitMode
import app.mise.data.db.AnnotationDao
import app.mise.data.db.AnnotationEntity
import app.mise.data.db.LibraryRow
import app.mise.data.db.MiseDatabase
import app.mise.data.db.RecipeDao
import app.mise.data.db.RecipeEntity
import app.mise.data.db.SourceTextDao
import app.mise.data.db.SourceTextEntity
import app.mise.data.db.ViewStateDao
import app.mise.data.db.ViewStateEntity
import app.mise.data.settings.Settings
import app.mise.data.settings.SettingsStore
import app.mise.data.settings.SourceKind
import app.mise.data.source.RecipeSource
import app.mise.data.source.SourceException
import app.mise.di.ApplicationScope
import app.mise.di.Clock
import androidx.room.withTransaction
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Picks the source that feeds the library (bound to [app.mise.data.source.SourceFactory]). */
fun interface SourceProvider {
    fun source(settings: Settings): RecipeSource
}

data class LibraryItem(
    val id: String,
    val title: String,
    val tags: List<String>,
    val ingredientNames: List<String>,
    val status: RecipeStatus,
    val favorite: Boolean,
    val lastCooked: String?,
)

/** A recipe as the detail screen sees it: cached recipe + my annotations (and edits) + remembered view state. */
data class RecipeDetail(
    /** As converted, before my edits. */
    val original: Recipe,
    val sourceKind: SourceKind,
    val annotation: AnnotationEntity?,
    val viewState: ViewStateEntity?,
) {
    val edits: RecipeEdits get() = annotation?.editsJson?.let(::decodeEdits) ?: RecipeEdits()

    /** With my edits applied: what every screen shows. */
    val recipe: Recipe by lazy { RecipeEditor.apply(original, edits) }

    val favorite: Boolean get() = annotation?.favorite ?: false
    val status: RecipeStatus
        get() = if (annotation?.reviewed == true) RecipeStatus.READY else recipe.status
    val cookNotes: List<String>
        get() = annotation?.cookNotesJson?.let(::decodeStrings) ?: recipe.cookNotes
    val cookLog: List<String> get() = annotation?.cookLogJson?.let(::decodeStrings).orEmpty()
    val lastCooked: String? get() = annotation?.lastCooked
    val flagsDismissed: Boolean get() = annotation?.flagsDismissed ?: false
    val factor: Double get() = viewState?.factor ?: 1.0
    val checked: Set<String> get() = viewState?.checked?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()
}

sealed interface SyncResult {
    data class Success(val added: Int, val updated: Int, val removed: Int, val pushed: Int) : SyncResult
    data class Failed(val message: String, val reason: SourceException.Reason?) : SyncResult
}

data class SyncStatus(val running: Boolean = false, val lastResult: SyncResult? = null)

private val stringList = ListSerializer(String.serializer())
internal fun decodeStrings(json: String): List<String> =
    runCatching { RecipeJson.lenient.decodeFromString(stringList, json) }.getOrDefault(emptyList())
internal fun encodeStrings(list: List<String>): String = RecipeJson.canonical.encodeToString(stringList, list)
internal fun decodeEdits(json: String): RecipeEdits? =
    runCatching { RecipeJson.lenient.decodeFromString(RecipeEdits.serializer(), json) }.getOrNull()
internal fun encodeEdits(edits: RecipeEdits): String = RecipeJson.canonical.encodeToString(RecipeEdits.serializer(), edits)

/**
 * Offline-first: the UI only ever reads Room. [sync] pushes pending annotations, then pulls whatever
 * changed (by ETag) from the active source, and removes recipes the source no longer has.
 */
@Singleton
class RecipeRepository @Inject constructor(
    private val db: MiseDatabase,
    private val recipes: RecipeDao,
    private val annotations: AnnotationDao,
    private val viewState: ViewStateDao,
    private val sourceTexts: SourceTextDao,
    private val settings: SettingsStore,
    private val sources: SourceProvider,
    private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val syncMutex = Mutex()
    private val _pushProblem = MutableStateFlow<String?>(null)
    /** Why the PC last refused a change from this phone (it's kept here and retried), or null. */
    val pushProblem: StateFlow<String?> = _pushProblem.asStateFlow()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    // ------------------------------------------------------------------ reads

    fun library(): Flow<List<LibraryItem>> =
        recipes.library().map { rows -> rows.map(::toItem).sortedBy { it.title.lowercase() } }

    fun detail(id: String): Flow<RecipeDetail?> =
        combine(recipes.observe(id), annotations.observe(id), viewState.observe(id)) { r, a, v ->
            r?.let { RecipeDetail(RecipeJson.decodeRecipe(it.json), sourceKindOf(it.sourceKind), a, v) }
        }

    private fun toItem(row: LibraryRow) = LibraryItem(
        id = row.id,
        title = row.editsJson?.let(::decodeEdits)?.title ?: row.title,
        tags = row.tags.split('\n').filter { it.isNotBlank() },
        ingredientNames = row.ingredientNames.split('\n').filter { it.isNotBlank() },
        status = if (row.reviewed == true) RecipeStatus.READY
        else RecipeStatus.entries.firstOrNull { it.name == row.status } ?: RecipeStatus.NEEDS_REVIEW,
        favorite = row.favorite ?: false,
        lastCooked = row.lastCooked,
    )

    private fun sourceKindOf(name: String) = SourceKind.entries.firstOrNull { it.name == name } ?: SourceKind.SAMPLE

    // ------------------------------------------------------------------ sync

    /** Sync unless one finished in the last [minIntervalMs] (app open / screen resume). */
    suspend fun syncIfStale(minIntervalMs: Long = 60_000): SyncResult? {
        val last = settings.current().lastSyncAt ?: 0
        val empty = recipes.count() == 0
        return if (empty || clock.now() - last > minIntervalMs) sync() else null
    }

    suspend fun sync(): SyncResult = syncMutex.withLock {
        _status.value = _status.value.copy(running = true)
        val result = try {
            doSync(settings.current())
        } catch (e: SourceException) {
            SyncResult.Failed(e.message ?: "Sync failed", e.reason)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Failed("Sync failed: ${e.message ?: e.javaClass.simpleName}", null)
        }
        settings.recordSync(clock.now(), (result as? SyncResult.Failed)?.message)
        _status.value = SyncStatus(running = false, lastResult = result)
        result
    }

    private suspend fun doSync(s: Settings): SyncResult {
        val source = sources.source(s)
        val pushed = pushAnnotations(source)
        val summaries = source.listRecipes(null)
        val remote = summaries.associateBy { it.id }
        val local = recipes.all().associateBy { it.id }
        var added = 0
        var updated = 0
        for (summary in summaries) {
            val cached = local[summary.id]
            val unchanged = cached != null && cached.sourceKind == source.kind.name &&
                cached.etag != null && cached.etag == summary.etag
            if (unchanged) continue
            val recipe = source.getRecipe(summary.id)
            store(recipe, source.kind, summary.etag)
            if (cached == null) added++ else updated++
        }
        val gone = local.keys - remote.keys
        if (gone.isNotEmpty()) {
            db.withTransaction {
                recipes.delete(gone.toList())
                sourceTexts.delete(gone.toList())
            }
        }
        return SyncResult.Success(added, updated, gone.size, pushed)
    }

    private suspend fun store(recipe: Recipe, kind: SourceKind, etag: String?) {
        val entity = RecipeEntity(
            id = recipe.id,
            sourceKind = kind.name,
            json = RecipeJson.encodeRecipe(recipe),
            title = recipe.title,
            tags = recipe.tags.joinToString("\n"),
            ingredientNames = recipe.ingredients.flatMap { listOf(it.name, it.shortName) }.distinct().joinToString("\n"),
            status = recipe.status.name,
            updatedAt = recipe.updatedAt,
            etag = etag,
            cachedAt = clock.now(),
            image = recipe.image,
        )
        db.withTransaction {
            recipes.upsert(entity)
            // The source's view of my annotations wins unless I have unpushed local changes.
            val user = recipe.user
            val mine = annotations.get(recipe.id)
            if (user != null && (mine == null || !mine.dirty)) {
                annotations.upsert(
                    (mine ?: AnnotationEntity(recipeId = recipe.id)).copy(
                        favorite = user.favorite ?: false,
                        reviewed = user.reviewed,
                        cookNotesJson = null, // the served recipe already has the merged cook_notes
                        lastCooked = user.lastCooked,
                        cookLogJson = encodeStrings(user.cookLog.orEmpty()),
                        editsJson = user.edits?.takeUnless { it.isEmpty }?.let(::encodeEdits),
                    ),
                )
            }
        }
    }

    /** Push dirty annotations if the source supports them. Returns how many were pushed. */
    private suspend fun pushAnnotations(source: RecipeSource): Int {
        if (!source.capabilities.supportsAnnotations) return 0
        var pushed = 0
        for (a in annotations.dirty()) {
            if (recipes.get(a.recipeId) == null) continue
            try {
                source.putAnnotations(a.recipeId, a.toAnnotations())
            } catch (e: SourceException) {
                // One rejected change (e.g. edits an older PC server doesn't know) mustn't stop the rest
                // syncing. It stays saved on the phone and is retried next time.
                if (e.reason != SourceException.Reason.REJECTED) throw e
                _pushProblem.value = e.message
                continue
            }
            annotations.update(a.recipeId) { cur ->
                // Only clear dirty if nothing changed while the request was in flight.
                if (cur.updatedAt == a.updatedAt) cur.copy(dirty = false) else cur
            }
            pushed++
        }
        return pushed
    }

    private fun AnnotationEntity.toAnnotations() = Annotations(
        favorite = favorite,
        reviewed = reviewed,
        cookNotes = cookNotesJson?.let(::decodeStrings),
        lastCooked = lastCooked,
        cookLog = decodeStrings(cookLogJson),
        edits = editsJson?.let(::decodeEdits),
    )

    /** Save locally now, push in the background (it's fine if the PC is off: next sync retries). */
    private suspend fun annotate(id: String, change: (AnnotationEntity) -> AnnotationEntity) {
        annotations.update(id) { change(it).copy(dirty = true, updatedAt = clock.now()) }
        appScope.launch {
            runCatching {
                val source = sources.source(settings.current())
                syncMutex.withLock { pushAnnotations(source) }
            }
        }
    }

    // ------------------------------------------------------------------ annotations

    suspend fun setFavorite(id: String, favorite: Boolean) = annotate(id) { it.copy(favorite = favorite) }

    suspend fun markReviewed(id: String, reviewed: Boolean = true) = annotate(id) { it.copy(reviewed = reviewed) }

    suspend fun setCookNotes(id: String, notes: List<String>) =
        annotate(id) { it.copy(cookNotesJson = encodeStrings(notes.map(String::trim).filter(String::isNotEmpty))) }

    suspend fun madeThis(id: String, date: LocalDate = LocalDate.now()) = annotate(id) {
        val iso = date.toString()
        val log = (decodeStrings(it.cookLogJson) + iso).distinct().sorted()
        it.copy(lastCooked = log.last(), cookLogJson = encodeStrings(log))
    }

    // ------------------------------------------------------------------ edits

    /** Change my edits of a recipe: saved on the phone now, pushed to the PC in the background. */
    suspend fun updateEdits(id: String, change: (RecipeEdits) -> RecipeEdits) = annotate(id) {
        val next = change(it.editsJson?.let(::decodeEdits) ?: RecipeEdits())
        // "{}" (not null) so that undoing everything reaches the PC too.
        it.copy(editsJson = encodeEdits(next))
    }

    /** Save one ingredient as edited, or put it back as converted (edit = null). */
    suspend fun editIngredient(id: String, ingredientId: String, edit: IngredientEdit?) = updateEdits(id) {
        it.copy(ingredients = if (edit == null) it.ingredients - ingredientId else it.ingredients + (ingredientId to edit))
    }

    suspend fun editStep(id: String, stepId: String, edit: StepEdit?) = updateEdits(id) {
        it.copy(steps = if (edit == null) it.steps - stepId else it.steps + (stepId to edit))
    }

    suspend fun editServings(id: String, servings: RecipeYield?) = updateEdits(id) { it.copy(recipeYield = servings) }

    suspend fun editTitle(id: String, title: String?) = updateEdits(id) { it.copy(title = title?.trim()?.ifEmpty { null }) }

    /** Undo every edit of this recipe (review progress included). */
    suspend fun resetEdits(id: String) = updateEdits(id) { RecipeEdits() }

    /** Guided review: mark one flag as checked; when all are, the recipe counts as reviewed. */
    suspend fun checkFlag(id: String, path: String, allPaths: Collection<String>) = annotate(id) { a ->
        val edits = a.editsJson?.let(::decodeEdits) ?: RecipeEdits()
        val checked = (edits.checkedFlags + path).distinct()
        a.copy(
            editsJson = encodeEdits(edits.copy(checkedFlags = checked)),
            reviewed = if (checked.containsAll(allPaths)) true else a.reviewed,
        )
    }

    /** Local only: hide the review banner on this phone. */
    suspend fun dismissFlags(id: String) = annotations.update(id) { it.copy(flagsDismissed = true) }

    // ------------------------------------------------------------------ view state

    suspend fun setFactor(id: String, factor: Double?) = viewState.update(id) { it.copy(factor = factor) }

    suspend fun setUnitMode(id: String, mode: UnitMode) = viewState.update(id) { it.copy(unitMode = mode.name) }

    suspend fun toggleChecked(id: String, ingredientId: String) = viewState.update(id) { v ->
        val set = v.checked.split(',').filter { it.isNotEmpty() }.toMutableSet()
        if (!set.remove(ingredientId)) set += ingredientId
        v.copy(checked = set.sorted().joinToString(","))
    }

    suspend fun clearChecked(id: String) = viewState.update(id) { it.copy(checked = "") }

    // ------------------------------------------------------------------ extras

    /** The original text: cached copy first, else fetched from the source (and cached). */
    suspend fun sourceText(id: String): Result<String?> {
        sourceTexts.get(id)?.let { return Result.success(it.text) }
        return runCatching {
            val text = sources.source(settings.current()).getSourceText(id)
            if (text != null) sourceTexts.upsert(SourceTextEntity(id, text, clock.now()))
            text
        }
    }

    suspend fun image(id: String): ByteArray? = runCatching {
        sources.source(settings.current()).getImage(id)
    }.getOrNull()

    suspend fun testConnection(s: Settings): Result<String> = runCatching { sources.source(s).testConnection() }

    // ------------------------------------------------------------------ adding recipes

    suspend fun serverFeatures(): Result<app.mise.data.source.ServerFeatures> =
        runCatching { sources.source(settings.current()).features() }

    suspend fun importRecipe(text: String, title: String?, source: String?): Result<app.mise.data.source.ImportJob> =
        runCatching { sources.source(settings.current()).importRecipe(text, title, source) }

    suspend fun importStatus(jobId: String): Result<app.mise.data.source.ImportJob?> =
        runCatching { sources.source(settings.current()).importStatus(jobId) }

    /** Decoded recipes, for planning features (shopping lists, feasts). */
    /** Recipes with my edits applied (shopping lists and meal plans use the edited amounts). */
    suspend fun recipesById(ids: Collection<String>): Map<String, Recipe> = ids.mapNotNull { id ->
        recipes.get(id)?.let { row ->
            val edits = annotations.get(id)?.editsJson?.let(::decodeEdits)
            id to RecipeEditor.apply(RecipeJson.decodeRecipe(row.json), edits)
        }
    }.toMap()
}
