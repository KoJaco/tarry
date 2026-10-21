package me.tarry.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Every semantic colour token from `docs/design/tokens.md`. Read through [TarryTheme.colors]; screens never
 * use hex literals. [pulse] is already an alpha colour (the tracking pulse ring).
 */
@Immutable
data class TarryColors(
    val paper: Color,
    val card: Color,
    val inset: Color,
    val ink: Color,
    val onInk: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val outline: Color,
    val accent: Color,
    val onAccent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val alert: Color,
    val alertSoft: Color,
    val onAlertSoft: Color,
    val alertDot: Color,
    val pulse: Color,
)

private val Apricot = Color(0xFFF2B064)

val LightTarryColors = TarryColors(
    paper = Color(0xFFF3EFE8),
    card = Color(0xFFFFFFFF),
    inset = Color(0xFFEFE9DF),
    ink = Color(0xFF1B1813),
    onInk = Color(0xFFF3EFE8),
    muted = Color(0xFF655E54),
    faint = Color(0xFFC2BAAE),
    line = Color(0xFFDDD5C8),
    outline = Color(0xFF8F877B),
    accent = Apricot,
    onAccent = Color(0xFF1B1813),
    accentStrong = Color(0xFF8A5310),
    accentSoft = Color(0xFFFBE3C4),
    onAccentSoft = Color(0xFF4A2C05),
    alert = Color(0xFFB3261E),
    alertSoft = Color(0xFFF9DEDC),
    onAlertSoft = Color(0xFF8C1D17),
    alertDot = Color(0xFFFF6B5E),
    pulse = Apricot.copy(alpha = 0.35f),
)

val DarkTarryColors = TarryColors(
    paper = Color(0xFF14120F),
    card = Color(0xFF201D18),
    inset = Color(0xFF2D2924),
    ink = Color(0xFFF2EDE4),
    onInk = Color(0xFF14120F),
    muted = Color(0xFFA9A193),
    faint = Color(0xFF5E574D),
    line = Color(0xFF3A352E),
    outline = Color(0xFF7A7266),
    accent = Apricot,
    onAccent = Color(0xFF1B1813),
    accentStrong = Apricot,
    accentSoft = Color(0xFF3A2A16),
    onAccentSoft = Color(0xFFF6CDA6),
    alert = Color(0xFFF2B8B5),
    alertSoft = Color(0xFF4A1F1B),
    onAlertSoft = Color(0xFFF9DEDC),
    alertDot = Color(0xFFFF6B5E),
    pulse = Apricot.copy(alpha = 0.25f),
)

/** Defaults to light so previews and tests without [TarryTheme] still work. */
val LocalTarryColors = staticCompositionLocalOf { LightTarryColors }

/** M3 `onError` per tokens.md: white in light, `#601410` in dark. */
internal val LightOnError = Color(0xFFFFFFFF)
internal val DarkOnError = Color(0xFF601410)
