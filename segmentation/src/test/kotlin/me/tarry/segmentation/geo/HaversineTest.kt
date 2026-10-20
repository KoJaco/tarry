package me.tarry.segmentation.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class HaversineTest {
    @Test
    fun samePointIsZero() {
        assertEquals(0.0, haversineMeters(-33.87, 151.21, -33.87, 151.21), 1e-9)
    }

    @Test
    fun oneDegreeOfLatitudeIsAbout111190Metres() {
        assertEquals(111_195.0, haversineMeters(0.0, 0.0, 1.0, 0.0), 50.0)
    }

    @Test
    fun shortPairIsAboutSixtyMetres() {
        // 0.00054 degrees of latitude = 0.00054 * 111_195 m = ~60.0 m
        assertEquals(60.0, haversineMeters(-33.8700, 151.2100, -33.87054, 151.2100), 0.5)
    }
}
