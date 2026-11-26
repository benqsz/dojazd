package app.dojazd.gtfs

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceCalendarTest {
    private val weekdays = 0b0011111
    private val saturday = 0b0100000

    private val calendar =
        ServiceCalendar(
            rules =
                listOf(
                    ServiceCalendar.Rule(service = 1, weekdays = weekdays, start = 20260101, end = 20261231),
                    ServiceCalendar.Rule(service = 2, weekdays = saturday, start = 20260101, end = 20261231),
                    ServiceCalendar.Rule(service = 3, weekdays = weekdays, start = 20260601, end = 20260831),
                ),
            changesByDate =
                mapOf(
                    // Friday 1 May 2026 is a public holiday: the weekday service is replaced.
                    20260501 to
                        listOf(
                            ServiceCalendar.Change(service = 1, added = false),
                            ServiceCalendar.Change(service = 4, added = true),
                        ),
                ),
        )

    @Test
    fun selectsServicesByWeekday() {
        assertEquals(setOf(1L), calendar.activeOn(LocalDate(2026, 3, 2))) // Monday
        assertEquals(setOf(2L), calendar.activeOn(LocalDate(2026, 3, 7))) // Saturday
        assertEquals(emptySet(), calendar.activeOn(LocalDate(2026, 3, 8))) // Sunday
    }

    @Test
    fun respectsServiceDateRange() {
        assertEquals(setOf(1L, 3L), calendar.activeOn(LocalDate(2026, 6, 1)))
        assertEquals(setOf(1L), calendar.activeOn(LocalDate(2026, 9, 1)))
        assertEquals(emptySet(), calendar.activeOn(LocalDate(2027, 1, 4)))
    }

    @Test
    fun appliesAddedAndRemovedDates() {
        assertEquals(setOf(4L), calendar.activeOn(LocalDate(2026, 5, 1)))
    }

    @Test
    fun formatsGtfsDate() {
        assertEquals(20260305, LocalDate(2026, 3, 5).toGtfsDate())
    }
}
