package me.tarry.app.ui.places

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import me.tarry.app.ui.timeline.displayLabel
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.TimelineRepository
import javax.inject.Inject

/** One place on the Places list. [label] is the name, else the address, else coordinates. */
@Immutable
data class PlaceRow(val id: String, val label: String, val visitCount: Int, val lastVisitAt: Long?, val ignored: Boolean)

@Immutable
data class PlacesUiState(val rows: List<PlaceRow>)

/** Backs the Places list: live places, most recently visited first, with their visit counts. */
@HiltViewModel
class PlacesViewModel @Inject constructor(
    places: PlaceRepository,
    timeline: TimelineRepository,
) : ViewModel() {
    /** Null until the first read finishes. */
    val state: StateFlow<PlacesUiState?> = combine(places.observeAll(), timeline.observePlaceVisitCounts()) { all, counts ->
        PlacesUiState(
            all.filter { it.mergedIntoId == null }
                .map { PlaceRow(it.id, it.displayLabel(), counts[it.id] ?: 0, it.lastVisitAt, it.ignored) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
