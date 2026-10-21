package me.tarry.app.ui.shell

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.components.PreviewSurface
import me.tarry.app.ui.components.TarryPreviews
import me.tarry.app.ui.components.rememberReducedMotion
import me.tarry.app.ui.theme.TarryTheme

/** Test tags for the three equal-width slots and the gliding icon backing. */
object BottomBarTags {
    const val Bar = "bottomBar"
    const val TimelineSlot = "bottomBar.timeline"
    const val ToggleSlot = "bottomBar.toggle"
    const val SettingsSlot = "bottomBar.settings"
    const val Indicator = "bottomBar.indicator"
}

private val SlotMinHeight = 56.dp
private val TopBorder = 1.dp
private val IconBackingWidth = 56.dp
private val IconBackingHeight = 32.dp
private val IconLabelGap = 6.dp
private const val GlideMillis = 200
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Stateless full-width bottom bar: Timeline (left), tracking toggle (centre), Settings (right).
 * `paper` background with a 1 dp `line` top border only; nav-bar insets are applied inside.
 *
 * Nav items are an icon over a label. Only the selected icon is backed, by a single `inset` indicator that glides
 * between the Timeline and Settings icons across the toggle slot (it jumps under reduced motion). When neither is
 * selected (Sessions, Permissions) the indicator is hidden.
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
    var containerOrigin by remember { mutableStateOf<Offset?>(null) }
    var timelineIcon by remember { mutableStateOf<Offset?>(null) }
    var settingsIcon by remember { mutableStateOf<Offset?>(null) }
    val target = when {
        timelineSelected -> timelineIcon
        settingsSelected -> settingsIcon
        else -> null
    }
    val origin = containerOrigin
    Box(
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
            .onGloballyPositioned { containerOrigin = it.positionInRoot() }
            .testTag(BottomBarTags.Bar),
    ) {
        if (target != null && origin != null) {
            IconBackingIndicator(x = target.x - origin.x, y = target.y - origin.y)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(horizontal = TarryTheme.space.xs, vertical = TarryTheme.space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slot(BottomBarTags.TimelineSlot) {
                NavItem(
                    icon = R.drawable.ic_timeline,
                    label = stringResource(R.string.nav_timeline),
                    selected = timelineSelected,
                    onClick = onTimelineClick,
                    onIconPositioned = { timelineIcon = it },
                )
            }
            Slot(BottomBarTags.ToggleSlot) {
                TrackingToggle(state = trackingState, onClick = onToggleClick)
            }
            Slot(BottomBarTags.SettingsSlot) {
                NavItem(
                    icon = R.drawable.ic_settings,
                    label = stringResource(R.string.nav_settings),
                    selected = settingsSelected,
                    onClick = onSettingsClick,
                    onIconPositioned = { settingsIcon = it },
                )
            }
        }
    }
}

/**
 * The single `inset` backing behind the selected icon. It is composed only while a nav item is selected, so it
 * appears in place (no glide from a stale position) and then glides on selection changes: 200 ms, emphasized easing.
 */
@Composable
private fun IconBackingIndicator(x: Float, y: Float) {
    val density = LocalDensity.current
    val reduced = rememberReducedMotion()
    val animatedX by animateDpAsState(
        targetValue = with(density) { x.toDp() },
        animationSpec = if (reduced) snap() else tween(GlideMillis, easing = EmphasizedEasing),
        label = "navIndicatorX",
    )
    Box(
        modifier = Modifier
            .offset { IntOffset(animatedX.roundToPx(), y.toInt()) }
            .size(IconBackingWidth, IconBackingHeight)
            .clip(TarryTheme.shapes.card)
            .background(TarryTheme.colors.inset)
            .testTag(BottomBarTags.Indicator),
    )
}

@Composable
private fun RowScope.Slot(tag: String, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = SlotMinHeight)
            .padding(horizontal = TarryTheme.space.xs)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Icon over label. Selected: `ink` icon and label (the icon is backed by the bar's indicator). Unselected: `muted`. */
@Composable
private fun NavItem(
    @DrawableRes icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onIconPositioned: (Offset) -> Unit,
) {
    val colors = TarryTheme.colors
    val tint = if (selected) colors.ink else colors.muted
    Column(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(TarryTheme.shapes.pill)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = TarryTheme.space.s, vertical = TarryTheme.space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(IconBackingWidth, IconBackingHeight)
                .onGloballyPositioned { onIconPositioned(it.positionInRoot()) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(IconLabelGap))
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
