package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.theme.TarryTheme

private val MinTouch = 48.dp
private val MarkSize = 20.dp
private val RingWidth = 2.dp

/**
 * The row under the week strip: the compact Day | Map toggle first, then the "Needs review" switch (only when
 * [showToggle], and not on the map) and "New visit". "New visit" drops its label to a `+` icon when the labelled row
 * would not fit on one line; past that (large font scales) the row wraps instead of shrinking or truncating.
 */
@Composable
internal fun ReviewRow(
    reviewOnly: Boolean,
    showToggle: Boolean,
    onReviewOnlyChange: (Boolean) -> Unit,
    onNewVisit: () -> Unit,
    modifier: Modifier = Modifier,
    showMap: Boolean = false,
    onShowMapChange: (Boolean) -> Unit = {},
) {
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        // The fully labelled row, measured unconstrained; its semantics are cleared so it never reaches TalkBack or tests.
        val labelledWidth = subcompose("probe") {
            ReviewRowItems(reviewOnly, showToggle, {}, {}, showMap, {}, compact = false, Modifier.clearAndSetSemantics {})
        }.first().measure(Constraints()).width
        val row = subcompose("row") {
            ReviewRowItems(
                reviewOnly, showToggle, onReviewOnlyChange, onNewVisit, showMap, onShowMapChange,
                compact = labelledWidth > constraints.maxWidth,
            )
        }.first().measure(constraints.copy(minWidth = 0))
        layout(constraints.maxWidth, row.height) { row.place(0, 0) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewRowItems(
    reviewOnly: Boolean,
    showToggle: Boolean,
    onReviewOnlyChange: (Boolean) -> Unit,
    onNewVisit: () -> Unit,
    showMap: Boolean,
    onShowMapChange: (Boolean) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
        verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ViewToggle(showMap, onShowMapChange, Modifier.testTag("timeline_view_toggle"))
        // The filter dims canvas blocks, so it means nothing on the map.
        if (showToggle && !showMap) NeedsReviewToggle(reviewOnly, onReviewOnlyChange, Modifier.testTag("timeline_filter_review"))
        NewVisitButton(onNewVisit, compact, Modifier.testTag("timeline_new_visit"))
    }
}

/** Day | Map: a compact `card` track with an `ink` pill on the selected view. Each half is a 48 dp tab. */
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
            val (label, icon) = labelAndIcon
            val description = stringResource(label)
            val selected = map == showMap
            Box(
                modifier = Modifier
                    .heightIn(min = MinTouch)
                    .widthIn(min = MinTouch)
                    .clip(TarryTheme.shapes.pill)
                    .background(if (selected) c.ink else c.card)
                    .semantics { contentDescription = description }
                    .selectable(selected = selected, role = Role.Tab, onClick = { onChange(map) })
                    .padding(horizontal = TarryTheme.space.m),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = if (selected) c.onInk else c.ink,
                    modifier = Modifier.size(24.dp),
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

/** `+` icon and "New visit" on a `card` pill. [compact] is a 48 dp `+` circle that keeps the label as its description. */
@Composable
private fun NewVisitButton(onClick: () -> Unit, compact: Boolean, modifier: Modifier = Modifier) {
    val c = TarryTheme.colors
    val label = stringResource(R.string.timeline_new_visit)
    Row(
        modifier = modifier
            .heightIn(min = MinTouch)
            .widthIn(min = MinTouch)
            .clip(TarryTheme.shapes.pill)
            .background(c.card)
            .then(if (compact) Modifier.semantics { contentDescription = label } else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 0.dp else TarryTheme.space.l, vertical = TarryTheme.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s, Alignment.CenterHorizontally),
    ) {
        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = c.ink, modifier = Modifier.size(MarkSize))
        if (!compact) Text(text = label, style = MaterialTheme.typography.labelLarge, color = c.ink)
    }
}
