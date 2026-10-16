package app.mise.core.pairing

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class PairingInfo(val serverUrl: String, val token: String)

/**
 * The pairing QR code shown by the PC server: `mise://pair?url=<server url>&token=<token>`.
 * Also accepts a bare server URL with a `token` query parameter, for manual entry.
 */
object PairingUri {
    const val SCHEME = "mise"
    const val HOST = "pair"

    fun build(info: PairingInfo): String =
        "$SCHEME://$HOST?url=${enc(info.serverUrl)}&token=${enc(info.token)}"

    fun parse(text: String): PairingInfo? {
        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
        val params = query(uri.rawQuery)
        return when (uri.scheme?.lowercase()) {
            SCHEME -> {
                if (uri.host != HOST) return null
                val url = params["url"]?.let(::normalizeServerUrl) ?: return null
                val token = params["token"]?.takeIf { it.isNotBlank() } ?: return null
                PairingInfo(url, token)
            }
            "http", "https" -> {
                val token = params["token"]?.takeIf { it.isNotBlank() } ?: return null
                val base = URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
                normalizeServerUrl(base)?.let { PairingInfo(it, token) }
            }
            else -> null
        }
    }

    /**
     * "192.168.1.20:8765" → "http://192.168.1.20:8765". Strips trailing slashes and a trailing
     * "/api/v1". Returns null for anything that isn't an http(s) URL with a host.
     */
    fun normalizeServerUrl(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "http://$s"
        s = s.trimEnd('/').removeSuffix("/api/v1").trimEnd('/')
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        return s
    }

    private fun query(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            dec(k) to dec(v)
        }

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
    private fun dec(s: String) = URLDecoder.decode(s, StandardCharsets.UTF_8)
}
