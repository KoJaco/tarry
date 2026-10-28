package me.tarry.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object TimelineRoute : NavKey

@Serializable
data object SettingsRoute : NavKey

/** Step-by-step permission setup (phase 2a T9). Pushed on top of [TimelineRoute]. */
@Serializable
data object PermissionsRoute : NavKey

/** Debug sessions list and raw export (phase 2a T10). Pushed on top of [SettingsRoute]. */
@Serializable
data object SessionsRoute : NavKey

/**
 * Bottom-bar navigation between top-level routes. Timeline is the root: selecting it
 * pops back to [TimelineRoute]; selecting another route leaves exactly [Timeline, route].
 */
fun MutableList<NavKey>.navigateTopLevel(route: NavKey) {
    if (lastOrNull() == route) return
    clear()
    add(TimelineRoute)
    if (route != TimelineRoute) add(route)
}

/** Jobs management (phase 4a). Pushed on top of [SettingsRoute]. */
@Serializable
data object JobsRoute : NavKey

/** Tags and tag groups management (phase 4a). Pushed on top of [SettingsRoute]. */
@Serializable
data object TagsRoute : NavKey

/** Weekly schedule grid (phase 6). Pushed on top of [SettingsRoute]. */
@Serializable
data object ScheduleRoute : NavKey

/** Places list (phase 4b). Pushed on top of [SettingsRoute]. */
@Serializable
data object PlacesRoute : NavKey

/** One place's detail (phase 4b). Pushed on top of [PlacesRoute]. */
@Serializable
data class PlaceDetailRoute(val id: String) : NavKey
