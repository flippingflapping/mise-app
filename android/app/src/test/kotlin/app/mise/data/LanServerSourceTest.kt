package app.mise.data

import app.mise.TestFixtures
import app.mise.core.model.Annotations
import app.mise.data.source.LanServerSource
import app.mise.data.source.SourceException
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class LanServerSourceTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val listJson = """{"recipes":[{"id":"sri-lankan-lamb-curry-for-kothu","title":"Curry","tags":["lamb"],
        "status":"needs_review","updated_at":"2026-10-15T00:00:00Z","etag":"\"abc\""}],"ids":["sri-lankan-lamb-curry-for-kothu"],
        "server_time":"2026-10-16T00:00:00Z","future":1}"""

    private fun source(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        LanServerSource(HttpClient(MockEngine { req -> requests += req; handler(req) }) { expectSuccess = false }, "http://pc:8765/", "secret")

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun listsWithBearerTokenAndCachesByEtag() = runTest {
        val s = source { req ->
            if (req.headers[HttpHeaders.IfNoneMatch] == "\"list1\"") respond("", HttpStatusCode.NotModified)
            else respond(listJson, HttpStatusCode.OK, headersOf(HttpHeaders.ETag to listOf("\"list1\""), HttpHeaders.ContentType to listOf("application/json")))
        }
        val first = s.listRecipes(null)
        assertThat(first.single().etag).isEqualTo("\"abc\"")
        assertThat(requests[0].url.toString()).isEqualTo("http://pc:8765/api/v1/recipes")
        assertThat(requests[0].headers[HttpHeaders.Authorization]).isEqualTo("Bearer secret")
        val second = s.listRecipes(null) // 304 → same list
        assertThat(second).isEqualTo(first)
        assertThat(requests[1].headers[HttpHeaders.IfNoneMatch]).isEqualTo("\"list1\"")
    }

    @Test fun getsAndDecodesTheFixture() = runTest {
        val s = source { respond(TestFixtures.lambJson, HttpStatusCode.OK, json) }
        val r = s.getRecipe("sri-lankan-lamb-curry-for-kothu")
        assertThat(r.ingredients).hasSize(26)
        assertThat(requests.single().url.encodedPath).isEqualTo("/api/v1/recipes/sri-lankan-lamb-curry-for-kothu")
    }

    @Test fun putsAnnotationsAsJson() = runTest {
        val s = source { respond("{}", HttpStatusCode.OK, json) }
        s.putAnnotations("x", Annotations(favorite = true, cookNotes = listOf("a")))
        val req = requests.single()
        assertThat(req.method).isEqualTo(HttpMethod.Put)
        val body = (req.body as TextContent).text
        assertThat(body).contains("\"favorite\":true")
        assertThat(body).contains("\"cook_notes\":[\"a\"]")
    }

    @Test fun mapsErrorsToFriendlyReasons() = runTest {
        val unauthorized = source { respondError(HttpStatusCode.Unauthorized) }
        val e1 = runCatching { unauthorized.listRecipes(null) }.exceptionOrNull() as SourceException
        assertThat(e1.reason).isEqualTo(SourceException.Reason.UNAUTHORIZED)

        val down = source { throw IOException("connect failed") }
        val e2 = runCatching { down.listRecipes(null) }.exceptionOrNull() as SourceException
        assertThat(e2.reason).isEqualTo(SourceException.Reason.UNREACHABLE)
        assertThat(e2.message).isEqualTo("Can't reach your PC")

        val garbage = source { respond("not json", HttpStatusCode.OK, json) }
        val e3 = runCatching { garbage.getRecipe("x") }.exceptionOrNull() as SourceException
        assertThat(e3.reason).isEqualTo(SourceException.Reason.BAD_DATA)

        val missing = source { respondError(HttpStatusCode.NotFound) }
        assertThat(missing.getSourceText("x")).isNull()
    }

    @Test fun unreachableMessagesNameTheLikelyCause() {
        fun msg(e: Throwable) = LanServerSource.unreachableMessage(e)
        assertThat(msg(java.net.SocketTimeoutException("connect timed out"))).contains("firewall")
        assertThat(msg(IOException("wrapped", java.net.ConnectException("Connection refused")))).contains("refused")
        assertThat(msg(java.net.NoRouteToHostException())).contains("same Wi-Fi")
        assertThat(msg(IOException("x"))).isEqualTo("Can't reach your PC")
    }

    @Test fun importsRecipesAndFollowsTheJob() = runTest {
        val s = source { req ->
            when {
                req.url.encodedPath.endsWith("/health") ->
                    respond("""{"status":"ok","can_import":true,"can_convert":false}""", HttpStatusCode.OK, json)
                req.method == HttpMethod.Post ->
                    respond("""{"id":"j1","filename":"from-phone/x.txt","status":"saved","recipe_id":null,"error":null,"created_at":"x"}""", HttpStatusCode.Accepted, json)
                req.url.encodedPath.endsWith("/imports/j1") ->
                    respond("""{"id":"j1","filename":"from-phone/x.txt","status":"done","recipe_id":"curry"}""", HttpStatusCode.OK, json)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        assertThat(s.features()).isEqualTo(app.mise.data.source.ServerFeatures(canImport = true, canConvert = false))
        val job = s.importRecipe("2 eggs", "Eggs", null)
        assertThat(job.status).isEqualTo("saved")
        assertThat(job.finished).isTrue()
        val body = (requests.first { it.method == HttpMethod.Post }.body as TextContent).text
        assertThat(body).contains("\"text\":\"2 eggs\"")
        assertThat(body).contains("\"title\":\"Eggs\"")
        assertThat(s.importStatus("j1")!!.recipeId).isEqualTo("curry")
        assertThat(s.importStatus("gone")).isNull()
    }

    @Test fun testConnectionChecksHealthAndToken() = runTest {
        val s = source { req ->
            if (req.url.encodedPath.endsWith("/health")) respond("""{"status":"ok","version":"0.1.0","schema_version":1}""", HttpStatusCode.OK, json)
            else respond(listJson, HttpStatusCode.OK, json)
        }
        assertThat(s.testConnection()).isEqualTo("Connected to Mise server 0.1.0 — 1 recipe")
        assertThat(requests.first().headers[HttpHeaders.Authorization]).isNull() // health is open
    }
}
