package app.dojazd.gtfs

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * The moment, in epoch seconds, that the GTFS times of the service day [date]
 * count from in [timezone]: noon minus twelve hours. That is midnight on every
 * day but the two a year the clocks change, when it is an hour off.
 */
fun serviceDayStart(
    date: LocalDate,
    timezone: String,
): Long = date.atTime(NOON_HOUR, 0).toInstant(TimeZone.of(timezone)).epochSeconds - NOON_HOUR * SECONDS_PER_HOUR

private const val NOON_HOUR = 12
private const val SECONDS_PER_HOUR = 3_600

/** A moment expressed the way timetable queries need it: local date plus seconds since local midnight. */
data class LocalMoment(
    val date: LocalDate,
    val secondsOfDay: Int,
) {
    companion object {
        /** The current moment in [timezone] (an IANA id such as `Europe/Warsaw`). */
        fun now(
            timezone: String,
            clock: Clock = Clock.System,
        ): LocalMoment {
            val local = clock.now().toLocalDateTime(TimeZone.of(timezone))
            return LocalMoment(local.date, local.time.toSecondOfDay())
        }
    }
}
