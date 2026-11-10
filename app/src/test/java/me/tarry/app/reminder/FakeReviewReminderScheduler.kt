package me.tarry.app.reminder

class FakeReviewReminderScheduler : ReviewReminderScheduler {
    @Volatile var rearms = 0

    /** When set, [rearm] counts the call and then throws it. */
    @Volatile var failure: Exception? = null

    override suspend fun rearm() {
        rearms++
        failure?.let { throw it }
    }
}
