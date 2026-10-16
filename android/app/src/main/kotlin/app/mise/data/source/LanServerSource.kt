package app.mise.data.source

import app.mise.core.model.Annotations
import app.mise.core.model.Recipe
import app.mise.core.model.RecipeJson
import app.mise.core.model.RecipeSummary
import app.mise.data.settings.SourceKind
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.Instant

/** Talks to the PC companion server (`server/`) over the home network. */
class LanServerSource(
    private val http: HttpClient,
    baseUrl: String,
    private val token: String,
) : RecipeSource {
    private val api = baseUrl.trimEnd('/') + "/api/v1"

    override val kind = SourceKind.LAN
    override val capabilities = SourceCapabilities(
        readOnly = false,
        supportsAnnotations = true,
        supportsSourceText = true,
        supportsImages = true,
        supportsSince = true,
    )

    @Serializable
    private data class Listing(val recipes: List<RecipeSummary>, val ids: List<String> = emptyList())

    @Serializable
    private data class Health(val status: String, val version: String = "?", val schema_version: Int = 0)

    // Cached list response for If-None-Match: an unchanged library costs one 304.
    @Volatile private var listCache: Pair<String, List<RecipeSummary>>? = null

    override suspend fun listRecipes(since: Instant?): List<RecipeSummary> {
        val cached = listCache.takeIf { since == null }
        val resp = call {
            http.get("$api/recipes") {
                auth()
                since?.let { parameter("since", it.toString()) }
                cached?.let { header(HttpHeaders.IfNoneMatch, it.first) }
            }
        }
        if (resp.status == HttpStatusCode.NotModified && cached != null) return cached.second
        ensureOk(resp)
        val listing = decode { RecipeJson.lenient.decodeFromString(Listing.serializer(), resp.bodyAsText()) }
        if (since == null) resp.headers[HttpHeaders.ETag]?.let { listCache = it to listing.recipes }
        return listing.recipes
    }

    override suspend fun getRecipe(id: String): Recipe {
        val resp = call { http.get("$api/recipes/${seg(id)}") { auth() } }
        ensureOk(resp)
        return decode { RecipeJson.decodeRecipe(resp.bodyAsText()) }
    }

    override suspend fun getSourceText(id: String): String? {
        val resp = call { http.get("$api/recipes/${seg(id)}/source") { auth() } }
        if (resp.status == HttpStatusCode.NotFound) return null
        ensureOk(resp)
        return resp.bodyAsText()
    }

    override suspend fun getImage(id: String): ByteArray? {
        val resp = call { http.get("$api/recipes/${seg(id)}/image") { auth() } }
        if (resp.status == HttpStatusCode.NotFound) return null
        ensureOk(resp)
        return resp.bodyAsBytes()
    }

    override suspend fun putAnnotations(id: String, a: Annotations) {
        val body = RecipeJson.canonical.encodeToString(Annotations.serializer(), a.copy(updatedAt = null))
        val resp = call {
            http.put("$api/recipes/${seg(id)}/annotations") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        ensureOk(resp)
        listCache = null
    }

    override suspend fun testConnection(): String {
        val health = call { http.get("$api/health") }
        ensureOk(health)
        val h = decode { RecipeJson.lenient.decodeFromString(Health.serializer(), health.bodyAsText()) }
        val n = listRecipes(null).size // checks the token too
        return "Connected to Mise server ${h.version} — $n recipe${if (n == 1) "" else "s"}"
    }

    private fun HttpRequestBuilder.auth() = header(HttpHeaders.Authorization, "Bearer $token")

    private fun seg(id: String) = java.net.URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: IOException) {
        throw SourceException(SourceException.Reason.UNREACHABLE, "Can't reach your PC", e)
    } catch (e: Exception) {
        // Unresolvable host, malformed URL, timeouts that aren't IOExceptions, …
        throw SourceException(SourceException.Reason.UNREACHABLE, "Can't reach your PC (${e.javaClass.simpleName})", e)
    }

    private fun ensureOk(resp: HttpResponse) {
        when {
            resp.status.value in 200..299 -> Unit
            resp.status == HttpStatusCode.Unauthorized ->
                throw SourceException(SourceException.Reason.UNAUTHORIZED, "The PC rejected the access token — pair again")
            resp.status == HttpStatusCode.NotFound ->
                throw SourceException(SourceException.Reason.NOT_FOUND, "Not found on the PC")
            else -> throw SourceException(SourceException.Reason.OTHER, "The PC server answered ${resp.status.value}")
        }
    }

    private inline fun <T> decode(block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw SourceException(SourceException.Reason.BAD_DATA, "The PC sent data this app can't read", e)
    } catch (e: IllegalArgumentException) {
        throw SourceException(SourceException.Reason.BAD_DATA, "The PC sent data this app can't read", e)
    }
}
