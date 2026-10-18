package app.mise.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import app.mise.BuildConfig
import app.mise.data.repo.RecipeRepository
import app.mise.data.settings.SettingsStore
import app.mise.data.settings.SourceKind
import app.mise.data.source.LanServerSource
import app.mise.ui.LocalNetworkPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/** One line of the connection check. */
data class DiagStep(val title: String, val status: Status, val detail: String) {
    enum class Status(val mark: String) { OK("✓"), WARN("!"), FAIL("✗"), INFO("·") }
}

/**
 * Settings → Connection check: tests each link between the phone and the PC server separately
 * (permission, Wi-Fi, TCP, HTTP, token) and says where it breaks. The report never contains the token.
 */
@Singleton
class ConnectionDoctor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val repository: RecipeRepository,
) {
    /** Runs every step, reporting progress through [onProgress]. */
    suspend fun run(onProgress: (List<DiagStep>) -> Unit = {}): List<DiagStep> = withContext(Dispatchers.IO) {
        val steps = mutableListOf<DiagStep>()
        fun add(step: DiagStep) { steps += step; onProgress(steps.toList()) }

        val s = settings.current()
        add(DiagStep(
            "This phone", DiagStep.Status.INFO,
            "Mise ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL}",
        ))

        if (s.sourceKind != SourceKind.LAN) {
            add(DiagStep("Recipes come from", DiagStep.Status.WARN, "${s.sourceKind.label}, not the PC server. Change it at the top of Settings."))
        }
        val uri = runCatching { URI(s.serverUrl.trim()) }.getOrNull()
        val host = uri?.host
        val port = uri?.port?.takeIf { it > 0 } ?: 8765
        when {
            s.serverUrl.isBlank() -> add(DiagStep("Paired with", DiagStep.Status.FAIL, "Not paired yet. Scan the QR code the server shows."))
            host == null -> add(DiagStep("Paired with", DiagStep.Status.FAIL, "“${s.serverUrl}” isn't a valid address. Pair again."))
            uri.scheme.equals("https", ignoreCase = true) ->
                add(DiagStep("Paired with", DiagStep.Status.FAIL, "${s.serverUrl} starts with https:// but the server speaks plain http://. Pair again."))
            else -> add(DiagStep(
                "Paired with", if (s.token.isBlank()) DiagStep.Status.FAIL else DiagStep.Status.OK,
                "${uri.scheme}://$host:$port · access token " + if (s.token.isBlank()) "missing — pair again" else "set (${s.token.length} characters)",
            ))
        }
        addressHint(host)?.let { add(it) }

        add(permissionStep())
        val (networkStep, wifi, default) = networkStep(host)
        add(networkStep)

        if (host != null) {
            add(tcpStep("Connect to $host:$port", host, port, null))
            if (wifi != null && wifi != default) add(tcpStep("Connect through Wi-Fi directly (skipping the VPN)", host, port, wifi))
            add(healthStep(host, port))
            add(tokenStep())
        }

        val last = s.lastSyncAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
        add(DiagStep(
            "Last sync", DiagStep.Status.INFO,
            (last?.let { "at $it" } ?: "never") + (s.lastSyncError?.let { " · failed: $it" } ?: if (last != null) " · OK" else ""),
        ))
        steps
    }

    private fun permissionStep(): DiagStep = when {
        Build.VERSION.SDK_INT < 37 -> DiagStep("Nearby devices permission", DiagStep.Status.OK, "Not needed on this Android version.")
        !LocalNetworkPermission.needed(context) -> DiagStep("Nearby devices permission", DiagStep.Status.OK, "Allowed.")
        else -> DiagStep(
            "Nearby devices permission", DiagStep.Status.FAIL,
            "Not allowed, so Android silently blocks the connection. App info → Permissions → Nearby devices → Allow.",
        )
    }

    private data class NetworkResult(val step: DiagStep, val wifi: Network?, val default: Network?)

    private fun networkStep(serverHost: String?): NetworkResult {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkResult(DiagStep("Phone's network", DiagStep.Status.WARN, "Couldn't read the network state."), null, null)
        val default = cm.activeNetwork
        val caps = default?.let { cm.getNetworkCapabilities(it) }
        @Suppress("DEPRECATION") // allNetworks: the only way to find Wi-Fi underneath a VPN without a callback
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        val kinds = buildList {
            if (caps == null) add("no connection")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("Ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("mobile data")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
        }
        val addresses = listOfNotNull(default, wifi).distinct()
            .flatMap { n -> cm.getLinkProperties(n)?.linkAddresses.orEmpty().map { it.address } }
            .filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct()
        val detail = "Using ${kinds.joinToString(" + ")}; this phone's address: ${addresses.joinToString().ifEmpty { "none" }}"
        val (status, extra) = when {
            caps == null -> DiagStep.Status.FAIL to "The phone isn't connected to any network."
            wifi == null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                DiagStep.Status.FAIL to "Not on Wi-Fi: the PC is only reachable from your home Wi-Fi."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
                DiagStep.Status.WARN to "A VPN is on; it can send home-network traffic away. Try with it off."
            else -> subnetHint(addresses, serverHost)?.let { DiagStep.Status.WARN to it } ?: (DiagStep.Status.OK to null)
        }
        return NetworkResult(DiagStep("Phone's network", status, listOfNotNull(detail, extra).joinToString(". ")), wifi, default)
    }

    private fun tcpStep(title: String, host: String, port: Int, via: Network?): DiagStep {
        val start = System.nanoTime()
        return try {
            (via?.socketFactory?.createSocket() ?: Socket()).use { it.connect(InetSocketAddress(host, port), TIMEOUT_MS) }
            DiagStep(title, DiagStep.Status.OK, "Connected in ${(System.nanoTime() - start) / 1_000_000} ms: the network path to the PC works.")
        } catch (e: Exception) {
            DiagStep(title, DiagStep.Status.FAIL, explainSocketError(e, port))
        }
    }

    private fun healthStep(host: String, port: Int): DiagStep {
        val title = "Ask the server if it's there (no token needed)"
        return try {
            val conn = URL("http://$host:$port/api/v1/health").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            try {
                val code = conn.responseCode
                val body = runCatching { conn.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
                when {
                    code == 200 && "mise-server" in body -> DiagStep(title, DiagStep.Status.OK, "Mise server answered: ${body.take(160)}")
                    code == 200 -> DiagStep(title, DiagStep.Status.FAIL, "Something answered, but it isn't the Mise server: ${body.take(120)}")
                    else -> DiagStep(title, DiagStep.Status.FAIL, "HTTP $code from that address. Is it the Mise server's port?")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            DiagStep(title, DiagStep.Status.FAIL, explainSocketError(e, port))
        }
    }

    private suspend fun tokenStep(): DiagStep {
        val title = "Sign in with the access token and list recipes"
        val s = settings.current()
        return repository.testConnection(s).fold(
            onSuccess = { DiagStep(title, DiagStep.Status.OK, it) },
            onFailure = { DiagStep(title, DiagStep.Status.FAIL, it.message ?: LanServerSource.unreachableMessage(it)) },
        )
    }

    private fun addressHint(host: String?): DiagStep? {
        val octets = host?.split('.')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 } ?: return null
        return when {
            octets[0] == 127 || host == "localhost" ->
                DiagStep("PC address", DiagStep.Status.FAIL, "$host means “this phone”, not your PC. Pair again with the PC's 192.168.x.x address.")
            octets[0] == 172 && octets[1] in 16..31 -> DiagStep(
                "PC address", DiagStep.Status.WARN,
                "$host is often a virtual adapter (WSL, Docker, Hyper-V) the phone can't reach. Open http://localhost:8765 on the PC for the other addresses.",
            )
            else -> null
        }
    }

    companion object {
        const val TIMEOUT_MS = 5_000

        /** Warns when no phone address is in the same /24 as the PC (a guest network, or another router). */
        fun subnetHint(phoneAddresses: List<String>, serverHost: String?): String? {
            val server = serverHost?.split('.')?.takeIf { it.size == 4 && it.all { p -> p.toIntOrNull() != null } } ?: return null
            if (phoneAddresses.isEmpty()) return null
            val same = phoneAddresses.any { it.split('.').take(3) == server.take(3) }
            return if (same) null else
                "The phone (${phoneAddresses.joinToString()}) and the PC ($serverHost) look like different networks. " +
                    "Is the phone on a guest Wi-Fi, or the PC on another router?"
        }

        /** Plain-English reason for a failed connection, from the exception chain. */
        fun explainSocketError(e: Throwable, port: Int): String {
            val chain = generateSequence(e) { it.cause }.take(8).toList()
            val raw = chain.joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
            val why = when {
                chain.any { it is SocketTimeoutException } ->
                    "Nothing answered within ${TIMEOUT_MS / 1000} s. Usually the PC's firewall, the wrong address, a different network, " +
                        "or (Android 17) the Nearby devices permission."
                chain.any { it is ConnectException && it.message.orEmpty().contains("refused", true) } ->
                    "The PC answered but nothing is listening on port $port. Is the server running, on this port?"
                chain.any { it is NoRouteToHostException } || chain.any { it.message.orEmpty().contains("EHOSTUNREACH") } ->
                    "No route to that address. The phone and PC aren't on the same network."
                chain.any { it is SecurityException || it.message.orEmpty().contains("EPERM") || it.message.orEmpty().contains("EACCES") } ->
                    "Android blocked the connection. Allow the Nearby devices permission for Mise."
                chain.any { it.message.orEmpty().contains("ENETUNREACH") } -> "The phone has no network that can reach that address."
                else -> "Failed."
            }
            return "$why\n($raw)"
        }

        fun report(steps: List<DiagStep>, at: Long): String = buildString {
            appendLine("Mise connection check · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))}")
            steps.forEach { appendLine("${it.status.mark} ${it.title}: ${it.detail}") }
        }.trimEnd()
    }
}
