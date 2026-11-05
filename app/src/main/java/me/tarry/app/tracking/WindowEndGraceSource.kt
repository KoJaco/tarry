package me.tarry.app.tracking

/** The longest the session may stay open past the schedule end, in minutes (user setting); 0 = stop on time. */
fun interface WindowEndGraceSource {
    suspend fun graceMaxMin(): Int
}

/** Asks the tracking controller to reconcile now (a user action such as a changed setting, never a timer). */
fun interface ReconcileTrigger {
    suspend fun reconcileNow()
}
