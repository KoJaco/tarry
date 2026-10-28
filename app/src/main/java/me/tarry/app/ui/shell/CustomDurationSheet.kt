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
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.ceil

private const val MINUTES_PER_HOUR = 60
private const val MS_PER_MINUTE = 60_000.0
private const val DEFAULT_MINUTES = 60

/**
 * What the custom-duration sheet can ask for: a length in minutes from now (stepper), or an exact end instant
 * ("until a time"). When [onStartUntil] / [onPauseUntil] are not given, "until a time" falls back to whole minutes.
 */
data class CustomDurationActions(
    val onStart: (Int) -> Unit,
    val onPause: (Int) -> Unit,
    val onStartUntil: ((Instant) -> Unit)? = null,
    val onPauseUntil: ((Instant) -> Unit)? = null,
)

/**
 * The exact instant of the next occurrence of the local [time] after [now], for "until a time". A time at or before
 * [now] means tomorrow, resolved in [now]'s zone (a time in a skipped DST hour lands after the gap). Capped at 24 h of
 * real time from [now], so a fall-back night cannot push it past a day.
 */
fun untilTime(now: ZonedDateTime, time: LocalTime): Instant {
    var target = now.toLocalDate().atTime(time).atZone(now.zone)
    if (!target.isAfter(now)) target = now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
    return minOf(target.toInstant(), now.toInstant().plus(Duration.ofDays(1)))
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
                val current = nowProvider()
                val until = untilTime(current, LocalTime.of(hour, minute))
                val toUntil = if (pausing) actions.onPauseUntil else actions.onStartUntil
                if (toUntil != null) {
                    toUntil(until)
                } else {
                    val fallback = ceil(Duration.between(current.toInstant(), until).toMillis() / MS_PER_MINUTE).toInt()
                    if (pausing) actions.onPause(fallback) else actions.onStart(fallback)
                }
                onDone()
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
