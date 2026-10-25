package me.tarry.app.ui.schedule

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import me.tarry.app.R
import me.tarry.app.ui.components.IconCircleButton
import me.tarry.app.ui.components.PrimaryButton
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.components.SegmentedControl
import me.tarry.app.ui.components.TertiaryButton
import me.tarry.app.ui.drawer.ConfirmDialog
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.model.Schedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RowHeightDp = 48.dp
private const val MinGridRows = 4
/** Room kept below the grid for the Save / Discard bar and a save error line. */
private val SaveBarReserve = 120.dp

/** What the editor can do. The ViewModel wires these; tests record them. */
data class ScheduleEditorActions(
    val onModeChange: (ScheduleMode) -> Unit,
    /** -1 = previous week, 1 = next week. */
    val onShiftWeek: (Int) -> Unit,
    val onToggleCell: (DayOfWeek, Int) -> Unit,
    val onStroke: (Schedule) -> Unit,
    val onWorkWeekPreset: () -> Unit,
    val onClearAll: () -> Unit,
    val onSetDayHours: (DayOfWeek, Int, Int) -> Unit,
    val onCopyDay: (DayOfWeek, Set<DayOfWeek>) -> Unit,
    val onClearDay: (DayOfWeek) -> Unit,
    val onSave: () -> Unit,
    val onDiscard: () -> Unit,
    val onUseEveryWeek: () -> Unit,
    val onConfirmPending: () -> Unit,
    val onCancelPending: () -> Unit,
)

/**
 * Stateless schedule editor: mode switch, week navigation, the own-schedule banner, presets, the grid, and a Save /
 * Discard bar while there are unsaved edits. [onClose] leaves the screen once back-with-edits has been confirmed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditorContent(
    state: ScheduleUiState,
    actions: ScheduleEditorActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val space = TarryTheme.space
    val colors = TarryTheme.colors
    var sheetDay by rememberSaveable { mutableStateOf<DayOfWeek?>(null) }
    var confirmEveryWeek by rememberSaveable { mutableStateOf(false) }
    var confirmBack by rememberSaveable { mutableStateOf(false) }
    val weekMode = state.mode == ScheduleMode.WEEK

    // While a save is in flight Back does nothing: there is nothing to discard and nothing to lose by waiting.
    BackHandler(enabled = state.dirty || state.saving) { if (!state.saving) confirmBack = true }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = space.gutter)
            .padding(top = space.l),
    ) {
        // The controls scroll away at large font scales, so the grid always keeps at least [MinGridRows] rows.
        val barReserve = if (state.dirty) SaveBarReserve else 0.dp
        val controlsMax = (maxHeight - RowHeightDp * MinGridRows - barReserve - space.s * 3).coerceAtLeast(0.dp)
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(space.s)) {
            Column(
                modifier = Modifier.heightIn(max = controlsMax).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(space.s),
            ) {
                Text(
                    text = stringResource(R.string.schedule_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.ink,
                    modifier = Modifier.semantics { heading() },
                )
                SegmentedControl(
                    options = listOf(stringResource(R.string.schedule_mode_every_week), stringResource(R.string.schedule_mode_this_week)),
                    selectedIndex = if (weekMode) 1 else 0,
                    onSelect = { actions.onModeChange(if (it == 1) ScheduleMode.WEEK else ScheduleMode.TEMPLATE) },
                    enabled = !state.saving,
                )
                if (weekMode) {
                    WeekNav(state, actions.onShiftWeek)
                    if (state.hasOverride) OverrideBanner(onUseEveryWeek = { confirmEveryWeek = true })
                } else if (state.currentWeekHasOverride) {
                    Text(
                        text = stringResource(R.string.schedule_template_note_override),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(space.s), verticalArrangement = Arrangement.spacedBy(space.s)) {
                    SecondaryButton(stringResource(R.string.schedule_preset_work_week), onClick = actions.onWorkWeekPreset)
                    SecondaryButton(stringResource(R.string.schedule_preset_clear), onClick = actions.onClearAll)
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(TarryTheme.shapes.card)
                    .background(colors.card),
            ) {
                ScheduleGrid(
                    schedule = state.draft,
                    onToggle = actions.onToggleCell,
                    onStroke = actions.onStroke,
                    onDayHeaderClick = { sheetDay = it },
                    highlightDay = if (weekMode && state.viewedWeek == state.currentWeek) state.today.dayOfWeek else null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (state.saveFailed) {
                Text(
                    text = stringResource(R.string.schedule_save_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.alert,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (state.dirty) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = space.s),
                    horizontalArrangement = Arrangement.spacedBy(space.s),
                ) {
                    SecondaryButton(stringResource(R.string.schedule_discard), onClick = actions.onDiscard, modifier = Modifier.weight(1f))
                    PrimaryButton(stringResource(R.string.schedule_save), onClick = actions.onSave, enabled = !state.saving, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    sheetDay?.let { day ->
        ScheduleDaySheet(
            day = day,
            schedule = state.draft,
            actions = ScheduleDayActions(
                onSetHours = { start, end -> actions.onSetDayHours(day, start, end) },
                onCopyTo = { actions.onCopyDay(day, it) },
                onClearDay = { actions.onClearDay(day) },
            ),
            onDismiss = { sheetDay = null },
        )
    }
    if (confirmEveryWeek) {
        ConfirmDialog(
            title = stringResource(R.string.schedule_use_every_week_title),
            body = stringResource(R.string.schedule_use_every_week_body),
            confirmLabel = stringResource(R.string.schedule_use_every_week),
            onConfirm = {
                confirmEveryWeek = false
                actions.onUseEveryWeek()
            },
            onDismiss = { confirmEveryWeek = false },
        )
    }
    if (state.pending != null) {
        DiscardDialog(onConfirm = actions.onConfirmPending, onDismiss = actions.onCancelPending)
    }
    if (confirmBack) {
        DiscardDialog(
            onConfirm = {
                confirmBack = false
                actions.onDiscard()
                onClose()
            },
            onDismiss = { confirmBack = false },
        )
    }
}

@Composable
private fun DiscardDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.schedule_discard_title),
        body = stringResource(R.string.schedule_discard_body),
        confirmLabel = stringResource(R.string.schedule_discard),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun WeekNav(state: ScheduleUiState, onShift: (Int) -> Unit) {
    val locale = currentLocale()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s)) {
        IconCircleButton(
            icon = R.drawable.ic_chevron_left,
            contentDescription = stringResource(R.string.schedule_previous_week),
            onClick = { onShift(-1) },
            enabled = state.canGoPrevious && !state.saving,
        )
        Text(
            text = weekRangeLabel(state.viewedWeek, locale),
            style = MaterialTheme.typography.titleMedium,
            color = TarryTheme.colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconCircleButton(
            icon = R.drawable.ic_chevron_right,
            contentDescription = stringResource(R.string.schedule_next_week),
            onClick = { onShift(1) },
            enabled = !state.saving,
        )
    }
}

/** "26 Oct – 1 Nov" for the week starting [monday], in the user's language. */
@Composable
private fun weekRangeLabel(monday: LocalDate, locale: Locale): String {
    val formatter = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMM"), locale) }
    return stringResource(R.string.schedule_week_range, monday.format(formatter), monday.plusDays(6).format(formatter))
}

@Composable
private fun OverrideBanner(onUseEveryWeek: () -> Unit) {
    val c = TarryTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TarryTheme.shapes.card)
            .background(c.accentSoft)
            .padding(horizontal = TarryTheme.space.l, vertical = TarryTheme.space.s),
    ) {
        Text(
            text = stringResource(R.string.schedule_override_banner),
            style = MaterialTheme.typography.titleSmall,
            color = c.onAccentSoft,
        )
        TertiaryButton(stringResource(R.string.schedule_use_every_week), onClick = onUseEveryWeek)
    }
}
