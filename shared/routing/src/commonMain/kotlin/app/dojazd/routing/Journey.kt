package app.dojazd.routing

import app.dojazd.model.Line
import app.dojazd.model.Stop
import app.dojazd.model.TripRef

/**
 * One way of getting from the origin to the destination. All times are seconds
 * since midnight of the date the network was built for; 86400 and above means
 * the following calendar day.
 */
data class Journey(
    val legs: List<Leg>,
) {
    val departure: Int get() = legs.first().departure
    val arrival: Int get() = legs.last().arrival

    /** Number of vehicles boarded. */
    val rides: Int get() = legs.count { it is Leg.Ride }
}

sealed interface Leg {
    val departure: Int
    val arrival: Int

    /** On foot. A null [from] or [to] stands for the journey's own origin or destination. */
    data class Walk(
        val from: Stop?,
        val to: Stop?,
        override val departure: Int,
        override val arrival: Int,
    ) : Leg

    data class Ride(
        val line: Line,
        val headsign: String,
        /** Every stop the vehicle calls at while the passenger is on board, first and last included. */
        val calls: List<Call>,
        val boardOnRequest: Boolean,
        val alightOnRequest: Boolean,
        /** The path the vehicle follows along streets or tracks, for the timetable to look up; null when not known. */
        val shapeId: Long? = null,
        /** The run and the call where the passenger gets on, for matching live data; null when not known. */
        val trip: TripRef? = null,
    ) : Leg {
        val from: Stop get() = calls.first().stop
        val to: Stop get() = calls.last().stop
        override val departure: Int get() = calls.first().time
        override val arrival: Int get() = calls.last().time
    }
}

/** A vehicle at a stop: the departure time, except at the stop where the passenger gets off. */
data class Call(
    val stop: Stop,
    val time: Int,
)
