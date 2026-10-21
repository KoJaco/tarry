package me.tarry.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.theme.TarryTheme

private val MinTouch = 48.dp

/**
 * Selectable pill. Selected = `ink` fill with `onInk` text; unselected = [unselectedColor] fill
 * (`card` on paper by default, pass `TarryTheme.colors.inset` on a card).
 */
@Composable
fun TarryChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    unselectedColor: Color = TarryTheme.colors.card,
) {
    val c = TarryTheme.colors
    val container = if (selected) c.ink else unselectedColor
    val content = when {
        !enabled -> c.faint
        selected -> c.onInk
        else -> c.ink
    }
    Box(
        modifier = modifier
            .heightIn(min = MinTouch)
            .clip(TarryTheme.shapes.pill)
            .background(container)
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
            .padding(horizontal = TarryTheme.space.l, vertical = TarryTheme.space.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/** A `card` track with an `ink` pill for the selected option. Each option is a tab. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TarryTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(TarryTheme.shapes.pill)
            .background(c.card)
            .padding(TarryTheme.space.xs)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.xs),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouch)
                    .clip(TarryTheme.shapes.pill)
                    .background(if (selected) c.ink else c.card)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = TarryTheme.space.s, vertical = TarryTheme.space.s),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) c.onInk else c.ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 48 dp round icon button. [contentDescription] is required: the button has no visible label. */
@Composable
fun IconCircleButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = TarryTheme.colors.card,
) {
    val c = TarryTheme.colors
    Box(
        modifier = modifier
            .size(MinTouch)
            .clip(CircleShape)
            .background(containerColor)
            .semantics { this.contentDescription = contentDescription }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (enabled) c.ink else c.faint,
            modifier = Modifier.size(24.dp),
        )
    }
}

@TarryPreviews
@Composable
private fun ControlsPreview() = PreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TarryChip("Selected", selected = true, onClick = {})
            TarryChip("Other", selected = false, onClick = {})
        }
        SegmentedControl(listOf("System", "Light", "Dark"), selectedIndex = 1, onSelect = {})
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconCircleButton(R.drawable.ic_chevron_left, "Previous day", {})
            IconCircleButton(R.drawable.ic_chevron_right, "Next day", {}, enabled = false)
        }
    }
}
