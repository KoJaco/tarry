package me.tarry.app.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.components.DestructiveButton
import me.tarry.app.ui.components.IconCircleButton
import me.tarry.app.ui.components.PrimaryButton
import me.tarry.app.ui.components.SectionHeader
import me.tarry.app.ui.components.TarryChip
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.model.Schedule
import java.time.DayOfWeek
import java.time.format.TextStyle

private val WEEKDAYS = DayOfWeek.entries.take(5)

/** What the day sheet can do to one day. Every action also closes the sheet. */
data class ScheduleDayActions(
    /** Active from the first slot up to, not including, the second (48 = midnight). */
    val onSetHours: (startSlot: Int, endSlotExclusive: Int) -> Unit,
    val onCopyTo: (Set<DayOfWeek>) -> Unit,
    val onClearDay: () -> Unit,
)

/** Day sheet chrome, styled like the tracking sheet: `container` top corners on a `paper` surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleDaySheet(day: DayOfWeek, schedule: Schedule, actions: ScheduleDayActions, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = TarryTheme.shapes.container.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        containerColor = TarryTheme.colors.paper,
    ) {
        ScheduleDaySheetContent(day, schedule, actions, onDismiss)
    }
}

/**
 * Set hours (30 minute steps; the non-drag way to paint a day), Copy to other days, and Clear day. Stateless apart from
 * the pending hours and checked days, so it can be tested without the sheet chrome.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleDaySheetContent(day: DayOfWeek, schedule: Schedule, actions: ScheduleDayActions, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val space = TarryTheme.space
    val locale = currentLocale()
    val (initialStart, initialEnd) = remember(day) { initialHours(schedule.daySlots(day)) }
    var start by rememberSaveable(day) { mutableIntStateOf(initialStart) }
    var end by rememberSaveable(day) { mutableIntStateOf(initialEnd) }
    var targets by remember(day) { mutableStateOf(emptySet<DayOfWeek>()) }
    val others = DayOfWeek.entries - day
    val weekdayTargets = WEEKDAYS - day

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = space.gutter)
            .padding(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
        Text(
            text = dayName(day, TextStyle.FULL, locale),
            style = MaterialTheme.typography.titleLarge,
            color = TarryTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )

        SectionHeader(stringResource(R.string.schedule_set_hours), Modifier.padding(top = space.s))
        HoursStepper(
            label = stringResource(R.string.schedule_hours_start),
            slot = start,
            earlierDescription = stringResource(R.string.schedule_start_earlier),
            laterDescription = stringResource(R.string.schedule_start_later),
            canGoEarlier = start > 0,
            canGoLater = start < end - 1,
            onEarlier = { start -= 1 },
            onLater = { start += 1 },
        )
        HoursStepper(
            label = stringResource(R.string.schedule_hours_end),
            slot = end,
            earlierDescription = stringResource(R.string.schedule_end_earlier),
            laterDescription = stringResource(R.string.schedule_end_later),
            canGoEarlier = end > start + 1,
            canGoLater = end < Schedule.SLOTS_PER_DAY,
            onEarlier = { end -= 1 },
            onLater = { end += 1 },
        )
        PrimaryButton(
            stringResource(R.string.schedule_set_hours),
            onClick = {
                actions.onSetHours(start, end)
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(stringResource(R.string.schedule_copy_to), Modifier.padding(top = space.m))
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(space.s),
            verticalArrangement = Arrangement.spacedBy(space.xs),
        ) {
            if (weekdayTargets.isNotEmpty()) {
                val allWeekdays = targets.containsAll(weekdayTargets)
                TarryChip(
                    text = stringResource(R.string.schedule_copy_weekdays),
                    selected = allWeekdays,
                    role = Role.Checkbox,
                    onClick = { targets = if (allWeekdays) targets - weekdayTargets.toSet() else targets + weekdayTargets },
                )
            }
            others.forEach { other ->
                TarryChip(
                    text = dayName(other, TextStyle.SHORT, locale),
                    selected = other in targets,
                    role = Role.Checkbox,
                    onClick = { targets = if (other in targets) targets - other else targets + other },
                )
            }
        }
        PrimaryButton(
            stringResource(R.string.schedule_copy_apply),
            onClick = {
                actions.onCopyTo(targets)
                onDone()
            },
            enabled = targets.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )

        DestructiveButton(
            stringResource(R.string.schedule_clear_day),
            onClick = {
                actions.onClearDay()
                onDone()
            },
            modifier = Modifier.fillMaxWidth().padding(top = space.m),
        )
    }
}

@Composable
private fun HoursStepper(
    label: String,
    slot: Int,
    earlierDescription: String,
    laterDescription: String,
    canGoEarlier: Boolean,
    canGoLater: Boolean,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = TarryTheme.colors.ink, modifier = Modifier.weight(1f))
        IconCircleButton(R.drawable.ic_minus, earlierDescription, onEarlier, enabled = canGoEarlier, containerColor = TarryTheme.colors.inset)
        Text(
            text = slotTime(slot),
            style = MaterialTheme.typography.titleMedium,
            color = TarryTheme.colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 64.dp),
        )
        IconCircleButton(R.drawable.ic_plus, laterDescription, onLater, enabled = canGoLater, containerColor = TarryTheme.colors.inset)
    }
}

/** The day's single range as (start, end exclusive), or 07:00 to 17:00 when the day is off or has several ranges. */
private fun initialHours(slots: List<Boolean>): Pair<Int, Int> {
    val first = slots.indexOfFirst { it }
    val last = slots.indexOfLast { it }
    return if (first < 0 || slots.subList(first, last + 1).any { !it }) 14 to 34 else first to last + 1
}
