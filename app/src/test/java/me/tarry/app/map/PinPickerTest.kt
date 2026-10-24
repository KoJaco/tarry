package me.tarry.app.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PinPickerTest {
    private val a = PickPlace("a", "A", GeoPoint(-33.0, 151.0), lastVisitAt = 100)
    private val b = PickPlace("b", "B", GeoPoint(-34.0, 151.0), lastVisitAt = 500)
    private val never = PickPlace("n", "N", GeoPoint(-35.0, 151.0), lastVisitAt = null)

    @Test
    fun startsAtTheChosenPlaceFirst() {
        assertThat(startCentre(GeoPoint(1.0, 2.0), listOf(a, b))).isEqualTo(GeoPoint(1.0, 2.0))
    }

    @Test
    fun thenTheMostRecentlyVisitedPlace() {
        assertThat(startCentre(null, listOf(never, a, b))).isEqualTo(b.centre)
    }

    @Test
    fun thenAnyPlaceThenNull() {
        assertThat(startCentre(null, listOf(never))).isEqualTo(never.centre)
        assertThat(startCentre(null, emptyList())).isNull()
    }

    @Test
    fun nearbyPlacesAreWithinSnapDistanceNearestFirst() {
        val pin = GeoPoint(-33.0, 151.0)
        val close = PickPlace("c", "C", GeoPoint(-33.0005, 151.0), null) // about 55 m
        val outside = PickPlace("e", "E", GeoPoint(-33.0020, 151.0), null) // about 222 m
        val result = nearbyPlaces(pin, listOf(outside, close, a))
        assertThat(result.map { it.place.id }).containsExactly("a", "c").inOrder()
        assertThat(result.first().distanceM).isWithin(0.5).of(0.0)
    }

    @Test
    fun coordinatesUseFiveDecimalsWithADot() {
        assertThat(formatPinCoordinates(-33.8688197, 151.2092955)).isEqualTo("-33.86882, 151.20930")
    }
}
