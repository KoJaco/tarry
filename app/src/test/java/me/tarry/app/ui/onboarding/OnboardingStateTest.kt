package me.tarry.app.ui.onboarding

import com.google.common.truth.Truth.assertThat
import me.tarry.app.tracking.WindowBounds
import me.tarry.app.ui.permissions.PermissionSnapshot
import me.tarry.app.ui.permissions.PermissionStep
import me.tarry.app.ui.permissions.buildPermissionsUiState
import org.junit.Test
import java.time.Instant

class OnboardingStateTest {
    private val now = Instant.ofEpochMilli(1_000_000)

    @Test
    fun pagesAreWelcomeThenTheMissingPermissionsInOrderThenScheduleAndDone() {
        val pages = onboardingPages(listOf(PermissionStep.LOCATION, PermissionStep.NOTIFICATIONS))
        assertThat(pages).containsExactly(
            OnboardingPage.Welcome,
            OnboardingPage.Permission(PermissionStep.LOCATION),
            OnboardingPage.Permission(PermissionStep.NOTIFICATIONS),
            OnboardingPage.Schedule,
            OnboardingPage.Done,
        ).inOrder()
    }

    @Test
    fun aPermissionPageIsSatisfiedOnlyOnceItsStepIsGranted() {
        val state = OnboardingUiState(onboardingPages(listOf(PermissionStep.LOCATION)), index = 1)
        assertThat(state.permissionPageSatisfied(buildPermissionsUiState(PermissionSnapshot(), emptySet()))).isFalse()
        assertThat(state.permissionPageSatisfied(buildPermissionsUiState(PermissionSnapshot(fineLocation = true), emptySet()))).isTrue()
        // Not a permission page.
        assertThat(state.copy(index = 0).permissionPageSatisfied(buildPermissionsUiState(PermissionSnapshot(fineLocation = true), emptySet()))).isFalse()
    }

    @Test
    fun backIsOnlyOfferedAfterTheFirstPageAndNeverOnDone() {
        val pages = onboardingPages(emptyList())
        assertThat(OnboardingUiState(pages, index = 0).canGoBack).isFalse()
        assertThat(OnboardingUiState(pages, index = 1).canGoBack).isTrue()
        assertThat(OnboardingUiState(pages, index = 2).canGoBack).isFalse()
    }

    @Test
    fun aWindowStartingNowMeansTrackingNow() {
        val outcome = doneOutcome(listOf(WindowBounds(now, now.plusSeconds(600))), now, "UTC")
        assertThat(outcome).isEqualTo(DoneOutcome.TrackingNow)
    }

    @Test
    fun aLaterWindowMeansNextWithItsStart() {
        val start = now.plusSeconds(3_600)
        val outcome = doneOutcome(listOf(WindowBounds(start, start.plusSeconds(600))), now, "Australia/Sydney")
        assertThat(outcome).isEqualTo(DoneOutcome.Next(start.toEpochMilli(), "Australia/Sydney"))
    }

    @Test
    fun noWindowsMeansNoHours() {
        assertThat(doneOutcome(emptyList(), now, "UTC")).isEqualTo(DoneOutcome.NoHours)
    }

    @Test
    fun backSkipsGrantedPermissionPagesAndLandsOnWelcomeAtTheStart() {
        // Welcome, LOCATION, BACKGROUND_LOCATION, NOTIFICATIONS, Schedule, Done; LOCATION and NOTIFICATIONS are granted now.
        val steps = listOf(PermissionStep.LOCATION, PermissionStep.BACKGROUND_LOCATION, PermissionStep.NOTIFICATIONS)
        val permissions = buildPermissionsUiState(
            PermissionSnapshot(fineLocation = true, coarseLocation = true, notifications = true), emptySet(),
        )
        val state = OnboardingUiState(onboardingPages(steps), index = 4)
        // Schedule -> past granted NOTIFICATIONS -> BACKGROUND_LOCATION (not granted).
        assertThat(state.previousIndex(permissions)).isEqualTo(2)
        // BACKGROUND_LOCATION -> past granted LOCATION -> Welcome.
        assertThat(state.copy(index = 2).previousIndex(permissions)).isEqualTo(0)
        assertThat(state.copy(index = 1).previousIndex(permissions)).isEqualTo(0)
    }
}
