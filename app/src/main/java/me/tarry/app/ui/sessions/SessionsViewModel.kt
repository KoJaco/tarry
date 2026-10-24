package me.tarry.app.ui.sessions

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.tarry.app.export.ExportRequest
import me.tarry.app.export.RawExporter
import me.tarry.app.segmentation.SegmentationScheduler
import me.tarry.app.tracking.ArSessionLog
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.TrackingController
import me.tarry.app.tracking.SegmentationError
import me.tarry.app.tracking.TrackingStatusStore
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.repository.TrackingRepository
import javax.inject.Inject

/** One row of the debug sessions list. */
data class SessionListItem(
    val id: Long,
    val start: Long,
    val end: Long?,
    val zoneId: String,
    val trigger: SessionTrigger,
    val endReason: SessionEndReason?,
    val pingCount: Int,
    /** Activity Recognition transitions seen in this session (diagnostics), if any were logged. */
    val activityLog: ArSessionLog? = null,
)

/** What the Sessions screen shows about segmentation. */
data class SegmentationUi(
    val running: Boolean = false,
    val lastSuccessMs: Long? = null,
    val error: SegmentationError? = null,
)

sealed interface ExportResult {
    data class Done(val pingCount: Int) : ExportResult

    data object Failed : ExportResult
}

@HiltViewModel
class SessionsViewModel @Inject constructor(
    trackingRepo: TrackingRepository,
    private val exporter: RawExporter,
    private val controller: TrackingController,
    private val segmentationScheduler: SegmentationScheduler,
    statusStore: TrackingStatusStore,
    // The test window must be scheduled even if the user leaves the screen right after tapping.
    @param:CaptureScope private val captureScope: CoroutineScope,
) : ViewModel() {
    val sessions: StateFlow<List<SessionListItem>> =
        combine(trackingRepo.observeSessions(), trackingRepo.observePingCounts(), statusStore.arLogs) { sessions, counts, arLogs ->
            sessions.map { s ->
                SessionListItem(s.id, s.start, s.end, s.zoneId, s.trigger, s.endReason, counts[s.id] ?: 0, arLogs[s.id])
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    /** Running while the "run now" work is queued or running; plus the last outcome. */
    val segmentation: StateFlow<SegmentationUi> =
        combine(segmentationScheduler.observeNow(), statusStore.status) { infos, status ->
            SegmentationUi(
                running = infos.any { !it.state.isFinished },
                lastSuccessMs = status.lastSegmentationSuccessMs,
                error = status.segmentationError,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), SegmentationUi())

    private val _exportResult = MutableStateFlow<ExportResult?>(null)

    /** Outcome of the last export, shown once on the screen. */
    val exportResult: StateFlow<ExportResult?> = _exportResult

    /** Called with the document the user picked in the system file chooser. */
    fun export(request: ExportRequest, uri: Uri) {
        viewModelScope.launch {
            _exportResult.value = try {
                ExportResult.Done(exporter.export(request, uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportResult.Failed
            }
        }
    }

    fun dismissExportResult() {
        _exportResult.value = null
    }

    fun runSegmentation() {
        segmentationScheduler.enqueueNow()
    }

    /** Debug builds only; the controller ignores it in release builds. */
    fun startTestWindow() {
        captureScope.launch { controller.startDebugTestWindow() }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
