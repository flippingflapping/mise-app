package app.mise.data.repo

import androidx.room.withTransaction
import app.mise.core.edit.RecipeEditor
import app.mise.core.edit.StepText
import app.mise.core.model.Annotations
import app.mise.core.model.Ingredient
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
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Picks the source that feeds the library (bound to [app.mise.data.source.SourceFactory]). */
fun interface SourceProvider {
    /** The main source (the PC when paired); see [Settings.effectiveSource]. */
    fun source(settings: Settings): RecipeSource

    /** Every source the library is made of right now: the PC and/or a folder, or else the demo. */
    fun sources(settings: Settings): List<RecipeSource> = listOf(source(settings))
}

data class LibraryItem(
    val id: String,
    val title: String,
    val tags: List<String>,
    val ingredientNames: List<String>,
    val status: RecipeStatus,
    val favorite: Boolean,
    val lastCooked: String?,
    /** Where it's kept: on the PC, on this phone, in a folder (or the demo). */
    val location: SourceKind = SourceKind.LAN,
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
        location = sourceKindOf(row.sourceKind),
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

    /**
     * The library combines the PC, a folder and recipes kept on the phone. Each source is synced in
     * turn and only removes its own recipes; one failing (the PC is off) doesn't stop the others,
     * but is what the result reports.
     */
    private suspend fun doSync(s: Settings): SyncResult {
        val all = sources.sources(s)
        // The demo recipe only fills a library that would otherwise be empty.
        val enabled = if (all.all { it.kind == SourceKind.SAMPLE } && recipes.countOf(SourceKind.PHONE.name) > 0) emptyList() else all
        var added = 0
        var updated = 0
        var removed = 0
        var pushed = 0
        var failure: SyncResult.Failed? = null
        for (source in enabled) {
            try {
                val r = pull(source)
                added += r.added; updated += r.updated; removed += r.removed; pushed += r.pushed
            } catch (e: SourceException) {
                if (failure == null) failure = SyncResult.Failed(e.message ?: "Sync failed", e.reason)
            }
        }
        // Recipes from places that aren't part of the library any more (unpaired, folder forgotten, the
        // demo once there's something real) go. The phone's own recipes never do.
        val keep = enabled.map { it.kind.name }.toSet() + SourceKind.PHONE.name
        val stale = recipes.all().filter { it.sourceKind !in keep }.map { it.id }
        delete(stale)
        return failure ?: SyncResult.Success(added, updated, removed + stale.size, pushed)
    }

    /** When two places have a recipe with the same id: the phone's own wins, then the PC, then a folder. */
    private fun rank(kind: String) = when (kind) {
        SourceKind.PHONE.name -> 3
        SourceKind.LAN.name -> 2
        SourceKind.FOLDER.name -> 1
        else -> 0
    }

    private suspend fun pull(source: RecipeSource): SyncResult.Success {
        val pushed = pushAnnotations(source)
        val summaries = source.listRecipes(null)
        val remote = summaries.associateBy { it.id }
        val local = recipes.all().associateBy { it.id }
        var added = 0
        var updated = 0
        for (summary in summaries) {
            val cached = local[summary.id]
            if (cached != null && cached.sourceKind != source.kind.name && rank(cached.sourceKind) >= rank(source.kind.name)) continue
            val unchanged = cached != null && cached.sourceKind == source.kind.name &&
                cached.etag != null && cached.etag == summary.etag
            if (unchanged) continue
            val recipe = source.getRecipe(summary.id)
            store(recipe, source.kind, summary.etag)
            if (cached == null) added++ else updated++
        }
        val gone = local.values.filter { it.sourceKind == source.kind.name && it.id !in remote }.map { it.id }
        delete(gone)
        return SyncResult.Success(added, updated, gone.size, pushed)
    }

    private suspend fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        db.withTransaction {
            recipes.delete(ids)
            sourceTexts.delete(ids)
        }
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
            // Only that source's own recipes: notes on a recipe kept on the phone stay on the phone.
            if (recipes.get(a.recipeId)?.sourceKind != source.kind.name) continue
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
                val source = sourceFor(id) ?: return@runCatching
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

    /** Add an ingredient of my own (see [RecipeEditor.nextAddedId] for its id). */
    suspend fun addIngredient(id: String, ingredient: Ingredient) =
        updateEdits(id) { it.copy(addedIngredients = it.addedIngredients + ingredient) }

    /**
     * Remove an ingredient. Steps that use it keep its name as plain words, without an amount
     * ("Add the garlic"), so the method still reads; they can be reworded afterwards.
     */
    suspend fun removeIngredient(id: String, ingredientId: String) {
        val original = recipes.get(id)?.let { RecipeJson.decodeRecipe(it.json) } ?: return
        updateEdits(id) { e ->
            val current = RecipeEditor.apply(original, e)
            val ing = current.ingredient(ingredientId) ?: return@updateEdits e
            val steps = e.steps.toMutableMap()
            for (step in current.steps) {
                val reworded = StepText.withoutIngredient(step.content, ing)
                if (reworded != step.content) steps[step.id] = (e.steps[step.id] ?: RecipeEditor.editOf(step)).copy(content = reworded)
            }
            val wasAdded = e.addedIngredients.any { it.id == ingredientId }
            e.copy(
                addedIngredients = e.addedIngredients.filter { it.id != ingredientId },
                removedIngredients = if (wasAdded) e.removedIngredients else (e.removedIngredients + ingredientId).distinct(),
                ingredients = e.ingredients - ingredientId,
                steps = steps,
            )
        }
    }

    /** Put a removed ingredient back (the steps keep their plain wording; reword them to show the amount again). */
    suspend fun restoreIngredient(id: String, ingredientId: String) =
        updateEdits(id) { it.copy(removedIngredients = it.removedIngredients - ingredientId) }

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

    // ------------------------------------------------------------------ recipes kept on the phone

    private suspend fun uniqueId(base: String): String {
        val taken = recipes.allIds().toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    /** Keep a recipe on this phone (an imported file…); its id is made unique if needed. Returns the id. */
    suspend fun saveOnPhone(recipe: Recipe, sourceText: String? = null): String = syncMutex.withLock {
        val id = uniqueId(recipe.id)
        store(recipe.copy(id = id, user = null), SourceKind.PHONE, etag = null)
        sourceText?.let { sourceTexts.upsert(SourceTextEntity(id, it, clock.now())) }
        id
    }

    /**
     * An independent copy on this phone, with my edits baked in: it stays if the original goes (or the
     * PC is unpaired), and changes to one don't touch the other. Returns the copy's id.
     */
    suspend fun keepCopyOnPhone(id: String): String? {
        val d = detail(id).first() ?: return null
        val text = sourceText(id).getOrNull()
        return syncMutex.withLock {
            val copyId = uniqueId("$id-phone")
            store(d.recipe.copy(id = copyId, user = null), SourceKind.PHONE, etag = null)
            text?.let { sourceTexts.upsert(SourceTextEntity(copyId, it, clock.now())) }
            d.annotation?.let { a -> annotations.upsert(a.copy(recipeId = copyId, editsJson = null, dirty = false)) }
            copyId
        }
    }

    sealed interface SaveToPc {
        data object Saved : SaveToPc
        /** The PC already has a recipe with this id: ask before replacing it. */
        data object AlreadyThere : SaveToPc
        data class Failed(val message: String) : SaveToPc
    }

    /**
     * Put a recipe kept on the phone (or in a folder) on the PC. From then on it's a PC recipe, still
     * available offline like the rest; my notes and edits go with it.
     */
    suspend fun saveToPc(id: String, replace: Boolean = false): SaveToPc {
        val row = recipes.get(id) ?: return SaveToPc.Failed("That recipe isn't here any more")
        val pc = try { pc() } catch (e: SourceException) { return SaveToPc.Failed(e.message ?: "Pair with your PC first") }
        val text = sourceText(id).getOrNull()
        val served = try {
            pc.addRecipe(RecipeJson.decodeRecipe(row.json), text, replace)
        } catch (e: SourceException) {
            return if (e.reason == SourceException.Reason.CONFLICT) SaveToPc.AlreadyThere
            else SaveToPc.Failed(e.message ?: "Couldn't save it to your PC")
        }
        syncMutex.withLock {
            store(served, SourceKind.LAN, etag = null) // the same recipe, now kept on the PC
            if (annotations.get(id) != null) annotations.update(id) { it.copy(dirty = true, updatedAt = clock.now()) }
            runCatching { pushAnnotations(pc) }
        }
        return SaveToPc.Saved
    }

    /** Delete one of the phone's own recipes (PC and folder recipes are managed there). */
    suspend fun deleteFromPhone(id: String) = syncMutex.withLock {
        if (recipes.get(id)?.sourceKind != SourceKind.PHONE.name) return@withLock
        db.withTransaction {
            recipes.delete(listOf(id))
            sourceTexts.delete(listOf(id))
            annotations.delete(id)
            viewState.delete(id)
        }
    }

    // ------------------------------------------------------------------ extras

    /** The source a recipe comes from (null for the phone's own recipes, which live only here). */
    private suspend fun sourceFor(id: String): RecipeSource? {
        val kind = recipes.get(id)?.sourceKind ?: return null
        return sources.sources(settings.current()).firstOrNull { it.kind.name == kind }
    }

    /** The PC server, for what only it can do (converting, writing recipes, substitutes…). */
    private suspend fun pc(): RecipeSource =
        sources.sources(settings.current()).firstOrNull { it.kind == SourceKind.LAN }
            ?: throw SourceException(SourceException.Reason.NOT_CONFIGURED, "Pair with your PC first (Settings → Your PC)")

    /** The original text: cached copy first, else fetched from the source (and cached). */
    suspend fun sourceText(id: String): Result<String?> {
        sourceTexts.get(id)?.let { return Result.success(it.text) }
        return runCatching {
            val text = sourceFor(id)?.getSourceText(id)
            if (text != null) sourceTexts.upsert(SourceTextEntity(id, text, clock.now()))
            text
        }
    }

    suspend fun image(id: String): ByteArray? = runCatching { sourceFor(id)?.getImage(id) }.getOrNull()

    suspend fun testConnection(s: Settings): Result<String> = runCatching { sources.source(s).testConnection() }

    // ------------------------------------------------------------------ adding recipes

    suspend fun serverFeatures(): Result<app.mise.data.source.ServerFeatures> =
        runCatching { pc().features() }

    /** Swap ideas from the PC. My edits are pushed first, so the ideas fit the recipe as I see it. */
    suspend fun substitutes(id: String, ingredientId: String): Result<List<app.mise.data.source.Substitute>> = runCatching {
        val source = pc()
        if (recipes.get(id)?.sourceKind != SourceKind.LAN.name) {
            throw SourceException(SourceException.Reason.OTHER, "Substitute ideas work for recipes on your PC. Save this one to your PC first.")
        }
        syncMutex.withLock { pushAnnotations(source) }
        source.substitutes(id, ingredientId)
    }

    /** Use a swap idea: it becomes an ordinary edit of that ingredient ("leave it out" removes it). */
    suspend fun useSubstitute(id: String, ingredientId: String, s: app.mise.data.source.Substitute) {
        if (s.name.trim().equals("leave it out", ignoreCase = true)) return removeIngredient(id, ingredientId)
        // The steps should name the new ingredient too ("Add 2 shallots", not "Add 2 onion").
        editIngredient(id, ingredientId, IngredientEdit(name = s.name.trim(), shortName = s.name.trim(), amount = s.amount, unit = s.unit))
    }

    suspend fun requestRecipe(text: String): Result<app.mise.data.source.ImportJob> =
        runCatching { pc().requestRecipe(text) }

    suspend fun importRecipe(text: String, title: String?, source: String?): Result<app.mise.data.source.ImportJob> =
        runCatching { pc().importRecipe(text, title, source) }

    suspend fun importStatus(jobId: String): Result<app.mise.data.source.ImportJob?> =
        runCatching { pc().importStatus(jobId) }

    /** Recipes with my edits applied (shopping lists and meal plans use the edited amounts). */
    suspend fun recipesById(ids: Collection<String>): Map<String, Recipe> = ids.mapNotNull { id ->
        recipes.get(id)?.let { row ->
            val edits = annotations.get(id)?.editsJson?.let(::decodeEdits)
            id to RecipeEditor.apply(RecipeJson.decodeRecipe(row.json), edits)
        }
    }.toMap()
}
