package me.tarry.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import me.tarry.app.ui.theme.TarryTheme

/** Flat `card`-coloured surface. Pass `shape = TarryTheme.shapes.container` for the summary card and sheets. */
@Composable
fun TarryCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = TarryTheme.shapes.card,
    contentPadding: PaddingValues = PaddingValues(TarryTheme.space.l),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(TarryTheme.colors.card)
            .padding(contentPadding),
        content = content,
    )
}

/** Upper-cased `labelSmall` section label in `muted`. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TarryTheme.colors.muted,
        modifier = modifier.semantics {
            heading()
            // The original case: TalkBack would otherwise spell out the upper-cased text as shouting.
            contentDescription = text
        },
    )
}

@TarryPreviews
@Composable
private fun TarryCardPreview() = PreviewSurface {
    Column {
        SectionHeader("Tracking")
        TarryCard {
            Text("Card content", style = MaterialTheme.typography.titleMedium, color = TarryTheme.colors.ink)
        }
    }
}
