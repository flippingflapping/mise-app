package app.mise.photos

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Recipe photos kept on the phone, as JPEG bytes by recipe id: the photos of recipes kept on the
 * phone, and a copy of PC photos so they show offline.
 */
interface PhotoStore {
    fun get(id: String): ByteArray?
    fun put(id: String, jpeg: ByteArray)
    fun delete(id: String)
    fun has(id: String): Boolean = get(id) != null
}

class FilePhotoStore(context: Context) : PhotoStore {
    private val dir = File(context.filesDir, "photos").apply { mkdirs() }
    private fun file(id: String) = File(dir, "$id.jpg")

    override fun get(id: String): ByteArray? = file(id).takeIf { it.isFile }?.readBytes()
    override fun has(id: String): Boolean = file(id).isFile
    override fun delete(id: String) { file(id).delete() }

    override fun put(id: String, jpeg: ByteArray) {
        val tmp = File(dir, "$id.jpg.tmp")
        tmp.writeBytes(jpeg)
        tmp.renameTo(file(id))
    }
}

/** For tests. */
class InMemoryPhotoStore : PhotoStore {
    private val photos = ConcurrentHashMap<String, ByteArray>()
    override fun get(id: String) = photos[id]
    override fun put(id: String, jpeg: ByteArray) { photos[id] = jpeg }
    override fun delete(id: String) { photos.remove(id) }
}
