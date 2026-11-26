package app.dojazd.routing

import app.dojazd.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WalkEstimateTest {
    @Test
    fun hundredMetersTakesAboutHundredSeconds() {
        // 100 m * 1.3 detour / 1.25 m/s
        assertEquals(104, WalkEstimate.seconds(100.0))
    }

    @Test
    fun samePointTakesNoTime() {
        // given
        val point = GeoPoint(lat = 53.4285, lon = 14.5528)

        // then
        assertEquals(0, WalkEstimate.seconds(point, point))
    }

    @Test
    fun rejectsNegativeDistance() {
        assertFailsWith<IllegalArgumentException> { WalkEstimate.seconds(-1.0) }
    }
}
