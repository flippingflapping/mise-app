package app.mise.data

import app.mise.core.model.Annotations
import app.mise.data.source.LanServerSource
import app.mise.data.source.SourceException
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real app client against the real Python server (cross-language contract check). Opt-in:
 *   scripts/server.sh  (in another terminal), then
 *   ./gradlew :app:testDebugUnitTest --tests '*LiveServerE2ETest' -Pmise.e2eUrl=http://127.0.0.1:8765 -Pmise.e2eToken=<token>
 */
class LiveServerE2ETest {
    private val url = System.getProperty("mise.e2eUrl").orEmpty()
    private val token = System.getProperty("mise.e2eToken").orEmpty()

    @Test fun clientAndServerAgree() = runTest {
        assumeTrue("set -Pmise.e2eUrl and -Pmise.e2eToken to run", url.isNotBlank() && token.isNotBlank())
        val http = HttpClient(OkHttp) { expectSuccess = false }
        val source = LanServerSource(http, url, token)

        assertThat(source.testConnection()).startsWith("Connected to Mise server")
        val list = source.listRecipes(null)
        assertThat(list).isNotEmpty()
        assertThat(source.listRecipes(null)).isEqualTo(list) // second call is a 304 from the list ETag

        val id = list.first().id
        val recipe = source.getRecipe(id)
        assertThat(recipe.steps).isNotEmpty()
        assertThat(source.getSourceText(id)).isNotEmpty()

        source.putAnnotations(id, Annotations(favorite = true, cookNotes = listOf("e2e note"), lastCooked = "2026-10-16", cookLog = listOf("2026-10-16")))
        val after = source.getRecipe(id)
        assertThat(after.user?.favorite).isTrue()
        assertThat(after.cookNotes).containsExactly("e2e note")
        assertThat(source.listRecipes(null).first { it.id == id }.etag).isNotEqualTo(list.first().etag)

        val wrongToken = LanServerSource(http, url, "nope")
        val e = runCatching { wrongToken.listRecipes(null) }.exceptionOrNull() as SourceException
        assertThat(e.reason).isEqualTo(SourceException.Reason.UNAUTHORIZED)
    }
}
