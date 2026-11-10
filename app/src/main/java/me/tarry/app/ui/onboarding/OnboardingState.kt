package me.tarry.app.ui.onboarding

import me.tarry.app.tracking.WindowBounds
import me.tarry.app.ui.permissions.PermissionStep
import me.tarry.app.ui.permissions.PermissionsUiState
import me.tarry.app.ui.permissions.StepStatus
import me.tarry.core.data.model.Schedule
import java.time.Instant

/** One screen of first-run onboarding. */
sealed interface OnboardingPage {
    data object Welcome : OnboardingPage

    /** One permission that was not granted when onboarding began. */
    data class Permission(val step: PermissionStep) : OnboardingPage

    data object Schedule : OnboardingPage

    data object Done : OnboardingPage
}

/** What the Done page says: tracking has started, when it will next start, or that no hours are set. */
sealed interface DoneOutcome {
    data object TrackingNow : DoneOutcome

    data class Next(val startMs: Long, val zoneId: String) : DoneOutcome

    data object NoHours : DoneOutcome
}

/**
 * @property pages fixed when onboarding opens: welcome, the permissions still missing then (in [PermissionStep] order),
 *   the schedule, done.
 * @property schedule the grid being edited; starts as [Schedule.MON_FRI_7_TO_17] and is always saved, edited or not.
 * @property outcome set once the schedule is saved and the Done page can say what happens next.
 */
data class OnboardingUiState(
    val pages: List<OnboardingPage>,
    val index: Int = 0,
    val schedule: Schedule = Schedule.MON_FRI_7_TO_17,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val outcome: DoneOutcome? = null,
) {
    val page: OnboardingPage get() = pages[index]
    val canGoBack: Boolean get() = index > 0 && page != OnboardingPage.Done && !saving
}

fun onboardingPages(missing: List<PermissionStep>): List<OnboardingPage> =
    listOf(OnboardingPage.Welcome) + missing.map { OnboardingPage.Permission(it) } + OnboardingPage.Schedule + OnboardingPage.Done

/**
 * Where Back lands: the nearest earlier page that is not a permission already granted (those pages would only bounce the
 * user forward again), or the first page.
 */
fun OnboardingUiState.previousIndex(permissions: PermissionsUiState): Int {
    val granted = permissions.items.filter { it.status == StepStatus.GRANTED }.map { it.step }.toSet()
    var i = index - 1
    while (i > 0) {
        val p = pages[i]
        if (p !is OnboardingPage.Permission || p.step !in granted) break
        i--
    }
    return i.coerceAtLeast(0)
}

/**
 * Where Next lands: the nearest later page that is not a permission already granted (arriving on one would be a dead end:
 * its page only moves on when the grant changes), or the last page.
 */
fun OnboardingUiState.nextIndex(permissions: PermissionsUiState): Int {
    val granted = permissions.items.filter { it.status == StepStatus.GRANTED }.map { it.step }.toSet()
    var i = index + 1
    while (i < pages.lastIndex) {
        val p = pages[i]
        if (p !is OnboardingPage.Permission || p.step !in granted) break
        i++
    }
    return i.coerceAtMost(pages.lastIndex)
}

/** The index of the schedule page, where "Not now" on the location page jumps to. */
val OnboardingUiState.scheduleIndex: Int get() = pages.indexOf(OnboardingPage.Schedule)

/** True when the current page asks for a permission that has since been granted, so the flow moves on by itself. */
fun OnboardingUiState.permissionPageSatisfied(permissions: PermissionsUiState): Boolean {
    val current = page as? OnboardingPage.Permission ?: return false
    return permissions.items.any { it.step == current.step && it.status == StepStatus.GRANTED }
}

/** [intervals] are the active stretches from [now] on (clipped to it): the first says whether tracking starts now or later. */
fun doneOutcome(intervals: List<WindowBounds>, now: Instant, zoneId: String): DoneOutcome {
    val first = intervals.firstOrNull() ?: return DoneOutcome.NoHours
    return if (first.start <= now) DoneOutcome.TrackingNow else DoneOutcome.Next(first.start.toEpochMilli(), zoneId)
}
