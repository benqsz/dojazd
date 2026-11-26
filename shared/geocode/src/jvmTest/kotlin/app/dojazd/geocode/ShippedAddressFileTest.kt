package app.dojazd.geocode

import app.dojazd.model.GeoBounds
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/** Searches the address file of Szczecin that ships with the app, as built by `tools/citypack`. */
class ShippedAddressFileTest {
    private val szczecinCentre = GeoBounds(south = 53.41, west = 14.52, north = 53.45, east = 14.58)

    @Test
    fun findsKnownPlacesInSzczecin() {
        val path = System.getProperty("dojazd.addresses")

        // when
        val (index, loadTime) =
            measureTimedValue {
                File(path).useLines { AddressIndex.parse(it) }
            }
        println("${index.addressCount} addresses, ${index.streetCount} streets, ${index.placeCount} places loaded in $loadTime")
        println("Source: ${index.source}")

        // then: each query's best match
        val expected =
            listOf(
                "wojska polskiego 12" to "Aleja Wojska Polskiego 12",
                "al. wojska polskiego 12" to "Aleja Wojska Polskiego 12",
                "mickiewicza 161" to "Adama Mickiewicza 161",
                "krzywoustego 9" to "Bolesława Krzywoustego 9",
                "małopolska 48" to "Małopolska 48",
                "filharmonia" to "Filharmonia im. Mieczysława Karłowicza",
                "galaxy" to "Galaxy",
                "pogodno" to "Pogodno",
                "plac rodla" to "Plac Rodła",
            )
        val best =
            expected.associate { (query, name) ->
                val matches = index.search(query, limit = 5)
                println("$query -> ${matches.joinToString { "${it.name} (${it.detail})" }}")
                val first = assertNotNull(matches.firstOrNull(), "nothing found for '$query'")
                assertEquals(name, first.name, "best match for '$query'")
                query to first
            }

        // The philharmonic hall is at Małopolska 48, so the address and the place must be the same spot,
        // and a point picked there must be named after the address.
        val hall = best.getValue("filharmonia").location
        val address = best.getValue("małopolska 48").location
        assertTrue(hall.distanceMetersTo(address) < 150, "hall at $hall, its address at $address")
        assertTrue(hall in szczecinCentre, "hall at $hall is outside the city centre")
        assertEquals("Małopolska 48", index.nearest(address)?.name)

        val queries = listOf("w", "po", "szkol", "wojska polskiego 1", "plac", "aleja", "12", "sw")
        val searchTime = measureTime { repeat(20) { queries.forEach { index.search(it) } } }
        println("${searchTime / (20 * queries.size)} per search")
    }
}
