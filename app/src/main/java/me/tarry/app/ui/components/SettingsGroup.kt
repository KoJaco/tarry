package me.tarry.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.theme.TarryTheme

private val GroupedRowMinHeight = 64.dp
private val GroupedIconSize = 24.dp

/** Test tag of the 1 dp divider between two rows of a [SettingsGroup]. */
const val SettingsGroupDividerTag = "settings_group_divider"

/** Builder for the rows of a [SettingsGroup]. */
class SettingsGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    /**
     * One row. Tappable (Role.Button, with a chevron) when [onClick] is set, otherwise read-only. [subtitle] is a
     * `muted` line under the title; [value] is a `muted` value on the right (for read-only label/value rows).
     */
    fun row(
        title: String,
        onClick: (() -> Unit)? = null,
        @DrawableRes icon: Int? = null,
        subtitle: String? = null,
        value: String? = null,
    ) {
        rows += { GroupedSettingsRow(title, onClick, icon, subtitle, value) }
    }
}

/**
 * Settings rows on ONE `card` surface, separated by 1 dp `divider` rules running edge to edge (no inset). The default for a settings group; the standalone [SettingsRow] stays for rows that aren't a group.
 */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: SettingsGroupScope.() -> Unit) {
    val scope = SettingsGroupScope().apply(content)
    TarryCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        scope.rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.testTag(SettingsGroupDividerTag),
                    thickness = 1.dp,
                    color = TarryTheme.colors.divider,
                )
            }
            row()
        }
    }
}

@Composable
private fun GroupedSettingsRow(
    title: String,
    onClick: (() -> Unit)?,
    @DrawableRes icon: Int?,
    subtitle: String?,
    value: String?,
) {
    val space = TarryTheme.space
    val frame = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    Row(
        modifier = frame
            .fillMaxWidth()
            .heightIn(min = GroupedRowMinHeight)
            .padding(horizontal = space.l, vertical = space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.l),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = TarryTheme.colors.ink,
                modifier = Modifier.size(GroupedIconSize),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TarryTheme.colors.ink)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
            }
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
        }
        if (onClick != null) Chevron()
    }
}

@TarryPreviews
@Composable
private fun SettingsGroupPreview() = PreviewSurface {
    SettingsGroup {
        row("Stop radius", onClick = {}, icon = R.drawable.ic_radius, subtitle = "100 m")
        row("Permissions", onClick = {}, icon = R.drawable.ic_permissions)
        row("Sessions", onClick = {}, icon = R.drawable.ic_sessions)
    }
}
