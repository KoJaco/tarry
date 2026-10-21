package me.tarry.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.theme.isDark
import me.tarry.core.data.settings.ThemeMode

/**
 * The app's root: resolves the stored [themeMode] into the effective dark flag and provides [TarryTheme].
 *
 * [themeMode] is null until the stored value has loaded (one DataStore read); nothing is composed until then, so the
 * window background (paper) shows instead of a flash of the wrong theme. [onDarkChanged] runs after every successful
 * composition with the effective flag, so the activity can restyle the system bars.
 */
@Composable
fun TarryRoot(
    themeMode: ThemeMode?,
    systemDark: Boolean = isSystemInDarkTheme(),
    onDarkChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    if (themeMode == null) return
    val dark = themeMode.isDark(systemDark)
    SideEffect { onDarkChanged(dark) }
    TarryTheme(darkTheme = dark, content = content)
}
