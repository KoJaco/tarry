package me.tarry.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import me.tarry.app.access.AccessIntents
import me.tarry.app.access.PurchaseClient
import me.tarry.app.reminder.ReviewReminderIntents
import me.tarry.app.reminder.ReviewReminderScheduler
import me.tarry.app.segmentation.SegmentationScheduler
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.HealthChecker
import me.tarry.app.tracking.ReconcileSource
import me.tarry.app.tracking.TrackingController
import me.tarry.app.tracking.TrackingStateRepository
import me.tarry.app.ui.TarryApp
import me.tarry.app.ui.TarryRoot
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var trackingState: TrackingStateRepository

    @Inject lateinit var controller: TrackingController

    @Inject lateinit var healthChecker: HealthChecker

    @Inject lateinit var segmentationScheduler: SegmentationScheduler

    @Inject lateinit var reviewReminder: ReviewReminderScheduler

    @Inject @CaptureScope lateinit var captureScope: CoroutineScope

    @Inject lateinit var purchaseClient: PurchaseClient

    /** The reminder notification was tapped: show today's Timeline with the Needs review filter on, once. */
    private var openReview by mutableStateOf(false)

    /** The trial-ended alert was tapped: push the paywall, once. */
    private var openPaywall by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A rotation or process restart replays the launching intent, so only a fresh launch reads it; a request not yet handled is carried in the saved state.
        openReview = if (savedInstanceState == null) {
            intent.getBooleanExtra(ReviewReminderIntents.EXTRA_OPEN_REVIEW, false)
        } else {
            savedInstanceState.getBoolean(STATE_OPEN_REVIEW, false)
        }
        openPaywall = if (savedInstanceState == null) {
            intent.getBooleanExtra(AccessIntents.EXTRA_OPEN_PAYWALL, false)
        } else {
            savedInstanceState.getBoolean(STATE_OPEN_PAYWALL, false)
        }
        setContent {
            val themeFlow = remember { settingsRepository.settings.map { it.theme } }
            // Null until the stored theme loads: TarryRoot composes nothing (the paper window background shows).
            val mode by themeFlow.collectAsStateWithLifecycle<ThemeMode?>(initialValue = null)
            TarryRoot(themeMode = mode, onDarkChanged = ::styleSystemBars) {
                TarryApp(
                    openReview = openReview,
                    openPaywall = openPaywall,
                    onOpenPaywallHandled = {
                        openPaywall = false
                        intent.removeExtra(AccessIntents.EXTRA_OPEN_PAYWALL)
                    },
                    onOpenReviewHandled = {
                        openReview = false
                        intent.removeExtra(ReviewReminderIntents.EXTRA_OPEN_REVIEW)
                    },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_OPEN_REVIEW, openReview)
        outState.putBoolean(STATE_OPEN_PAYWALL, openPaywall)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(ReviewReminderIntents.EXTRA_OPEN_REVIEW, false)) openReview = true
        if (intent.getBooleanExtra(AccessIntents.EXTRA_OPEN_PAYWALL, false)) openPaywall = true
    }

    private fun styleSystemBars(darkTheme: Boolean) {
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
    }

    override fun onStart() {
        super.onStart()
        // Permissions and settings can change while we were away: re-evaluate now, and let the controller
        // fix what it can (the app is in the foreground, so it may start the service directly).
        trackingState.refresh()
        segmentationScheduler.enqueueNow()
        // What this Google account owns can change behind the app (a refund, a purchase on another device).
        captureScope.launch { purchaseClient.refresh() }
        captureScope.launch {
            controller.reconcile(ReconcileSource.UI)
            healthChecker.clearResolved()
        }
        // Also catches a restore from backup, which changes the schedule and the reminder setting behind the app's back.
        captureScope.launch { reviewReminder.rearm() }
    }

    private companion object {
        const val STATE_OPEN_REVIEW = "open_review"
        const val STATE_OPEN_PAYWALL = "open_paywall"
    }
}
