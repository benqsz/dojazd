package app.dojazd.gtfs

import app.dojazd.model.Line
import app.dojazd.model.Stop

/** A distinct sequence of stops served by a line. Every trip follows exactly one pattern. */
class TripPattern(
    val id: Long,
    val line: Line,
    val stops: List<Stop>,
)

/**
 * One run of a vehicle on one service day. The arrays have one entry per stop
 * of the trip's pattern, in travel order.
 */
class ScheduledTrip(
    val patternId: Long,
    val headsign: String,
    /** Seconds since the start of the service day; may exceed 86400. */
    val arrivals: IntArray,
    val departures: IntArray,
    /** GTFS `pickup_type` per stop; see [StopService]. */
    val pickups: ByteArray,
    /** GTFS `drop_off_type` per stop. */
    val dropOffs: ByteArray,
    /** The path the vehicle follows, for [Timetable.shape]; null when the feed gives none. */
    val shapeId: Long? = null,
    /** `trip_id` of the feed, for matching live data; null when not known. */
    val gtfsId: String? = null,
    /** The service day of this run, as a GTFS `YYYYMMDD` integer. */
    val serviceDate: Int = 0,
    /** `stop_sequence` per stop; null when the stops are simply numbered from 1. */
    val sequences: IntArray? = null,
)

/** Values of GTFS `pickup_type` and `drop_off_type` that the app treats specially. */
object StopService {
    const val NONE: Byte = 1
    const val ON_REQUEST: Byte = 3
}

/** A row of `transfers.txt` between two stops. */
class StopTransfer(
    val from: Long,
    val to: Long,
    /** False when the feed says passengers cannot change between the two stops. */
    val possible: Boolean,
    /** Minimum time needed to change, or null when the feed does not give one. */
    val seconds: Int?,
)
