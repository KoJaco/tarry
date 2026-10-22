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
import me.tarry.app.ui.theme.TarryTheme

private val MinTouch = 48.dp
private val MarkSize = 20.dp
private val RingWidth = 2.dp

/**
 * The row under the week strip: the "Needs review" switch (only when [showToggle]) and the "New visit" button. It wraps
 * instead of shrinking or truncating, so both labels stay whole at large font scales.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewRow(
    reviewOnly: Boolean,
    showToggle: Boolean,
    onReviewOnlyChange: (Boolean) -> Unit,
    onNewVisit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
        verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (showToggle) NeedsReviewToggle(reviewOnly, onReviewOnlyChange, Modifier.testTag("timeline_filter_review"))
        NewVisitButton(onNewVisit, Modifier.testTag("timeline_new_visit"))
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

/** `+` icon and "New visit" on a `card` pill. */
@Composable
private fun NewVisitButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = TarryTheme.colors
    Row(
        modifier = modifier
            .heightIn(min = MinTouch)
            .clip(TarryTheme.shapes.pill)
            .background(c.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = TarryTheme.space.l, vertical = TarryTheme.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = c.ink, modifier = Modifier.size(MarkSize))
        Text(text = stringResource(R.string.timeline_new_visit), style = MaterialTheme.typography.labelLarge, color = c.ink)
    }
}
