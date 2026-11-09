package me.tarry.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import me.tarry.app.MainActivity
import me.tarry.app.R
import me.tarry.app.ui.timeline.Segment
import me.tarry.app.ui.timeline.TimelineItem
import me.tarry.app.ui.timeline.coverage
import me.tarry.app.ui.timeline.dayBounds
import me.tarry.app.ui.timeline.summarize
import me.tarry.core.data.repository.JobBlockRepository
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.TimelineRepository
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** How many visits on a day need review: the number the Timeline's Needs review filter and summary chip show. */
fun interface ReviewItemCounter {
    suspend fun count(date: LocalDate, zone: ZoneId): Int
}

/** Reads the day the way the Timeline does ([coverage] and [summarize]), so the reminder and the filter always agree. */
class TimelineReviewItemCounter @Inject constructor(
    private val timeline: TimelineRepository,
    private val places: PlaceRepository,
    private val blocks: JobBlockRepository,
) : ReviewItemCounter {
    override suspend fun count(date: LocalDate, zone: ZoneId): Int {
        val bounds = dayBounds(date, zone)
        val (from, to) = bounds
        val visits = timeline.visitsInRange(from, to)
        val trips = timeline.tripsInRange(from, to)
        val placesById = places.observeLabels().first().associateBy { it.id }
        val covered = coverage(
            visits.map { Segment(it.id, it.start, it.end) }, trips.map { Segment(it.id, it.start, it.end) },
            blocks.overlapping(from, to),
        )
        val items = visits.map {
            val ignored = it.placeId?.let(placesById::get)?.ignored ?: false
            TimelineItem.Visit(
                it.id, it.start, it.end, placeLabel = null, placeId = it.placeId, placeIgnored = ignored,
                needsReview = covered.needsReview(it.id, ignored),
            )
        }
        return summarize(items, bounds).needsReview
    }
}

/** Extras of the reminder notification's tap, handled by MainActivity. */
object ReviewReminderIntents {
    const val EXTRA_OPEN_REVIEW = "me.tarry.app.extra.OPEN_REVIEW"

    fun openReview(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CODE,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_REVIEW, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private const val REQUEST_CODE = 8
}

/** Posts "Review your day" when [date] still has something to review. Own channel, so it can be muted on its own. */
@Singleton
class ReviewReminderNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val counter: ReviewItemCounter,
) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    /** Returns the number of items the notification announced; 0 when nothing was posted for lack of items. */
    suspend fun postIfNeeded(date: LocalDate, zone: ZoneId): Int {
        val count = counter.count(date, zone)
        if (count <= 0 || !manager.areNotificationsEnabled()) return count
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.review_reminder_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val text = context.resources.getQuantityString(R.plurals.review_reminder_text, count, count)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setColor(ContextCompat.getColor(context, R.color.tarry_accent))
            .setContentTitle(context.getString(R.string.review_reminder_title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(ReviewReminderIntents.openReview(context))
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return count
    }

    companion object {
        const val NOTIFICATION_ID = 1003
        const val CHANNEL_ID = "review_reminder"
    }
}
