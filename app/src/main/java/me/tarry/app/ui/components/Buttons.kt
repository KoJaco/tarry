package me.tarry.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.tarry.app.ui.theme.TarryTheme

private val ButtonHeight = 52.dp
private val OutlineWidth = 1.5.dp
private val ButtonPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)

@Composable
private fun ButtonLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge)
}

/** `ink` pill, `onInk` label. The accent is never a button colour. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = TarryTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        shape = TarryTheme.shapes.pill,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.ink,
            contentColor = c.onInk,
            disabledContainerColor = c.inset,
            disabledContentColor = c.faint,
        ),
    ) { ButtonLabel(text) }
}

/** 1.5 dp `ink` outline pill. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = TarryTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        shape = TarryTheme.shapes.pill,
        contentPadding = ButtonPadding,
        border = BorderStroke(OutlineWidth, if (enabled) c.ink else c.faint),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink, disabledContentColor = c.faint),
    ) { ButtonLabel(text) }
}

/** Text button in `ink`. */
@Composable
fun TertiaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = TarryTheme.colors
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        shape = TarryTheme.shapes.pill,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.textButtonColors(contentColor = c.ink, disabledContentColor = c.faint),
    ) { ButtonLabel(text) }
}

/** 1.5 dp `alert` outline pill with `alert` label. */
@Composable
fun DestructiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = TarryTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        shape = TarryTheme.shapes.pill,
        contentPadding = ButtonPadding,
        border = BorderStroke(OutlineWidth, if (enabled) c.alert else c.faint),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.alert, disabledContentColor = c.faint),
    ) { ButtonLabel(text) }
}

@TarryPreviews
@Composable
private fun ButtonsPreview() = PreviewSurface {
    Column {
        PrimaryButton("Primary", {})
        SecondaryButton("Secondary", {}, Modifier.padding(top = 8.dp))
        TertiaryButton("Tertiary", {}, Modifier.padding(top = 8.dp))
        DestructiveButton("Destructive", {}, Modifier.padding(top = 8.dp))
        PrimaryButton("Disabled", {}, Modifier.padding(top = 8.dp), enabled = false)
    }
}
