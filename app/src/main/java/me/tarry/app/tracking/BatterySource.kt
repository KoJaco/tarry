package me.tarry.app.tracking

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/** Battery level and whether the phone is charging (or full on a charger). */
data class BatteryReading(val percent: Int, val charging: Boolean)

/** The battery as the low-battery switch sees it. Android's own broadcast, never a timer of ours (AGENTS rule 5). */
interface BatterySource {
    /** The current reading, or null when the platform gives none. */
    fun read(): BatteryReading?

    /** A reading each time the platform reports a change. */
    val changes: Flow<BatteryReading>
}

/** Hysteresis for the low-battery switch to Saver; pure so it is unit-tested without a battery. */
class LowBatteryRule(private val config: TrackingConfig) {
    /** The next state given the current [active] one and a [reading] (null = unknown: keep it). Charging always ends it. */
    fun next(active: Boolean, reading: BatteryReading?): Boolean = when {
        reading == null -> active
        reading.charging -> false
        !active -> reading.percent <= config.lowBatteryEnterPercent
        else -> reading.percent < config.lowBatteryExitPercent
    }
}

class AndroidBatterySource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : BatterySource {
    override fun read(): BatteryReading? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.toReading()

    // Registered while collected only; nothing in the manifest.
    override val changes: Flow<BatteryReading> = callbackFlow {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.toReading()?.let { trySend(it) }
            }
        }
        // The sticky value arrives straight away, so a collector starts from the current reading.
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.toReading()?.let { trySend(it) }
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun Intent.toReading(): BatteryReading? {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryReading(percent = level * 100 / scale, charging = charging)
    }
}
