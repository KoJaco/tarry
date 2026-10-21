package me.tarry.app.ui.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MinuteClockTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private var wallMs = 10 * 60_000L + 30_000L
    private var reads = 0
    private val enabled = mutableStateOf(true)
    private var observed = 0L

    private fun show(owner: Owner) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val clock by rememberMinuteClock(enabled.value) { reads++; wallMs }
                observed = clock
            }
        }
    }

    private fun tick(ms: Long) {
        wallMs += ms
        composeRule.mainClock.advanceTimeBy(ms)
    }

    @Test
    fun ticksOnTheMinuteWhileStarted() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        show(owner)
        composeRule.mainClock.advanceTimeBy(1)
        assertThat(observed).isEqualTo(10 * 60_000L + 30_000L)
        tick(30_001)
        assertThat(observed).isEqualTo(11 * 60_000L + 1)
    }

    @Test
    fun doesNotTickInTheBackgroundAndRefreshesOnReturn() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        show(owner)
        composeRule.mainClock.advanceTimeBy(1)
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.mainClock.advanceTimeBy(1)
        val stopped = observed
        val readsBefore = reads
        tick(5 * 60_000L)
        assertThat(observed).isEqualTo(stopped)
        assertThat(reads).isEqualTo(readsBefore)
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.mainClock.advanceTimeBy(1)
        assertThat(observed).isEqualTo(wallMs)
    }

    @Test
    fun doesNotTickWhenDisabled() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        enabled.value = false
        show(owner)
        composeRule.mainClock.advanceTimeBy(1)
        val readsBefore = reads
        tick(5 * 60_000L)
        assertThat(reads).isEqualTo(readsBefore)
    }
}
