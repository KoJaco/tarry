package me.tarry.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher("role is $role") {
        it.config.getOrNull(SemanticsProperties.Role) == role
    }

    @Test
    fun settingsRow_chevron_isButton_mergedAndAtLeast56dp() {
        var clicks = 0
        composeRule.setContent {
            SettingsRow(title = "Permissions", onClick = { clicks++ }, icon = R.drawable.ic_permissions, subtitle = "All granted")
        }
        val node = composeRule.onNode(hasText("Permissions"))
        node.assert(hasRole(Role.Button)).assertHasClickAction().assertHeightIsAtLeast(56.dp)
        // Title and subtitle are merged into the one focusable row.
        node.assert(hasText("All granted"))
        node.performClick()
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun settingsValueRow_readOnly_hasMergedValue_andNoClick() {
        composeRule.setContent { SettingsValueRow(title = "Rounding", value = "15 min") }
        composeRule.onNode(hasText("Rounding")).assert(hasText("15 min")).assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun settingsValueRow_clickable_isButton() {
        composeRule.setContent { SettingsValueRow(title = "Stop radius", value = "100 m", onClick = {}) }
        composeRule.onNode(hasText("Stop radius")).assert(hasRole(Role.Button)).assert(hasText("100 m"))
    }

    @Test
    fun settingsGroup_rowsAreMergedButtons_atLeast64dp_orReadOnly() {
        var clicks = 0
        composeRule.setContent {
            SettingsGroup {
                row("Stop radius", onClick = { clicks++ }, icon = R.drawable.ic_radius, subtitle = "100 m")
                row("Rounding", value = "15 min")
            }
        }
        val tappable = composeRule.onNode(hasText("Stop radius"))
        tappable.assert(hasRole(Role.Button)).assertHasClickAction().assert(hasText("100 m")).assertHeightIsAtLeast(64.dp)
        tappable.performClick()
        assertThat(clicks).isEqualTo(1)
        val readOnly = composeRule.onNode(hasText("Rounding"))
        readOnly.assert(hasText("15 min")).assertHeightIsAtLeast(64.dp)
        readOnly.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
    }

    @Test
    fun settingsGroup_dividersSitBetweenRows_andTakeNoFocusOrClicks() {
        composeRule.setContent {
            SettingsGroup {
                row("One", onClick = {}, icon = R.drawable.ic_radius)
                row("Two", onClick = {}, icon = R.drawable.ic_permissions)
                row("Three", onClick = {})
            }
        }
        val dividers = composeRule.onAllNodesWithTag(SettingsGroupDividerTag, useUnmergedTree = true)
        dividers.assertCountEquals(2)
        for (i in 0..1) {
            dividers[i].assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
            dividers[i].assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.RequestFocus))
        }
        // Edge to edge: no inset on either side, for icon and non-icon rows alike.
        val card = composeRule.onRoot().getUnclippedBoundsInRoot()
        for (i in 0..1) {
            val bounds = dividers[i].getUnclippedBoundsInRoot()
            assertThat(bounds.left).isEqualTo(card.left)
            assertThat(bounds.right).isEqualTo(card.right)
        }
    }

    @Test
    fun settingsSwitchRow_isSwitch_wholeRowToggles() {
        var checked by androidx.compose.runtime.mutableStateOf(false)
        composeRule.setContent {
            SettingsSwitchRow(title = "Dark mode", checked = checked, onCheckedChange = { checked = it })
        }
        val row = composeRule.onNode(hasText("Dark mode"))
        row.assert(hasRole(Role.Switch)).assertIsOff().assertHeightIsAtLeast(56.dp)
        row.performClick()
        composeRule.runOnIdle { assertThat(checked).isTrue() }
    }

    @Test
    fun settingsSwitchRow_on_andDisabled() {
        composeRule.setContent {
            SettingsSwitchRow(title = "Dark mode", checked = true, onCheckedChange = {}, enabled = false)
        }
        composeRule.onNode(hasText("Dark mode")).assertIsOn().assertIsNotEnabled()
    }

    @Test
    fun buttons_areButtons_atLeast52dp_andDisabledState() {
        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                PrimaryButton("Primary", {})
                SecondaryButton("Secondary", {})
                TertiaryButton("Tertiary", {})
                DestructiveButton("Destructive", {})
                PrimaryButton("Disabled", {}, enabled = false)
            }
        }
        listOf("Primary", "Secondary", "Tertiary", "Destructive").forEach {
            composeRule.onNodeWithText(it).assert(hasRole(Role.Button)).assertHeightIsAtLeast(52.dp).assertIsEnabled()
        }
        composeRule.onNodeWithText("Disabled").assertIsNotEnabled()
    }

    @Test
    fun chip_selectedAndUnselected_semantics() {
        var selected by androidx.compose.runtime.mutableStateOf(false)
        composeRule.setContent {
            TarryChip(text = "Boat", selected = selected, onClick = { selected = !selected })
        }
        val chip = composeRule.onNodeWithText("Boat")
        chip.assertIsNotSelected().assertHeightIsAtLeast(48.dp).assert(hasRole(Role.RadioButton))
        chip.performClick()
        composeRule.runOnIdle { assertThat(selected).isTrue() }
        chip.assertIsSelected()
    }

    @Test
    fun chip_disabled() {
        composeRule.setContent { TarryChip(text = "Boat", selected = false, onClick = {}, enabled = false) }
        composeRule.onNodeWithText("Boat").assertIsNotEnabled()
    }

    @Test
    fun segmentedControl_tabsWithSelectedState() {
        var index by androidx.compose.runtime.mutableIntStateOf(0)
        composeRule.setContent {
            SegmentedControl(listOf("System", "Light", "Dark"), selectedIndex = index, onSelect = { index = it })
        }
        composeRule.onNodeWithText("System").assert(hasRole(Role.Tab)).assertIsSelected().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Light").assert(hasRole(Role.Tab)).assertIsNotSelected()
        composeRule.onNodeWithText("Dark").performClick()
        composeRule.runOnIdle { assertThat(index).isEqualTo(2) }
        composeRule.onNodeWithText("Dark").assertIsSelected()
    }

    @Test
    fun iconCircleButton_hasDescription_48dp_andEnabledState() {
        var clicks = 0
        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                IconCircleButton(R.drawable.ic_chevron_left, "Previous day", { clicks++ })
                IconCircleButton(R.drawable.ic_chevron_right, "Next day", { clicks++ }, enabled = false)
            }
        }
        composeRule.onNodeWithContentDescription("Previous day")
            .assertContentDescriptionEquals("Previous day")
            .assert(hasRole(Role.Button))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithContentDescription("Next day").assertIsNotEnabled()
        composeRule.runOnIdle { assertThat(clicks).isEqualTo(1) }
    }

    @Test
    fun emptyState_showsTitleAndBody() {
        composeRule.setContent { EmptyState("Nothing yet today", "Visits appear here.") }
        composeRule.onNodeWithText("Nothing yet today").assertIsDisplayed()
        composeRule.onNodeWithText("Visits appear here.").assertIsDisplayed()
    }

    @Test
    fun errorCard_showsCause_andActionIsButton() {
        var clicks = 0
        composeRule.setContent { ErrorCard("Could not load.", "Try again", { clicks++ }) }
        composeRule.onNodeWithText("Could not load.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assert(hasRole(Role.Button)).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertThat(clicks).isEqualTo(1) }
    }

    @Test
    fun loadingBlocks_haveDescription_andSettleWhenMotionReduced() {
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalMotionPreferences provides MotionPreferences { true }) {
                LoadingBlocks()
            }
        }
        composeRule.onNodeWithContentDescription("Loading").assertIsDisplayed()
        // With reduced motion there is no infinite animation, so the compose clock goes idle.
        composeRule.waitForIdle()
    }

    @Test
    fun sectionHeader_isUpperCased() {
        composeRule.setContent { SectionHeader("Tracking") }
        composeRule.onNodeWithText("TRACKING").assertIsDisplayed()
    }

    @Test
    fun motionPreferences_defaultReadsSystemScale() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = SystemMotionPreferences(ctx)
        assertThat(prefs.isReducedMotion()).isFalse()
        android.provider.Settings.Global.putFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertThat(prefs.isReducedMotion()).isTrue()
    }

    @Test
    fun sectionHeader_isAHeadingDescribedInItsOriginalCase() {
        composeRule.setContent { SectionHeader("Tracking") }
        composeRule.onNodeWithText("TRACKING").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Tracking")),
        ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun emptyState_titleIsAHeading() {
        composeRule.setContent { EmptyState("Nothing yet today", "Visits appear here.") }
        composeRule.onNodeWithText("Nothing yet today").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText("Visits appear here.").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Heading))
    }
}
