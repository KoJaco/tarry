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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.theme.TarryTheme

private val RowMinHeight = 56.dp
private val IconCircleSize = 40.dp

/** Chevron variant: a tappable row that opens something. */
@Composable
fun SettingsRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
) {
    RowFrame(
        modifier = modifier.clip(TarryTheme.shapes.card).clickable(role = Role.Button, onClick = onClick),
        title = title,
        subtitle = subtitle,
        icon = icon,
    ) { Chevron() }
}

/**
 * Value variant: shows [value] on the right. Tappable (with a chevron) when [onClick] is set,
 * otherwise a read-only row.
 */
@Composable
fun SettingsValueRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    @DrawableRes icon: Int? = null,
) {
    val frameModifier = if (onClick != null) {
        modifier.clip(TarryTheme.shapes.card).clickable(role = Role.Button, onClick = onClick)
    } else {
        modifier.semantics(mergeDescendants = true) {}
    }
    RowFrame(frameModifier, title, subtitle = null, icon = icon) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TarryTheme.colors.muted,
        )
        if (onClick != null) Chevron()
    }
}

/** Switch variant: the whole row toggles; the [Switch] itself is non-interactive. */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val colors = TarryTheme.colors
    RowFrame(
        modifier = modifier.clip(TarryTheme.shapes.card).toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        title = title,
        subtitle = subtitle,
        icon = icon,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.ink,
                checkedThumbColor = colors.paper,
                checkedBorderColor = colors.ink,
                uncheckedTrackColor = colors.inset,
                uncheckedThumbColor = colors.paper,
                uncheckedBorderColor = colors.outline,
                disabledCheckedTrackColor = colors.faint,
                disabledCheckedThumbColor = colors.paper,
                disabledCheckedBorderColor = colors.faint,
                disabledUncheckedTrackColor = colors.inset,
                disabledUncheckedThumbColor = colors.paper,
                disabledUncheckedBorderColor = colors.faint,
            ),
        )
    }
}

@Composable
private fun RowFrame(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    icon: Int?,
    trailing: @Composable () -> Unit,
) {
    val space = TarryTheme.space
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TarryTheme.colors.card)
            .heightIn(min = RowMinHeight)
            .padding(horizontal = space.m, vertical = space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.m),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.size(IconCircleSize).clip(CircleShape).background(TarryTheme.colors.inset),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = TarryTheme.colors.ink,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TarryTheme.colors.ink)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
            }
        }
        trailing()
    }
}

@Composable
private fun Chevron() {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = TarryTheme.colors.muted,
        modifier = Modifier.size(24.dp),
    )
}

@TarryPreviews
@Composable
private fun SettingsRowPreview() = PreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsRow("Permissions", onClick = {}, icon = R.drawable.ic_permissions)
        SettingsValueRow("Stop radius", "100 m", onClick = {}, icon = R.drawable.ic_radius)
        SettingsValueRow("Rounding", "15 min")
        SettingsSwitchRow("Dark mode", checked = true, onCheckedChange = {}, icon = R.drawable.ic_theme)
        SettingsSwitchRow("Light mode", checked = false, onCheckedChange = {}, icon = R.drawable.ic_theme)
    }
}
