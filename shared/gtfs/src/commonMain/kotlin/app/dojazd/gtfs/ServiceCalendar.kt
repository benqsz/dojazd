package app.dojazd.gtfs

import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

/** Answers which GTFS services run on a given date (`calendar.txt` plus `calendar_dates.txt`). */
class ServiceCalendar internal constructor(
    private val rules: List<Rule>,
    private val changesByDate: Map<Int, List<Change>>,
) {
    /** [weekdays] has bit 0 set for Monday through bit 6 for Sunday; dates are YYYYMMDD. */
    internal class Rule(
        val service: Long,
        val weekdays: Int,
        val start: Int,
        val end: Int,
    )

    internal class Change(
        val service: Long,
        val added: Boolean,
    )

    fun activeOn(date: LocalDate): Set<Long> {
        val day = date.toGtfsDate()
        val weekdayBit = 1 shl (date.dayOfWeek.isoDayNumber - 1)
        val active = HashSet<Long>()
        for (rule in rules) {
            if (rule.weekdays and weekdayBit != 0 && day in rule.start..rule.end) active += rule.service
        }
        changesByDate[day]?.forEach { change ->
            if (change.added) active += change.service else active -= change.service
        }
        return active
    }
}

/** The date as a GTFS `YYYYMMDD` integer. */
fun LocalDate.toGtfsDate(): Int = year * 10_000 + month.number * 100 + day
