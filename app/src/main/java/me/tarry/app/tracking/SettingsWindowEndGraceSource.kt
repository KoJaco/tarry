package me.tarry.app.tracking

import kotlinx.coroutines.flow.first
import me.tarry.core.data.settings.SettingsRepository
import javax.inject.Inject

class SettingsWindowEndGraceSource @Inject constructor(
    private val settings: SettingsRepository,
) : WindowEndGraceSource {
    override suspend fun graceMaxMin(): Int = settings.settings.first().windowEndGraceMaxMin
}
