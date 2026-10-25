package me.tarry.app.segmentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RadiusPreviewDotsTest {
    private val home = PreviewCentre(-33.0, 151.0)
    private val other = PreviewCentre(-33.0 + 150 / 111_195.0, 151.0)

    private fun north(m: Double) = PreviewCentre(home.lat + m / 111_195.0, home.lng)
    private fun visit(place: String, m: Double, frozen: Boolean = false) =
        north(m).let { PreviewVisit(place, frozen, it.lat, it.lng) }

    private val input = RadiusPreviewInput(
        "home", home, mapOf("other" to other),
        listOf(
            visit("home", 10.0), visit("home", 50.0), visit("home", 90.0),
            visit("home", 20.0, frozen = true), visit("home", 300.0, frozen = true),
            visit("other", 40.0), visit("other", 140.0), visit("other", 400.0), visit("other", 40.0, frozen = true),
            PreviewVisit("home", false, null, null),
        ),
    )

    @Test
    fun dotsAgreeWithCountsAtEveryRadius() {
        for (r in listOf(5f, 20f, 60f, 75f, 100f, 200f)) {
            val c = radiusCounts(input, r)
            val d = radiusDots(input, r)
            assertThat(d.stay).hasSize(c.stay)
            assertThat(d.leave).hasSize(c.leave)
            assertThat(d.join).hasSize(c.join)
            assertThat(d.frozen).hasSize(c.frozen)
        }
    }

    @Test
    fun neighboursThatDoNotJoinStayOutside() {
        val d = radiusDots(input, 60f)
        assertThat(d.join).hasSize(1)
        assertThat(d.neighbours).hasSize(2)
        assertThat(d.inside).hasSize(d.stay.size + 1)
    }
}
