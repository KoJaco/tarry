package me.tarry.app.ui.shell

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.components.PreviewSurface
import me.tarry.app.ui.components.TarryPreviews
import me.tarry.app.ui.components.rememberReducedMotion
import me.tarry.app.ui.theme.TarryTheme

/** Test tags for the four slots (three equal nav slots and a wider toggle) and the gliding icon backing. */
object BottomBarTags {
    const val Bar = "bottomBar"
    const val TimelineSlot = "bottomBar.timeline"
    const val SummarySlot = "bottomBar.summary"
    const val ToggleSlot = "bottomBar.toggle"
    const val SettingsSlot = "bottomBar.settings"
    const val Indicator = "bottomBar.indicator"
}


private val SlotMinHeight = 56.dp
private val TopBorder = 1.dp
private val IconBackingWidth = 64.dp
private val IconBackingHeight = 32.dp
private val IconLabelGap = 6.dp
private const val GlideMillis = 350

/** The toggle slot is wider than a nav slot so its label still fits at large font sizes. */
private const val ToggleSlotWeight = 1.5f
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Stateless full-width bottom bar: Timeline, Summary, Settings, then the tracking toggle at the right end.
 * `paper` background with a 1 dp `line` top border only; nav-bar insets are applied inside.
 *
 * Nav items are an icon over a label. Only the selected icon is backed, by a single `accentSoft` indicator that glides
 * between the three nav icons (it jumps under reduced motion). When none is selected (Sessions, Permissions) the
 * indicator is hidden.
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
    onToggleLongClick: () -> Unit = {},
    summarySelected: Boolean = false,
    onSummaryClick: () -> Unit = {},
) {
    val colors = TarryTheme.colors
    var containerOrigin by remember { mutableStateOf<Offset?>(null) }
    var timelineIcon by remember { mutableStateOf<Offset?>(null) }
    var summaryIcon by remember { mutableStateOf<Offset?>(null) }
    var settingsIcon by remember { mutableStateOf<Offset?>(null) }
    val target = when {
        timelineSelected -> timelineIcon
        summarySelected -> summaryIcon
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
            Slot(BottomBarTags.SummarySlot) {
                NavItem(
                    icon = R.drawable.ic_summary,
                    label = stringResource(R.string.nav_summary),
                    selected = summarySelected,
                    onClick = onSummaryClick,
                    onIconPositioned = { summaryIcon = it },
                )
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
            Slot(BottomBarTags.ToggleSlot, weight = ToggleSlotWeight, horizontalPadding = TarryTheme.space.xs) {
                TrackingToggle(state = trackingState, onClick = onToggleClick, onLongClick = onToggleLongClick)
            }
        }
    }
}

/**
 * The single `accentSoft` backing behind the selected icon. It is composed only while a nav item is selected, so it
 * appears in place (no glide from a stale position) and then glides on selection changes: 350 ms, emphasized easing.
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
            .clip(TarryTheme.shapes.pill)
            .background(TarryTheme.colors.accentSoft)
            .testTag(BottomBarTags.Indicator),
    )
}

@Composable
private fun RowScope.Slot(tag: String, weight: Float = 1f, horizontalPadding: Dp = 0.dp, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .weight(weight)
            .heightIn(min = SlotMinHeight)
            .padding(horizontal = horizontalPadding)
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
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(TarryTheme.shapes.pill)
            .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onClick()
                },
            )
            .padding(horizontal = TarryTheme.space.xs, vertical = TarryTheme.space.xs),
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
