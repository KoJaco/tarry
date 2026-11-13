package me.tarry.app.tracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LowBatteryRuleTest {
    private val rule = LowBatteryRule(TrackingConfig())

    private fun r(percent: Int, charging: Boolean = false) = BatteryReading(percent, charging)

    @Test
    fun entersAtTwentyAndNotAtTwentyOne() {
        assertThat(rule.next(false, r(21))).isFalse()
        assertThat(rule.next(false, r(20))).isTrue()
    }

    @Test
    fun staysOnBelowTheExitAndLeavesAtTwentyFive() {
        assertThat(rule.next(true, r(24))).isTrue()
        assertThat(rule.next(true, r(25))).isFalse()
        assertThat(rule.next(true, r(22))).isTrue()
    }

    @Test
    fun chargingEndsItAndBlocksEntry() {
        assertThat(rule.next(true, r(10, charging = true))).isFalse()
        assertThat(rule.next(false, r(10, charging = true))).isFalse()
    }

    @Test
    fun noReadingKeepsTheState() {
        assertThat(rule.next(true, null)).isTrue()
        assertThat(rule.next(false, null)).isFalse()
    }
}
