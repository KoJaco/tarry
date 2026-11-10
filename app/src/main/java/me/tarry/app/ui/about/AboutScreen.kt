package me.tarry.app.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import me.tarry.app.BuildConfig
import me.tarry.app.R
import me.tarry.app.ui.components.SectionHeader
import me.tarry.app.ui.components.SettingsGroup
import me.tarry.app.ui.theme.TarryTheme

/** Wired entry point: opens links and mail in other apps, and says so in a snackbar when none can handle them. */
@Composable
fun AboutScreen(
    onOpenLicences: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noBrowser = stringResource(R.string.about_no_browser)
    val noEmail = stringResource(R.string.about_no_email_app, AboutLinks.SUPPORT_EMAIL)
    val subject = stringResource(R.string.about_support_subject)
    fun launch(intent: Intent, failure: String) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbarHostState?.showSnackbar(failure) }
        }
    }
    AboutContent(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        onOpenLink = { url -> launch(AboutLinks.viewIntent(url), noBrowser) },
        onEmailSupport = { launch(AboutLinks.supportIntent(subject), noEmail) },
        onOpenLicences = onOpenLicences,
        modifier = modifier,
    )
}

/** "1.0 (1)": the version as About and the Settings About row show it. */
@Composable
fun versionLabel(versionName: String, versionCode: Int): String =
    stringResource(R.string.about_version_value, versionName, versionCode)

/** Stateless About screen. Link taps go out through [onOpenLink] and [onEmailSupport] so tests need no activity. */
@Composable
fun AboutContent(
    versionName: String,
    versionCode: Int,
    onOpenLink: (String) -> Unit,
    onEmailSupport: () -> Unit,
    onOpenLicences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val space = TarryTheme.space
    val colors = TarryTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = space.gutter, vertical = space.l),
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
        // Decorative: the title below names the app.
        Image(
            painter = painterResource(R.drawable.tarry_logo),
            contentDescription = null,
            modifier = Modifier.size(64.dp).testTag("about_logo"),
        )
        Text(
            text = stringResource(R.string.about_title),
            style = MaterialTheme.typography.headlineMedium,
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.about_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )

        SectionHeader(stringResource(R.string.about_section_app), Modifier.padding(top = space.m))
        val versionTitle = stringResource(R.string.about_version)
        val versionValue = versionLabel(versionName, versionCode)
        val sourceTitle = stringResource(R.string.about_source)
        val sourceSubtitle = stringResource(R.string.about_source_subtitle)
        SettingsGroup {
            row(versionTitle, value = versionValue)
            row(sourceTitle, onClick = { onOpenLink(AboutLinks.SOURCE_URL) }, subtitle = sourceSubtitle)
        }

        SectionHeader(stringResource(R.string.about_section_privacy), Modifier.padding(top = space.m))
        val privacyTitle = stringResource(R.string.about_privacy_policy)
        SettingsGroup {
            item {
                Text(
                    text = stringResource(R.string.about_privacy_promise),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    modifier = Modifier.padding(horizontal = space.l, vertical = space.m),
                )
            }
            row(privacyTitle, onClick = { onOpenLink(AboutLinks.PRIVACY_URL) })
        }

        SectionHeader(stringResource(R.string.about_section_help), Modifier.padding(top = space.m))
        val supportTitle = stringResource(R.string.about_support)
        SettingsGroup {
            row(supportTitle, onClick = onEmailSupport, subtitle = AboutLinks.SUPPORT_EMAIL)
        }

        SectionHeader(stringResource(R.string.about_section_credits), Modifier.padding(top = space.m))
        val mapTitle = stringResource(R.string.about_map_data)
        val mapValue = stringResource(R.string.about_map_data_value)
        val licencesTitle = stringResource(R.string.about_licences)
        SettingsGroup {
            row(mapTitle, subtitle = mapValue)
            row(licencesTitle, onClick = onOpenLicences)
        }
    }
}
