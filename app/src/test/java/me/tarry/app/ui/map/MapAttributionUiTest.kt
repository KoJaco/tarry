package me.tarry.app.ui.map

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.components.LocalMotionPreferences
import me.tarry.app.ui.components.MotionPreferences
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h800dp")
class MapAttributionUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(startExpanded: Boolean?, dark: Boolean = false, fontScale: Float = 1f, reducedMotion: Boolean = false) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale),
                LocalMotionPreferences provides MotionPreferences { reducedMotion },
            ) {
                TarryTheme(darkTheme = dark) { MapAttribution(startExpanded = startExpanded) }
            }
        }
    }

    private fun lineShown() = composeRule.onAllNodesWithTagCount("map_attribution_line") > 0

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun theFullLineShowsFirstThenCollapsesToTheInfoButtonAfterTheTimeout() {
        show(startExpanded = true)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("map_attribution_line").assertExists()
        composeRule.mainClock.advanceTimeBy(AttributionCollapseMs - 500)
        assertThat(lineShown()).isTrue()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.mainClock.advanceTimeBy(400) // the 200 ms exit
        assertThat(lineShown()).isFalse()
        composeRule.onNodeWithTag("map_attribution_info").assertExists()
    }

    @Test
    fun tappingTheInfoButtonShowsTheLineAgainAndItCollapsesAgain() {
        show(startExpanded = false)
        assertThat(lineShown()).isFalse()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("map_attribution_info").performClick()
        composeRule.mainClock.advanceTimeBy(400)
        assertThat(lineShown()).isTrue()
        composeRule.mainClock.advanceTimeBy(AttributionCollapseMs + 400)
        assertThat(lineShown()).isFalse()
    }

    @Test
    fun aSecondTapOnTheInfoButtonCollapsesTheLine() {
        show(startExpanded = false)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("map_attribution_info").performClick()
        composeRule.mainClock.advanceTimeBy(400)
        assertThat(lineShown()).isTrue()
        composeRule.onNodeWithTag("map_attribution_info").performClick()
        composeRule.mainClock.advanceTimeBy(400)
        assertThat(lineShown()).isFalse()
    }

    @Test
    fun reducedMotionCollapsesInstantly() {
        show(startExpanded = true, reducedMotion = true)
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(AttributionCollapseMs + 50)
        assertThat(lineShown()).isFalse()
    }

    private fun checkInfoButton(dark: Boolean, scale: Float) {
        show(startExpanded = false, dark = dark, fontScale = scale)
        composeRule.onNodeWithTag("map_attribution_info").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val config = composeRule.onNodeWithTag("map_attribution_info").fetchSemanticsNode().config
        assertThat(config[SemanticsProperties.ContentDescription]).containsExactly("Map data credits")
        // The clearance maps keep at the top is the info button, not the full line.
        assertThat(MapAttributionClearance).isEqualTo(48.dp)
    }

    @Test fun infoButtonLightNormalText() = checkInfoButton(dark = false, scale = 1f)

    @Test fun infoButtonLightLargeText() = checkInfoButton(dark = false, scale = 2f)

    @Test fun infoButtonDarkNormalText() = checkInfoButton(dark = true, scale = 1f)

    @Test fun infoButtonDarkLargeText() = checkInfoButton(dark = true, scale = 2f)

    @Test
    fun onlyTheFirstMapOfAProcessStartsExpanded() {
        AttributionSession.shown = false
        assertThat(AttributionSession.claimFirst()).isTrue()
        assertThat(AttributionSession.claimFirst()).isFalse()
        AttributionSession.shown = true // leave later tests as a later map would find it
    }
}
