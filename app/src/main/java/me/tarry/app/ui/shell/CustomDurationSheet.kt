package me.tarry.app.ui.shell

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.tracking.TrackingDetails
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.drawer.TimePickerDialog
import me.tarry.app.ui.theme.TarryTheme
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
private const val DEFAULT_MINUTES = 60

/** What the custom-duration sheet can ask for, in minutes from now. */
data class CustomDurationActions(
    val onStart: (Int) -> Unit,
    val onPause: (Int) -> Unit,
)

/**
 * Minutes from [now] to the next occurrence of the local [time], for "until a time". A time at or before [now]
 * (to the minute) means tomorrow. Real elapsed minutes, so a DST change in between counts; capped at 24 h.
 */
fun minutesUntil(now: ZonedDateTime, time: LocalTime): Int {
    val from = now.truncatedTo(ChronoUnit.MINUTES)
    var target = from.toLocalDate().atTime(time).atZone(from.zone)
    if (!target.isAfter(from)) target = from.toLocalDate().plusDays(1).atTime(time).atZone(from.zone)
    return Duration.between(from, target).toMinutes().toInt().coerceAtMost(MINUTES_PER_DAY)
}

/** Long-press sheet of the toggle: start or pause for a chosen length. Same chrome as [TrackingSheet]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomDurationSheet(
    state: TrackingState,
    details: TrackingDetails,
    stepMin: Int,
    maxMin: Int,
    actions: CustomDurationActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = TarryTheme.shapes.container.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        containerColor = TarryTheme.colors.paper,
    ) {
        CustomDurationSheetContent(state, details, stepMin, maxMin, actions, onDismiss)
    }
}

/**
 * Sheet body without the chrome, so it can be tested directly. The length starts at one hour and moves in [stepMin]
 * steps between one step and [maxMin]; [nowProvider] feeds the "until a time" picker. Every action also calls [onDone].
 */
@Composable
fun CustomDurationSheetContent(
    state: TrackingState,
    details: TrackingDetails,
    stepMin: Int,
    maxMin: Int,
    actions: CustomDurationActions,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    val pausing = state == TrackingState.TRACKING || state == TrackingState.PAUSED
    var minutes by rememberSaveable { mutableIntStateOf(DEFAULT_MINUTES.coerceIn(stepMin, maxMin)) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    val label = durationText(minutes)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = TarryTheme.space.gutter)
            .padding(bottom = TarryTheme.space.l),
        verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        SheetTitle(
            when (state) {
                TrackingState.PAUSED -> R.string.custom_duration_replace_pause_title
                TrackingState.TRACKING -> R.string.custom_duration_pause_title
                else -> R.string.custom_duration_start_title
            },
        )
        if (state == TrackingState.PAUSED) {
            Text(
                text = pausedText(details.pausedUntil),
                style = MaterialTheme.typography.bodyMedium,
                color = TarryTheme.colors.muted,
            )
        }
        Stepper(
            label = label,
            canDecrease = minutes - stepMin >= stepMin,
            canIncrease = minutes + stepMin <= maxMin,
            onDecrease = { minutes = (minutes - stepMin).coerceAtLeast(stepMin) },
            onIncrease = { minutes = (minutes + stepMin).coerceAtMost(maxMin) },
        )
        if (pausing) {
            SheetRow(stringResource(R.string.custom_duration_pause_for, label), onDone, R.drawable.ic_pause) {
                actions.onPause(minutes)
            }
        } else {
            SheetRow(stringResource(R.string.custom_duration_start_for, label), onDone, R.drawable.ic_play_arrow) {
                actions.onStart(minutes)
            }
        }
        // The picker opens on top of the sheet, so this row must not dismiss it.
        SheetRow(stringResource(R.string.custom_duration_until_time), {}, R.drawable.ic_calendar) { pickingTime = true }
    }
    if (pickingTime) {
        val now = nowProvider()
        TimePickerDialog(
            title = stringResource(R.string.custom_duration_time_picker_title),
            initialMs = now.plusMinutes(minutes.toLong()).toInstant().toEpochMilli(),
            zone = now.zone,
            is24Hour = DateFormat.is24HourFormat(LocalContext.current),
            onConfirm = { hour, minute ->
                pickingTime = false
                val picked = minutesUntil(nowProvider(), LocalTime.of(hour, minute))
                if (picked > 0) {
                    if (pausing) actions.onPause(picked) else actions.onStart(picked)
                    onDone()
                }
            },
            onDismiss = { pickingTime = false },
        )
    }
}

@Composable
private fun Stepper(label: String, canDecrease: Boolean, canIncrease: Boolean, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    val colors = TarryTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TarryTheme.shapes.card)
            .background(colors.card)
            .heightIn(min = 56.dp)
            .padding(horizontal = TarryTheme.space.s, vertical = TarryTheme.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        StepButton(R.drawable.ic_minus, R.string.custom_duration_shorter, canDecrease, onDecrease)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = colors.ink, textAlign = TextAlign.Center)
        }
        StepButton(R.drawable.ic_plus, R.string.custom_duration_longer, canIncrease, onIncrease)
    }
}

@Composable
private fun StepButton(icon: Int, descriptionRes: Int, enabled: Boolean, onClick: () -> Unit) {
    val colors = TarryTheme.colors
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = colors.inset,
            contentColor = colors.ink,
            disabledContainerColor = colors.inset,
            disabledContentColor = colors.muted,
        ),
    ) {
        Icon(painter = painterResource(icon), contentDescription = stringResource(descriptionRes), modifier = Modifier.size(24.dp))
    }
}

/** "45 min", "2 h" or "1 h 15 min". */
@Composable
private fun durationText(minutes: Int): String {
    val hours = minutes / MINUTES_PER_HOUR
    val rest = minutes % MINUTES_PER_HOUR
    return when {
        hours == 0 -> stringResource(R.string.custom_duration_minutes, rest)
        rest == 0 -> stringResource(R.string.custom_duration_hours, hours)
        else -> stringResource(R.string.custom_duration_hours_minutes, hours, rest)
    }
}
