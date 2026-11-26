package app.dojazd.routing

import app.dojazd.model.GeoPoint
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/** Walks the street network of Szczecin that ships with the app, as built by `tools/citypack`. */
class ShippedWalkNetworkTest {
    @Test
    fun walksAlongTheStreetsOfSzczecin() {
        // given
        val (network, loadTime) = measureTimedValue { WalkNetwork.read(File(System.getProperty("dojazd.walks")).readBytes()) }
        println("${network.pointCount} points loaded in $loadTime")
        assertTrue(network.pointCount > 100_000)

        // The philharmonic hall and a point on the other side of the old town.
        val hall = GeoPoint(53.42939, 14.55806)
        val square = GeoPoint(53.42520, 14.55050)
        val straight = hall.distanceMetersTo(square)

        // when
        val (path, searchTime) = measureTimedValue { network.path(hall, square, maxMeters = 3_000.0) }
        val checked = assertNotNull(path, "no way on foot through the city centre")
        println(
            "straight ${straight.toInt()} m, on foot ${checked.meters.toInt()} m over ${checked.points.size} points, found in $searchTime",
        )

        // then: longer than the straight line, but not absurdly so, and made of short steps along streets
        assertTrue(checked.meters >= straight, "a walk cannot be shorter than the straight line")
        assertTrue(checked.meters < straight * 1.8, "${checked.meters} m for $straight m in a straight line")
        assertTrue(checked.points.size > 10)
        checked.points.zipWithNext { from, to -> assertTrue(from.distanceMetersTo(to) < 400, "a jump from $from to $to") }

        // The river Odra: Łasztownia island is a stone's throw from the west bank, but only by bridge.
        val westBank = GeoPoint(53.4275, 14.5650)
        val island = GeoPoint(53.4275, 14.5720)
        val across = assertNotNull(network.path(westBank, island, maxMeters = 5_000.0))
        println("across the river: straight ${westBank.distanceMetersTo(island).toInt()} m, on foot ${across.meters.toInt()} m")
        assertTrue(across.meters > westBank.distanceMetersTo(island) * 1.3, "the walk across the river ignores the water")

        val many = measureTime { repeat(50) { network.distances(hall, listOf(square, westBank, island), maxMeters = 1_900.0) } }
        println("${many / 50} per search of everything within 1.9 km")
    }
}
