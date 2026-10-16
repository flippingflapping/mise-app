package app.mise.ui

import app.mise.ui.detail.parseAmount
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ParseAmountTest {
    @Test fun parsesKitchenNumbers() {
        assertThat(parseAmount("400")).isEqualTo(400.0)
        assertThat(parseAmount("1.5")).isEqualTo(1.5)
        assertThat(parseAmount("1,5")).isEqualTo(1.5)
        assertThat(parseAmount("1½")).isEqualTo(1.5)
        assertThat(parseAmount("½")).isEqualTo(0.5)
        assertThat(parseAmount("1 1/2")).isEqualTo(1.5)
        assertThat(parseAmount("3/4")).isEqualTo(0.75)
        assertThat(parseAmount("")).isNull()
        assertThat(parseAmount("abc")).isNull()
        assertThat(parseAmount("0")).isNull()
        assertThat(parseAmount("1/0")).isNull()
    }
}
