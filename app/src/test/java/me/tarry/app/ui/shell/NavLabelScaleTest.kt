package me.tarry.app.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h800dp")
class NavLabelScaleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val base = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

    @Test
    fun belowTheCap_theStyleIsUntouched() {
        assertThat(base.cappedAtFontScale(1.3f, 1.0f)).isEqualTo(base)
        assertThat(base.cappedAtFontScale(1.3f, 1.3f)).isEqualTo(base)
    }

    @Test
    fun aboveTheCap_sizeAndLineHeightComeToTheCappedScale() {
        val capped = base.cappedAtFontScale(1.3f, 2.0f)
        assertThat(capped.fontSize.value * 2.0f).isWithin(0.001f).of(13f * 1.3f)
        assertThat(capped.lineHeight.value * 2.0f).isWithin(0.001f).of(18f * 1.3f)
    }

    @Test
    fun navSlot_isNoTallerAtTwiceTheFontSizeThanAtTheCap() {
        composeRule.setContent {
            val d = LocalDensity.current
            Column {
                for (scale in listOf(1.3f, 2.0f)) {
                    CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                        TarryTheme {
                            TarryBottomBar(TrackingState.IDLE, timelineSelected = true, settingsSelected = false, {}, {}, {})
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val slots = composeRule.onAllNodesWithTag(BottomBarTags.TimelineSlot)
        val atCap = slots[0].getUnclippedBoundsInRoot().height.value
        val atTwice = slots[1].getUnclippedBoundsInRoot().height.value
        assertThat(atTwice).isWithin(0.5f).of(atCap)
    }
}
