package me.tarry.app.ui.map

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TarryMapOptionsTest {
    @Test
    fun mapsDrawIntoATextureSoTheyFollowScreenTransitions() {
        val options = tarryMapOptions(ApplicationProvider.getApplicationContext())
        // A SurfaceView map ignores the navigation fade/slide and lingers under the next screen.
        assertThat(options.textureMode).isTrue()
        assertThat(options.logoEnabled).isFalse()
        assertThat(options.attributionEnabled).isFalse()
    }
}
