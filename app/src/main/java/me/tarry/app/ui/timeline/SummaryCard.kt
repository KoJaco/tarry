package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import me.tarry.app.R
import me.tarry.app.ui.components.TarryCard
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.theme.tabular

/** Tracked total, a needs-review chip when visits have no job, and a "3 visits · 1 trip · 1 gap" caption. */
@Composable
fun SummaryCard(
    summary: DaySummary,
    isToday: Boolean,
    modifier: Modifier = Modifier,
    reviewOnly: Boolean = false,
    onReviewOnlyChange: (Boolean) -> Unit = {},
) {
    val c = TarryTheme.colors
    TarryCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("timeline_summary"),
        shape = TarryTheme.shapes.container,
    ) {
        Text(
            text = stringResource(
                if (isToday) R.string.timeline_summary_title_today else R.string.timeline_summary_title_other,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
        )
        Text(
            text = formatDuration(summary.trackedMs),
            style = MaterialTheme.typography.headlineLarge.tabular(),
            color = c.ink,
            modifier = Modifier.testTag("timeline_summary_total"),
        )
        Column(
            modifier = Modifier.padding(top = TarryTheme.space.s),
            verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
        ) {
            if (summary.needsReview > 0) {
                ReviewChip(summary.needsReview, reviewOnly, onReviewOnlyChange)
            }
            val parts = listOfNotNull(
                if (summary.visits > 0) pluralStringResource(R.plurals.timeline_summary_visits, summary.visits, summary.visits) else null,
                if (summary.trips > 0) pluralStringResource(R.plurals.timeline_summary_trips, summary.trips, summary.trips) else null,
                if (summary.gaps > 0) pluralStringResource(R.plurals.timeline_summary_gaps, summary.gaps, summary.gaps) else null,
            )
            if (parts.isNotEmpty()) {
                Text(
                    text = parts.joinToString(stringResource(R.string.timeline_summary_separator)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.muted,
                    modifier = Modifier.testTag("timeline_summary_caption"),
                )
            }
        }
    }
}

/**
 * "N visits need review" doubling as the filter. Off: `accentSoft` pill. On: a leading check and an `accent` fill with
 * `onAccent` text (shape as well as colour). The touch target is 48 dp tall; the pill is drawn inside it.
 */
@Composable
private fun ReviewChip(count: Int, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = TarryTheme.colors
    val content = if (on) c.onAccent else c.onAccentSoft
    Box(
        modifier = Modifier
            .heightIn(min = ChipTouch)
            .toggleable(value = on, role = Role.Checkbox, onValueChange = onChange)
            .testTag("timeline_filter_review"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .clip(TarryTheme.shapes.pill)
                .background(if (on) c.accent else c.accentSoft)
                .padding(horizontal = TarryTheme.space.m, vertical = TarryTheme.space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.xs),
        ) {
            if (on) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = content, modifier = Modifier.size(ChipCheck))
            Text(
                text = pluralStringResource(R.plurals.timeline_summary_needs_review, count, count),
                style = MaterialTheme.typography.labelMedium,
                color = content,
            )
        }
    }
}

private val ChipTouch = 48.dp
private val ChipCheck = 16.dp
