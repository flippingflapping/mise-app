package app.mise.diag

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mise.data.FakeSettings
import app.mise.data.FakeSource
import app.mise.data.db.MiseDatabase
import app.mise.data.repo.RecipeRepository
import app.mise.data.repo.SourceProvider
import app.mise.data.settings.Settings
import app.mise.di.Clock
import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

@RunWith(AndroidJUnit4::class)
class ConnectionDoctorTest {
    private lateinit var db: MiseDatabase
    private val source = FakeSource()
    private val token = "secret-token-1234567890"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MiseDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun doctor(url: String): ConnectionDoctor {
        val settings = FakeSettings(Settings(serverUrl = url, token = token))
        val repo = RecipeRepository(
            db, db.recipes(), db.annotations(), db.viewState(), db.sourceTexts(), settings,
            SourceProvider { source }, Clock { 0L }, CoroutineScope(Dispatchers.Unconfined),
        )
        return ConnectionDoctor(ApplicationProvider.getApplicationContext(), settings, repo)
    }

    private fun List<DiagStep>.step(prefix: String) = single { it.title.startsWith(prefix) }

    @Test fun walksEveryLinkAgainstARealServer() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/health") { ex ->
                val body = """{"status":"ok","name":"mise-server","version":"0.1.0"}""".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            var progress = 0
            val steps = doctor(url).run { progress++ }
            assertThat(progress).isEqualTo(steps.size)
            assertThat(steps.step("Paired with").status).isEqualTo(DiagStep.Status.OK)
            assertThat(steps.step("PC address").status).isEqualTo(DiagStep.Status.FAIL) // 127.x is the phone itself
            assertThat(steps.step("Connect to").status).isEqualTo(DiagStep.Status.OK)
            assertThat(steps.step("Ask the server").status).isEqualTo(DiagStep.Status.OK)
            assertThat(steps.step("Sign in").status).isEqualTo(DiagStep.Status.OK)
            val report = ConnectionDoctor.report(steps, 0)
            assertThat(report).contains("✓ Connect to 127.0.0.1")
            assertThat(report).doesNotContain(token)
        } finally {
            server.stop(0)
        }
    }

    @Test fun nothingListeningIsExplained() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // free port, now closed
        val steps = doctor("http://127.0.0.1:$port").run()
        val tcp = steps.step("Connect to")
        assertThat(tcp.status).isEqualTo(DiagStep.Status.FAIL)
        assertThat(tcp.detail).contains("nothing is listening on port $port")
    }

    @Test fun httpsAndMissingPairingAreCaught() = runBlocking {
        assertThat(doctor("https://192.168.1.48:8765").run().step("Paired with").detail).contains("plain http://")
        assertThat(doctor("").run().step("Paired with").status).isEqualTo(DiagStep.Status.FAIL)
    }

    @Test fun helpers() {
        assertThat(ConnectionDoctor.subnetHint(listOf("192.168.1.23"), "192.168.1.48")).isNull()
        assertThat(ConnectionDoctor.subnetHint(listOf("192.168.0.23"), "192.168.1.48")).contains("different networks")
        assertThat(ConnectionDoctor.subnetHint(emptyList(), "192.168.1.48")).isNull()
        assertThat(ConnectionDoctor.explainSocketError(SocketTimeoutException("connect timed out"), 8765)).contains("firewall")
    }
}
