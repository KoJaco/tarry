package me.tarry.app.ui.theme

import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.UUID

class JobPaletteTest {
    @Test
    fun hasEightDistinctSlots() {
        assertThat(JobPalette.slots).hasSize(8)
        assertThat(JobPalette.slots.map { it.key }.toSet()).hasSize(8)
    }

    @Test
    fun knownColourMapsToItsSlotRegardlessOfJobId() {
        val sage = JobPalette.slots.first { it.name == "Sage" }
        assertThat(sage.key).isEqualTo(0xFFE2F2E6.toInt())
        assertThat(JobPalette.lookup(0xFFE2F2E6.toInt(), "any-id")).isSameInstanceAs(sage)
        assertThat(sage.colors(dark = true).container.toArgb()).isEqualTo(0xFF16301F.toInt())
    }

    @Test
    fun unknownOrNullFallsBackDeterministicallyInRange() {
        val id = "3f2b8a52-0c1e-4d57-9a7e-6f1d2c3b4a59"
        val a = JobPalette.lookup(0x12345678, id)
        assertThat(a).isSameInstanceAs(JobPalette.lookup(null, id))
        assertThat(a).isSameInstanceAs(JobPalette.fallbackFor(id))
        assertThat(JobPalette.slots).contains(a)
    }

    @Test
    fun fallbackIsInRangeForManyIdsIncludingNegativeHashes() {
        repeat(500) {
            assertThat(JobPalette.slots).contains(JobPalette.fallbackFor(UUID.randomUUID().toString()))
        }
        // "polygenelubricants".hashCode() == Int.MIN_VALUE: floorMod must not go negative.
        assertThat(JobPalette.slots).contains(JobPalette.fallbackFor("polygenelubricants"))
    }
}
