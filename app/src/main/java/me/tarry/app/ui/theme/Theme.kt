package me.tarry.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import me.tarry.core.data.settings.ThemeMode

/** Effective dark flag for a stored [ThemeMode]; SYSTEM follows [systemDark]. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** True when the Tarry dark palette is active; provided by [TarryTheme]. */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/** Accessors for the Tarry tokens, mirroring [MaterialTheme]. */
object TarryTheme {
    val colors: TarryColors
        @Composable @ReadOnlyComposable get() = LocalTarryColors.current
    val space: TarrySpace
        @Composable @ReadOnlyComposable get() = LocalTarrySpace.current
    val shapes: TarryShapes
        @Composable @ReadOnlyComposable get() = LocalTarryShapes.current
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalIsDarkTheme.current
}

/** M3 mapping exactly per the tokens.md "M3 role mapping" table. */
internal fun TarryColors.toMaterial(dark: Boolean) =
    if (dark) {
        darkColorScheme(
            background = paper, surface = paper,
            surfaceContainerLowest = card, surfaceContainerLow = card, surfaceContainer = card,
            surfaceContainerHigh = inset, surfaceContainerHighest = inset, surfaceVariant = inset,
            onBackground = ink, onSurface = ink, onSurfaceVariant = muted,
            primary = ink, onPrimary = onInk,
            secondary = accent, onSecondary = onAccent,
            secondaryContainer = accentSoft, onSecondaryContainer = onAccentSoft,
            tertiary = accentStrong,
            error = alert, onError = DarkOnError,
            errorContainer = alertSoft, onErrorContainer = onAlertSoft,
            outline = outline, outlineVariant = line,
            // Roles the table above does not list explicitly, mapped to the nearest token so no baseline purple survives.
            primaryContainer = inset, onPrimaryContainer = ink,
            tertiaryContainer = accentSoft, onTertiaryContainer = onAccentSoft, onTertiary = card,
            inversePrimary = accent, inverseSurface = ink, inverseOnSurface = onInk,
            surfaceTint = paper, surfaceBright = card, surfaceDim = inset,
            primaryFixed = inset, primaryFixedDim = line, onPrimaryFixed = ink, onPrimaryFixedVariant = muted,
            secondaryFixed = accentSoft, secondaryFixedDim = accent,
            onSecondaryFixed = onAccentSoft, onSecondaryFixedVariant = onAccentSoft,
            tertiaryFixed = accentSoft, tertiaryFixedDim = accent,
            onTertiaryFixed = onAccentSoft, onTertiaryFixedVariant = onAccentSoft,
        )
    } else {
        lightColorScheme(
            background = paper, surface = paper,
            surfaceContainerLowest = card, surfaceContainerLow = card, surfaceContainer = card,
            surfaceContainerHigh = inset, surfaceContainerHighest = inset, surfaceVariant = inset,
            onBackground = ink, onSurface = ink, onSurfaceVariant = muted,
            primary = ink, onPrimary = onInk,
            secondary = accent, onSecondary = onAccent,
            secondaryContainer = accentSoft, onSecondaryContainer = onAccentSoft,
            tertiary = accentStrong,
            error = alert, onError = LightOnError,
            errorContainer = alertSoft, onErrorContainer = onAlertSoft,
            outline = outline, outlineVariant = line,
            // Roles the table above does not list explicitly, mapped to the nearest token so no baseline purple survives.
            primaryContainer = inset, onPrimaryContainer = ink,
            tertiaryContainer = accentSoft, onTertiaryContainer = onAccentSoft, onTertiary = card,
            inversePrimary = accent, inverseSurface = ink, inverseOnSurface = onInk,
            surfaceTint = paper, surfaceBright = card, surfaceDim = inset,
            primaryFixed = inset, primaryFixedDim = line, onPrimaryFixed = ink, onPrimaryFixedVariant = muted,
            secondaryFixed = accentSoft, secondaryFixedDim = accent,
            onSecondaryFixed = onAccentSoft, onSecondaryFixedVariant = onAccentSoft,
            tertiaryFixed = accentSoft, tertiaryFixedDim = accent,
            onTertiaryFixed = onAccentSoft, onTertiaryFixedVariant = onAccentSoft,
        )
    }

private val TarryM3Shapes = TarryShapes().let {
    Shapes(
        extraSmall = it.small,
        small = it.block,
        medium = it.block,
        large = it.card,
        extraLarge = it.container,
    )
}

@Composable
fun TarryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkTarryColors else LightTarryColors
    CompositionLocalProvider(
        LocalTarryColors provides colors,
        LocalTarrySpace provides TarrySpace(),
        LocalTarryShapes provides TarryShapes(),
        LocalIsDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(darkTheme),
            typography = Typography,
            shapes = TarryM3Shapes,
            content = content,
        )
    }
}
