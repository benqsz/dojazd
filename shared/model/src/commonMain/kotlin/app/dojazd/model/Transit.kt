package app.dojazd.model

enum class VehicleType {
    TRAM,
    BUS,
    RAIL,
    METRO,
    FERRY,
    OTHER,
    ;

    companion object {
        /** Maps GTFS `route_type`, including the extended (three- and four-digit) codes. */
        fun fromGtfsRouteType(type: Int): VehicleType =
            when (type) {
                0, in 900..999 -> TRAM
                1, in 400..499 -> METRO
                2, in 100..199 -> RAIL
                3, 11, in 700..899 -> BUS
                4, in 1000..1099, in 1200..1299 -> FERRY
                else -> OTHER
            }
    }
}

/** A line as passengers know it ("75", "A"). Ids are local to one imported timetable. */
data class Line(
    val id: Long,
    val name: String,
    val longName: String,
    /** Operator's own grouping, e.g. "Linia nocna"; empty when the feed has none. */
    val category: String,
    val type: VehicleType,
    /** 0xRRGGBB, or null when the feed gives no colour. */
    val color: Int?,
    val textColor: Int?,
)

/** A single boarding point (one pole or platform). Several usually share a [name]. */
data class Stop(
    val id: Long,
    val code: String,
    val name: String,
    val platform: String,
    val location: GeoPoint,
    /** `stop_id` of the timetable feed, which live data may name the stop by; empty when not known. */
    val gtfsId: String = "",
)

/** One direction of a line, represented by its most frequently served stop sequence. */
data class LineDirection(
    val direction: Int,
    val headsign: String,
    val stops: List<Stop>,
    /** The path the vehicles follow along [stops], when the feed describes it. */
    val shapeId: Long? = null,
    /** The other stop sequences of this direction, run less often: short runs, runs to or from the depot. Most frequent first. */
    val variants: List<LineVariant> = emptyList(),
)

/** A stop sequence a line runs less often than its usual one. */
data class LineVariant(
    val headsign: String,
    val stops: List<Stop>,
    val tripCount: Int,
    val shapeId: Long? = null,
)

data class Departure(
    /**
     * Seconds since midnight of the date the query was made for: 86400 and
     * above means the following calendar day, negative the day before (only
     * for queries that reach back before midnight).
     */
    val time: Int,
    val line: Line,
    val headsign: String,
    val stop: Stop,
    val onRequest: Boolean,
    /** Null when the feed does not say. */
    val lowFloor: Boolean?,
    /** Which run of which trip this is, for matching live data; null when not known. */
    val trip: TripRef? = null,
)

/** One call of one run of a trip, named the way live (GTFS-RT) data names it. */
data class TripRef(
    /** `trip_id` of the timetable feed. */
    val gtfsId: String,
    /** The service day the run belongs to, as a GTFS `YYYYMMDD` integer. */
    val serviceDate: Int,
    /** `stop_sequence` of the call at the stop. */
    val stopSequence: Int,
    /** `stop_id` of the stop, for live data that names calls by stop alone; null when not known. */
    val stopGtfsId: String? = null,
)
