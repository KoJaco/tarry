package me.tarry.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.tarry.core.data.settings.Settings
import me.tarry.core.data.settings.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {
    /** Null until the first DataStore read completes. */
    val settings: StateFlow<Settings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Applies from the next stop; a stop already being tracked keeps its radius. */
    fun setStayGeofenceRadiusM(meters: Int) {
        viewModelScope.launch { repository.setStayGeofenceRadiusM(meters) }
    }
}
