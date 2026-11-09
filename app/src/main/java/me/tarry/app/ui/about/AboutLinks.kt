package me.tarry.app.ui.about

import android.content.Intent
import androidx.core.net.toUri

/** Outbound addresses shown on the About screen. Opened in the system browser or mail app, never fetched by Tarry. */
object AboutLinks {
    const val SOURCE_URL = "https://github.com/KoJaco/tarry"

    // TODO(phase 8c): owner fills before release
    const val PRIVACY_URL = "https://tarry.app/privacy"

    // TODO(phase 8c): owner fills before release
    const val SUPPORT_EMAIL = "support@tarry.app"

    /** `mailto:` intent with the support subject. [subject] is passed in so it can come from strings.xml. */
    fun supportIntent(subject: String): Intent =
        Intent(Intent.ACTION_SENDTO, "mailto:$SUPPORT_EMAIL".toUri()).putExtra(Intent.EXTRA_SUBJECT, subject)

    fun viewIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, url.toUri())
}
