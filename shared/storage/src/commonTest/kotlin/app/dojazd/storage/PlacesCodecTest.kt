package app.dojazd.storage

import app.dojazd.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlacesCodecTest {
    @Test
    fun documentSurvivesEncodeAndDecode() {
        // given
        val document =
            PlacesDocument(
                places =
                    listOf(
                        Place(id = "1", name = "Dom", icon = "home", address = "Polna 3, Szczecin", location = GeoPoint(53.43, 14.55)),
                        Place(id = "2", name = "Basen", location = GeoPoint(53.44, 14.50)),
                    ),
            )

        // when
        val decoded = PlacesCodec.decode(PlacesCodec.encode(document))

        // then
        assertEquals(document, decoded)
    }

    @Test
    fun encodedDocumentCarriesSchemaVersion() {
        // when
        val text = PlacesCodec.encode(PlacesDocument())

        // then
        assertTrue("\"schemaVersion\": 2" in text, text)
    }

    @Test
    fun decodeToleratesFieldsFromOtherVersions() {
        // given: "kind" is from the first version of the format, the rest from an imagined later one
        val text =
            """
            {
              "schemaVersion": 3,
              "syncedAt": "2027-01-01",
              "places": [
                {"id": "1", "name": "Praca", "kind": "work", "colour": "blue", "location": {"lat": 53.4, "lon": 14.5}}
              ]
            }
            """.trimIndent()

        // when
        val decoded = PlacesCodec.decode(text)

        // then: what is not known is skipped, what is missing is empty
        assertEquals(3, decoded.schemaVersion)
        assertEquals(Place(id = "1", name = "Praca", location = GeoPoint(53.4, 14.5)), decoded.places.single())
    }
}
