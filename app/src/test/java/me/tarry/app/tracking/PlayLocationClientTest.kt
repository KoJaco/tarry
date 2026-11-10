package me.tarry.app.tracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.ExecutionException

class PlayLocationClientTest {
    @Test
    fun aSecurityExceptionAnywhereInTheCauseChainIsADenial() {
        assertThat(SecurityException("no permission").hasSecurityCause()).isTrue()
        assertThat(ExecutionException(RuntimeException("api", SecurityException("no permission"))).hasSecurityCause()).isTrue()
    }

    @Test
    fun otherFailuresAreNot() {
        assertThat(RuntimeException("boom").hasSecurityCause()).isFalse()
        assertThat(ExecutionException(IllegalStateException("x")).hasSecurityCause()).isFalse()
    }

    @Test
    fun aCauseCycleDoesNotHang() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertThat(a.hasSecurityCause()).isFalse()
    }
}
