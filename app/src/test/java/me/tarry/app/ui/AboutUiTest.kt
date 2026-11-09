package me.tarry.app.ui

import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.about.AboutContent
import me.tarry.app.ui.about.AboutLinks
import me.tarry.app.ui.about.LicencesContent
import me.tarry.app.ui.about.OssNotices
import me.tarry.app.ui.settings.SettingsContent
import me.tarry.core.data.settings.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AboutUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun setAbout(fontScale: Float = 1f, onLicences: () -> Unit = {}, onEmail: () -> Unit = { opened += "email" }) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                AboutContent(
                    versionName = "1.2.3",
                    versionCode = 42,
                    onOpenLink = { opened += it },
                    onEmailSupport = onEmail,
                    onOpenLicences = onLicences,
                )
            }
        }
    }

    @Test
    fun settingsEndsWithAnAboutRowThatOpensAbout() {
        var calls = 0
        composeRule.setContent { SettingsContent(Settings(), onOpenAbout = { calls++ }) }

        composeRule.onNodeWithText("About", ignoreCase = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("About Tarry").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun aboutShowsVersionAndTheLicenceLine() {
        setAbout()

        composeRule.onNodeWithText("About Tarry").assertIsDisplayed()
        composeRule.onNodeWithText("1.2.3 (42)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Source available under FSL-1.1-ALv2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Map data").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun linksFireTheRightTargets() {
        var licences = 0
        setAbout(onLicences = { licences++ })

        composeRule.onNodeWithText("Source code").performScrollTo().performClick()
        composeRule.onNodeWithText("Privacy policy").performScrollTo().performClick()
        composeRule.onNodeWithText("Support").performScrollTo().performClick()
        composeRule.onNodeWithText("Open-source licences").performScrollTo().performClick()

        assertThat(opened).containsExactly(AboutLinks.SOURCE_URL, AboutLinks.PRIVACY_URL, "email").inOrder()
        assertThat(licences).isEqualTo(1)
    }

    @Test
    fun supportIntentIsAMailtoWithTheSubject() {
        val intent = AboutLinks.supportIntent("Tarry support")

        assertThat(intent.action).isEqualTo(Intent.ACTION_SENDTO)
        assertThat(intent.data.toString()).isEqualTo("mailto:${AboutLinks.SUPPORT_EMAIL}")
        assertThat(intent.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("Tarry support")
        assertThat(AboutLinks.viewIntent(AboutLinks.SOURCE_URL).action).isEqualTo(Intent.ACTION_VIEW)
    }

    @Test
    fun aboutFitsAtTwiceTheFontSize() {
        setAbout(fontScale = 2f)

        composeRule.onNodeWithText("Tarry has no account", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Privacy policy").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Open-source licences").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun licencesListsEveryNoticeAndOpensItsText() {
        composeRule.setContent { LicencesContent(OssNotices, textFor = { "TEXT OF ${it.assetFile}" }) }

        for (name in listOf("Nunito", "Nunito Sans", "MapLibre Native", "OpenStreetMap data", "Android and Google libraries")) {
            composeRule.onNodeWithText(name).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithText("BSD-2-Clause").assertIsDisplayed()
        composeRule.onNodeWithText("MapLibre Native").performClick()

        composeRule.onNodeWithText("TEXT OF BSD-2-MapLibre.txt").assertIsDisplayed()
    }

    @Test
    fun licenceAssetsExistForEveryNotice() {
        val dir = java.io.File("src/main/assets/licenses")
        for (notice in OssNotices) {
            assertThat(java.io.File(dir, notice.assetFile).length()).isGreaterThan(100L)
        }
    }
}
