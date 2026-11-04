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

    @Test
    fun doneKeyMapsToTheNeutralDoneStyleAndIsNotASlotKey() {
        assertThat(JobPalette.lookup(JobPalette.DONE_KEY, "any")).isSameInstanceAs(JobPalette.DONE)
        assertThat(JobPalette.slots.map { it.key }).doesNotContain(JobPalette.DONE_KEY)
        assertThat(JobPalette.displayKey(0xFFE2F2E6.toInt(), done = true)).isEqualTo(JobPalette.DONE_KEY)
        assertThat(JobPalette.displayKey(0xFFE2F2E6.toInt(), done = false)).isEqualTo(0xFFE2F2E6.toInt())
        assertThat(JobPalette.slots).doesNotContain(JobPalette.DONE)
    }

    @Test
    fun doneStyleIsInsetContainerOutlineBorderInkText() {
        val light = JobPalette.DONE.colors(dark = false)
        assertThat(light.container).isEqualTo(LightTarryColors.inset)
        assertThat(light.border).isEqualTo(LightTarryColors.outline)
        assertThat(light.text).isEqualTo(LightTarryColors.ink)
        val dark = JobPalette.DONE.colors(dark = true)
        assertThat(dark.container).isEqualTo(DarkTarryColors.inset)
        assertThat(dark.border).isEqualTo(DarkTarryColors.outline)
        assertThat(dark.text).isEqualTo(DarkTarryColors.ink)
    }
}
