package me.tarry.app.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.permissions.PermissionStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The onboarding auto-advance: a grant while on the page moves on; arriving on a granted page does not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AdvanceWhenGrantedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var page by mutableStateOf<OnboardingPage>(OnboardingPage.Permission(PermissionStep.LOCATION))
    private var satisfied by mutableStateOf(false)
    private var advances = 0

    private fun show() = composeRule.setContent { AdvanceWhenGranted(page, satisfied) { advances++ } }

    @Test
    fun aGrantWhileOnThePageAdvancesOnce() {
        show()
        composeRule.waitForIdle()
        assertThat(advances).isEqualTo(0)

        satisfied = true
        composeRule.waitForIdle()
        assertThat(advances).isEqualTo(1)

        // A later recomposition with the same state does not advance again.
        satisfied = true
        composeRule.waitForIdle()
        assertThat(advances).isEqualTo(1)
    }

    @Test
    fun arrivingOnAnAlreadyGrantedPageStaysPut() {
        satisfied = true
        show()
        composeRule.waitForIdle()
        assertThat(advances).isEqualTo(0)

        // Back lands on another page that is already granted: still no move.
        page = OnboardingPage.Permission(PermissionStep.NOTIFICATIONS)
        composeRule.waitForIdle()
        assertThat(advances).isEqualTo(0)
    }

    @Test
    fun aRevokeThenGrantOnThePageAdvances() {
        satisfied = true
        show()
        composeRule.waitForIdle()
        satisfied = false
        composeRule.waitForIdle()
        satisfied = true
        composeRule.waitForIdle()
        assertThat(advances).isEqualTo(1)
    }
}
