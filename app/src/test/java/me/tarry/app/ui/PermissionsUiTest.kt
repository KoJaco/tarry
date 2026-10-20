package me.tarry.app.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.permissions.PermissionStep
import me.tarry.app.ui.permissions.PermissionSnapshot
import me.tarry.app.ui.permissions.PermissionsActions
import me.tarry.app.ui.permissions.PermissionsContent
import me.tarry.app.ui.permissions.StepStatus
import me.tarry.app.ui.permissions.buildPermissionsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PermissionsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun statuses(
        snapshot: PermissionSnapshot,
        skipped: Set<PermissionStep> = emptySet(),
    ) = buildPermissionsUiState(snapshot, skipped).items.associate { it.step to it.status }

    @Test
    fun nothingGranted_onlyLocationIsCurrentAndRestIsLocked() {
        val s = statuses(PermissionSnapshot())
        assertThat(s[PermissionStep.LOCATION]).isEqualTo(StepStatus.CURRENT)
        assertThat(s.filterKeys { it != PermissionStep.LOCATION }.values.toSet()).containsExactly(StepStatus.LOCKED)
    }

    @Test
    fun stepsAdvanceInOrder() {
        val s = statuses(PermissionSnapshot(fineLocation = true, coarseLocation = true))
        assertThat(s[PermissionStep.LOCATION]).isEqualTo(StepStatus.GRANTED)
        assertThat(s[PermissionStep.BACKGROUND_LOCATION]).isEqualTo(StepStatus.CURRENT)
        assertThat(s[PermissionStep.ACTIVITY_RECOGNITION]).isEqualTo(StepStatus.LOCKED)
    }

    @Test
    fun skippedStepUnlocksNextAndStaysActionable() {
        val snapshot = PermissionSnapshot(fineLocation = true)
        val ui = buildPermissionsUiState(snapshot, setOf(PermissionStep.BACKGROUND_LOCATION))
        val bg = ui.items.first { it.step == PermissionStep.BACKGROUND_LOCATION }
        assertThat(bg.status).isEqualTo(StepStatus.SKIPPED)
        assertThat(bg.actionable).isTrue()
        assertThat(ui.items.first { it.step == PermissionStep.ACTIVITY_RECOGNITION }.status).isEqualTo(StepStatus.CURRENT)
        assertThat(ui.missingRequired).containsExactly(PermissionStep.BACKGROUND_LOCATION, PermissionStep.EXACT_ALARMS)
    }

    @Test
    fun fineLocationCannotBeSkipped() {
        val ui = buildPermissionsUiState(PermissionSnapshot(), setOf(PermissionStep.LOCATION))
        val item = ui.items.first { it.step == PermissionStep.LOCATION }
        assertThat(item.status).isEqualTo(StepStatus.CURRENT)
        assertThat(item.canSkip).isFalse()
    }

    @Test
    fun notificationsStepIsHiddenBelowApi33() {
        val ui = buildPermissionsUiState(PermissionSnapshot(notificationsApplicable = false), emptySet())
        assertThat(ui.items.map { it.step }).doesNotContain(PermissionStep.NOTIFICATIONS)
    }

    private val noop = PermissionsActions({}, {}, {}, {}, {})

    @Test
    fun rendersEveryStatus_withActionsOnlyWhereActionable() {
        val snapshot = PermissionSnapshot(fineLocation = true, activityRecognition = false)
        val ui = buildPermissionsUiState(snapshot, setOf(PermissionStep.BACKGROUND_LOCATION))
        composeRule.setContent { PermissionsContent(ui, noop) }

        composeRule.onNodeWithTag("permission_status_LOCATION").assertExists()
        composeRule.onNodeWithTag("permission_grant_LOCATION").assertDoesNotExist()
        composeRule.onNodeWithTag("permission_grant_BACKGROUND_LOCATION").assertExists()
        composeRule.onNodeWithTag("permission_skip_BACKGROUND_LOCATION").assertDoesNotExist()
        composeRule.onNodeWithTag("permission_grant_ACTIVITY_RECOGNITION").assertExists()
        composeRule.onNodeWithTag("permission_skip_ACTIVITY_RECOGNITION").assertExists()
        composeRule.onNodeWithTag("permission_grant_EXACT_ALARMS").assertDoesNotExist()
        composeRule.onNodeWithText("Without it, scheduled tracking cannot start by itself.").assertExists()
        composeRule.onNodeWithTag("permissions_missing_required").assertExists()
    }

    @Test
    fun grantAndSkipButtons_haveDescriptionsAndTouchTargets() {
        val ui = buildPermissionsUiState(PermissionSnapshot(fineLocation = true, backgroundLocation = true), emptySet())
        composeRule.setContent { PermissionsContent(ui, noop) }

        composeRule.onNode(hasContentDescription("Allow Physical activity")).assertHeightIsAtLeast(48.dp)
        composeRule.onNode(hasContentDescription("Skip Physical activity")).assertHeightIsAtLeast(48.dp)
        composeRule.onNode(hasContentDescription("Precise location: Granted")).assertExists()
    }

    @Test
    fun tappingGrantAndSkipInvokesActions() {
        val granted = mutableListOf<PermissionStep>()
        val skipped = mutableListOf<PermissionStep>()
        val ui = buildPermissionsUiState(PermissionSnapshot(fineLocation = true), emptySet())
        composeRule.setContent {
            PermissionsContent(ui, noop.copy(onGrant = { granted += it }, onSkip = { skipped += it }))
        }
        composeRule.onNodeWithTag("permission_grant_BACKGROUND_LOCATION").performClick()
        composeRule.onNodeWithTag("permission_skip_BACKGROUND_LOCATION").performClick()

        assertThat(granted).containsExactly(PermissionStep.BACKGROUND_LOCATION)
        assertThat(skipped).containsExactly(PermissionStep.BACKGROUND_LOCATION)
    }

    @Test
    fun backgroundDisclosure_statesWhatWhyAndThatItStaysOnDevice() {
        val ui = buildPermissionsUiState(PermissionSnapshot(fineLocation = true), emptySet(), showBackgroundDisclosure = true)
        var confirmed = false
        composeRule.setContent {
            PermissionsContent(ui, noop.copy(onConfirmBackgroundDisclosure = { confirmed = true }))
        }
        composeRule.onNodeWithText("Continue").assertExists().performClick()
        composeRule.onNode(androidx.compose.ui.test.hasText("stored only on this phone", substring = true)).assertExists()
        assertThat(confirmed).isTrue()
    }
}
