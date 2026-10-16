package app.mise.core.pairing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PairingUriTest {
    @Test fun roundTrip() {
        val info = PairingInfo("http://192.168.1.20:8765", "abc_DEF-123+/=")
        val uri = PairingUri.build(info)
        assertThat(uri).startsWith("mise://pair?url=http%3A%2F%2F192.168.1.20%3A8765&token=")
        assertThat(PairingUri.parse(uri)).isEqualTo(info)
    }

    @Test fun acceptsHttpUrlWithToken() {
        assertThat(PairingUri.parse("http://10.0.0.5:8765/?token=xyz"))
            .isEqualTo(PairingInfo("http://10.0.0.5:8765", "xyz"))
    }

    @Test fun rejectsJunk() {
        assertThat(PairingUri.parse("hello")).isNull()
        assertThat(PairingUri.parse("mise://pair?url=http%3A%2F%2Fx")).isNull() // no token
        assertThat(PairingUri.parse("mise://other?url=a&token=b")).isNull()
        assertThat(PairingUri.parse("ftp://host?token=a")).isNull()
    }

    @Test fun normalizesServerUrl() {
        assertThat(PairingUri.normalizeServerUrl("192.168.1.20:8765")).isEqualTo("http://192.168.1.20:8765")
        assertThat(PairingUri.normalizeServerUrl("http://pc.local:8765/api/v1/")).isEqualTo("http://pc.local:8765")
        assertThat(PairingUri.normalizeServerUrl("")).isNull()
        assertThat(PairingUri.normalizeServerUrl("ftp://x")).isNull()
    }
}
