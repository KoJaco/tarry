package me.tarry.app.ui.summary

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow
import me.tarry.app.summary.SummaryExportEvent

/**
 * Shows each export outcome from [events] as a snackbar on [hostState]. Hosted by the app shell, not the Summary screen,
 * so "Saved ..." still appears when the user leaves the Summary tab while a (slow) export runs.
 */
@Composable
fun SummaryExportSnackbarEffect(events: Flow<SummaryExportEvent>, hostState: SnackbarHostState) {
    val resources = LocalContext.current.resources
    LaunchedEffect(events, hostState) {
        events.collect { event -> hostState.showSnackbar(summaryExportMessage(resources, event)) }
    }
}
