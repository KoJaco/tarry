package me.tarry.app.tracking

/** The stay geofence radius to use for the next STATIONARY entry (user setting). */
fun interface StayRadiusSource {
    suspend fun stayRadiusM(): Float
}
