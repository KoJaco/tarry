package me.tarry.app.spike

// THROWAWAY — phase 2a T1 background-start spike. Reverted after ADR 0003.

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

private const val TAG = "TarrySpike"

/** adb: am broadcast -a me.tarry.app.SPIKE_ARM -n me.tarry.app/.spike.SpikeArmReceiver --ei delaySec 90 --es mode exact|window */
class SpikeArmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val delaySec = intent.getIntExtra("delaySec", 90)
        val mode = intent.getStringExtra("mode") ?: "exact"
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, SpikeAlarmReceiver::class.java).putExtra("mode", mode),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = System.currentTimeMillis() + delaySec * 1000L
        Log.i(TAG, "ARM mode=$mode delaySec=$delaySec canExact=${am.canScheduleExactAlarms()}")
        try {
            if (mode == "exact") {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi)
            }
            Log.i(TAG, "ARM ok at=$at")
        } catch (e: SecurityException) {
            Log.e(TAG, "ARM failed: $e")
        }
    }
}

class SpikeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pm = context.getSystemService(PowerManager::class.java)
        Log.i(
            TAG,
            "ALARM fired mode=${intent.getStringExtra("mode")} idle=${pm.isDeviceIdleMode} " +
                "interactive=${pm.isInteractive} ${permState(context)}",
        )
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SpikeLocationService::class.java).putExtra("firedAt", SystemClock.elapsedRealtime()),
            )
            Log.i(TAG, "startForegroundService returned")
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService threw: $e")
        }
    }
}

class SpikeLocationService : Service() {
    private var firedAt = 0L
    private var fixes = 0
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach {
                fixes++
                Log.i(
                    TAG,
                    "FIX #$fixes after ${(SystemClock.elapsedRealtime() - firedAt) / 1000}s " +
                        "acc=${it.accuracy} provider=${it.provider}",
                )
            }
            if (fixes >= 3) stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        firedAt = intent?.getLongExtra("firedAt", SystemClock.elapsedRealtime()) ?: SystemClock.elapsedRealtime()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("spike", "Spike", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, "spike")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Tarry spike")
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, 42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            Log.i(TAG, "startForeground OK ${permState(this)}")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground threw: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L).build(),
                callback,
                Looper.getMainLooper(),
            )
            Log.i(TAG, "requestLocationUpdates OK")
        } catch (e: SecurityException) {
            Log.e(TAG, "requestLocationUpdates threw: $e")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(callback)
        Log.i(TAG, "service destroyed fixes=$fixes")
        super.onDestroy()
    }
}

private fun permState(c: Context): String {
    fun g(p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
    return "fine=${g(Manifest.permission.ACCESS_FINE_LOCATION)} bg=${g(Manifest.permission.ACCESS_BACKGROUND_LOCATION)} " +
        "canExact=${c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()}"
}
