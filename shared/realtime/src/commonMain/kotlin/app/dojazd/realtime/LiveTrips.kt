package app.dojazd.realtime

import app.dojazd.model.Departure
import app.dojazd.model.TripRef

/** What live data says about one call of a trip. */
sealed interface Prediction {
    /** The vehicle runs [seconds] late (early when negative) against the timetable. */
    data class Delay(
        val seconds: Int,
    ) : Prediction

    /** The whole run will not take place. */
    data object Cancelled : Prediction

    /** The run takes place but will not call at this stop. */
    data object Skipped : Prediction
}

/** The trip updates of one feed, indexed for looking up single calls. */
class LiveTrips(
    updates: List<TripUpdate>,
) {
    private val byTrip: Map<String, List<TripUpdate>> =
        updates.filter { it.trip.tripId != null }.groupBy { it.trip.tripId!! }

    /**
     * The update of the run of trip [gtfsId] on the service day [serviceDate]
     * (GTFS `YYYYMMDD`), or of any run when [serviceDate] is null.
     */
    fun update(
        gtfsId: String,
        serviceDate: Int?,
    ): TripUpdate? {
        val candidates = byTrip[gtfsId] ?: return null
        if (serviceDate == null) return candidates.first()
        // A trip that runs every day has the same id every day; after midnight two of its runs may be in the feed.
        return candidates.firstOrNull { it.trip.startDate?.toIntOrNull() == serviceDate }
            ?: candidates.firstOrNull { it.trip.startDate == null }
    }

    /**
     * The prediction for the call [ref], or null when live data says nothing
     * about it. [scheduledEpoch] is the timetabled departure in epoch seconds,
     * needed only for producers that give absolute times instead of delays.
     */
    fun prediction(
        ref: TripRef,
        scheduledEpoch: Long? = null,
    ): Prediction? {
        val update = update(ref.gtfsId, ref.serviceDate) ?: return null
        if (update.trip.relationship.cancelled) return Prediction.Cancelled
        val exact =
            update.stopTimeUpdates.lastOrNull { it.stopSequence == ref.stopSequence }
                // Some producers name a call by its stop alone. A trip that calls there twice cannot be told apart then.
                ?: ref.stopGtfsId?.let { stop -> update.stopTimeUpdates.lastOrNull { it.stopSequence == null && it.stopId == stop } }
        if (exact != null) {
            when (exact.relationship) {
                StopRelationship.SKIPPED -> return Prediction.Skipped
                StopRelationship.NO_DATA -> return null
                else -> Unit
            }
            val event = exact.departure ?: exact.arrival
            event?.delay?.let { return Prediction.Delay(it) }
            if (event?.time != null && scheduledEpoch != null) return Prediction.Delay((event.time - scheduledEpoch).toInt())
        }
        return delayBefore(update, ref.stopSequence)?.let(Prediction::Delay)
    }

    /**
     * The delay a vehicle on run [gtfsId] of [serviceDate] (any run when null)
     * has at the stop with sequence [stopSequence], or the latest known when that is null.
     */
    fun delay(
        gtfsId: String,
        serviceDate: Int?,
        stopSequence: Int?,
    ): Int? {
        val update = update(gtfsId, serviceDate) ?: return null
        if (update.trip.relationship.cancelled) return null
        return delayBefore(update, (stopSequence ?: Int.MAX_VALUE - 1) + 1)
    }

    /**
     * The delay carried on from the last update before [stopSequence]: per the
     * specification a delay holds for the following stops until the next update.
     */
    private fun delayBefore(
        update: TripUpdate,
        stopSequence: Int,
    ): Int? {
        val earlier =
            update.stopTimeUpdates
                .filter {
                    val sequence = it.stopSequence
                    sequence != null && sequence < stopSequence
                }.sortedBy { it.stopSequence }
        for (stop in earlier.asReversed()) {
            when (stop.relationship) {
                StopRelationship.NO_DATA -> return null
                StopRelationship.SKIPPED -> continue
                else -> (stop.departure?.delay ?: stop.arrival?.delay)?.let { return it }
            }
        }
        return update.delay
    }
}

/** A departure with what live data adds to it. */
data class LiveDeparture(
    val departure: Departure,
    /** Null when live data says nothing about this departure, so the timetable is all there is. */
    val prediction: Prediction?,
) {
    /** When the vehicle is expected to leave, in the same seconds as [Departure.time]. */
    val time: Int
        get() = departure.time + ((prediction as? Prediction.Delay)?.seconds ?: 0)

    /** False when the vehicle will not come: a cancelled run or a skipped stop. */
    val runs: Boolean get() = prediction !is Prediction.Cancelled && prediction !is Prediction.Skipped
}

object LiveBoard {
    /** A vehicle predicted to have left this long ago may still be at the stop. */
    const val GRACE_SECONDS = 30

    /**
     * Applies [trips] to timetabled [departures] and keeps those still to
     * come at [now], in order of expected time. Times are seconds since the
     * midnight that [midnightEpoch] gives in epoch seconds. [departures] should
     * reach back far enough to include late vehicles that were due earlier.
     */
    fun merge(
        departures: List<Departure>,
        trips: LiveTrips?,
        midnightEpoch: Long,
        now: Int,
        limit: Int,
    ): List<LiveDeparture> =
        departures
            .map { departure ->
                val prediction = departure.trip?.let { trips?.prediction(it, midnightEpoch + departure.time) }
                LiveDeparture(departure, prediction)
            }.filter {
                when (it.prediction) {
                    is Prediction.Delay -> it.time >= now - GRACE_SECONDS

                    // Shown until its timetabled time, so that whoever waits for it learns it is not coming.
                    else -> it.departure.time >= now
                }
            }.sortedBy { it.time }
            .take(limit)
}
