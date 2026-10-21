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
import java.io.File
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
)

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
        )
    }

    suspend fun current(): TrackingStatus = status.first()

    suspend fun setLastHeartbeat(timeMs: Long?) = set(HEARTBEAT, timeMs)
    suspend fun setCaptureMode(mode: CaptureMode?) = set(CAPTURE_MODE, mode?.name)
    suspend fun setActiveSessionId(id: Long?) = set(ACTIVE_SESSION, id)
    suspend fun setAttentionCause(cause: AttentionCause?) = set(ATTENTION_CAUSE, cause?.name)
    suspend fun setOpenHealthGapId(id: String?) = set(OPEN_GAP, id)
    suspend fun setDebugTestWindowStart(timeMs: Long?) = set(TEST_WINDOW_START, timeMs)
    suspend fun setAlarmDegraded(degraded: Boolean) = set(ALARM_DEGRADED, degraded)

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

    /** Clears per-session runtime state (heartbeat, mode, session id). Attention, gap and test window are kept. */
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
    }
}

private inline fun <reified E : Enum<E>> String?.toEnumOrNull(): E? =
    this?.let { name -> enumValues<E>().firstOrNull { it.name == name } }
