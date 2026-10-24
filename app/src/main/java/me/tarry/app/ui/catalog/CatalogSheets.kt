package me.tarry.app.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.components.TarryCard
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.theme.TarryTheme

private val ActionRowMinHeight = 56.dp
private val ColourTarget = 48.dp
private val ColourBorder = 1.5.dp

/** One row of an [ActionsSheet]. A disabled action with a [reason] shows the reason under its label. */
internal data class SheetAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val reason: String? = null,
    val destructive: Boolean = false,
)

@Composable
private fun sheetShape() = TarryTheme.shapes.container.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))

/** A bottom sheet titled [title] with a card of full-width action rows (the "menu" of a job, tag or group). */
@Composable
internal fun ActionsSheet(title: String, actions: List<SheetAction>, onDismiss: () -> Unit) {
    SheetFrame(title, onDismiss) {
        TarryCard(contentPadding = PaddingValues(0.dp)) {
            actions.forEachIndexed { index, action ->
                if (index > 0) HorizontalDivider(thickness = 1.dp, color = TarryTheme.colors.divider)
                ActionRow(action)
            }
        }
    }
}

@Composable
private fun ActionRow(action: SheetAction) {
    val c = TarryTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
            .heightIn(min = ActionRowMinHeight)
            .padding(horizontal = TarryTheme.space.l, vertical = TarryTheme.space.m),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            action.label,
            style = MaterialTheme.typography.titleSmall,
            color = when {
                !action.enabled -> c.muted
                action.destructive -> c.alert
                else -> c.ink
            },
        )
        if (action.reason != null) {
            Text(action.reason, style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
    }
}

/** Sheet chrome shared by the actions list and the colour grid: paper surface, scrolling column, heading title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetFrame(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = sheetShape(), containerColor = TarryTheme.colors.paper) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = TarryTheme.space.gutter)
                .padding(bottom = TarryTheme.space.l),
            verticalArrangement = Arrangement.spacedBy(TarryTheme.space.m),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = TarryTheme.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

/**
 * The eight [JobPalette] slots as 48 dp swatches that wrap, each spoken by its slot name and marked selected with a
 * check (never colour alone). [selectedArgb] is the stored key; [allowNone] adds a "No colour" row that stores 0.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColourGrid(selectedArgb: Int, allowNone: Boolean, onPick: (Int) -> Unit) {
    val dark = TarryTheme.isDark
    val c = TarryTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s)) {
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
            verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
        ) {
            JobPalette.slots.forEach { slot ->
                val colours = slot.colors(dark)
                val selected = slot.key == selectedArgb
                Box(
                    modifier = Modifier
                        .size(ColourTarget)
                        .clip(TarryTheme.shapes.block)
                        .background(colours.container)
                        .border(ColourBorder, colours.border, TarryTheme.shapes.block)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(slot.key) })
                        .semantics { contentDescription = slot.name },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = colours.text,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
        if (allowNone) {
            val none = selectedArgb == 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ColourTarget)
                    .selectable(selected = none, role = Role.RadioButton, onClick = { onPick(0) })
                    .padding(vertical = TarryTheme.space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.m),
            ) {
                Text(
                    stringResource(R.string.catalog_colour_none),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.ink,
                    modifier = Modifier.weight(1f),
                )
                if (none) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = c.ink, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
