package me.tarry.app.tracking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.tarry.core.data.settings.PowerMode
import me.tarry.core.data.settings.SettingsRepository
import javax.inject.Inject

/** The two battery-use settings capture follows while a session is open. */
data class PowerSettings(val mode: PowerMode, val lowBatterySaver: Boolean) {
    /** The mode capture runs in: Saver while the low-battery switch is on and active, otherwise the chosen one. */
    fun effective(lowBatteryActive: Boolean): PowerMode = if (lowBatterySaver && lowBatteryActive) PowerMode.SAVER else mode
}

interface PowerSettingsSource {
    val settings: Flow<PowerSettings>
}

class SettingsPowerSettingsSource @Inject constructor(
    private val repo: SettingsRepository,
) : PowerSettingsSource {
    override val settings: Flow<PowerSettings> =
        repo.settings.map { PowerSettings(it.powerMode, it.lowBatterySaver) }.distinctUntilChanged()
}
