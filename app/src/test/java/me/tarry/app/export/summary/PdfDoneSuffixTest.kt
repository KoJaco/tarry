package me.tarry.app.export.summary

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The PDF names a done job with "(done)" (the DB field is still `archived`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PdfDoneSuffixTest {
    @Test
    fun aDoneJobGetsTheDoneSuffixAndAnOpenJobDoesNot() {
        val text = AndroidSummaryPdfText(ApplicationProvider.getApplicationContext<Context>())

        assertThat(text.jobName("Bravo", archived = true)).isEqualTo("Bravo (done)")
        assertThat(text.jobName("Alpha", archived = false)).isEqualTo("Alpha")
    }
}
