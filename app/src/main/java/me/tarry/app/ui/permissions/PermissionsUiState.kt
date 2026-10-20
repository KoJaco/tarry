package me.tarry.app.ui.permissions

/**
 * The permission steps, in the order they are offered. Only [LOCATION] is mandatory; every other step can be
 * skipped, and a skipped step shows as such on the screen (and, for the two required ones, as an ATTENTION cause).
 *
 * @property requiredForScheduled needed for scheduled capture to work at all (ADR 0003).
 */
enum class PermissionStep(val skippable: Boolean, val requiredForScheduled: Boolean) {
    /** Fine + coarse location. */
    LOCATION(skippable = false, requiredForScheduled = true),

    /** "Allow all the time"; asked after a prominent disclosure. */
    BACKGROUND_LOCATION(skippable = true, requiredForScheduled = true),
    ACTIVITY_RECOGNITION(skippable = true, requiredForScheduled = false),

    /** API 33+ only. */
    NOTIFICATIONS(skippable = true, requiredForScheduled = false),
    EXACT_ALARMS(skippable = true, requiredForScheduled = true),
    BATTERY_OPTIMISATION(skippable = true, requiredForScheduled = false),
}

/** What the platform says is granted right now. [notifications] is true below API 33 (nothing to ask). */
data class PermissionSnapshot(
    val fineLocation: Boolean = false,
    val coarseLocation: Boolean = false,
    val backgroundLocation: Boolean = false,
    val activityRecognition: Boolean = false,
    val notifications: Boolean = false,
    val exactAlarms: Boolean = false,
    val batteryExempt: Boolean = false,
    val notificationsApplicable: Boolean = true,
) {
    fun isGranted(step: PermissionStep): Boolean = when (step) {
        // Fine location is the one that matters; coarse is requested together with it.
        PermissionStep.LOCATION -> fineLocation
        PermissionStep.BACKGROUND_LOCATION -> backgroundLocation
        PermissionStep.ACTIVITY_RECOGNITION -> activityRecognition
        PermissionStep.NOTIFICATIONS -> notifications
        PermissionStep.EXACT_ALARMS -> exactAlarms
        PermissionStep.BATTERY_OPTIMISATION -> batteryExempt
    }
}

enum class StepStatus {
    GRANTED,

    /** The step the user should do next. */
    CURRENT,

    /** Skipped by the user and still not granted; can still be granted later. */
    SKIPPED,

    /** An earlier step is not resolved yet. */
    LOCKED,
}

data class PermissionItem(val step: PermissionStep, val status: StepStatus) {
    /** The grant button is shown for the current step and for skipped ones. */
    val actionable: Boolean get() = status == StepStatus.CURRENT || status == StepStatus.SKIPPED

    val canSkip: Boolean get() = status == StepStatus.CURRENT && step.skippable
}

/**
 * Everything the permission screen renders. Pure data so a Robolectric Compose test can render every status
 * without Hilt or the platform.
 *
 * @property showBackgroundDisclosure the prominent disclosure dialog for [PermissionStep.BACKGROUND_LOCATION].
 */
data class PermissionsUiState(
    val items: List<PermissionItem>,
    val showBackgroundDisclosure: Boolean = false,
) {
    /** Fine location is granted, so tracking can at least be attempted. */
    val canTrack: Boolean get() = items.firstOrNull { it.step == PermissionStep.LOCATION }?.status == StepStatus.GRANTED

    /** Required steps (ADR 0003) that are not granted. Used for the "scheduled tracking won't start" notice. */
    val missingRequired: List<PermissionStep>
        get() = items.filter { it.step.requiredForScheduled && it.status != StepStatus.GRANTED }.map { it.step }
}

/** Maps the platform [snapshot] and the user's [skipped] steps to the screen state. Steps are strictly ordered. */
fun buildPermissionsUiState(
    snapshot: PermissionSnapshot,
    skipped: Set<PermissionStep>,
    showBackgroundDisclosure: Boolean = false,
): PermissionsUiState {
    val steps = PermissionStep.entries.filter { it != PermissionStep.NOTIFICATIONS || snapshot.notificationsApplicable }
    var currentTaken = false
    val items = steps.map { step ->
        val status = when {
            snapshot.isGranted(step) -> StepStatus.GRANTED
            step in skipped && step.skippable -> StepStatus.SKIPPED
            !currentTaken -> StepStatus.CURRENT.also { currentTaken = true }
            else -> StepStatus.LOCKED
        }
        PermissionItem(step, status)
    }
    return PermissionsUiState(items, showBackgroundDisclosure)
}
