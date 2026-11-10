package me.tarry.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.settings.SettingsContent
import me.tarry.core.data.settings.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsReminderUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val toggles = mutableListOf<Boolean>()
    private val times = mutableListOf<Int>()
    private var notificationSettingsOpened = 0

    private fun show(
        enabled: Boolean,
        minuteOfDay: Int = 17 * 60 + 30,
        blocked: Boolean = false,
        fontScale: Float = 1f,
    ) = composeRule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
            SettingsContent(
                Settings(reviewReminderEnabled = enabled, reviewReminderMinuteOfDay = minuteOfDay),
                onReviewReminderChange = { toggles += it },
                onReviewReminderTimeSelected = { times += it },
                notificationsBlocked = blocked,
                onOpenNotificationSettings = { notificationSettingsOpened++ },
            )
        }
    }

    @Test
    fun theSwitchRowReflectsTheSettingAndTogglesIt() {
        show(enabled = false)

        composeRule.onNode(hasText("Review reminder") and androidx.compose.ui.test.isToggleable()).performScrollTo()
            .assertIsOff().assertHeightIsAtLeast(48.dp).performClick()

        assertThat(toggles).containsExactly(true)
    }

    @Test
    fun turningItOffReportsFalse() {
        show(enabled = true)

        composeRule.onNode(hasText("Review reminder") and androidx.compose.ui.test.isToggleable()).performScrollTo()
            .assertIsOn().performClick()

        assertThat(toggles).containsExactly(false)
    }

    @Test
    fun theSubtitleNamesTheTimeOnlyWhileOn() {
        show(enabled = true)
        composeRule.onNodeWithText("Scheduled days at 5:30 PM, if something needs review", substring = true, ignoreCase = true)
            .performScrollTo().assertExists()
    }

    @Test
    fun noSubtitleWhileOff() {
        show(enabled = false)
        composeRule.onNodeWithText("if something needs review", substring = true).assertDoesNotExist()
    }

    @Test
    fun theTimeRowIsDisabledButStillShowsItsValueWhileOff() {
        show(enabled = false)

        composeRule.onNode(hasText("Review reminder time")).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("5:30 PM", ignoreCase = true).assertExists()
        composeRule.onNode(hasText("Review reminder time")).performClick()
        composeRule.onNodeWithText("OK").assertDoesNotExist()
    }

    @Test
    fun theTimePickerReturnsTheMinuteOfDay() {
        show(enabled = true, minuteOfDay = 17 * 60 + 30)

        composeRule.onNode(hasText("Review reminder time")).performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithText("OK").performScrollTo().performClick()

        assertThat(times).containsExactly(17 * 60 + 30)
    }

    @Test
    fun theTimePickerStartsOnTheStoredTime() {
        show(enabled = true, minuteOfDay = 9 * 60 + 5)

        composeRule.onNode(hasText("Review reminder time")).performScrollTo().performClick()
        composeRule.onNodeWithText("OK").performScrollTo().performClick()

        assertThat(times).containsExactly(9 * 60 + 5)
    }

    @Test
    fun cancelLeavesTheTimeAlone() {
        show(enabled = true)

        composeRule.onNode(hasText("Review reminder time")).performScrollTo().performClick()
        composeRule.onNodeWithText("Cancel").performScrollTo().performClick()

        assertThat(times).isEmpty()
    }

    @Test
    fun deniedNotificationsShowTheHintAndItOpensTheSystemSettings() {
        show(enabled = true, blocked = true)

        composeRule.onNodeWithText("Notifications are off for Tarry. Turn them on to get reminders.")
            .performScrollTo().assertHeightIsAtLeast(48.dp).performClick()

        assertThat(notificationSettingsOpened).isEqualTo(1)
    }

    @Test
    fun noHintWhileTheReminderIsOffOrNotificationsAreOn() {
        show(enabled = false, blocked = true)
        composeRule.onNodeWithText("Notifications are off for Tarry. Turn them on to get reminders.").assertDoesNotExist()
    }

    @Test
    fun noHintWhenNotificationsAreOn() {
        show(enabled = true, blocked = false)
        composeRule.onNodeWithText("Notifications are off for Tarry. Turn them on to get reminders.").assertDoesNotExist()
    }

    @Test
    fun theRemindersGroupWorksAtFontScaleTwo() {
        show(enabled = true, blocked = true, fontScale = 2f)

        composeRule.onNode(hasText("Review reminder") and androidx.compose.ui.test.isToggleable()).performScrollTo().assertHeightIsAtLeast(48.dp)
        composeRule.onNode(hasText("Review reminder time")).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("OK").assertExists()
        composeRule.onNodeWithText("OK").performScrollTo().performClick()
        assertThat(times).containsExactly(17 * 60 + 30)
        composeRule.onNodeWithText("Notifications are off for Tarry. Turn them on to get reminders.").performScrollTo().assertExists()
    }
}
