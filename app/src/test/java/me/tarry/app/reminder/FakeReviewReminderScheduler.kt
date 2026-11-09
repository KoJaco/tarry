package me.tarry.app.reminder

class FakeReviewReminderScheduler : ReviewReminderScheduler {
    @Volatile var rearms = 0

    override suspend fun rearm() {
        rearms++
    }
}
