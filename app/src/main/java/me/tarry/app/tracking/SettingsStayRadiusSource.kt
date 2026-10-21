package me.tarry.app.tracking

import kotlinx.coroutines.flow.first
import me.tarry.core.data.settings.SettingsRepository
import javax.inject.Inject

class SettingsStayRadiusSource @Inject constructor(
    private val settings: SettingsRepository,
) : StayRadiusSource {
    override suspend fun stayRadiusM(): Float = settings.settings.first().stayGeofenceRadiusM.toFloat()
}
