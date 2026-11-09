package me.tarry.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tarry.app.R
import me.tarry.app.ui.components.SettingsGroup
import me.tarry.app.ui.components.TarrySheet
import me.tarry.app.ui.theme.TarryTheme
import java.io.IOException

/** Wired entry point: reads each notice from `assets/licenses/` when its row is opened. */
@Composable
fun LicencesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LicencesContent(
        notices = OssNotices,
        textFor = { notice ->
            withContext(Dispatchers.IO) {
                try {
                    context.assets.open("licenses/${notice.assetFile}").bufferedReader().use { it.readText() }
                } catch (e: IOException) {
                    context.getString(R.string.licences_open_failed)
                }
            }
        },
        modifier = modifier,
    )
}

/** Stateless licences list. A row opens the full notice in a scrollable sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesContent(
    notices: List<OssNotice>,
    textFor: suspend (OssNotice) -> String,
    modifier: Modifier = Modifier,
) {
    val space = TarryTheme.space
    val colors = TarryTheme.colors
    var open by remember { mutableStateOf<OssNotice?>(null) }
    val rows = notices.map { stringResource(it.nameRes) to stringResource(it.subtitleRes) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = space.gutter, vertical = space.l),
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
        Text(
            text = stringResource(R.string.licences_title),
            style = MaterialTheme.typography.headlineMedium,
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        SettingsGroup {
            notices.forEachIndexed { index, notice ->
                val (name, subtitle) = rows[index]
                row(name, onClick = { open = notice }, subtitle = subtitle)
            }
        }
    }
    open?.let { notice ->
        val shape = TarryTheme.shapes.container.copy(
            bottomStart = CornerSize(0.dp),
            bottomEnd = CornerSize(0.dp),
        )
        TarrySheet(
            onDismissRequest = { open = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = shape,
            containerColor = colors.card,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = space.gutter)
                    .padding(bottom = space.l),
                verticalArrangement = Arrangement.spacedBy(space.s),
            ) {
                Text(stringResource(notice.nameRes), style = MaterialTheme.typography.titleLarge, color = colors.ink)
                val text by produceState<String?>(null, notice) { value = textFor(notice) }
                Text(text.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = colors.ink)
            }
        }
    }
}
