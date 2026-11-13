package me.tarry.app.ui.about

import android.content.Intent
import androidx.core.net.toUri

/** Outbound addresses shown on the About screen. Opened in the system browser or mail app, never fetched by Tarry. */
object AboutLinks {
    const val SOURCE_URL = "https://github.com/KoJaco/tarry"

    const val PRIVACY_URL = "https://www.tarry.me/privacy"

    const val SUPPORT_EMAIL = "hello@tarry.me"

    /** `mailto:` intent with the support subject. [subject] is passed in so it can come from strings.xml. */
    fun supportIntent(subject: String): Intent =
        Intent(Intent.ACTION_SENDTO, "mailto:$SUPPORT_EMAIL".toUri()).putExtra(Intent.EXTRA_SUBJECT, subject)

    fun viewIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, url.toUri())
}
