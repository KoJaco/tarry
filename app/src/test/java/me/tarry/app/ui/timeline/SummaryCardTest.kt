package me.tarry.app.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SummaryCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(vararg reviews: Int, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                TarryTheme {
                    Column {
                        reviews.forEachIndexed { i, n ->
                            Box(Modifier.testTag("card_$i")) { SummaryCard(DaySummary(3_600_000L, 3, 1, 0, n), isToday = true) }
                        }
                    }
                }
            }
        }
    }

    private fun height(tag: String) = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot().height

    @Test
    fun theReviewChipKeepsAFortyEightDpTouchTarget() {
        show(2)
        composeRule.onNodeWithTag("timeline_filter_review").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun theTouchTargetDoesNotAddVisibleSpaceToTheCard() {
        show(0, 2)
        // The pill (about 24 dp) and the 8 dp gap above the caption; a layout-sized 48 dp target would add 56 dp.
        val added = height("card_1") - height("card_0")
        assertThat(added.value).isAtMost(32f + 4f)
        assertThat(added.value).isGreaterThan(0f)
    }
}
