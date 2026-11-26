package app.dojazd.gtfs

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GtfsTimeTest {
    @Test
    fun parsesRegularTime() {
        assertEquals(29_130, GtfsTime.parseSeconds("08:05:30"))
    }

    @Test
    fun parsesSingleDigitHour() {
        assertEquals(25_200, GtfsTime.parseSeconds("7:00:00"))
    }

    @Test
    fun parsesTimeAfterMidnightOfTheServiceDay() {
        assertEquals(90_600, GtfsTime.parseSeconds("25:10:00"))
    }

    @Test
    fun rejectsMalformedValues() {
        listOf("", "08:05", "8:60:00", "8:00:60", "-1:00:00", "aa:bb:cc").forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { GtfsTime.parseSeconds(value) }
        }
    }

    @Test
    fun serviceDayStartsAtMidnightExceptWhenTheClocksChange() {
        // given the days around the change to winter time in Poland, 25 October 2026
        val zone = "Europe/Warsaw"
        val ordinary = serviceDayStart(LocalDate(2026, 10, 24), zone)
        val changing = serviceDayStart(LocalDate(2026, 10, 25), zone)
        val after = serviceDayStart(LocalDate(2026, 10, 26), zone)

        // then the day of the change starts an hour into itself and lasts a day like any other
        assertEquals(LocalDate(2026, 10, 24).atStartOfDayIn(TimeZone.of(zone)).epochSeconds, ordinary)
        assertEquals(LocalDate(2026, 10, 25).atStartOfDayIn(TimeZone.of(zone)).epochSeconds + 3_600, changing)
        assertEquals(86_400, after - changing)
    }
}
