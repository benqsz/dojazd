package app.dojazd.gtfs

import app.dojazd.model.Departure

/**
 * A classic printed-style timetable for one line at one stop: a row per hour
 * with the minutes of each departure. Departures that do not go to the usual
 * destination carry a letter explained in [legend].
 */
data class StopTimetable(
    val hours: List<Hour>,
    val legend: List<Variant>,
) {
    data class Hour(
        val hour: Int,
        val minutes: List<Minute>,
    )

    data class Minute(
        val minute: Int,
        val mark: Char?,
        val onRequest: Boolean,
    )

    data class Variant(
        val mark: Char,
        val headsign: String,
    )

    companion object {
        /** [departures] must be in time order, as returned by [Timetable.lineDeparturesAtStop]. */
        fun of(departures: List<Departure>): StopTimetable {
            val usualHeadsign =
                departures
                    .groupingBy { it.headsign }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
            val marks = LinkedHashMap<String, Char>()
            val hours =
                departures
                    // Grouping by the unwrapped hour keeps after-midnight departures at the end of the day.
                    .groupBy { it.time / 3600 }
                    .map { (hour, inHour) ->
                        Hour(
                            hour = hour % 24,
                            minutes =
                                inHour.map { departure ->
                                    Minute(
                                        minute = departure.time % 3600 / 60,
                                        mark =
                                            if (departure.headsign == usualHeadsign) {
                                                null
                                            } else {
                                                marks.getOrPut(departure.headsign) { 'a' + marks.size }
                                            },
                                        onRequest = departure.onRequest,
                                    )
                                },
                        )
                    }
            return StopTimetable(hours, marks.map { (headsign, mark) -> Variant(mark, headsign) })
        }
    }
}
