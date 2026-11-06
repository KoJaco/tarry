package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.theme.surfaceEdge

/** Touch size of each zoom button. */
internal val ZoomButtonSize = 48.dp

/** At rest, how far the pair rises into the day card's 16 dp top padding (leaving 4 dp above the buttons). */
internal val ZoomRestLift = 12.dp

/** How far right the pair moves into the day card's 8 dp side padding: flush with the card edge. */
internal val ZoomRestShift = 8.dp

private const val DisabledAlpha = 0.38f

/**
 * The "−" and "+" pair pinned to the top-right of the day canvas: a `card` pill (with the dark-mode `line` edge) of two
 * 48 dp icon buttons. Each tap steps one level; a button is disabled at its limit. This is the single-pointer
 * alternative to the pinch (WCAG 2.5.1). It is read first in the canvas's traversal group.
 */
@Composable
internal fun ZoomControls(level: ZoomLevel, onLevelChange: (ZoomLevel) -> Unit, modifier: Modifier = Modifier) {
    val c = TarryTheme.colors
    val state = stringResource(R.string.timeline_zoom_state, level.tickMinutes)
    val out = level.zoomedOut()
    val finer = level.zoomedIn()
    Row(
        modifier = modifier
            .clip(TarryTheme.shapes.pill)
            .background(c.card)
            .surfaceEdge(TarryTheme.shapes.pill)
            .semantics {
                isTraversalGroup = true
                traversalIndex = -1f
            },
    ) {
        ZoomStepButton(R.drawable.ic_zoom_out, R.string.timeline_zoom_out, state, out != null, { out?.let(onLevelChange) }, Modifier.testTag("timeline_zoom_out"))
        ZoomStepButton(R.drawable.ic_zoom_in, R.string.timeline_zoom_in, state, finer != null, { finer?.let(onLevelChange) }, Modifier.testTag("timeline_zoom_in"))
    }
}

@Composable
private fun ZoomStepButton(icon: Int, label: Int, state: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = TarryTheme.colors
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(ZoomButtonSize).semantics { stateDescription = state },
        colors = IconButtonDefaults.iconButtonColors(contentColor = c.ink, disabledContentColor = c.ink.copy(alpha = DisabledAlpha)),
    ) {
        Icon(painterResource(icon), contentDescription = stringResource(label), modifier = Modifier.size(24.dp))
    }
}
