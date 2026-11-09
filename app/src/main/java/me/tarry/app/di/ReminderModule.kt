package me.tarry.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.tarry.app.reminder.AndroidReviewReminderScheduler
import me.tarry.app.reminder.InputsScheduledDays
import me.tarry.app.reminder.ReviewItemCounter
import me.tarry.app.reminder.ReviewReminderScheduler
import me.tarry.app.reminder.ScheduledDays
import me.tarry.app.reminder.TimelineReviewItemCounter

@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderModule {
    @Binds
    abstract fun bindScheduler(impl: AndroidReviewReminderScheduler): ReviewReminderScheduler

    @Binds
    abstract fun bindCounter(impl: TimelineReviewItemCounter): ReviewItemCounter

    @Binds
    abstract fun bindScheduledDays(impl: InputsScheduledDays): ScheduledDays
}
