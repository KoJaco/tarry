package me.tarry.app.tracking

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
    val openHealthGapId: Long? = null,
    /** Debug-only test window start (epoch ms), consumed by the schedule calculator (T6). */
    val debugTestWindowStartMs: Long? = null,
)

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
        )
    }

    suspend fun current(): TrackingStatus = status.first()

    suspend fun setLastHeartbeat(timeMs: Long?) = set(HEARTBEAT, timeMs)
    suspend fun setCaptureMode(mode: CaptureMode?) = set(CAPTURE_MODE, mode?.name)
    suspend fun setActiveSessionId(id: Long?) = set(ACTIVE_SESSION, id)
    suspend fun setAttentionCause(cause: AttentionCause?) = set(ATTENTION_CAUSE, cause?.name)
    suspend fun setOpenHealthGapId(id: Long?) = set(OPEN_GAP, id)
    suspend fun setDebugTestWindowStart(timeMs: Long?) = set(TEST_WINDOW_START, timeMs)

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
        val OPEN_GAP = longPreferencesKey("open_health_gap_id")
        val TEST_WINDOW_START = longPreferencesKey("debug_test_window_start_ms")
    }
}

private inline fun <reified E : Enum<E>> String?.toEnumOrNull(): E? =
    this?.let { name -> enumValues<E>().firstOrNull { it.name == name } }
