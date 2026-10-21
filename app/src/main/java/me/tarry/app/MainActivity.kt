package me.tarry.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.tarry.core.data.settings.SettingsRepository
import me.tarry.core.data.settings.ThemeMode
import me.tarry.app.ui.theme.DarkTarryColors
import me.tarry.app.ui.theme.LightTarryColors
import me.tarry.app.ui.theme.isDark
import me.tarry.app.segmentation.SegmentationScheduler
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.HealthChecker
import me.tarry.app.tracking.ReconcileSource
import me.tarry.app.tracking.TrackingController
import me.tarry.app.tracking.TrackingStateRepository
import me.tarry.app.ui.TarryApp
import me.tarry.app.ui.theme.TarryTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var trackingState: TrackingStateRepository

    @Inject lateinit var controller: TrackingController

    @Inject lateinit var healthChecker: HealthChecker

    @Inject lateinit var segmentationScheduler: SegmentationScheduler

    @Inject @CaptureScope lateinit var captureScope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeFlow = remember { settingsRepository.settings.map { it.theme } }
            val mode by themeFlow
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val darkTheme = mode.isDark(isSystemInDarkTheme())
            DisposableEffect(darkTheme) {
                val scrim = (if (darkTheme) DarkTarryColors else LightTarryColors).paper.toArgb()
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(scrim)
                    } else {
                        SystemBarStyle.light(scrim, scrim)
                    },
                )
                onDispose { }
            }
            TarryTheme(darkTheme = darkTheme) {
                TarryApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Permissions and settings can change while we were away: re-evaluate now, and let the controller
        // fix what it can (the app is in the foreground, so it may start the service directly).
        trackingState.refresh()
        segmentationScheduler.enqueueNow()
        captureScope.launch {
            controller.reconcile(ReconcileSource.UI)
            healthChecker.clearResolved()
        }
    }
}
