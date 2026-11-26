package app.dojazd.gtfs

object GtfsTime {
    /**
     * Parses a GTFS time (`H:MM:SS` or `HH:MM:SS`) into seconds since the start
     * of the service day. Hours may exceed 23: a trip that started before
     * midnight keeps counting, so `25:10:00` is 01:10 on the next calendar day.
     */
    fun parseSeconds(value: String): Int {
        val parts = value.trim().split(':')
        require(parts.size == 3) { "Invalid GTFS time: '$value'" }
        val (hours, minutes, seconds) =
            parts.map {
                it.toIntOrNull() ?: throw IllegalArgumentException("Invalid GTFS time: '$value'")
            }
        require(hours >= 0 && minutes in 0..59 && seconds in 0..59) { "Invalid GTFS time: '$value'" }
        return hours * 3600 + minutes * 60 + seconds
    }

    /** Formats seconds since the start of a day as a wall-clock `HH:MM`, wrapping past midnight both ways. */
    fun format(seconds: Int): String {
        val ofDay = (seconds % DAY_SECONDS + DAY_SECONDS) % DAY_SECONDS
        val hours = ofDay / 3600
        val minutes = ofDay % 3600 / 60
        return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
    }

    private const val DAY_SECONDS = 86_400
}
