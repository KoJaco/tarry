package me.tarry.app.segmentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaceGeocoderTest {
    @Test
    fun addressLineZeroWins() {
        assertThat(formatAddress("1 Main St, Sydney", "Main St", "Sydney", "NSW")).isEqualTo("1 Main St, Sydney")
    }

    @Test
    fun blankLineFallsBackToPartsSkippingNullAndBlank() {
        assertThat(formatAddress(" ", "Main St", null, "NSW")).isEqualTo("Main St, NSW")
        assertThat(formatAddress(null, "Main St", "Sydney", "NSW")).isEqualTo("Main St, Sydney, NSW")
        assertThat(formatAddress(null, "", "Sydney", " ")).isEqualTo("Sydney")
    }

    @Test
    fun nothingUsableIsNull() {
        assertThat(formatAddress(null, null, "", " ")).isNull()
    }
}
