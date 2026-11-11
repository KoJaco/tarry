package me.tarry.app.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ResolveNeedsOnboardingTest {
    @Test
    fun passesTheAnswerThrough() = runBlocking {
        assertThat(resolveNeedsOnboarding { true }).isTrue()
        assertThat(resolveNeedsOnboarding { false }).isFalse()
    }

    @Test
    fun aThrowingGateMeansNoOnboardingInsteadOfACrash() = runBlocking {
        assertThat(resolveNeedsOnboarding { throw IllegalStateException("datastore unreadable") }).isFalse()
    }

    @Test
    fun cancellationStillPropagates() {
        assertThrows(CancellationException::class.java) {
            runBlocking { resolveNeedsOnboarding { throw CancellationException("gone") } }
        }
    }
}
