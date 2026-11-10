package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.components.FadingScrollRow
import me.tarry.app.ui.theme.TarryTheme

private val MinTouch = 48.dp
private val MarkSize = 20.dp

/**
 * The row under the week strip: the labelled Timeline | Map toggle first, then "Add job" stretched to the content edge. (The needs-review filter is the
 * chip in the summary card.) It is one row that scrolls sideways at large font scales
 * (edges fade while more lies that way) and never wraps or drops a label.
 */
@Composable
internal fun ReviewRow(
    onAddJob: () -> Unit,
    modifier: Modifier = Modifier,
    showMap: Boolean = false,
    onShowMapChange: (Boolean) -> Unit = {},
) {
    if (stackedTimeLabels(LocalDensity.current.fontScale)) {
        // Large font: "Add job" sits on its own line, fully labelled, instead of hiding off the end of the scrolling row.
        Column(
            modifier = modifier.fillMaxWidth().testTag("timeline_review_row"),
            verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
        ) {
            FadingScrollRow(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ViewToggle(showMap, onShowMapChange, Modifier.testTag("timeline_view_toggle"))
            }
            AddJobButton(onAddJob, Modifier.fillMaxWidth().testTag("timeline_add_job"))
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val viewport = maxWidth
        FadingScrollRow(
            modifier = Modifier.fillMaxWidth().testTag("timeline_review_row"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FillToWidth(viewport, TarryTheme.space.s) {
                ViewToggle(showMap, onShowMapChange, Modifier.testTag("timeline_view_toggle"))
                AddJobButton(onAddJob, Modifier.testTag("timeline_add_job"))
            }
        }
    }
}

/**
 * Two children side by side; the second stretches to end at [width] (so "Add job" lines up with the content edge) but never
 * shrinks below its natural width. When both don't fit, the row is wider than [width] and the parent scrolls.
 */
@Composable
private fun FillToWidth(width: Dp, gap: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, _ ->
        val first = measurables[0].measure(Constraints())
        val gapPx = gap.roundToPx()
        val natural = measurables[1].maxIntrinsicWidth(Constraints.Infinity)
        val secondWidth = maxOf(natural, width.roundToPx() - first.width - gapPx)
        val second = measurables[1].measure(Constraints.fixedWidth(secondWidth))
        val height = maxOf(first.height, second.height)
        layout(first.width + gapPx + second.width, height) {
            first.place(0, (height - first.height) / 2)
            second.place(first.width + gapPx, (height - second.height) / 2)
        }
    }
}

/** Timeline | Map: a `card` track with an `ink` pill on the selected view. Each half is an icon and a label, at least 48 dp tall. */
@Composable
private fun ViewToggle(showMap: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = TarryTheme.colors
    Row(
        modifier = modifier
            .clip(TarryTheme.shapes.pill)
            .background(c.card)
            .selectableGroup(),
    ) {
        listOf(false to (R.string.timeline_view_day to R.drawable.ic_calendar), true to (R.string.timeline_view_map to R.drawable.ic_map)).forEach { (map, labelAndIcon) ->
            val (labelRes, icon) = labelAndIcon
            val selected = map == showMap
            Row(
                modifier = Modifier
                    .heightIn(min = MinTouch)
                    .widthIn(min = MinTouch)
                    .clip(TarryTheme.shapes.pill)
                    .background(if (selected) c.ink else c.card)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onChange(map) })
                    .padding(horizontal = TarryTheme.space.l),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = if (selected) c.onInk else c.ink,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) c.onInk else c.ink,
                )
            }
        }
    }
}

/** `+` icon and "Add job" on a `card` pill, at least 48 dp tall. */
@Composable
private fun AddJobButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = TarryTheme.colors
    val label = stringResource(R.string.timeline_add_job)
    Row(
        modifier = modifier
            .heightIn(min = MinTouch)
            .widthIn(min = MinTouch)
            .clip(TarryTheme.shapes.pill)
            .background(c.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = TarryTheme.space.l, vertical = TarryTheme.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s, Alignment.CenterHorizontally),
    ) {
        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = c.ink, modifier = Modifier.size(MarkSize))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = c.ink)
    }
}
