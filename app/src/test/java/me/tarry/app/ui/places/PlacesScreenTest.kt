package me.tarry.app.ui.places

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlacesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 10, 23, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val opened = mutableListOf<String>()

    private fun daysAgo(d: Long) = now - d * 24 * 3_600_000L

    private fun show(state: PlacesUiState?) = composeRule.setContent {
        TarryTheme { PlacesContent(state, onOpenPlace = { opened += it }, nowMs = now, zone = zone) }
    }

    @Test
    fun rowsShowLabelVisitCountAndRelativeLastVisit() {
        show(
            PlacesUiState(
                listOf(
                    PlaceRow("a", "Shop", 3, daysAgo(0), ignored = false),
                    PlaceRow("b", "1 Main St", 1, daysAgo(1), ignored = false),
                    PlaceRow("c", "Depot", 12, daysAgo(3), ignored = false),
                    PlaceRow("d", "Old yard", 2, daysAgo(40), ignored = false),
                ),
            ),
        )

        composeRule.onNodeWithText("Shop").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("3 visits · last today").assertExists()
        composeRule.onNodeWithText("1 visit · last yesterday").assertExists()
        composeRule.onNodeWithText("12 visits · last 3 days ago").assertExists()
        composeRule.onNodeWithText("2 visits · last 13 Sep").assertExists()
    }

    @Test
    fun ignoredPlacesShowAChipAndTappingARowOpensIt() {
        show(PlacesUiState(listOf(PlaceRow("a", "Home", 4, daysAgo(0), ignored = true))))

        composeRule.onNodeWithText("Ignored").assertExists()
        composeRule.onNodeWithText("Home").performClick()

        assertThat(opened).containsExactly("a")
    }

    @Test
    fun emptyStateExplainsWhereTheyComeFrom() {
        show(PlacesUiState(emptyList()))

        composeRule.onNodeWithText("No places yet").assertExists()
    }
}
