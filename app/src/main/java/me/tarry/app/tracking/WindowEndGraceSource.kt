package me.tarry.app.tracking

/** The longest the session may stay open past the schedule end, in minutes (user setting); 0 = stop on time. */
fun interface WindowEndGraceSource {
    suspend fun graceMaxMin(): Int
}
