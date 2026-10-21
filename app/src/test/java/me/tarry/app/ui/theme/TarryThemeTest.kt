package me.tarry.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
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
}
