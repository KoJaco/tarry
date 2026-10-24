package me.tarry.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.tarry.app.R
import me.tarry.app.ui.components.EmptyState
import me.tarry.app.ui.components.LoadingBlocks
import me.tarry.app.ui.components.TarryCard
import me.tarry.app.ui.theme.TarryTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val PlaceRowMinHeight = 64.dp
private const val RELATIVE_DAYS = 7

/** Hilt-wired Places list, pushed over Settings. */
@Composable
fun PlacesScreen(onOpenPlace: (String) -> Unit, modifier: Modifier = Modifier, viewModel: PlacesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlacesContent(state = state, onOpenPlace = onOpenPlace, modifier = modifier)
}

/** Stateless Places list. [nowMs] and [zone] only decide how "last visit" reads. */
@Composable
fun PlacesContent(
    state: PlacesUiState?,
    onOpenPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val space = TarryTheme.space
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = space.gutter, vertical = space.l),
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
        Text(
            text = stringResource(R.string.places_title),
            style = MaterialTheme.typography.headlineMedium,
            color = TarryTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        when {
            state == null -> LoadingBlocks(count = 3)
            state.rows.isEmpty() -> EmptyState(stringResource(R.string.places_empty_title), stringResource(R.string.places_empty_body))
            else -> TarryCard(contentPadding = PaddingValues(0.dp)) {
                state.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(thickness = 1.dp, color = TarryTheme.colors.divider)
                    PlaceListRow(row, nowMs, zone) { onOpenPlace(row.id) }
                }
            }
        }
    }
}

@Composable
private fun PlaceListRow(row: PlaceRow, nowMs: Long, zone: ZoneId, onClick: () -> Unit) {
    val space = TarryTheme.space
    val subtitle = if (row.visitCount == 0 || row.lastVisitAt == null) {
        stringResource(R.string.places_no_visits)
    } else {
        stringResource(
            R.string.places_visits_last,
            pluralStringResource(R.plurals.places_visit_count, row.visitCount, row.visitCount),
            relativeDay(row.lastVisitAt, nowMs, zone),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = PlaceRowMinHeight)
            .padding(horizontal = space.l, vertical = space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.m),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.titleSmall, color = TarryTheme.colors.ink)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
        }
        if (row.ignored) {
            Text(
                stringResource(R.string.places_ignored_chip),
                style = MaterialTheme.typography.labelMedium,
                color = TarryTheme.colors.ink,
                modifier = Modifier
                    .background(TarryTheme.colors.inset, TarryTheme.shapes.pill)
                    .padding(horizontal = space.m, vertical = space.xs),
            )
        }
    }
}

/** "today", "yesterday", "3 days ago", else a date like "4 Oct" (with the year when it is not this year). */
@Composable
private fun relativeDay(epochMs: Long, nowMs: Long, zone: ZoneId): String {
    val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today).toInt()
    return when {
        days <= 0 -> stringResource(R.string.places_last_today)
        days == 1 -> stringResource(R.string.places_last_yesterday)
        days < RELATIVE_DAYS -> pluralStringResource(R.plurals.places_last_days_ago, days, days)
        else -> DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMM" else "d MMM yyyy", androidx.compose.ui.text.intl.Locale.current.platformLocale).format(day)
    }
}
