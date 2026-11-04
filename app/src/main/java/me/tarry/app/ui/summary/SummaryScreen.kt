package me.tarry.app.ui.summary

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.tarry.app.R
import me.tarry.app.export.summary.SummaryExportFormat
import me.tarry.app.export.summary.suggestedFileName
import me.tarry.app.summary.DayBilled
import me.tarry.app.summary.BlockPiece
import me.tarry.app.summary.DaySection
import me.tarry.app.summary.JobSummary
import me.tarry.app.summary.RangePreset
import me.tarry.app.summary.SummaryExportEvent
import me.tarry.app.summary.SummaryGrouping
import me.tarry.app.summary.SummaryRange
import me.tarry.app.summary.SummaryResult
import me.tarry.app.summary.SummaryUiState
import me.tarry.app.summary.SummaryViewModel
import me.tarry.app.ui.catalog.SheetFrame
import me.tarry.app.ui.components.EmptyState
import me.tarry.app.ui.components.IconCircleButton
import me.tarry.app.ui.components.LoadingBlocks
import me.tarry.app.ui.components.SectionHeader
import me.tarry.app.ui.components.SegmentedControl
import me.tarry.app.ui.components.TarryCard
import me.tarry.app.ui.components.TarryChip
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.theme.surfaceEdgeStroke
import me.tarry.app.ui.theme.tabular
import me.tarry.app.ui.timeline.formatDuration
import me.tarry.app.ui.timeline.rememberTimeFormats
import me.tarry.app.ui.timeline.spokenDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Test tags. */
object SummaryTags {
    const val Chips = "summary_chips"
    const val Grouping = "summary_grouping"
    const val Empty = "summary_empty"
    const val Totals = "summary_totals"
    const val Unlabelled = "summary_unlabelled"
    const val Sheet = "summary_job_sheet"
    const val Export = "summary_export"
    fun exportFormat(format: SummaryExportFormat) = "summary_export_${format.name}"
    fun row(jobId: String) = "summary_row_$jobId"
    fun rowBilled(jobId: String) = "summary_row_billed_$jobId"
    fun piece(blockId: String, date: LocalDate) = "summary_piece_${blockId}_$date"
}

private val DotSize = 16.dp

/** Width of the fade on the range chips' scrolling edges. */
internal val ChipsEdgeFadeWidth = 32.dp

/** From this font scale up, a job row stacks its billed figure under the name and caption. */
internal const val StackedRowFontScale = 1.5f

/** Hilt-wired Summary tab. [onOpenDay] opens that local day on the timeline. */
@Composable
fun SummaryScreen(
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // After midnight "Today" and "This week" move on; re-derive them when the user comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshToday() }
    // The file goes only where the user picks it (SAF); cancelling the picker does nothing.
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SummaryExportFormat.CSV.mimeType)) { uri ->
        if (uri != null) viewModel.export(SummaryExportFormat.CSV, uri)
    }
    val createPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SummaryExportFormat.PDF.mimeType)) { uri ->
        if (uri != null) viewModel.export(SummaryExportFormat.PDF, uri)
    }
    SummaryContent(
        state = state,
        onPreset = viewModel::selectPreset,
        onCustomRange = viewModel::setCustomRange,
        onGrouping = viewModel::setGrouping,
        onOpenDay = onOpenDay,
        modifier = modifier,
        onExport = { format ->
            val range = state?.range ?: return@SummaryContent
            when (format) {
                SummaryExportFormat.CSV -> createCsv.launch(format.suggestedFileName(range))
                SummaryExportFormat.PDF -> createPdf.launch(format.suggestedFileName(range))
            }
        },
    )
}

internal fun summaryExportMessage(resources: Resources, event: SummaryExportEvent): String = when (event) {
    is SummaryExportEvent.Saved -> resources.getQuantityString(
        R.plurals.summary_export_saved, event.blocks, event.blocks, resources.getString(event.format.labelRes()),
    )
    SummaryExportEvent.Failed -> resources.getString(R.string.summary_export_failed)
}

private fun SummaryExportFormat.labelRes(): Int = when (this) {
    SummaryExportFormat.CSV -> R.string.summary_export_format_csv
    SummaryExportFormat.PDF -> R.string.summary_export_format_pdf
}

/**
 * The Export icon at the right of the title. Disabled (but in place) with no job blocks; shows progress while a file is
 * being written. [hasBlocks] is null until the first calculation finishes.
 */
@Composable
private fun ExportButton(hasBlocks: Boolean?, exporting: Boolean, onExport: (SummaryExportFormat) -> Unit) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    if (exporting) {
        val label = stringResource(R.string.summary_exporting)
        Box(
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = label; disabled() }
                .testTag(SummaryTags.Export),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = TarryTheme.colors.ink)
        }
        return
    }
    val enabled = hasBlocks == true
    val description = stringResource(if (hasBlocks == false) R.string.summary_export_disabled else R.string.summary_export)
    Box {
        IconCircleButton(
            icon = R.drawable.ic_export,
            contentDescription = description,
            onClick = { menuOpen = true },
            enabled = enabled,
            containerColor = Color.Transparent,
            modifier = Modifier.testTag(SummaryTags.Export),
        )
        DropdownMenu(
            expanded = menuOpen && enabled,
            onDismissRequest = { menuOpen = false },
            border = surfaceEdgeStroke(),
        ) {
            for (format in SummaryExportFormat.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(format.menuLabelRes())) },
                    onClick = {
                        menuOpen = false
                        onExport(format)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(SummaryTags.exportFormat(format)),
                )
            }
        }
    }
}

private fun SummaryExportFormat.menuLabelRes(): Int = when (this) {
    SummaryExportFormat.CSV -> R.string.summary_export_menu_csv
    SummaryExportFormat.PDF -> R.string.summary_export_menu_pdf
}

/** Stateless Summary: range chips, grouping, job rows or day sections, then the totals and unlabelled cards. */
@Composable
fun SummaryContent(
    state: SummaryUiState?,
    onPreset: (RangePreset) -> Unit,
    onCustomRange: (LocalDate, LocalDate) -> Unit,
    onGrouping: (SummaryGrouping) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    /** The user chose a format in the export menu; the caller opens the file picker. */
    onExport: (SummaryExportFormat) -> Unit = {},
) {
    val space = TarryTheme.space
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    // The open drill-down: a job, and for the by-day grouping the day. Resolved from the live state on every read.
    var openJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var openDay by rememberSaveable { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = space.gutter, vertical = space.l),
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.summary_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = TarryTheme.colors.ink,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                ExportButton(
                    hasBlocks = state?.result?.hasBlocks,
                    exporting = state?.exporting == true,
                    onExport = onExport,
                )
            }
        }
        item {
            RangeChips(
                preset = state?.preset,
                onPreset = { preset ->
                    if (preset == RangePreset.CUSTOM) pickerOpen = true else onPreset(preset)
                },
            )
        }
        if (state != null) {
            item {
                Text(
                    text = rangeText(state.range),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TarryTheme.colors.muted,
                    modifier = Modifier.testTag("summary_range_text"),
                )
            }
        }
        item {
            val options = listOf(stringResource(R.string.summary_group_by_job), stringResource(R.string.summary_group_by_day))
            SegmentedControl(
                options = options,
                selectedIndex = if (state?.grouping == SummaryGrouping.BY_DAY) 1 else 0,
                onSelect = { onGrouping(if (it == 1) SummaryGrouping.BY_DAY else SummaryGrouping.BY_JOB) },
                modifier = Modifier.testTag(SummaryTags.Grouping),
            )
        }
        val result = state?.result
        if (state == null || result == null) {
            item { LoadingBlocks(count = 3) }
        } else {
            if (!result.hasBlocks) {
                item {
                    EmptyState(
                        title = stringResource(R.string.summary_empty_title),
                        body = stringResource(R.string.summary_empty_body),
                        modifier = Modifier.testTag(SummaryTags.Empty),
                    )
                }
            } else if (state.grouping == SummaryGrouping.BY_JOB) {
                item {
                    TarryCard(contentPadding = PaddingValues(0.dp)) {
                        result.byJob.forEachIndexed { index, row ->
                            if (index > 0) HorizontalDivider(thickness = 1.dp, color = TarryTheme.colors.divider)
                            JobRowItem(row) { openJobId = row.jobId; openDay = null }
                        }
                    }
                }
            } else {
                items(result.byDay, key = { it.date.toEpochDay() }) { section ->
                    DaySectionItem(section) { row -> openJobId = row.jobId; openDay = section.date.toEpochDay() }
                }
            }
            if (result.hasBlocks) item { TotalsCard(result, state.range) }
            if (result.hasUnlabelled) item { UnlabelledCard(result) }
        }
    }

    if (pickerOpen && state != null) {
        RangePickerDialog(
            initial = state.range,
            onConfirm = { from, to ->
                pickerOpen = false
                onCustomRange(from, to)
            },
            onDismiss = { pickerOpen = false },
        )
    }

    val result = state?.result
    val sheetRow = result?.let { r ->
        val id = openJobId ?: return@let null
        val day = openDay
        if (day == null) {
            r.byJob.firstOrNull { it.jobId == id }
        } else {
            r.byDay.firstOrNull { it.date.toEpochDay() == day }?.rows?.firstOrNull { it.jobId == id }
        }
    }
    if (state != null && sheetRow != null) {
        JobBlocksSheet(
            row = sheetRow,
            zone = state.zone,
            onOpenDay = { date ->
                openJobId = null
                onOpenDay(date)
            },
            onDismiss = { openJobId = null },
        )
    }
    // The row disappeared (its blocks were edited away or the range changed): forget the open drill-down.
    val gone = openJobId != null && result != null && sheetRow == null
    LaunchedEffect(gone) { if (gone) openJobId = null }
}

@Composable
private fun RangeChips(preset: RangePreset?, onPreset: (RangePreset) -> Unit) {
    val label = stringResource(R.string.summary_range_chips_description)
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scrollEdgeFade(scrollState, ChipsEdgeFadeWidth)
            .horizontalScroll(scrollState)
            .selectableGroup()
            .semantics { contentDescription = label }
            .testTag(SummaryTags.Chips),
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        for (p in RangePreset.entries) {
            TarryChip(
                text = stringResource(p.labelRes()),
                selected = p == preset,
                onClick = { onPreset(p) },
                modifier = Modifier.testTag("summary_chip_${p.name}"),
            )
        }
    }
}


/** Which horizontal bands (start..end px) fade out: the left one while scrolled away from the start, the right one while more follows. */
internal fun scrollEdgeFadeBands(canScrollBackward: Boolean, canScrollForward: Boolean, widthPx: Float, fadePx: Float): List<ClosedFloatingPointRange<Float>> {
    val w = fadePx.coerceAtMost(widthPx / 2f)
    return buildList {
        if (canScrollBackward) add(0f..w)
        if (canScrollForward) add((widthPx - w)..widthPx)
    }
}

/** Fades the right edge while [state] can scroll forward and the left edge while it can scroll back; nothing when it all fits. */
internal fun Modifier.scrollEdgeFade(state: ScrollState, width: Dp): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val bands = scrollEdgeFadeBands(state.canScrollBackward, state.canScrollForward, size.width, width.toPx())
        for (band in bands) {
            val toRight = band.start > 0f
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = if (toRight) listOf(Color.Black, Color.Transparent) else listOf(Color.Transparent, Color.Black),
                    startX = band.start,
                    endX = band.endInclusive,
                ),
                topLeft = Offset(band.start, 0f),
                size = Size(band.endInclusive - band.start, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

private fun RangePreset.labelRes(): Int = when (this) {
    RangePreset.TODAY -> R.string.summary_range_today
    RangePreset.YESTERDAY -> R.string.summary_range_yesterday
    RangePreset.THIS_WEEK -> R.string.summary_range_this_week
    RangePreset.LAST_WEEK -> R.string.summary_range_last_week
    RangePreset.CUSTOM -> R.string.summary_range_custom
}

@Composable
private fun dateFormatter(skeleton: String): DateTimeFormatter {
    val locale = Locale.current.platformLocale
    val configuration = LocalConfiguration.current
    return remember(locale, configuration, skeleton) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }
}

@Composable
private fun rangeText(range: SummaryRange): String {
    val format = dateFormatter("dMMMy")
    return if (range.from == range.to) {
        format.format(range.from)
    } else {
        stringResource(R.string.summary_range_from_to, format.format(range.from), format.format(range.to))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerDialog(initial: SummaryRange, onConfirm: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
    fun LocalDate.utcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val picker = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial.from.utcMillis(),
        initialSelectedEndDateMillis = initial.to.utcMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = picker.selectedStartDateMillis != null,
                onClick = {
                    val start = picker.selectedStartDateMillis ?: return@TextButton
                    val end = picker.selectedEndDateMillis ?: start
                    fun Long.date() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
                    onConfirm(start.date(), end.date())
                },
                modifier = Modifier.heightIn(min = TarryTheme.space.huge),
            ) { Text(stringResource(R.string.summary_picker_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TarryTheme.space.huge)) {
                Text(stringResource(R.string.summary_picker_cancel))
            }
        },
    ) {
        DateRangePicker(state = picker, modifier = Modifier.weight(1f, fill = false))
    }
}

/** "4 h 15 min", with a real zero ("0 min") instead of the timeline's "<1 min". */
@Composable
internal fun summaryDuration(ms: Long): String =
    if (ms <= 0L) stringResource(R.string.summary_duration_zero) else formatDuration(ms)

/** "4 hours 15 minutes" for TalkBack; zero reads as "0 minutes". */
@Composable
internal fun summarySpoken(ms: Long): String =
    if (ms <= 0L) stringResource(R.string.summary_spoken_zero) else spokenDuration(ms)

@Composable
private fun JobDot(job: JobSummary) {
    val colors = JobPalette.lookup(job.colorArgb, job.jobId).colors(TarryTheme.isDark)
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(DotSize)
            .clip(CircleShape)
            .background(colors.container)
            .border(JobPalette.BorderWidth, colors.border, CircleShape),
    )
}

@Composable
private fun jobTitle(job: JobSummary): String =
    if (job.archived) stringResource(R.string.summary_job_archived, job.name) else job.name

@Composable
private fun JobRowItem(row: JobSummary, onClick: () -> Unit) {
    val title = jobTitle(row)
    val blocks = pluralStringResource(R.plurals.summary_blocks, row.blockCount, row.blockCount)
    val description = stringResource(
        R.string.summary_row_description, title, blocks, summarySpoken(row.totalMs), summarySpoken(row.billedMs),
    )
    // At large font sizes the billed figure drops under the caption so the name keeps the full width.
    val stacked = LocalDensity.current.fontScale >= StackedRowFontScale
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = TarryTheme.space.huge)
            .padding(horizontal = TarryTheme.space.l, vertical = TarryTheme.space.m)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag(SummaryTags.row(row.jobId)),
        verticalAlignment = if (stacked) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.m),
    ) {
        if (stacked) {
            Box(Modifier.padding(top = TarryTheme.space.xs)) { JobDot(row) }
        } else {
            JobDot(row)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TarryTheme.colors.ink)
            Text(
                stringResource(R.string.summary_row_caption, blocks, summaryDuration(row.totalMs)),
                style = MaterialTheme.typography.bodyMedium,
                color = TarryTheme.colors.muted,
            )
            if (stacked) {
                Text(
                    stringResource(R.string.summary_row_billed, summaryDuration(row.billedMs)),
                    style = MaterialTheme.typography.titleMedium.tabular(),
                    fontWeight = FontWeight.Bold,
                    color = TarryTheme.colors.ink,
                    modifier = Modifier.testTag(SummaryTags.rowBilled(row.jobId)),
                )
            }
        }
        if (!stacked) {
            Text(
                summaryDuration(row.billedMs),
                style = MaterialTheme.typography.titleMedium.tabular(),
                fontWeight = FontWeight.Bold,
                color = TarryTheme.colors.ink,
            )
        }
    }
}

@Composable
private fun DaySectionItem(section: DaySection, onRow: (JobSummary) -> Unit) {
    val format = dateFormatter("EEEdMMM")
    val dayText = format.format(section.date)
    Column(verticalArrangement = Arrangement.spacedBy(TarryTheme.space.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = TarryTheme.space.s),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(dayText)
            Text(
                stringResource(R.string.summary_day_billed, summaryDuration(section.billedMs)),
                style = MaterialTheme.typography.labelMedium,
                color = TarryTheme.colors.muted,
            )
        }
        TarryCard(contentPadding = PaddingValues(0.dp)) {
            section.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(thickness = 1.dp, color = TarryTheme.colors.divider)
                JobRowItem(row) { onRow(row) }
            }
        }
    }
}

@Composable
private fun TotalsCard(result: SummaryResult, range: SummaryRange) {
    val billed = summaryDuration(result.billedMs)
    val clock = summaryDuration(result.onClockMs)
    val description = stringResource(R.string.summary_totals_description, summarySpoken(result.billedMs), summarySpoken(result.onClockMs))
    TarryCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag(SummaryTags.Totals),
        shape = TarryTheme.shapes.container,
    ) {
        Text(
            text = stringResource(R.string.summary_totals_title, rangeText(range)),
            style = MaterialTheme.typography.bodyMedium,
            color = TarryTheme.colors.muted,
        )
        Text(
            text = stringResource(R.string.summary_totals_billed, billed),
            style = MaterialTheme.typography.headlineLarge.tabular(),
            color = TarryTheme.colors.ink,
        )
        Text(
            text = stringResource(R.string.summary_totals_on_clock, clock),
            style = MaterialTheme.typography.bodyMedium,
            color = TarryTheme.colors.muted,
        )
    }
}

@Composable
private fun UnlabelledCard(result: SummaryResult) {
    val onSite = summaryDuration(result.unlabelledOnSiteMs)
    val travel = summaryDuration(result.unlabelledTravelMs)
    val description = stringResource(
        R.string.summary_unlabelled_description, summarySpoken(result.unlabelledOnSiteMs), summarySpoken(result.unlabelledTravelMs),
    )
    TarryCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag(SummaryTags.Unlabelled),
        shape = TarryTheme.shapes.container,
    ) {
        Text(
            stringResource(R.string.summary_unlabelled_title),
            style = MaterialTheme.typography.bodyMedium,
            color = TarryTheme.colors.muted,
        )
        UnlabelledLine(stringResource(R.string.summary_unlabelled_on_site), onSite)
        UnlabelledLine(stringResource(R.string.summary_unlabelled_travel), travel)
    }
}

@Composable
private fun UnlabelledLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = TarryTheme.space.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = TarryTheme.colors.ink)
        Text(value, style = MaterialTheme.typography.titleSmall.tabular(), color = TarryTheme.colors.ink)
    }
}

/** One job's blocks in the range (or the day, from the by-day grouping). A block opens its day on the timeline. */
@Composable
private fun JobBlocksSheet(row: JobSummary, zone: ZoneId, onOpenDay: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val perDay = row.pieces.any { it.billable && it.billedMs == null }
    val dayFormat = dateFormatter("EEEdMMM")
    SheetFrame(title = jobTitle(row), onDismiss = onDismiss) {
        TarryCard(contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag(SummaryTags.Sheet)) {
            val byDate = row.pieces.groupBy { it.date }
            var first = true
            for ((date, pieces) in byDate) {
                for (piece in pieces) {
                    if (!first) HorizontalDivider(thickness = 1.dp, color = TarryTheme.colors.divider)
                    first = false
                    PieceRow(piece, dayFormat.format(date), zone) { onOpenDay(piece.date) }
                }
                val billed = row.days.firstOrNull { it.date == date }
                if (perDay && billed != null) DayTotalRow(billed)
            }
        }
    }
}

/** [this] with its spaces (normal and narrow no-break) replaced by NO-BREAK SPACE, so the text wraps only between words it is joined to. */
internal fun String.nonBreaking(): String = replace(' ', '\u00A0').replace('\u202F', '\u00A0')

@Composable
private fun PieceRow(piece: BlockPiece, dateText: String, zone: ZoneId, onClick: () -> Unit) {
    val formats = rememberTimeFormats()
    val start = formats.time(piece.start, zone)
    val end = formats.time(piece.end, zone)
    // Lines may break around the dash but never inside a time ("9:00 / am" at large font scales).
    val range = stringResource(R.string.timeline_time_range, start.nonBreaking(), end.nonBreaking())
    val spokenRange = stringResource(R.string.timeline_spoken_range, start, end)
    val duration = summaryDuration(piece.durationMs)
    val billedText = piece.billedMs?.let { summaryDuration(it) }
    val rightText = if (!piece.billable) stringResource(R.string.summary_piece_not_billable) else billedText
    val description = buildList {
        add(dateText)
        add(spokenRange)
        add(summarySpoken(piece.durationMs))
        if (!piece.billable) {
            add(stringResource(R.string.summary_piece_not_billable))
        } else if (piece.billedMs != null) {
            add(stringResource(R.string.summary_piece_billed_description, summarySpoken(piece.billedMs)))
        }
        piece.notes?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.summary_piece_notes_description, it)) }
    }.joinToString(", ")
    val hint = stringResource(R.string.summary_piece_open_hint)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = hint, onClick = onClick)
            .heightIn(min = TarryTheme.space.huge)
            .padding(horizontal = TarryTheme.space.l, vertical = TarryTheme.space.m)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag(SummaryTags.piece(piece.blockId, piece.date)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.m),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(dateText, style = MaterialTheme.typography.titleSmall, color = TarryTheme.colors.ink)
            Text(
                stringResource(R.string.summary_row_caption, range, duration),
                style = MaterialTheme.typography.bodyMedium,
                color = TarryTheme.colors.muted,
            )
            piece.notes?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted, maxLines = 2)
            }
        }
        if (rightText != null) {
            Text(
                rightText,
                style = MaterialTheme.typography.titleSmall.tabular(),
                fontWeight = if (piece.billable) FontWeight.Bold else FontWeight.Normal,
                color = if (piece.billable) TarryTheme.colors.ink else TarryTheme.colors.muted,
            )
        }
    }
}

/** Per-day mode bills a job's blocks of one day together, so the day's billed time is its own line. */
@Composable
private fun DayTotalRow(day: DayBilled) {
    val spoken = summarySpoken(day.billedMs)
    val label = stringResource(R.string.summary_piece_day_total)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TarryTheme.colors.inset)
            .padding(horizontal = TarryTheme.space.l, vertical = TarryTheme.space.s)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $spoken" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
        Text(
            summaryDuration(day.billedMs),
            style = MaterialTheme.typography.titleSmall.tabular(),
            fontWeight = FontWeight.Bold,
            color = TarryTheme.colors.ink,
        )
    }
}
