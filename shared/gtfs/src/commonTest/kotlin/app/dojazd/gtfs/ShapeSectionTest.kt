package app.dojazd.gtfs

import app.dojazd.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShapeSectionTest {
    /** A point given in metres north and east of an arbitrary corner; a hundredth of a degree is about a kilometre. */
    private fun at(
        metersNorth: Double,
        metersEast: Double = 0.0,
    ) = GeoPoint(53.0 + metersNorth / 111_320.0, 14.0 + metersEast / 66_990.0)

    // North for 400 m, then east for 400 m, a point every 100 m.
    private val shape = (0..4).map { at(it * 100.0) } + (1..4).map { at(400.0, it * 100.0) }

    @Test
    fun cutsThePathBetweenTwoStopsBesideIt() {
        // given: stops ten metres to the side of the path, at 100 m and just round the corner
        val from = at(100.0, 10.0)
        val to = at(410.0, 200.0)

        // when
        val section = ShapeSection.between(shape, from, to)

        // then: round the corner, not across it
        assertEquals(shape.subList(1, 7), section)
    }

    @Test
    fun refusesStopsInTheWrongOrderOrOffThePath() {
        assertNull(ShapeSection.between(shape, at(410.0, 200.0), at(100.0, 10.0)), "the vehicle does not run backwards")
        assertNull(ShapeSection.between(shape, at(100.0), at(100.0, 2_000.0)), "the second stop is nowhere near the path")
        assertNull(ShapeSection.between(shape, at(100.0), at(100.0)), "no distance travelled")
        assertNull(ShapeSection.between(listOf(at(0.0)), at(0.0), at(0.0)))
    }

    @Test
    fun findsTheRightPassOnALineThatComesBackOnItself() {
        // given: out along a street and back the same way; the stop nearest the start is passed on the way out and again at the very end
        val outAndBack = (0..4).map { at(it * 100.0) } + (3 downTo 0).map { at(it * 100.0, 2.0) }
        val nearStart = at(0.0, 3.0)
        val farEnd = at(400.0)

        // when: riding from the far end back to the start
        val back = ShapeSection.between(outAndBack, farEnd, nearStart)

        // and from the start out to the far end, although the last point of the path is the one nearest to the start
        val out = ShapeSection.between(outAndBack, nearStart, farEnd)

        // then
        assertEquals(outAndBack.subList(4, 9), back)
        assertEquals(outAndBack.subList(0, 5), out)
    }
}
