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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import me.tarry.app.ui.drawer.JobOption
import me.tarry.app.ui.drawer.JobPickerSheet
import me.tarry.app.ui.drawer.NameDialog
import me.tarry.app.ui.drawer.TagGroupOption
import me.tarry.app.ui.drawer.TagMultiSelectSheet
import me.tarry.app.ui.theme.TarryTheme

/** Test tag of the bar; it replaces the bottom navigation while visits are selected. */
const val SelectionBarTag = "selection_bar"

private enum class BulkDialog { Job, NewJob, Tags }

private val BarBorder = 1.dp
private val BarMaxHeight = 280.dp

/**
 * Bottom bar for multi-select: "N selected" and the bulk actions. Each action is one call: the host applies it to every
 * selected visit as a single edit with a single undo, then leaves selection mode. The actions wrap onto more rows when
 * the font is large; nothing is truncated. [jobs] and [tagGroups] must already hide archived entries.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectionBar(
    count: Int,
    jobs: List<JobOption>,
    tagGroups: List<TagGroupOption>,
    onSetJob: (String?) -> Unit,
    onNewJob: (String) -> Unit,
    onAddTags: (Set<String>) -> Unit,
    onSetBillable: (Boolean) -> Unit,
    onIgnorePlace: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TarryTheme.colors
    var dialog by rememberSaveable { mutableStateOf<BulkDialog?>(null) }
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
            SecondaryButton(stringResource(R.string.selection_set_job), onClick = { dialog = BulkDialog.Job })
            SecondaryButton(stringResource(R.string.selection_add_tags), onClick = { dialog = BulkDialog.Tags })
            SecondaryButton(stringResource(R.string.selection_billable), onClick = { onSetBillable(true) })
            SecondaryButton(stringResource(R.string.selection_not_billable), onClick = { onSetBillable(false) })
            SecondaryButton(stringResource(R.string.selection_ignore_place), onClick = onIgnorePlace)
            TertiaryButton(stringResource(R.string.selection_cancel), onClick = onCancel)
        }
    }
    when (dialog) {
        BulkDialog.Job -> JobPickerSheet(
            title = stringResource(R.string.selection_job_picker_title),
            noneLabel = stringResource(R.string.edit_job_none),
            jobs = jobs,
            selectedId = null,
            noneSelected = false,
            onSelect = {
                dialog = null
                onSetJob(it)
            },
            onNewJob = { dialog = BulkDialog.NewJob },
            onDismiss = { dialog = null },
        )
        BulkDialog.NewJob -> NameDialog(
            title = stringResource(R.string.edit_job_new_title),
            label = stringResource(R.string.edit_job_name_label),
            onConfirm = {
                dialog = null
                onNewJob(it)
            },
            onDismiss = { dialog = null },
        )
        BulkDialog.Tags -> TagMultiSelectSheet(
            groups = tagGroups,
            onApply = {
                dialog = null
                onAddTags(it)
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}
