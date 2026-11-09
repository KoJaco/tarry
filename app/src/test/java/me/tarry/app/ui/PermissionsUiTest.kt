package me.tarry.app.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    private fun text(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().config[SemanticsProperties.Text].single().text

    private fun assertStatusIconSize(scale: Float, size: androidx.compose.ui.unit.Dp) {
        val ui = buildPermissionsUiState(PermissionSnapshot(fineLocation = true), emptySet())
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = scale)) {
                PermissionsContent(ui, noop)
            }
        }
        composeRule.onNodeWithTag("permission_status_icon_LOCATION", useUnmergedTree = true)
            .assertWidthIsEqualTo(size).assertHeightIsEqualTo(size)
    }

    @Test
    fun statusIconKeepsItsSizeAtDefaultFont() = assertStatusIconSize(1f, 18.dp)

    @Test
    fun statusIconGrowsAtFontScaleTwo() = assertStatusIconSize(2f, 30.dp) // 18 sp on the platform's non-linear 2.0 curve

    @Test
    fun everyCardIsOneButton_exceptALockedStep() {
        val snapshot = PermissionSnapshot(fineLocation = true)
        val ui = buildPermissionsUiState(snapshot, setOf(PermissionStep.BACKGROUND_LOCATION))
        composeRule.setContent { PermissionsContent(ui, noop) }

        // Granted, skipped and current steps are buttons; the locked ones are not.
        composeRule.onNodeWithTag("permission_row_LOCATION").assertHasClickAction()
        composeRule.onNodeWithTag("permission_row_BACKGROUND_LOCATION").assertHasClickAction()
        composeRule.onNodeWithTag("permission_row_ACTIVITY_RECOGNITION").assertHasClickAction()
        composeRule.onNodeWithTag("permission_row_EXACT_ALARMS").assertHasNoClickAction()
    }

    @Test
    fun noSeparateGrantOrChangeButtonsAreLeft() {
        val ui = buildPermissionsUiState(PermissionSnapshot(fineLocation = true), emptySet())
        composeRule.setContent { PermissionsContent(ui, noop) }
        listOf("permission_grant_", "permission_change_").forEach { prefix ->
            PermissionStep.entries.forEach { composeRule.onNodeWithTag(prefix + it.name).assertDoesNotExist() }
        }
        composeRule.onNodeWithText("Allow").assertDoesNotExist()
    }

    @Test
    fun onlyTheCurrentSkippableStepHasASkipButton_outsideItsCard() {
        val skipped = mutableListOf<PermissionStep>()
        val ui = buildPermissionsUiState(PermissionSnapshot(fineLocation = true), emptySet())
        composeRule.setContent { PermissionsContent(ui, noop.copy(onSkip = { skipped += it })) }

        val skip = composeRule.onNodeWithTag("permission_skip_BACKGROUND_LOCATION").assertHeightIsAtLeast(48.dp)
        assertThat(skip.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())
            .isEqualTo("Skip Location all the time")
        composeRule.onNode(hasTestTag("permission_skip_BACKGROUND_LOCATION") and hasAnyAncestor(hasTestTag("permission_row_BACKGROUND_LOCATION")))
            .assertDoesNotExist()
        PermissionStep.entries.filter { it != PermissionStep.BACKGROUND_LOCATION }
            .forEach { composeRule.onNodeWithTag("permission_skip_${it.name}").assertDoesNotExist() }

        skip.performScrollTo().performClick()
        assertThat(skipped).containsExactly(PermissionStep.BACKGROUND_LOCATION)
    }

    @Test
    fun theFirstStepCannotBeSkipped() {
        val ui = buildPermissionsUiState(PermissionSnapshot(), emptySet())
        composeRule.setContent { PermissionsContent(ui, noop) }
        composeRule.onNodeWithTag("permission_skip_LOCATION").assertDoesNotExist()
    }

    @Test
    fun cardStacksStatusThenTitleThenDescriptionThenWarning() {
        val snapshot = PermissionSnapshot(fineLocation = true, activityRecognition = false)
        val ui = buildPermissionsUiState(snapshot, setOf(PermissionStep.BACKGROUND_LOCATION))
        composeRule.setContent { PermissionsContent(ui, noop) }

        fun top(t: String) = composeRule.onNode(hasText(t) and hasAnyAncestor(hasTestTag("permission_row_BACKGROUND_LOCATION")), useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y
        val status = composeRule.onNodeWithTag("permission_status_BACKGROUND_LOCATION", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        val title = top("Location all the time")
        val required = top("Required for scheduled tracking")
        val degraded = top("Without it, scheduled tracking cannot start by itself.")
        assertThat(text("permission_status_BACKGROUND_LOCATION")).isEqualTo("Skipped")
        assertThat(status).isLessThan(title)
        assertThat(title).isLessThan(required)
        assertThat(required).isLessThan(degraded)
        composeRule.onNodeWithTag("permissions_missing_required").assertExists()
    }

    @Test
    fun requiredNoteIsAWarningOnlyWhileNotGranted() {
        val ui = buildPermissionsUiState(PermissionSnapshot(fineLocation = true), emptySet())
        composeRule.setContent { PermissionsContent(ui, noop) }
        composeRule.onNodeWithTag("permission_required_note_LOCATION", useUnmergedTree = true).assertTextEquals("Needed for scheduled tracking")
        composeRule.onNodeWithTag("permission_required_note_BACKGROUND_LOCATION", useUnmergedTree = true).assertTextEquals("Required for scheduled tracking")
    }

    @Test
    fun statusIsAnIconAndAWord() {
        val ui = buildPermissionsUiState(PermissionSnapshot(fineLocation = true), emptySet())
        composeRule.setContent { PermissionsContent(ui, noop) }
        assertThat(text("permission_status_LOCATION")).isEqualTo("Granted")
        assertThat(text("permission_status_BACKGROUND_LOCATION")).isEqualTo("Not granted")
    }

    @Test
    fun cardIsOneMergedButtonWithAGrantOrChangeLabel_andMeetsTheTouchTarget() {
        val ui = buildPermissionsUiState(PermissionSnapshot(fineLocation = true), emptySet())
        composeRule.setContent { PermissionsContent(ui, noop) }

        val granted = composeRule.onNodeWithTag("permission_row_LOCATION").assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().config
        assertThat(granted[SemanticsProperties.Role]).isEqualTo(Role.Button)
        assertThat(granted[SemanticsActions.OnClick].label).isEqualTo("Change")
        // The merged node carries the status, the title and the description together.
        val spoken = granted[SemanticsProperties.Text].joinToString(" ") { it.text }
        assertThat(spoken).contains("Granted")
        assertThat(spoken).contains("Precise location")

        val current = composeRule.onNodeWithTag("permission_row_BACKGROUND_LOCATION").fetchSemanticsNode().config
        assertThat(current[SemanticsProperties.Role]).isEqualTo(Role.Button)
        assertThat(current[SemanticsActions.OnClick].label).isEqualTo("Grant")
    }

    @Test
    fun tappingAGrantedCardOpensChange_andANotGrantedOneRequestsIt() {
        val granted = mutableListOf<PermissionStep>()
        val changed = mutableListOf<PermissionStep>()
        val ui = buildPermissionsUiState(PermissionSnapshot(fineLocation = true), emptySet())
        composeRule.setContent {
            PermissionsContent(ui, noop.copy(onGrant = { granted += it }, onChange = { changed += it }))
        }
        composeRule.onNodeWithTag("permission_row_LOCATION").performScrollTo().performClick()
        composeRule.onNodeWithTag("permission_row_BACKGROUND_LOCATION").performScrollTo().performClick()

        assertThat(changed).containsExactly(PermissionStep.LOCATION)
        assertThat(granted).containsExactly(PermissionStep.BACKGROUND_LOCATION)
    }

    @Test
    fun aSkippedStepCanStillBeGrantedByTappingItsCard() {
        val granted = mutableListOf<PermissionStep>()
        val ui = buildPermissionsUiState(PermissionSnapshot(fineLocation = true), setOf(PermissionStep.BACKGROUND_LOCATION))
        composeRule.setContent { PermissionsContent(ui, noop.copy(onGrant = { granted += it })) }
        composeRule.onNodeWithTag("permission_row_BACKGROUND_LOCATION").performScrollTo().performClick()
        assertThat(granted).containsExactly(PermissionStep.BACKGROUND_LOCATION)
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
