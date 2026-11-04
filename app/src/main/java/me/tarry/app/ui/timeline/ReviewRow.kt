package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.components.FadingScrollRow
import me.tarry.app.ui.theme.TarryTheme

private val MinTouch = 48.dp
private val MarkSize = 20.dp
private val RingWidth = 2.dp

/**
 * The row under the week strip: the labelled Timeline | Map toggle first, then "Add job", then the "Needs review"
 * switch (only when [showToggle], and not on the map). It is one row that scrolls sideways at large font scales
 * (edges fade while more lies that way) and never wraps or drops a label.
 */
@Composable
internal fun ReviewRow(
    reviewOnly: Boolean,
    showToggle: Boolean,
    onReviewOnlyChange: (Boolean) -> Unit,
    onAddJob: () -> Unit,
    modifier: Modifier = Modifier,
    showMap: Boolean = false,
    onShowMapChange: (Boolean) -> Unit = {},
) {
    FadingScrollRow(
        modifier = modifier.fillMaxWidth().testTag("timeline_review_row"),
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewToggle(showMap, onShowMapChange, Modifier.testTag("timeline_view_toggle"))
        AddJobButton(onAddJob, Modifier.testTag("timeline_add_job"))
        // The filter dims canvas blocks, so it means nothing on the map.
        if (showToggle && !showMap) NeedsReviewToggle(reviewOnly, onReviewOnlyChange, Modifier.testTag("timeline_filter_review"))
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

/** Switch pill: `ink` fill with a check when on, `card` fill with a hollow ring when off (shape as well as colour). */
@Composable
private fun NeedsReviewToggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = TarryTheme.colors
    val content = if (on) c.onInk else c.ink
    Row(
        modifier = modifier
            .heightIn(min = MinTouch)
            .clip(TarryTheme.shapes.pill)
            .background(if (on) c.ink else c.card)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = TarryTheme.space.l, vertical = TarryTheme.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        if (on) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = content, modifier = Modifier.size(MarkSize))
        } else {
            Box(Modifier.size(MarkSize).border(RingWidth, content, CircleShape))
        }
        Text(text = stringResource(R.string.timeline_filter_needs_review), style = MaterialTheme.typography.labelLarge, color = content)
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
