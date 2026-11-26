package app.dojazd.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalkNetworkTest {
    // A street from A north to a corner, then east to B: 100 m each. Far to the north, a path that joins nothing else.
    private val a = spot(0.0)
    private val corner = spot(100.0)
    private val b = spot(100.0, 100.0)
    private val islandStart = spot(1_000.0)
    private val islandEnd = spot(1_000.0, 50.0)
    private val network = walkNetwork(listOf(a, corner, b, islandStart, islandEnd), listOf(listOf(0, 1, 2), listOf(3, 4)))

    private fun assertMeters(
        expected: Double,
        actual: Double?,
    ) {
        assertNotNull(actual)
        assertTrue(kotlin.math.abs(actual - expected) < 1.0, "expected about $expected m, got $actual")
    }

    @Test
    fun followsTheStreetAroundTheCorner() {
        // when
        val path = assertNotNull(network.path(a, b, maxMeters = 1_000.0))

        // then: 200 m along the street, not 141 m across the block
        assertMeters(200.0, path.meters)
        assertEquals(listOf(a, a, corner, b, b).map { it.rounded() }, path.points.map { it.rounded() })
    }

    @Test
    fun countsTheStraightBitBetweenAPlaceAndTheStreet() {
        // given: a house 30 m east of the first stretch, level with its start
        val house = spot(0.0, 30.0)

        // when
        val path = assertNotNull(network.path(house, b, maxMeters = 1_000.0))

        // then
        assertMeters(230.0, path.meters)
        assertEquals(house, path.points.first())
        assertEquals(b, path.points.last())
    }

    @Test
    fun measuresDistancesToSeveralPlacesAtOnce() {
        // when
        val distances = assertNotNull(network.distances(a, listOf(b, corner, islandEnd, spot(5_000.0)), maxMeters = 2_000.0))

        // then
        assertMeters(200.0, distances[0])
        assertMeters(100.0, distances[1])
        assertEquals(Double.POSITIVE_INFINITY, distances[2], "the island cannot be walked to")
        assertNull(distances[3], "nothing is known about a place far from every street")
    }

    @Test
    fun knowsNothingAboutAStartFarFromEveryStreet() {
        assertNull(network.distances(spot(5_000.0), listOf(a), maxMeters = 2_000.0))
        assertNull(network.path(spot(5_000.0), a, maxMeters = 2_000.0))
        assertNull(network.path(a, spot(5_000.0), maxMeters = 2_000.0))
    }

    @Test
    fun givesUpBeyondTheLongestWalkAsked() {
        assertNull(network.path(a, b, maxMeters = 150.0))
        assertEquals(listOf<Double?>(Double.POSITIVE_INFINITY), network.distances(a, listOf(b), maxMeters = 150.0))
        assertNull(network.path(a, islandEnd, maxMeters = 5_000.0))
    }

    @Test
    fun takesTheShorterWayRoundABlock() {
        // given: a block 100 m by 300 m; from one corner to the opposite corner along the short side first or the long side first is the same,
        // but a diagonal alley through the block is shorter
        val southWest = spot(0.0)
        val northWest = spot(100.0)
        val northEast = spot(100.0, 300.0)
        val southEast = spot(0.0, 300.0)
        val block =
            walkNetwork(
                points = listOf(southWest, northWest, northEast, southEast),
                ways = listOf(listOf(0, 1, 2, 3, 0), listOf(1, 3)),
            )

        // when: from the north-west corner to the south-east one
        val path = assertNotNull(block.path(northWest, southEast, maxMeters = 2_000.0))

        // then: the alley, about 316 m, instead of 400 m round either way
        assertMeters(316.2, path.meters)
        assertEquals(4, path.points.size)
    }

    @Test
    fun isNotFooledByADeadEndRightNextToThePlace() {
        // given: a scrap of path 5 m from the house that leads nowhere, and the street 30 m away
        val house = spot(0.0, 30.0)
        val scrapStart = spot(0.0, 35.0)
        val scrapEnd = spot(0.0, 45.0)
        val withScrap = walkNetwork(listOf(a, corner, b, scrapStart, scrapEnd), listOf(listOf(0, 1, 2), listOf(3, 4)))

        // when
        val path = assertNotNull(withScrap.path(house, b, maxMeters = 1_000.0))

        // then
        assertMeters(230.0, path.meters)
    }

    @Test
    fun rejectsFilesThatAreNotWalkFiles() {
        assertFailsWith<IllegalArgumentException> { WalkNetwork.read("<html>Not Found</html>".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { WalkNetwork.read(walkFile(listOf(a, b), listOf(listOf(0, 1))).copyOf(12)) }
        assertFailsWith<IllegalArgumentException> { walkNetwork(listOf(a, b), listOf(listOf(0, 7))) }
    }

    @Test
    fun readsAnEmptyNetwork() {
        // when
        val empty = walkNetwork(emptyList(), emptyList())

        // then
        assertEquals(0, empty.pointCount)
        assertNull(empty.path(a, b, maxMeters = 1_000.0))
    }

    /** Positions as stored in the file, to compare points that went through it with those that did not. */
    private fun app.dojazd.model.GeoPoint.rounded() = Math.round(lat * 1e6) to Math.round(lon * 1e6)
}
