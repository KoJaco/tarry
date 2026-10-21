package me.tarry.app.ui.sessions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.tarry.app.BuildConfig
import me.tarry.app.export.ExportFormat
import me.tarry.app.export.ExportRequest
import me.tarry.app.export.suggestedFileName

/**
 * Hilt-wired debug sessions screen. Each export opens the system "save as" chooser (SAF `CreateDocument`),
 * so nothing is written unless the user picks a destination.
 */
@Composable
fun SessionsScreen(
    modifier: Modifier = Modifier,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val exportResult by viewModel.exportResult.collectAsStateWithLifecycle()
    val segmentation by viewModel.segmentation.collectAsStateWithLifecycle()
    // The request that the chooser currently open is for.
    var pending by remember { mutableStateOf<ExportRequest?>(null) }

    val onResult = { uri: android.net.Uri? ->
        val request = pending
        pending = null
        if (uri != null && request != null) viewModel.export(request, uri)
    }
    val createGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.GPX.mimeType), onResult)
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.CSV.mimeType), onResult)

    val actions = remember(viewModel, createGpx, createCsv) {
        SessionsActions(
            onExport = { request ->
                pending = request
                val launcher = if (request.format == ExportFormat.GPX) createGpx else createCsv
                launcher.launch(request.suggestedFileName())
            },
            onStartTestWindow = viewModel::startTestWindow,
            onDismissExportResult = viewModel::dismissExportResult,
            onRunSegmentation = viewModel::runSegmentation,
        )
    }
    SessionsContent(
        sessions = sessions,
        showTestWindow = BuildConfig.DEBUG,
        exportResult = exportResult,
        segmentation = segmentation,
        actions = actions,
        modifier = modifier,
    )
}
