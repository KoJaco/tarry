package me.tarry.app.ui.timeline

import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.DarkTarryColors
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.theme.LightTarryColors
import org.junit.Test

/** A suggestion is a dashed outline straight on `card`: it must reach 3:1 there in both themes, for every job colour. */
class SuggestionOutlineTest {
    @Test
    fun everySlotsSuggestionOutlineReaches3to1OnCardInBothThemes() {
        val failures = mutableListOf<String>()
        for ((name, colors, dark) in listOf(Triple("light", LightTarryColors, false), Triple("dark", DarkTarryColors, true))) {
            for (slot in JobPalette.slots + JobPalette.DONE) {
                val border = suggestionBorder(slot.colors(dark).border, colors)
                val r = contrastRatio(border, colors.card)
                if (r < 3.0) failures += "${slot.name} $name: $r"
            }
        }
        assertThat(failures).isEmpty()
    }

    @Test
    fun theThemeOutlineIsUsedWhenTheJobColourIsTooFaintAndTheJobColourWhenItIsEnough() {
        val faint = JobPalette.slots.first { it.name == "Sage" }.colors(false).border
        assertThat(suggestionBorder(faint, LightTarryColors)).isEqualTo(LightTarryColors.outline)
        assertThat(suggestionBorder(DarkTarryColors.ink, DarkTarryColors)).isEqualTo(DarkTarryColors.ink)
    }

    @Test
    fun visitNeedsReviewOutlineIsOutlineAndReaches3to1OnCard() {
        assertThat(contrastRatio(LightTarryColors.outline, LightTarryColors.card)).isAtLeast(3.0)
        assertThat(contrastRatio(DarkTarryColors.outline, DarkTarryColors.card)).isAtLeast(3.0)
    }
}
