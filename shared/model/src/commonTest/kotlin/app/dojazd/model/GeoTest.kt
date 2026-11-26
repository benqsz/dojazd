package app.dojazd.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeoTest {
    @Test
    fun oneDegreeOfLongitudeOnTheEquator() {
        // given
        val from = GeoPoint(lat = 0.0, lon = 0.0)
        val to = GeoPoint(lat = 0.0, lon = 1.0)

        // when
        val distance = from.distanceMetersTo(to)

        // then: R * pi / 180
        assertEquals(111_195.08, distance, absoluteTolerance = 0.5)
    }

    @Test
    fun distanceIsSymmetricAndZeroToSelf() {
        // given
        val a = GeoPoint(lat = 53.4285, lon = 14.5528)
        val b = GeoPoint(lat = 53.3340, lon = 15.0500)

        // then
        assertEquals(0.0, a.distanceMetersTo(a), absoluteTolerance = 1e-6)
        assertEquals(a.distanceMetersTo(b), b.distanceMetersTo(a), absoluteTolerance = 1e-6)
    }

    @Test
    fun boundsContainOnlyPointsInside() {
        // given
        val bounds = GeoBounds(south = 53.2, west = 14.2, north = 53.7, east = 15.2)

        // then
        assertTrue(GeoPoint(lat = 53.4285, lon = 14.5528) in bounds)
        assertFalse(GeoPoint(lat = 52.2297, lon = 21.0122) in bounds)
    }
}
