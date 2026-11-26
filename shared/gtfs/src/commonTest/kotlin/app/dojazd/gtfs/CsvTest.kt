package app.dojazd.gtfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CsvTest {
    private fun parse(text: String): List<List<String>> = text.lineSequence().csvRecords().toList()

    @Test
    fun splitsPlainRecords() {
        assertEquals(
            listOf(listOf("a", "b", "c"), listOf("1", "", "3")),
            parse("a,b,c\n1,,3"),
        )
    }

    @Test
    fun handlesQuotesEscapesAndCommasInsideQuotes() {
        assertEquals(
            listOf(listOf("Plac \"Rodła\"", "x,y", "")),
            parse("\"Plac \"\"Rodła\"\"\",\"x,y\","),
        )
    }

    @Test
    fun joinsQuotedFieldSpanningLines() {
        assertEquals(
            listOf(listOf("1", "first\nsecond", "end")),
            parse("1,\"first\nsecond\",end"),
        )
    }

    @Test
    fun skipsByteOrderMarkAndBlankLines() {
        assertEquals(
            listOf(listOf("stop_id", "stop_name"), listOf("1", "A")),
            parse("\uFEFF\"stop_id\",\"stop_name\"\n\n1,A\n"),
        )
    }

    @Test
    fun columnsResolveByNameAndReportMissingOnes() {
        // given
        val columns = CsvColumns("stops.txt", listOf("stop_id", " stop_name "))

        // then
        assertEquals(1, columns.required("stop_name"))
        assertEquals(-1, columns.optional("stop_code"))
        assertEquals("", listOf("1", "A").field(columns.optional("stop_code")))
        assertFailsWith<GtfsFormatException> { columns.required("stop_lat") }
    }
}
