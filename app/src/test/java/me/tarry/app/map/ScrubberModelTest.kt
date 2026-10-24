package me.tarry.app.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrubberModelTest {
    private val visit1 = ScrubItem("v1", ScrubKind.VISIT, 1_000, 2_000)
    private val trip = ScrubItem("t1", ScrubKind.TRIP, 2_000, 3_000)
    private val visit2 = ScrubItem("v2", ScrubKind.VISIT, 3_000, 5_000)

    private fun model(items: List<ScrubItem> = listOf(visit2, visit1, trip), points: List<RoutePoint> = emptyList()) =
        ScrubberModel(items, 0, 10_000, points)

    private fun ping(t: Long, lat: Double, lng: Double, acc: Float = 10f) = RoutePoint(t, lat, lng, acc)

    @Test
    fun emptyDay() {
        val m = model(emptyList())
        assertThat(m.range).isNull()
        assertThat(m.blocks).isEmpty()
        assertThat(m.visitStarts()).isEmpty()
        assertThat(m.snap(42)).isEqualTo(42)
        assertThat(m.itemAt(1)).isNull()
    }

    @Test
    fun rangeClampedToDayBounds() {
        val m = ScrubberModel(
            listOf(ScrubItem("a", ScrubKind.TRIP, -500, 100), ScrubItem("b", ScrubKind.GAP, 900, 20_000)),
            0, 1_000, emptyList(),
        )
        assertThat(m.range).isEqualTo(TimeSpan(0, 1_000))
        assertThat(m.blocks.map { it.item.start to it.item.end }).containsExactly(0L to 100L, 900L to 1_000L).inOrder()
    }

    @Test
    fun itemsOutsideDayDropped() {
        val m = ScrubberModel(listOf(ScrubItem("a", ScrubKind.VISIT, 2_000, 3_000)), 0, 1_000, emptyList())
        assertThat(m.range).isNull()
    }

    @Test
    fun blocksSortedAndProportional() {
        val m = model()
        assertThat(m.range).isEqualTo(TimeSpan(1_000, 5_000))
        assertThat(m.blocks.map { it.item.id }).containsExactly("v1", "t1", "v2").inOrder()
        assertThat(m.blocks[0].startFraction).isEqualTo(0f)
        assertThat(m.blocks[0].endFraction).isEqualTo(0.25f)
        assertThat(m.blocks[2].startFraction).isEqualTo(0.5f)
        assertThat(m.blocks[2].endFraction).isEqualTo(1f)
    }

    @Test
    fun fractionTimeRoundTripAndClamp() {
        val m = model()
        for (t in listOf(1_000L, 1_250L, 3_000L, 5_000L)) assertThat(m.timeAt(m.fractionAt(t))).isEqualTo(t)
        assertThat(m.fractionAt(-5)).isEqualTo(0f)
        assertThat(m.fractionAt(99_999)).isEqualTo(1f)
        assertThat(m.timeAt(-1f)).isEqualTo(1_000)
        assertThat(m.timeAt(2f)).isEqualTo(5_000)
    }

    @Test
    fun interpolatesMidpoint() {
        val m = model(points = listOf(ping(1_000, 10.0, 20.0), ping(3_000, 12.0, 24.0)))
        val p = m.positionAt(2_000)!!
        assertThat(p.lat).isWithin(1e-9).of(11.0)
        assertThat(p.lng).isWithin(1e-9).of(22.0)
    }

    @Test
    fun exactPingReturned() {
        val m = model(points = listOf(ping(3_000, 12.0, 24.0), ping(1_000, 10.0, 20.0)))
        assertThat(m.positionAt(1_000)).isEqualTo(GeoPoint(10.0, 20.0))
        assertThat(m.positionAt(3_000)).isEqualTo(GeoPoint(12.0, 24.0))
    }

    @Test
    fun outsideSpanOrNoPingsIsNull() {
        val m = model(points = listOf(ping(1_000, 10.0, 20.0), ping(3_000, 12.0, 24.0)))
        assertThat(m.positionAt(999)).isNull()
        assertThat(m.positionAt(3_001)).isNull()
        assertThat(model().positionAt(2_000)).isNull()
    }

    @Test
    fun breakInRouteIsNull() {
        val gap = MapConfig().routeBreakMs
        val m = ScrubberModel(
            listOf(visit1), 0, 10_000_000,
            listOf(ping(0, 1.0, 1.0), ping(gap + 1, 2.0, 2.0), ping(gap + 1 + 60_000, 3.0, 3.0)),
        )
        assertThat(m.positionAt(gap / 2)).isNull()
        assertThat(m.positionAt(gap + 1 + 30_000)).isNotNull()
        val edge = ScrubberModel(listOf(visit1), 0, 10_000_000, listOf(ping(0, 1.0, 1.0), ping(gap, 2.0, 2.0)))
        assertThat(edge.positionAt(gap / 2)).isNotNull()
    }

    @Test
    fun inaccuratePingsIgnored() {
        val m = model(
            points = listOf(ping(1_000, 10.0, 20.0), ping(2_000, 50.0, 50.0, acc = 500f), ping(3_000, 12.0, 24.0)),
        )
        val p = m.positionAt(2_000)!!
        assertThat(p.lat).isWithin(1e-9).of(11.0)
    }

    @Test
    fun snapNearestTiesAndNone() {
        val m = model() // starts 1000, 3000
        assertThat(m.visitStarts()).containsExactly(1_000L, 3_000L).inOrder()
        assertThat(m.snap(1_900)).isEqualTo(1_000)
        assertThat(m.snap(2_100)).isEqualTo(3_000)
        assertThat(m.snap(2_000)).isEqualTo(1_000) // tie -> earlier
        assertThat(model(listOf(trip)).snap(2_500)).isEqualTo(2_500)
    }

    @Test
    fun nextAndPrevious() {
        val m = model()
        assertThat(m.next(0)).isEqualTo(1_000)
        assertThat(m.next(1_000)).isEqualTo(3_000)
        assertThat(m.next(2_000)).isEqualTo(3_000)
        assertThat(m.next(3_000)).isNull()
        assertThat(m.previous(3_000)).isEqualTo(1_000)
        assertThat(m.previous(2_000)).isEqualTo(1_000)
        assertThat(m.previous(1_000)).isNull()
        assertThat(m.previous(9_000)).isEqualTo(3_000)
    }

    @Test
    fun itemAtHalfOpenAndPrecedence() {
        val gap = ScrubItem("g", ScrubKind.GAP, 1_500, 2_500)
        val m = model(listOf(visit1, trip, gap))
        assertThat(m.itemAt(1_000)?.id).isEqualTo("v1")
        assertThat(m.itemAt(1_600)?.id).isEqualTo("v1") // visit beats gap
        assertThat(m.itemAt(2_000)?.id).isEqualTo("t1") // half-open: visit1 ended; trip beats gap
        assertThat(m.itemAt(3_000)).isNull()
        assertThat(m.itemAt(500)).isNull()
    }
}
