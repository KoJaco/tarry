package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.components.TertiaryButton
import me.tarry.app.ui.theme.TarryTheme

/** Test tag of the bar; it replaces the bottom navigation while visits are selected. */
const val SelectionBarTag = "selection_bar"

private val BarBorder = 1.dp
private val BarMaxHeight = 280.dp

/**
 * Bottom bar for multi-select: "N selected" and the bulk actions. Each action is one call: the host applies it to every
 * selected visit as a single edit with a single undo, then leaves selection mode. The actions wrap onto more rows when
 * the font is large; nothing is truncated.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectionBar(
    count: Int,
    onIgnorePlace: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TarryTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(SelectionBarTag)
            .background(c.paper)
            .drawBehind { drawLine(c.line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = BarBorder.toPx()) }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .heightIn(max = BarMaxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TarryTheme.space.gutter, vertical = TarryTheme.space.s),
        verticalArrangement = Arrangement.spacedBy(TarryTheme.space.xs),
    ) {
        Text(
            text = stringResource(R.string.selection_count, count),
            style = MaterialTheme.typography.titleSmall,
            color = c.ink,
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite // the count change is announced as visits are toggled
            },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
            verticalArrangement = Arrangement.spacedBy(TarryTheme.space.xs),
        ) {
            SecondaryButton(stringResource(R.string.selection_ignore_place), onClick = onIgnorePlace)
            TertiaryButton(stringResource(R.string.selection_cancel), onClick = onCancel)
        }
    }
}
