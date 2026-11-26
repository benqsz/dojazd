package app.dojazd.routing

import app.dojazd.model.GeoPoint
import app.dojazd.model.Stop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FootpathsTest {
    private val city = TestCity()

    private fun Footpaths.seconds(
        from: Stop,
        to: Stop,
    ): Int? = from(from.id).singleOrNull { it.to == to.id }?.seconds

    @Test
    fun pairsStopsWithinReachBothWays() {
        // given
        val a = city.stop("A", 0.0)
        val b = city.stop("B", 200.0)
        val far = city.stop("Far", 400.0)

        // when
        val footpaths = Footpaths.measure(city.stops)

        // then: 200 m take 208 seconds by the straight-line estimate; Far is 400 m from A
        assertEquals(208, footpaths.seconds(a, b))
        assertEquals(208, footpaths.seconds(b, a))
        assertEquals(208, footpaths.seconds(b, far))
        assertEquals(null, footpaths.seconds(a, far))
        assertEquals(4, footpaths.size)
    }

    @Test
    fun measuresAlongStreetsWhereKnown() {
        // given: A and B are joined by a street going round a block (600 m); C stands far from any street
        val a = city.stop("A", 0.0)
        val b = city.stop("B", 200.0)
        val c = city.stop("C", 100.0, 5_000.0)
        val d = city.stop("D", 100.0, 5_100.0)
        val streets =
            walkNetwork(
                points = listOf(a.location, spot(0.0, 200.0), spot(200.0, 200.0), b.location),
                ways = listOf(listOf(0, 1, 2, 3)),
            )

        // when
        val footpaths = Footpaths.measure(city.stops, streets)

        // then
        assertEquals(480, footpaths.seconds(a, b))
        assertEquals(480, footpaths.seconds(b, a))
        // 141 m in a straight line: estimated, as nothing is known about the streets there.
        assertEquals(footpaths.seconds(c, d), Footpaths.measure(city.stops).seconds(c, d))
    }

    @Test
    fun leavesOutStopsThatCannotBeReachedOnFoot() {
        // given: two stops either side of a river, each on its own street
        val a = city.stop("A", 0.0)
        val b = city.stop("B", 0.0, 150.0)
        val streets =
            walkNetwork(
                points = listOf(a.location, spot(300.0), b.location, spot(300.0, 150.0)),
                ways = listOf(listOf(0, 1), listOf(2, 3)),
            )

        // when
        val footpaths = Footpaths.measure(city.stops, streets)

        // then
        assertEquals(0, footpaths.size)
    }

    @Test
    fun ignoresStopsWithoutLocation() {
        // given
        city.stop("A", 0.0)
        city.stops += Stop(id = 999, code = "X", name = "X", platform = "", location = GeoPoint(0.0, 0.0))
        city.stops += Stop(id = 998, code = "Y", name = "Y", platform = "", location = GeoPoint(0.0, 0.0))

        // when
        val footpaths = Footpaths.measure(city.stops)

        // then
        assertTrue(footpaths.from(999).isEmpty())
        assertEquals(0, footpaths.size)
    }

    @Test
    fun survivesBeingWrittenAndReadBack() {
        // given
        val city = TestCity()
        val a = city.stop("A", 0.0)
        val b = city.stop("B", 100.0)
        val c = city.stop("C", 250.0)
        val measured = Footpaths.measure(city.stops)

        // when
        val read = Footpaths.decode(measured.encode())

        // then
        assertEquals(measured.size, read.size)
        for (stop in listOf(a, b, c)) {
            assertEquals(
                measured.from(stop.id).map { it.to to it.seconds }.toSet(),
                read.from(stop.id).map { it.to to it.seconds }.toSet(),
            )
        }
        assertFailsWith<IllegalArgumentException> { Footpaths.decode(ByteArray(7)) }
    }
}
