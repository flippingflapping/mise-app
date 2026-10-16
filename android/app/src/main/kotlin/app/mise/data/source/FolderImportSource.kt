package app.mise.data.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.mise.core.model.Annotations
import app.mise.core.model.Recipe
import app.mise.core.model.RecipeJson
import app.mise.core.model.RecipeSummary
import app.mise.data.settings.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** A flat folder of files. Two implementations: Storage Access Framework trees, and plain files. */
interface FileTree {
    data class Item(val name: String, val lastModified: Long, val size: Long)

    fun list(): List<Item>
    fun read(name: String): String?
    fun readBytes(name: String): ByteArray?
}

class DocumentFileTree(private val context: Context, treeUri: Uri) : FileTree {
    private val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw SourceException(SourceException.Reason.NOT_CONFIGURED, "That folder can't be opened")

    override fun list(): List<FileTree.Item> {
        if (!root.canRead()) {
            throw SourceException(SourceException.Reason.UNREACHABLE, "Mise lost access to the folder — choose it again in Settings")
        }
        return root.listFiles().filter { it.isFile }.map { FileTree.Item(it.name.orEmpty(), it.lastModified(), it.length()) }
    }

    private fun find(name: String) = root.findFile(name)?.takeIf { it.isFile }

    override fun read(name: String): String? =
        find(name)?.let { f -> context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() } }

    override fun readBytes(name: String): ByteArray? =
        find(name)?.let { f -> context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } }
}

class JavaFileTree(private val dir: File) : FileTree {
    override fun list(): List<FileTree.Item> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile }.map { FileTree.Item(it.name, it.lastModified(), it.length()) }

    override fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()
    override fun readBytes(name: String): ByteArray? = File(dir, name).takeIf { it.isFile }?.readBytes()
}

/**
 * Recipes from a folder picked with the Storage Access Framework — a Syncthing folder, a Google
 * Drive folder, a USB copy of the PC's recipes folder, … — plus individual JSON files imported
 * into the app ([imported]). Same layout as the PC folder: `<id>.json`, `<id>.source.txt`.
 * Read-only: annotations stay on the phone.
 */
class FolderImportSource(private val trees: List<FileTree>) : RecipeSource {
    override val kind = SourceKind.FOLDER
    override val capabilities = SourceCapabilities(
        readOnly = true, supportsAnnotations = false, supportsSourceText = true, supportsImages = true,
    )

    private data class Found(val tree: FileTree, val item: FileTree.Item, val recipe: Recipe?)

    private fun isRecipeFile(name: String) = name.endsWith(".json") && !name.endsWith(".user.json") && !name.startsWith(".")

    private suspend fun scan(): List<Found> = withContext(Dispatchers.IO) {
        trees.flatMap { tree ->
            tree.list().filter { isRecipeFile(it.name) }.map { item ->
                val recipe = runCatching { tree.read(item.name)?.let(RecipeJson::decodeRecipe) }.getOrNull()
                Found(tree, item, recipe)
            }
        }
    }

    override suspend fun listRecipes(since: Instant?): List<RecipeSummary> =
        scan().mapNotNull { f -> f.recipe?.let { r ->
            RecipeSummary(r.id, r.title, r.tags, r.status, r.updatedAt, etag = "${f.item.lastModified}-${f.item.size}")
        } }.distinctBy { it.id }

    override suspend fun getRecipe(id: String): Recipe =
        scan().firstNotNullOfOrNull { it.recipe?.takeIf { r -> r.id == id } }
            ?: throw SourceException(SourceException.Reason.NOT_FOUND, "$id.json isn't in the folder any more")

    override suspend fun getSourceText(id: String): String? = withContext(Dispatchers.IO) {
        trees.firstNotNullOfOrNull { runCatching { it.read("$id.source.txt") }.getOrNull() }
    }

    override suspend fun getImage(id: String): ByteArray? = withContext(Dispatchers.IO) {
        val name = runCatching { getRecipe(id).image }.getOrNull()
        val candidates = listOfNotNull(name) + listOf("jpg", "jpeg", "png", "webp").map { "$id.$it" }
        trees.firstNotNullOfOrNull { t -> candidates.firstNotNullOfOrNull { runCatching { t.readBytes(it) }.getOrNull() } }
    }

    override suspend fun putAnnotations(id: String, a: Annotations) =
        throw UnsupportedOperationException("Folder recipes keep notes on this phone only")

    override suspend fun testConnection(): String {
        val all = scan()
        val ok = all.count { it.recipe != null }
        val bad = all.size - ok
        return "Found $ok recipe${if (ok == 1) "" else "s"}" + if (bad > 0) " ($bad file${if (bad == 1) "" else "s"} couldn't be read)" else ""
    }

    companion object {
        /** Where "Import JSON files…" copies individual files. */
        fun importedDir(context: Context) = File(context.filesDir, "imported").apply { mkdirs() }
    }
}
