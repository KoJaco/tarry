package me.tarry.app.ui.shell

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.components.PreviewSurface
import me.tarry.app.ui.components.TarryPreviews
import me.tarry.app.ui.theme.TarryTheme

/** Test tags for the three equal-width slots. */
object BottomBarTags {
    const val Bar = "bottomBar"
    const val TimelineSlot = "bottomBar.timeline"
    const val ToggleSlot = "bottomBar.toggle"
    const val SettingsSlot = "bottomBar.settings"
}

private val SlotMinHeight = 56.dp
private val TopBorder = 1.dp

/**
 * Stateless full-width bottom bar: Timeline (left), tracking toggle (centre), Settings (right).
 * `paper` background with a 1 dp `line` top border only; nav-bar insets are applied inside.
 */
@Composable
fun TarryBottomBar(
    trackingState: TrackingState,
    timelineSelected: Boolean,
    settingsSelected: Boolean,
    onTimelineClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onToggleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TarryTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.paper)
            .drawBehind {
                val stroke = TopBorder.toPx()
                drawLine(
                    color = colors.line,
                    start = Offset(0f, stroke / 2),
                    end = Offset(size.width, stroke / 2),
                    strokeWidth = stroke,
                )
            }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = TarryTheme.space.xs, vertical = TarryTheme.space.xs)
            .testTag(BottomBarTags.Bar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slot(BottomBarTags.TimelineSlot) {
            NavPill(
                icon = R.drawable.ic_timeline,
                label = stringResource(R.string.nav_timeline),
                selected = timelineSelected,
                onClick = onTimelineClick,
            )
        }
        Slot(BottomBarTags.ToggleSlot) {
            TrackingToggle(state = trackingState, onClick = onToggleClick)
        }
        Slot(BottomBarTags.SettingsSlot) {
            NavPill(
                icon = R.drawable.ic_settings,
                label = stringResource(R.string.nav_settings),
                selected = settingsSelected,
                onClick = onSettingsClick,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Slot(tag: String, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = SlotMinHeight)
            .padding(horizontal = TarryTheme.space.xs)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Selected: `ink` pill with `onInk` icon and label. Unselected: `muted` on no fill. */
@Composable
private fun NavPill(
    @DrawableRes icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = TarryTheme.colors
    val tint = if (selected) colors.onInk else colors.muted
    Column(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(TarryTheme.shapes.pill)
            .background(if (selected) colors.ink else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = TarryTheme.space.s, vertical = TarryTheme.space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@TarryPreviews
@Composable
private fun TarryBottomBarPreview() = PreviewSurface {
    Column {
        TarryBottomBar(TrackingState.TRACKING, true, false, {}, {}, {})
        TarryBottomBar(TrackingState.ATTENTION, false, true, {}, {}, {})
    }
}
