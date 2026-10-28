package me.tarry.app.tracking

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.Schedule
import java.io.File
import java.time.LocalDate
import java.util.Base64
import javax.inject.Inject
import javax.inject.Qualifier

/** Qualifies the `tracking_status` Preferences DataStore (the unqualified one is settings). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TrackingStatusDataStore

/** Runtime tracking status snapshot. Null means "not set". */
data class TrackingStatus(
    val lastHeartbeatMs: Long? = null,
    val captureMode: CaptureMode? = null,
    val activeSessionId: Long? = null,
    val attentionCause: AttentionCause? = null,
    /** Id (UUID) of the open health Gap. */
    val openHealthGapId: String? = null,
    /** Debug-only test window start (epoch ms), consumed by the schedule calculator. */
    val debugTestWindowStartMs: Long? = null,
    /** True when the last alarm was armed inexact because exact alarms are not allowed (ADR 0003). */
    val alarmDegraded: Boolean = false,
    /** Last time a segmentation run completed. */
    val lastSegmentationSuccessMs: Long? = null,
    /** Set when segmentation gave up; cleared by the next successful run. */
    val segmentationError: SegmentationError? = null,
    /** True while the session is kept open past the schedule end (window-end grace); drives "Finishing up…". */
    val finishingUp: Boolean = false,
    /** When that grace began (epoch ms); kept across a process death so the orphaned grace can be accounted for. */
    val finishingUpSinceMs: Long? = null,
    /** Recent schedule saves, ascending; edits count from the moment they were saved (see [ScheduleEdit]). */
    val scheduleEdits: List<ScheduleEdit> = emptyList(),
)

/** Activity Recognition transitions seen in one session: how many, how many were ignored (an equal or newer one was already applied), and the last one. */
data class ArSessionLog(
    val count: Int,
    val ignored: Int,
    val lastType: ActivityType,
    /** When the transition happened (receipt time minus its age), and when it was received; epoch ms. */
    val lastEventMs: Long,
    val lastReceiptMs: Long,
)

private fun decodeArLog(raw: String?): Map<Long, ArSessionLog> =
    raw.orEmpty().split(';').mapNotNull { entry ->
        val c = entry.split(',')
        if (c.size != 6) return@mapNotNull null
        val type = ActivityType.entries.firstOrNull { it.name == c[3] } ?: return@mapNotNull null
        val id = c[0].toLongOrNull() ?: return@mapNotNull null
        id to ArSessionLog(
            count = c[1].toIntOrNull() ?: return@mapNotNull null,
            ignored = c[2].toIntOrNull() ?: return@mapNotNull null,
            lastType = type,
            lastEventMs = c[4].toLongOrNull() ?: return@mapNotNull null,
            lastReceiptMs = c[5].toLongOrNull() ?: return@mapNotNull null,
        )
    }.toMap()

/** Why and when segmentation last gave up. */
data class SegmentationError(val timeMs: Long, val message: String)

/** `tracking_status` DataStore configuration; a corrupt file is replaced by defaults. */
internal fun createTrackingStatusDataStore(
    scope: CoroutineScope,
    produceFile: () -> File,
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = scope,
    produceFile = produceFile,
)

class TrackingStatusStore @Inject constructor(
    @param:TrackingStatusDataStore private val dataStore: DataStore<Preferences>,
) {
    val status: Flow<TrackingStatus> = dataStore.data.map { p ->
        TrackingStatus(
            lastHeartbeatMs = p[HEARTBEAT],
            captureMode = p[CAPTURE_MODE].toEnumOrNull<CaptureMode>(),
            activeSessionId = p[ACTIVE_SESSION],
            attentionCause = p[ATTENTION_CAUSE].toEnumOrNull<AttentionCause>(),
            openHealthGapId = p[OPEN_GAP],
            debugTestWindowStartMs = p[TEST_WINDOW_START],
            alarmDegraded = p[ALARM_DEGRADED] ?: false,
            lastSegmentationSuccessMs = p[SEG_SUCCESS],
            segmentationError = p[SEG_ERROR_TIME]?.let { SegmentationError(it, p[SEG_ERROR_MESSAGE] ?: "") },
            finishingUp = p[FINISHING_UP] ?: false,
            finishingUpSinceMs = p[FINISHING_UP_SINCE],
            scheduleEdits = decodeScheduleEdits(p[SCHEDULE_EDITS]),
        )
    }

    suspend fun current(): TrackingStatus = status.first()

    /** Activity Recognition diagnostics per session id (the last [AR_LOG_SESSIONS] sessions). */
    val arLogs: Flow<Map<Long, ArSessionLog>> = dataStore.data.map { p -> decodeArLog(p[AR_LOG]) }

    /**
     * Logs one Activity Recognition transition for [sessionId]: its type, when it happened and when it was received.
     * [ignored] marks one that was too old to apply. Diagnostics only, so it needs no schema.
     */
    suspend fun recordArTransition(sessionId: Long, type: ActivityType, eventMs: Long, receiptMs: Long, ignored: Boolean) {
        dataStore.edit { p ->
            val logs = decodeArLog(p[AR_LOG]).toMutableMap()
            val old = logs[sessionId]
            logs[sessionId] = ArSessionLog(
                count = (old?.count ?: 0) + 1,
                ignored = (old?.ignored ?: 0) + if (ignored) 1 else 0,
                lastType = type,
                lastEventMs = eventMs,
                lastReceiptMs = receiptMs,
            )
            val kept = logs.toSortedMap().entries.toList().takeLast(AR_LOG_SESSIONS)
            p[AR_LOG] = kept.joinToString(";") { (id, l) ->
                "$id,${l.count},${l.ignored},${l.lastType.name},${l.lastEventMs},${l.lastReceiptMs}"
            }
        }
    }

    suspend fun setLastHeartbeat(timeMs: Long?) = set(HEARTBEAT, timeMs)
    suspend fun setCaptureMode(mode: CaptureMode?) = set(CAPTURE_MODE, mode?.name)
    suspend fun setActiveSessionId(id: Long?) = set(ACTIVE_SESSION, id)
    suspend fun setAttentionCause(cause: AttentionCause?) = set(ATTENTION_CAUSE, cause?.name)
    suspend fun setOpenHealthGapId(id: String?) = set(OPEN_GAP, id)
    suspend fun setDebugTestWindowStart(timeMs: Long?) = set(TEST_WINDOW_START, timeMs)
    suspend fun setAlarmDegraded(degraded: Boolean) = set(ALARM_DEGRADED, degraded)

    /** Appends [edit] to the history, pruning edits older than [retentionDays] and keeping at most [max] (see [ScheduleEdit.append]). */
    suspend fun appendScheduleEdit(edit: ScheduleEdit, retentionDays: Long, max: Int) {
        dataStore.edit { p ->
            val kept = ScheduleEdit.append(decodeScheduleEdits(p[SCHEDULE_EDITS]), edit, retentionDays, max)
            p[SCHEDULE_EDITS] = encodeScheduleEdits(kept)
        }
    }

    suspend fun setFinishingUp(on: Boolean, sinceMs: Long? = null) {
        dataStore.edit {
            if (on) {
                it[FINISHING_UP] = true
                if (sinceMs != null) it[FINISHING_UP_SINCE] = sinceMs
            } else {
                it.remove(FINISHING_UP)
                it.remove(FINISHING_UP_SINCE)
            }
        }
    }

    /** A successful run: records the time and clears any earlier error. */
    suspend fun recordSegmentationSuccess(timeMs: Long) {
        dataStore.edit {
            it[SEG_SUCCESS] = timeMs
            it.remove(SEG_ERROR_TIME)
            it.remove(SEG_ERROR_MESSAGE)
        }
    }

    suspend fun recordSegmentationFailure(timeMs: Long, message: String) {
        dataStore.edit {
            it[SEG_ERROR_TIME] = timeMs
            it[SEG_ERROR_MESSAGE] = message
        }
    }

    /** Clears per-session runtime state (heartbeat, mode, session id). Attention, gap, test window and an unaccounted grace are kept. */
    suspend fun clearSessionState() {
        dataStore.edit {
            it.remove(HEARTBEAT)
            it.remove(CAPTURE_MODE)
            it.remove(ACTIVE_SESSION)
        }
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T?) {
        dataStore.edit { if (value == null) it.remove(key) else it[key] = value }
    }

    private companion object {
        val HEARTBEAT = longPreferencesKey("last_heartbeat_ms")
        val CAPTURE_MODE = stringPreferencesKey("capture_mode")
        val ACTIVE_SESSION = longPreferencesKey("active_session_id")
        val ATTENTION_CAUSE = stringPreferencesKey("attention_cause")
        val OPEN_GAP = stringPreferencesKey("open_health_gap_uuid")
        val TEST_WINDOW_START = longPreferencesKey("debug_test_window_start_ms")
        val ALARM_DEGRADED = booleanPreferencesKey("alarm_degraded")
        val SEG_SUCCESS = longPreferencesKey("segmentation_success_ms")
        val SEG_ERROR_TIME = longPreferencesKey("segmentation_error_ms")
        val SEG_ERROR_MESSAGE = stringPreferencesKey("segmentation_error_message")
        val FINISHING_UP = booleanPreferencesKey("finishing_up")
        val FINISHING_UP_SINCE = longPreferencesKey("finishing_up_since_ms")
        val SCHEDULE_EDITS = stringPreferencesKey("schedule_edits")
        val AR_LOG = stringPreferencesKey("ar_log")
        const val AR_LOG_SESSIONS = 20
    }
}

private inline fun <reified E : Enum<E>> String?.toEnumOrNull(): E? =
    this?.let { name -> enumValues<E>().firstOrNull { it.name == name } }

/**
 * Edits joined by `;`, each `atMs|base64(templateBefore)|weekStart|base64(overrideBefore)`; the last two are empty
 * when the save touched no week override, and the last one alone when that week had no override. Compact, local
 * control state. Anything malformed reads as an empty history.
 */
private fun encodeScheduleEdits(edits: List<ScheduleEdit>): String = edits.joinToString(";") { e ->
    val enc = Base64.getEncoder()
    val week = e.weekBefore
    "${e.atMs}|${enc.encodeToString(e.templateBefore.toBytes())}|${week?.weekStart ?: ""}|" +
        (week?.overrideBefore?.let { enc.encodeToString(it.toBytes()) }.orEmpty())
}

private fun decodeScheduleEdits(raw: String?): List<ScheduleEdit> = runCatching {
    if (raw.isNullOrEmpty()) return emptyList()
    val dec = Base64.getDecoder()
    raw.split(';').map { entry ->
        val c = entry.split('|')
        require(c.size == 4)
        val week = if (c[2].isEmpty()) {
            require(c[3].isEmpty())
            null
        } else {
            WeekBefore(LocalDate.parse(c[2]), c[3].takeIf { it.isNotEmpty() }?.let { Schedule.fromBytes(dec.decode(it)) })
        }
        ScheduleEdit(c[0].toLong(), Schedule.fromBytes(dec.decode(c[1])), week)
    }
}.getOrElse { emptyList() }
