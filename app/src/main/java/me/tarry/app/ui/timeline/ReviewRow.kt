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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.components.FadingScrollRow
import me.tarry.app.ui.theme.TarryTheme

private val MinTouch = 48.dp
private val MarkSize = 20.dp
private val RingWidth = 2.dp

/**
 * The row under the week strip: the labelled Timeline | Map toggle first, then "Add job", then "Zoom" (not on the map), then the "Needs review"
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
    zoomLevel: ZoomLevel = ZoomLevel.Q15,
    onZoomLevelChange: (ZoomLevel) -> Unit = {},
) {
    FadingScrollRow(
        modifier = modifier.fillMaxWidth().testTag("timeline_review_row"),
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewToggle(showMap, onShowMapChange, Modifier.testTag("timeline_view_toggle"))
        AddJobButton(onAddJob, Modifier.testTag("timeline_add_job"))
        // The zoom scales the day canvas, so it means nothing on the map.
        if (!showMap) ZoomButton(zoomLevel, onZoomLevelChange, Modifier.testTag("timeline_zoom"))
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

/**
 * Zoom pill: a magnifier, the current step ("15 min") and a down chevron on a `card` pill, at least 48 dp tall. It opens
 * a menu of the three steps with the current one checked. This is the single-pointer alternative to the pinch (WCAG 2.5.1).
 */
@Composable
private fun ZoomButton(level: ZoomLevel, onLevelChange: (ZoomLevel) -> Unit, modifier: Modifier = Modifier) {
    val c = TarryTheme.colors
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.timeline_zoom_description, level.tickMinutes)
    Box(modifier) {
        Row(
            modifier = Modifier
                .heightIn(min = MinTouch)
                .widthIn(min = MinTouch)
                .clip(TarryTheme.shapes.pill)
                .background(c.card)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick { open = true; true }
                }
                .clickable(role = Role.Button, onClick = { open = true })
                .padding(horizontal = TarryTheme.space.l, vertical = TarryTheme.space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s, Alignment.CenterHorizontally),
        ) {
            Icon(painterResource(R.drawable.ic_zoom_in), contentDescription = null, tint = c.ink, modifier = Modifier.size(MarkSize))
            Text(text = stringResource(R.string.timeline_zoom_label, level.tickMinutes), style = MaterialTheme.typography.labelLarge, color = c.ink)
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = c.ink, modifier = Modifier.size(MarkSize).rotate(90f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ZoomLevel.entries.forEach { item ->
                val selected = item == level
                val label = stringResource(R.string.timeline_zoom_label, item.tickMinutes)
                val selectedLabel = stringResource(R.string.timeline_zoom_item_selected, item.tickMinutes)
                DropdownMenuItem(
                    text = { Text(label, style = MaterialTheme.typography.labelLarge) },
                    leadingIcon = {
                        if (selected) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(MarkSize))
                        } else {
                            Box(Modifier.size(MarkSize))
                        }
                    },
                    onClick = {
                        open = false
                        onLevelChange(item)
                    },
                    modifier = Modifier
                        .heightIn(min = MinTouch)
                        .testTag("timeline_zoom_${item.tickMinutes}")
                        .semantics { if (selected) contentDescription = selectedLabel },
                )
            }
        }
    }
}
