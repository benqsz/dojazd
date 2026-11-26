package app.dojazd.provider

import app.dojazd.model.GeoBounds
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CityRegistryTest {
    private val citiesFile = File(System.getProperty("dojazd.citiesFile"))
    private val dataDirectory = citiesFile.parentFile
    private val registry = CityRegistry.parse(citiesFile.readText())

    @Test
    fun shippedRegistryContainsSzczecin() {
        // when
        val szczecin = registry.byId("szczecin")

        // then
        assertNotNull(szczecin)
        assertEquals("Europe/Warsaw", szczecin.timezone)
        assertNotNull(szczecin.realtime?.tripUpdates)
        assertNotNull(szczecin.addresses)
        assertNotNull(szczecin.map)
    }

    @Test
    fun everyCityIsConsistent() {
        // then
        assertEquals(
            registry.cities.size,
            registry.cities
                .map { it.id }
                .toSet()
                .size,
            "city ids must be unique",
        )
        registry.cities.forEach { city ->
            assertTrue(city.center in city.bounds, "${city.id}: center must lie inside bounds")
            val urls =
                listOfNotNull(
                    city.gtfs.url,
                    city.realtime?.tripUpdates,
                    city.realtime?.vehiclePositions,
                    city.realtime?.alerts,
                    city.attribution.url,
                    city.attribution.apiDocs,
                )
            urls.forEach { assertTrue(it.startsWith("https://"), "${city.id}: $it must use https") }
            listOfNotNull(city.addresses, city.map, city.walks).forEach { file ->
                assertTrue(File(dataDirectory, file.path).isFile, "${city.id}: ${file.path} must exist in the data directory")
            }
            city.map?.let { map ->
                // The map has to cover the middle of the city and be a file the app can read.
                val header = File(dataDirectory, map.path).inputStream().use { it.readNBytes(MapArchive.HEADER_SIZE) }
                val archive = MapArchive.parse(header)
                assertTrue(city.center in archive.bounds, "${city.id}: the map ${archive.bounds} must contain the city centre")
                assertTrue(archive.maxZoom >= 13, "${city.id}: the map stops at zoom ${archive.maxZoom}, too coarse for streets")
            }
        }
    }

    @Test
    fun readsTheHeaderOfAMapFile() {
        // given: a header saying zoom 0 to 14 and the area 14.25..14.75 east, 53.25..53.5 north
        val header = ByteArray(MapArchive.HEADER_SIZE)
        "PMTiles".encodeToByteArray().copyInto(header)
        header[7] = 3
        header[100] = 0
        header[101] = 14

        fun put(
            offset: Int,
            degrees: Double,
        ) {
            val value = (degrees * 1e7).toInt()
            for (i in 0..3) header[offset + i] = (value shr (8 * i)).toByte()
        }
        put(102, 14.25)
        put(106, 53.25)
        put(110, 14.75)
        put(114, 53.5)

        // when
        val archive = MapArchive.parse(header)

        // then
        assertEquals(GeoBounds(south = 53.25, west = 14.25, north = 53.5, east = 14.75), archive.bounds)
        assertEquals(0, archive.minZoom)
        assertEquals(14, archive.maxZoom)
        assertFailsWith<IllegalArgumentException> { MapArchive.parse(ByteArray(MapArchive.HEADER_SIZE)) }
        assertFailsWith<IllegalArgumentException> { MapArchive.parse(ByteArray(10)) }
    }

    @Test
    fun unknownKeysAreIgnored() {
        // given
        val text =
            """
            {"schemaVersion": 2, "futureField": true, "cities": []}
            """.trimIndent()

        // when
        val parsed = CityRegistry.parse(text)

        // then
        assertEquals(2, parsed.schemaVersion)
        assertTrue(parsed.cities.isEmpty())
    }
}
