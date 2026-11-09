package me.tarry.app.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VisitPinsTest {
    private fun input(id: String, start: Long, pos: GeoPoint?) = PinInput(id, start, pos, 0xFF112233.toInt(), "job-$id")

    @Test
    fun numbersAllVisitsButPinsOnlyThoseWithPositions() {
        val pins = visitPins(
            listOf(
                input("c", 300, GeoPoint(3.0, 3.0)),
                input("a", 100, GeoPoint(1.0, 1.0)),
                input("b", 200, null),
            ),
        )
        assertThat(pins.map { it.visitId to it.number }).containsExactly("a" to 1, "c" to 3).inOrder()
        assertThat(pins[0].jobId).isEqualTo("job-a")
        assertThat(pins[0].jobColorArgb).isEqualTo(0xFF112233.toInt())
    }

    @Test
    fun tiesBrokenByVisitId() {
        val pins = visitPins(listOf(input("z", 100, GeoPoint(0.0, 0.0)), input("m", 100, GeoPoint(0.0, 0.0))))
        assertThat(pins.map { it.visitId to it.number }).containsExactly("m" to 1, "z" to 2).inOrder()
    }

    @Test
    fun emptyInput() {
        assertThat(visitPins(emptyList())).isEmpty()
    }

    @Test
    fun boundsEmptyIsNull() {
        assertThat(boundsOf(emptyList())).isNull()
    }

    @Test
    fun boundsSinglePointIsZeroSize() {
        assertThat(boundsOf(listOf(GeoPoint(-33.9, 151.2)))).isEqualTo(GeoBounds(-33.9, 151.2, -33.9, 151.2))
    }

    @Test
    fun boundsOfMany() {
        val b = boundsOf(listOf(GeoPoint(-33.9, 151.2), GeoPoint(-34.1, 151.0), GeoPoint(-33.8, 151.4)))
        assertThat(b).isEqualTo(GeoBounds(south = -34.1, west = 151.0, north = -33.8, east = 151.4))
    }

    @Test
    fun placesNearOrdersAndCutsOff() {
        val pin = GeoPoint(-33.9, 151.2)
        // ~0.001 deg lat is ~111 m.
        val places = listOf(
            "far" to GeoPoint(-33.9 + 0.01, 151.2),
            "mid" to GeoPoint(-33.9 + 0.001, 151.2),
            "near" to GeoPoint(-33.9 + 0.0002, 151.2),
        )
        val near = placesNear(pin, places)
        assertThat(near.map { it.first }).containsExactly("near", "mid").inOrder()
        assertThat(near[0].second).isWithin(1.0).of(22.2)
        assertThat(placesNear(pin, places, withinM = 10.0)).isEmpty()
        assertThat(placesNear(pin, places, withinM = 5_000.0).map { it.first }).containsExactly("near", "mid", "far").inOrder()
    }
}

class GroupCoincidentPinsTest {
    private fun pin(id: String, number: Int, lat: Double, lng: Double) = VisitPin(id, number, GeoPoint(lat, lng), null, null)

    @Test
    fun visitsAtTheSameSpotShareOneMarkerLabelledWithBothNumbers() {
        val groups = groupCoincidentPins(listOf(pin("a", 5, 1.0, 1.0), pin("b", 6, 2.0, 2.0), pin("c", 7, 2.0, 2.0)))
        assertThat(groups.map { it.label }).containsExactly("5", "6, 7").inOrder()
        assertThat(groups[1].first.visitId).isEqualTo("b")
    }

    @Test
    fun spotsMoreThanAHairApartStaySeparate() {
        val groups = groupCoincidentPins(listOf(pin("a", 1, 1.0, 1.0), pin("b", 2, 1.0001, 1.0)))
        assertThat(groups.map { it.label }).containsExactly("1", "2").inOrder()
    }

    @Test
    fun aSharedMarkerIsWiderThanALoneOne() {
        val lone = groupCoincidentPins(listOf(pin("a", 7, 1.0, 1.0))).single()
        val shared = groupCoincidentPins(listOf(pin("a", 6, 1.0, 1.0), pin("b", 7, 1.0, 1.0))).single()
        assertThat(shared.radius).isGreaterThan(lone.radius)
    }

    @Test
    fun noPinsNoGroups() {
        assertThat(groupCoincidentPins(emptyList())).isEmpty()
    }
}
