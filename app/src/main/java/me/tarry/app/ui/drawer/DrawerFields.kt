package me.tarry.app.ui.drawer

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import me.tarry.app.ui.theme.TarryTheme

private val FocusRingWidth = 2.dp
private val FieldMinHeight = 56.dp

/**
 * Tarry text input: `inset` fill, pill when single-line and `card` radius when multi-line, no underline, and a 2 dp
 * `ink` ring on focus. [label] is always shown (it doubles as the accessibility label).
 */
@Composable
internal fun InsetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    val c = TarryTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = if (singleLine) TarryTheme.shapes.pill else TarryTheme.shapes.card
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FieldMinHeight)
            .then(if (focused) Modifier.border(FocusRingWidth, c.ink, shape) else Modifier),
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        singleLine = singleLine,
        minLines = minLines,
        shape = shape,
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.inset,
            unfocusedContainerColor = c.inset,
            disabledContainerColor = c.inset,
            focusedTextColor = c.ink,
            unfocusedTextColor = c.ink,
            focusedLabelColor = c.muted,
            unfocusedLabelColor = c.muted,
            cursorColor = c.ink,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            errorIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}
