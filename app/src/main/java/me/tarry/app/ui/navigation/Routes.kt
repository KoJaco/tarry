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
