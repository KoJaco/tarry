package me.tarry.app.ui.catalog

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.tarry.app.R
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.undo.UndoEvent
import me.tarry.app.ui.undo.UndoEvents
import me.tarry.app.ui.undo.noticeRes
import me.tarry.core.data.repository.CatalogEditor
import me.tarry.core.data.repository.EditResult
import me.tarry.core.data.repository.JobRepository
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.TimelineEditor
import javax.inject.Inject

/** One job on the Jobs screen. [placeDefaults] is how many places use it as their default job. */
@Immutable
data class JobRow(val id: String, val name: String, val colorArgb: Int, val archived: Boolean, val placeDefaults: Int)

@Immutable
data class JobsUiState(val active: List<JobRow>, val archived: List<JobRow>) {
    val isEmpty: Boolean get() = active.isEmpty() && archived.isEmpty()
}

/** Backs the Jobs screen: list, create, rename, recolour, archive (undoable), delete when unused. */
@HiltViewModel
class JobsViewModel @Inject constructor(
    jobs: JobRepository,
    places: PlaceRepository,
    private val catalog: CatalogEditor,
    private val timeline: TimelineEditor,
    private val undoEvents: UndoEvents,
) : ViewModel() {
    /** Null until the first read finishes. */
    val state: StateFlow<JobsUiState?> = combine(jobs.observeAll(), places.observeAll()) { allJobs, allPlaces ->
        val defaults = allPlaces.mapNotNull { it.defaultJobId }.groupingBy { it }.eachCount()
        val rows = allJobs.map { JobRow(it.id, it.name, it.colorArgb, it.archived, defaults[it.id] ?: 0) }
        JobsUiState(active = rows.filterNot { it.archived }, archived = rows.filter { it.archived })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    /** What blocks Delete: live visits, trips and place defaults using the job. */
    suspend fun uses(jobId: String): Int = catalog.jobUses(jobId)

    fun create(name: String) = viewModelScope.launch { report(timeline.createJob(name, JobPalette.slots.map { it.key })) }

    fun rename(jobId: String, name: String) = viewModelScope.launch { report(catalog.renameJob(jobId, name)) }

    fun setColour(jobId: String, colorArgb: Int) = viewModelScope.launch { report(catalog.setJobColour(jobId, colorArgb)) }

    fun archive(jobId: String) = viewModelScope.launch {
        val result = catalog.archiveJob(jobId)
        if (result is EditResult.Ok) result.undo?.let { undoEvents.offer(UndoEvent(R.string.jobs_undo_archived, it)) }
        else report(result)
    }

    fun unarchive(jobId: String) = viewModelScope.launch { report(catalog.unarchiveJob(jobId)) }

    fun delete(jobId: String) = viewModelScope.launch { report(catalog.deleteJob(jobId)) }

    /** A refused edit (in use, not found, blank name) shows a short message instead of doing nothing. */
    private fun report(result: EditResult) {
        result.noticeRes()?.let(undoEvents::notice)
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
