package me.tarry.app.reminder

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.FakeClock
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.repository.JobBlockRepository
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.TimelineRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBroadcastPendingResult
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import me.tarry.core.data.db.entity.Job as JobEntity

private class FakeCounter(var count: Int) : ReviewItemCounter {
    val asked = mutableListOf<LocalDate>()

    override suspend fun count(date: LocalDate, zone: ZoneId): Int {
        asked += date
        return count
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReviewReminderNotifierTest {
    @get:org.junit.Rule
    val folder = org.junit.rules.TemporaryFolder()

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sydney = ZoneId.of("Australia/Sydney")
    private val today = LocalDate.of(2026, 11, 9)
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob() + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> })
    private lateinit var context: Context
    private lateinit var notifications: NotificationManager
    private lateinit var db: TarryDatabase
    private lateinit var status: me.tarry.app.tracking.TrackingStatusStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notifications = context.getSystemService(NotificationManager::class.java)
        db = Room.inMemoryDatabaseBuilder(context, TarryDatabase::class.java).allowMainThreadQueries().build()
        status = me.tarry.app.tracking.TrackingStatusStore(
            me.tarry.app.tracking.createTrackingStatusDataStore(ioScope) { folder.root.resolve("status.preferences_pb") },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        ioScope.cancel()
        db.close()
    }

    private fun posted() = shadowOf(notifications).allNotifications

    @Test
    fun nothingToReviewPostsNothing() = runBlocking<Unit> {
        ReviewReminderNotifier(context, FakeCounter(0)).postIfNeeded(today, sydney)

        assertThat(posted()).isEmpty()
    }

    @Test
    fun oneItemUsesTheSingularText() = runBlocking<Unit> {
        ReviewReminderNotifier(context, FakeCounter(1)).postIfNeeded(today, sydney)

        val n = posted().single()
        assertThat(n.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("Review your day")
        assertThat(n.extras.getString(Notification.EXTRA_TEXT)).isEqualTo("1 item needs review")
        assertThat(n.channelId).isEqualTo(ReviewReminderNotifier.CHANNEL_ID)
        assertThat(n.flags and Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
    }

    @Test
    fun severalItemsUseThePluralTextOnItsOwnDefaultImportanceChannel() = runBlocking<Unit> {
        ReviewReminderNotifier(context, FakeCounter(4)).postIfNeeded(today, sydney)

        assertThat(posted().single().extras.getString(Notification.EXTRA_TEXT)).isEqualTo("4 items need review")
        val channel = notifications.getNotificationChannel(ReviewReminderNotifier.CHANNEL_ID)
        assertThat(channel.name.toString()).isEqualTo("Review reminders")
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
    }

    @Test
    fun tappingOpensTheAppWithTheReviewExtra() = runBlocking<Unit> {
        ReviewReminderNotifier(context, FakeCounter(2)).postIfNeeded(today, sydney)

        val intent = shadowOf(posted().single().contentIntent).savedIntent
        assertThat(intent.component?.className).isEqualTo("me.tarry.app.MainActivity")
        assertThat(intent.getBooleanExtra(ReviewReminderIntents.EXTRA_OPEN_REVIEW, false)).isTrue()
    }

    private val settings by lazy {
        me.tarry.core.data.settings.SettingsRepository(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = ioScope) { folder.root.resolve("s.preferences_pb") },
        )
    }
    private var scheduledToday = true
    private val scheduledDays = object : ScheduledDays {
        override suspend fun among(first: LocalDate, lookAheadDays: Int, zone: ZoneId): Set<LocalDate> =
            if (scheduledToday) setOf(first) else emptySet()
    }
    private val scheduler = FakeReviewReminderScheduler()
    private val firedAt = ZonedDateTime.of(2026, 11, 9, 17, 31, 0, 0, sydney).toInstant().toEpochMilli()

    private fun receiver(counter: ReviewItemCounter, enabled: Boolean = true): ReviewReminderReceiver {
        runBlocking { settings.setReviewReminderEnabled(enabled) }
        return ReviewReminderReceiver().also {
            it.scheduler = scheduler
            it.notifier = ReviewReminderNotifier(context, counter)
            it.statusStore = status
            it.settings = settings
            it.scheduledDays = scheduledDays
            it.clock = FakeClock(now = firedAt, zone = sydney)
            it.scope = scope
        }
    }

    @Test
    fun theReceiverRearmsFirstAndPostsOnlyWhenTheDayStillHasItems() {
        val counter = FakeCounter(0)
        val receiver = receiver(counter)

        deliver(receiver)
        assertThat(scheduler.rearms).isEqualTo(1)
        assertThat(posted()).isEmpty()
        assertThat(counter.asked).containsExactly(today)
        assertThat(runBlocking { status.current().lastReviewReminderDate }).isEqualTo(today)

        counter.count = 3
        deliver(receiver)
        assertThat(scheduler.rearms).isEqualTo(2)
        assertThat(posted().single().extras.getString(Notification.EXTRA_TEXT)).isEqualTo("3 items need review")
    }

    @Test
    fun theReceiverPostsNothingWhenTheReminderWasTurnedOffMeanwhileButStillRearms() {
        val counter = FakeCounter(5)

        deliver(receiver(counter, enabled = false))

        assertThat(posted()).isEmpty()
        assertThat(counter.asked).isEmpty()
        assertThat(scheduler.rearms).isEqualTo(1)
    }

    @Test
    fun theReceiverPostsNothingWhenTodayIsNoLongerAScheduledDay() {
        scheduledToday = false
        val counter = FakeCounter(5)

        deliver(receiver(counter))

        assertThat(posted()).isEmpty()
        assertThat(counter.asked).isEmpty()
        assertThat(scheduler.rearms).isEqualTo(1)
    }

    @Test
    fun theRecheckActionOnlyRecomputes_itNeverPostsOrMarksTheDay() {
        val counter = FakeCounter(5)

        deliver(receiver(counter), Intent(AndroidReviewReminderScheduler.ACTION_RECHECK))

        assertThat(scheduler.rearms).isEqualTo(1)
        assertThat(posted()).isEmpty()
        assertThat(counter.asked).isEmpty()
        assertThat(runBlocking { status.current().lastReviewReminderDate }).isNull()
    }

    @Test
    fun theReceiverStillRearmsWhenReadingTheDayFails() {
        val failing = ReviewItemCounter { _, _ -> error("db closed") }

        runCatching { deliver(receiver(failing)) }

        assertThat(scheduler.rearms).isEqualTo(1)
    }

    @Test
    fun theReviewChannelTurnedOffCountsAsNotificationsOff() {
        assertThat(ReviewReminderNotifier.isBlocked(context)).isFalse()

        ReviewReminderNotifier.ensureChannel(context)
        assertThat(notifications.getNotificationChannel(ReviewReminderNotifier.CHANNEL_ID)).isNotNull()
        assertThat(ReviewReminderNotifier.isBlocked(context)).isFalse()

        notifications.createNotificationChannel(
            android.app.NotificationChannel(ReviewReminderNotifier.CHANNEL_ID, "Review reminders", NotificationManager.IMPORTANCE_NONE),
        )
        shadowOf(notifications).setNotificationsEnabled(true)
        assertThat(ReviewReminderNotifier.isBlocked(context)).isTrue()
    }

    @Test
    fun notificationsOffForTheAppCountAsBlocked() {
        shadowOf(notifications).setNotificationsEnabled(false)

        assertThat(ReviewReminderNotifier.isBlocked(context)).isTrue()
    }

    @Test
    fun afterARestoreOrDeleteAllTheReminderRearmsEvenIfTheUpgradeCheckThrows() = runBlocking<Unit> {
        var upgraded = 0
        me.tarry.app.export.backup.afterRestoreSteps({ upgraded++ }, scheduler)
        assertThat(upgraded).isEqualTo(1)
        assertThat(scheduler.rearms).isEqualTo(1)

        runCatching { me.tarry.app.export.backup.afterRestoreSteps({ error("rewind failed") }, scheduler) }
        assertThat(scheduler.rearms).isEqualTo(2)
    }
    @Test
    fun theCounterAgreesWithTheTimelinePredicate() = runBlocking<Unit> {
        val zone = sydney
        fun at(h: Int) = ZonedDateTime.of(2026, 11, 9, 0, 0, 0, 0, zone).plusHours(h.toLong()).toInstant().toEpochMilli()
        fun visit(id: String, from: Int, to: Int, place: String? = null) =
            Visit(id, place, at(from), at(to), zone.id, origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = 1L)
        val timeline = TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao())
        val places = PlaceRepository(db.placeDao())
        val blocks = JobBlockRepository(db.jobBlockDao())
        places.save(Place("ignored", 0.0, 0.0, ignored = true, updatedAt = 1L))
        db.jobDao().upsert(JobEntity("job", "Job", 0, updatedAt = 1L))
        timeline.saveVisit(visit("bare", 8, 9))
        timeline.saveVisit(visit("covered", 10, 11))
        timeline.saveVisit(visit("at-ignored", 12, 13, place = "ignored"))
        timeline.saveVisit(visit("tomorrow", 24 + 8, 24 + 9))
        blocks.save(JobBlock("b", "job", at(10), at(11), zone.id, billable = true, updatedAt = 1L), now = 1L)

        val count = TimelineReviewItemCounter(timeline, places, blocks).count(today, zone)

        assertThat(count).isEqualTo(1)
    }

    /** Delivers to [receiver] as the system would (with a pending result for goAsync) and waits for the async work. */
    private fun deliver(receiver: BroadcastReceiver, intent: Intent = Intent()) {
        // Hilt_* base classes inject on first receive; mark them injected so the hand-set fakes stay in place.
        generateSequence<Class<*>>(receiver.javaClass) { it.superclass }
            .mapNotNull { c -> runCatching { c.getDeclaredField("injected") }.getOrNull() }
            .forEach { it.isAccessible = true; it.setBoolean(receiver, true) }
        val setPendingResult = BroadcastReceiver::class.java.getDeclaredMethod(
            "setPendingResult", BroadcastReceiver.PendingResult::class.java,
        )
        setPendingResult.isAccessible = true
        val create = ShadowBroadcastPendingResult::class.java.getDeclaredMethod(
            "create", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType,
        )
        create.isAccessible = true
        setPendingResult.invoke(receiver, create.invoke(null, 0, null, null, false))
        receiver.onReceive(context, intent)
        runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.join() } }
    }
}
