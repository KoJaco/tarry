package me.tarry.app.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.tarry.app.ui.theme.TarryTheme

/** Light, dark and font scale 2.0 previews, applied to every component preview. */
@Preview(name = "light")
@Preview(name = "dark", uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "font 2.0", fontScale = 2f)
annotation class TarryPreviews

/** Themed paper background for previews; dark follows the preview's night mode. */
@Composable
internal fun PreviewSurface(content: @Composable () -> Unit) {
    TarryTheme(darkTheme = isSystemInDarkTheme()) {
        Box(Modifier.background(TarryTheme.colors.paper).padding(16.dp)) { content() }
    }
}
