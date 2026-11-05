package me.tarry.app.ui

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import me.tarry.app.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsIconsTest {
    @Test
    fun theSettingsRowIconsInflate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val icons = listOf(
            R.drawable.ic_timer, R.drawable.ic_swap_vert, R.drawable.ic_hourglass_bottom, R.drawable.ic_event_repeat,
            R.drawable.ic_notifications, R.drawable.ic_schedule, R.drawable.ic_history, R.drawable.ic_backup,
            R.drawable.ic_settings_backup_restore,
        )
        for (id in icons) {
            val drawable = ContextCompat.getDrawable(context, id)
            assertThat(drawable).isNotNull()
            assertThat(drawable!!.intrinsicWidth).isGreaterThan(0)
        }
    }
}
