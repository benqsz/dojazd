package app.dojazd.geocode

import app.dojazd.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddressIndexTest {
    private fun line(vararg fields: String) = fields.joinToString("\t")

    private val file =
        listOf(
            line("#dojazd-addresses", "1"),
            line("#source", "OpenStreetMap contributors, ODbL 1.0"),
            line("#built", "2026-11-23"),
            line("S", "Aleja Wojska Polskiego", "Szczecin", "53.4400", "14.5300"),
            line("N", "12", "53.4401", "14.5301"),
            line("N", "12A", "53.4402", "14.5302"),
            line("N", "120", "53.4450", "14.5200"),
            line("N", "7", "53.4390", "14.5310"),
            line("S", "Wojska Polskiego", "Police", "53.5500", "14.5700"),
            line("N", "12", "53.5501", "14.5701"),
            line("S", "Świętego Łazarza", "Szczecin", "53.4000", "14.5000"),
            line("S", "Polna", "Szczecin", "53.4100", "14.5100"),
            line("N", "3", "53.4101", "14.5101"),
            line("P", "Łazarz", "", "53.4010", "14.5010"),
            line("P", "Szkoła Podstawowa nr 12", "Polna 5", "53.4105", "14.5105"),
            line("P", "Wojska Polskiego Café", "Aleja Wojska Polskiego 7", "53.4391", "14.5311"),
        )

    private val index = AddressIndex.parse(file.asSequence())

    private fun names(
        query: String,
        limit: Int = 20,
    ) = index.search(query, limit).map { it.name }

    @Test
    fun readsStreetsNumbersAndPlaces() {
        assertEquals(4, index.streetCount)
        assertEquals(6, index.addressCount)
        assertEquals(3, index.placeCount)
        assertEquals("OpenStreetMap contributors, ODbL 1.0", index.source)
    }

    @Test
    fun findsAnAddressByStreetAndNumber() {
        // when
        val match = index.search("wojska polskiego 12a").first()

        // then
        assertEquals("Aleja Wojska Polskiego 12A", match.name)
        assertEquals("Szczecin", match.detail)
        assertEquals(GeoPoint(53.4402, 14.5302), match.location)
        assertEquals(PlaceMatch.Kind.ADDRESS, match.kind)
    }

    @Test
    fun ignoresDiacriticsCaseAndPunctuation() {
        assertEquals(listOf("Świętego Łazarza", "Łazarz"), names("LAZAR"))
        assertEquals("Aleja Wojska Polskiego 12", names("Wojska-Polskiego, 12").first())
    }

    @Test
    fun matchesTheStartOfEachWordInAnyOrder() {
        assertEquals("Aleja Wojska Polskiego", names("pol woj").first())
        assertTrue(names("olskiego").isEmpty(), "the middle of a word is not a match")
    }

    @Test
    fun understandsCommonAbbreviations() {
        assertEquals("Aleja Wojska Polskiego 7", names("al. Wojska Polskiego 7").first())
        assertEquals("Świętego Łazarza", names("ul. św. Łazarza").first())
        assertEquals("Polna 3", names("ulica polna 3").first())
    }

    @Test
    fun aTownNameTellsStreetsApart() {
        // when
        val inPolice = index.search("wojska polskiego 12 police")

        // then
        assertEquals(listOf("Wojska Polskiego 12" to "Police"), inPolice.map { it.name to it.detail })
    }

    @Test
    fun streetsKnownByTheEndOfTheirNameRankWithThoseNamedExactlySo() {
        // when
        val matches = index.search("wojska polskiego")

        // then: both streets match fully, the one with more addresses first; the café named after them last
        assertEquals(
            listOf(
                "Aleja Wojska Polskiego" to "Szczecin",
                "Wojska Polskiego" to "Police",
                "Wojska Polskiego Café" to "Aleja Wojska Polskiego 7",
            ),
            matches.map { it.name to it.detail },
        )
    }

    @Test
    fun aWordFromTheMiddleOfANameRanksLast() {
        // then: "wojska" starts the street in Police and the café, but sits in the middle of the avenue's name
        assertEquals(listOf("Wojska Polskiego", "Wojska Polskiego Café", "Aleja Wojska Polskiego"), names("wojska"))
    }

    @Test
    fun anUnknownNumberStillOffersTheStreetAndSimilarNumbers() {
        // when: there is no number 1, but 12, 12A and 120 start with it
        val matches = names("aleja wojska polskiego 1")

        // then
        assertEquals(
            listOf("Aleja Wojska Polskiego", "Aleja Wojska Polskiego 12", "Aleja Wojska Polskiego 120", "Aleja Wojska Polskiego 12A"),
            matches,
        )
    }

    @Test
    fun aNumberAloneIsPartOfAName() {
        // then: "12" on its own is not a house number, it finds the school
        assertEquals(listOf("Szkoła Podstawowa nr 12"), names("12"))
        assertEquals(listOf("Szkoła Podstawowa nr 12"), names("szkoła 12"))
    }

    @Test
    fun limitsTheNumberOfResults() {
        assertEquals(1, names("wojska", limit = 1).size)
        assertTrue(names("").isEmpty())
        assertTrue(names("  ,. ").isEmpty())
    }

    @Test
    fun findsTheNearestAddressWithinReach() {
        // when: a few metres from Polna 3, and far from everything
        val near = index.nearest(GeoPoint(53.41012, 14.51012))
        val far = index.nearest(GeoPoint(53.3000, 14.3000))

        // then
        assertEquals("Polna 3", near?.name)
        assertNull(far)
    }

    @Test
    fun rejectsFilesOfAnotherFormat() {
        assertFailsWith<IllegalArgumentException> { AddressIndex.parse(sequenceOf(line("#dojazd-addresses", "99"))) }
        assertFailsWith<IllegalArgumentException> { AddressIndex.parse(sequenceOf("<html>Not Found</html>")) }
    }

    @Test
    fun skipsLinesItCannotRead() {
        // given: a number before any street, a street without coordinates, an unknown kind of line
        val broken =
            listOf(
                line("#dojazd-addresses", "1"),
                line("N", "1", "53.0", "14.0"),
                line("S", "Krzywa", "Szczecin", "x", "y"),
                line("X", "future"),
                line("S", "Prosta", "Szczecin", "53.1", "14.1"),
            )

        // when
        val parsed = AddressIndex.parse(broken.asSequence())

        // then
        assertEquals(1, parsed.streetCount)
        assertEquals(0, parsed.addressCount)
    }
}
