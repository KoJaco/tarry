package me.tarry.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.settings.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TarryThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun themeModeResolvesEffectiveDarkFlag() {
        for (systemDark in listOf(false, true)) {
            assertThat(ThemeMode.SYSTEM.isDark(systemDark)).isEqualTo(systemDark)
            assertThat(ThemeMode.LIGHT.isDark(systemDark)).isFalse()
            assertThat(ThemeMode.DARK.isDark(systemDark)).isTrue()
        }
    }

    @Test
    fun darkThemeProvidesDarkTokens() {
        var colors: TarryColors? = null
        var scheme: ColorScheme? = null
        composeRule.setContent {
            TarryTheme(darkTheme = true) {
                colors = TarryTheme.colors
                scheme = MaterialTheme.colorScheme
            }
        }
        composeRule.waitForIdle()
        assertThat(colors).isEqualTo(DarkTarryColors)
        assertThat(scheme!!.background).isEqualTo(DarkTarryColors.paper)
        assertThat(scheme!!.onError).isEqualTo(Color(0xFF601410))
    }

    @Test
    fun lightThemeProvidesLightTokensAndMapsM3Roles() {
        var colors: TarryColors? = null
        var scheme: ColorScheme? = null
        composeRule.setContent {
            TarryTheme(darkTheme = false) {
                colors = TarryTheme.colors
                scheme = MaterialTheme.colorScheme
            }
        }
        composeRule.waitForIdle()
        assertThat(colors).isEqualTo(LightTarryColors)
        assertThat(scheme!!.background).isEqualTo(LightTarryColors.paper)
        assertThat(scheme!!.primary).isEqualTo(LightTarryColors.ink)
        assertThat(scheme!!.surfaceContainerHigh).isEqualTo(LightTarryColors.inset)
        assertThat(scheme!!.onError).isEqualTo(Color.White)
    }

    private fun roles(s: ColorScheme): Map<String, Color> = mapOf(
        "primary" to s.primary, "onPrimary" to s.onPrimary,
        "primaryContainer" to s.primaryContainer, "onPrimaryContainer" to s.onPrimaryContainer,
        "inversePrimary" to s.inversePrimary,
        "secondary" to s.secondary, "onSecondary" to s.onSecondary,
        "secondaryContainer" to s.secondaryContainer, "onSecondaryContainer" to s.onSecondaryContainer,
        "tertiary" to s.tertiary, "onTertiary" to s.onTertiary,
        "tertiaryContainer" to s.tertiaryContainer, "onTertiaryContainer" to s.onTertiaryContainer,
        "background" to s.background, "onBackground" to s.onBackground,
        "surface" to s.surface, "onSurface" to s.onSurface,
        "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant,
        "surfaceTint" to s.surfaceTint,
        "inverseSurface" to s.inverseSurface, "inverseOnSurface" to s.inverseOnSurface,
        "outline" to s.outline, "outlineVariant" to s.outlineVariant,
        "surfaceBright" to s.surfaceBright, "surfaceDim" to s.surfaceDim,
        "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest, "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "primaryFixed" to s.primaryFixed, "primaryFixedDim" to s.primaryFixedDim,
        "onPrimaryFixed" to s.onPrimaryFixed, "onPrimaryFixedVariant" to s.onPrimaryFixedVariant,
        "secondaryFixed" to s.secondaryFixed, "secondaryFixedDim" to s.secondaryFixedDim,
        "onSecondaryFixed" to s.onSecondaryFixed, "onSecondaryFixedVariant" to s.onSecondaryFixedVariant,
        "tertiaryFixed" to s.tertiaryFixed, "tertiaryFixedDim" to s.tertiaryFixedDim,
        "onTertiaryFixed" to s.onTertiaryFixed, "onTertiaryFixedVariant" to s.onTertiaryFixedVariant,
    )

    /**
     * The error family deliberately uses the M3 error palette (tokens.md: `alert` is B3261E / F2B8B5 and `onError` is
     * white / 601410), so those four may equal the baseline. Every other role must be a Tarry token.
     */
    private val m3ErrorRoles = setOf("error", "onError", "errorContainer", "onErrorContainer")

    /** In the light scheme `card` is white, which is also what M3 uses for these two roles; the mapping is still ours. */
    private val lightWhiteCardRoles = setOf("onTertiary", "surfaceContainerLowest")

    @Test
    fun noRoleKeepsTheM3BaselineValue() {
        for ((dark, baseline) in listOf(false to lightColorScheme(), true to darkColorScheme())) {
            val tokens = if (dark) DarkTarryColors else LightTarryColors
            val tarry = roles(tokens.toMaterial(dark))
            val exempt = m3ErrorRoles + if (dark) emptySet() else lightWhiteCardRoles
            val untouched = roles(baseline).filter { (name, value) -> name !in exempt && tarry.getValue(name) == value }
            assertThat(untouched.keys).isEmpty()
            lightWhiteCardRoles.forEach { assertThat(tarry.getValue(it)).isEqualTo(tokens.card) }
        }
    }

    @Test
    fun inverseRolesFollowTheTheme() {
        assertThat(LightTarryColors.toMaterial(false).inverseSurface).isEqualTo(LightTarryColors.ink)
        assertThat(DarkTarryColors.toMaterial(true).inverseSurface).isEqualTo(DarkTarryColors.ink)
        assertThat(DarkTarryColors.toMaterial(true).inverseOnSurface).isEqualTo(DarkTarryColors.onInk)
        assertThat(LightTarryColors.toMaterial(false).scrim).isEqualTo(Color.Black)
    }
}
