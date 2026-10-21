package me.tarry.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.DarkTarryColors
import me.tarry.app.ui.theme.LightTarryColors
import me.tarry.app.ui.theme.TarryColors
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.settings.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TarryRootTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun colorsFor(mode: ThemeMode?, systemDark: Boolean): TarryColors? {
        var colors: TarryColors? = null
        composeRule.setContent {
            TarryRoot(themeMode = mode, systemDark = systemDark) { colors = TarryTheme.colors }
        }
        composeRule.waitForIdle()
        return colors
    }

    @Test
    fun darkModeGivesDarkTokensEvenWhenTheSystemIsLight() {
        assertThat(colorsFor(ThemeMode.DARK, systemDark = false)).isEqualTo(DarkTarryColors)
    }

    @Test
    fun lightModeGivesLightTokensEvenWhenTheSystemIsDark() {
        assertThat(colorsFor(ThemeMode.LIGHT, systemDark = true)).isEqualTo(LightTarryColors)
    }

    @Test
    fun systemModeFollowsTheSystem() {
        assertThat(colorsFor(ThemeMode.SYSTEM, systemDark = true)).isEqualTo(DarkTarryColors)
    }

    @Test
    fun nothingIsComposedUntilTheThemeHasLoaded() {
        val mode = mutableStateOf<ThemeMode?>(null)
        composeRule.setContent {
            TarryRoot(themeMode = mode.value, systemDark = false) { Text("content") }
        }
        composeRule.onNodeWithText("content").assertDoesNotExist()
        composeRule.runOnUiThread { mode.value = ThemeMode.DARK }
        composeRule.onNodeWithText("content").assertExists()
    }

    @Test
    fun systemBarsAreRestyledWithTheEffectiveFlag() {
        val seen = mutableListOf<Boolean>()
        val mode = mutableStateOf<ThemeMode?>(null)
        composeRule.setContent {
            TarryRoot(themeMode = mode.value, systemDark = false, onDarkChanged = { seen += it }) { Text("c") }
        }
        assertThat(seen).isEmpty()
        composeRule.runOnUiThread { mode.value = ThemeMode.DARK }
        composeRule.waitForIdle()
        assertThat(seen.last()).isTrue()
        composeRule.runOnUiThread { mode.value = ThemeMode.LIGHT }
        composeRule.waitForIdle()
        assertThat(seen.last()).isFalse()
    }
}
