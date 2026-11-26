package app.dojazd.routing

import app.dojazd.model.GeoPoint

/** Where a journey starts or ends. */
data class Endpoint(
    val location: GeoPoint,
    /**
     * Ids of the stops the passenger is already at (or wants to end up at) when
     * the endpoint is a stop; empty for an address or any other point on the map.
     */
    val stopIds: Collection<Long> = emptyList(),
)

class Plan(
    /** Ordered by departure time. */
    val journeys: List<Journey>,
    /**
     * Departures up to this moment have been searched; a search for later
     * journeys continues right after it. Null when no vehicle leaves the origin any more.
     */
    val searchedUntil: Int?,
)

/**
 * Plans journeys over a [TransitNetwork]. With [walks], the walk to the first
 * stop and from the last one is measured along streets; without, it is
 * estimated from straight-line distance. Walks between stops to change
 * vehicles are timed when the network is built (see [Footpaths]).
 */
class JourneyPlanner(
    private val network: TransitNetwork,
    private val options: Options = Options(),
    private val walks: WalkNetwork? = null,
) {
    data class Options(
        /** Furthest straight-line walk between an address and a stop. */
        val maxWalkMeters: Double = 800.0,
        /** The same when the endpoint is itself a stop: other stops are offered only if they are close. */
        val maxWalkFromStopMeters: Double = 400.0,
        /** Up to this straight-line distance, walking the whole way is offered as well. */
        val maxDirectWalkMeters: Double = 2_000.0,
        /** A walk all the way this short is the only answer: no vehicle is looked for. */
        val walkOnlySeconds: Int = 300,
        val maxRides: Int = 5,
        /** Time needed to change vehicles without leaving the stop. */
        val changeSeconds: Int = 60,
        /** A journey with more changes is offered only if each extra change saves at least this much time. */
        val minSavingPerChangeSeconds: Int = 300,
        /** Each search covers departures over at least this long... */
        val windowSeconds: Int = 3_600,
        /** ...and at least this many distinct departure times, so that a search at night still finds the first morning runs. */
        val minDepartureTimes: Int = 20,
    )

    /**
     * Journeys leaving [from] at or after [earliestDeparture], which like every
     * time here is in seconds since midnight of the network's date.
     */
    fun plan(
        from: Endpoint,
        to: Endpoint,
        earliestDeparture: Int,
    ): Plan {
        // One for the whole plan: each search on its own would take memory for the entire street network anew.
        val measurer = walks?.Measurer()
        val walkSeconds = directWalkSeconds(from, to, measurer)
        val walk =
            walkSeconds?.let {
                Journey(listOf(Leg.Walk(from = null, to = null, departure = earliestDeparture, arrival = earliestDeparture + it)))
            }
        if (walk != null && walk.arrival - walk.departure <= options.walkOnlySeconds) return Plan(listOf(walk), searchedUntil = null)

        val access = stopsNear(from, measurer)
        val egress = stopsNear(to, measurer)
        val journeys = ArrayList<Journey>()

        val raptor = Raptor(network, access, egress, options.maxRides, options.changeSeconds)
        val times = raptor.departureTimes(earliestDeparture, earliestDeparture + SEARCH_HORIZON_SECONDS)
        var searchedUntil: Int? = null
        if (times.isNotEmpty()) {
            val inWindow = times.count { it <= earliestDeparture + options.windowSeconds }
            val wanted = maxOf(inWindow, options.minDepartureTimes).coerceAtMost(times.size)
            val until = times[wanted - 1]
            searchedUntil = until
            // Searching a little beyond the window first makes sure that a journey kept
            // from inside it is not beaten by one that leaves just after it.
            val searched = times.filter { it <= until + LOOKAHEAD_SECONDS }.reversed().toIntArray()
            raptor.search(searched).filterTo(journeys) { it.departure <= until }
        }

        if (walk != null) {
            // Walking can start at any time, so a journey that takes as long is never the better choice.
            journeys.removeAll { it.arrival - it.departure >= walk.arrival - walk.departure }
            journeys += walk
        }

        return Plan(
            journeys =
                journeys
                    .withoutDominated()
                    .withoutPointlessChanges()
                    .sortedWith(compareBy({ it.departure }, { it.arrival })),
            searchedUntil = searchedUntil,
        )
    }

    /** How long walking all the way takes, or null when it is too far to offer. */
    private fun directWalkSeconds(
        from: Endpoint,
        to: Endpoint,
        measurer: WalkNetwork.Measurer?,
    ): Int? {
        val meters = from.location.distanceMetersTo(to.location)
        if (meters > options.maxDirectWalkMeters) return null
        val path = measurer?.path(from.location, to.location, WalkEstimate.maxAlongStreets(meters))
        return if (path != null) WalkEstimate.secondsAlong(path.meters) else WalkEstimate.seconds(meters)
    }

    /**
     * Strictly speaking, leaving two minutes later is an advantage even at the
     * price of another change, but nobody wants that listed as an option.
     */
    private fun List<Journey>.withoutPointlessChanges(): List<Journey> {
        val kept = ArrayList<Journey>()
        for (journey in sortedBy { it.rides }) {
            val pointless =
                kept.any { simpler ->
                    val extraChanges = journey.rides - simpler.rides
                    val saving = maxOf(0, journey.departure - simpler.departure) + maxOf(0, simpler.arrival - journey.arrival)
                    extraChanges > 0 && saving < extraChanges * options.minSavingPerChangeSeconds
                }
            if (!pointless) kept += journey
        }
        return kept
    }

    private fun stopsNear(
        endpoint: Endpoint,
        measurer: WalkNetwork.Measurer?,
    ): List<StopAccess> {
        val own =
            endpoint.stopIds
                .mapNotNull(network::indexOf)
                .filter(network::isServed)
                .toSet()
        val maxMeters = if (own.isEmpty()) options.maxWalkMeters else options.maxWalkFromStopMeters

        val result = own.mapTo(ArrayList()) { StopAccess(it, 0) }
        val distances =
            network.stops.indices
                .filter { it !in own && network.isServed(it) }
                .map { it to endpoint.location.distanceMetersTo(network.stops[it].location) }
        val near = distances.filter { it.second <= maxMeters }
        val walked =
            measurer?.distances(
                from = endpoint.location,
                targets = near.map { network.stops[it.first].location },
                maxMeters = WalkEstimate.maxAlongStreets(maxMeters),
            )
        near.forEachIndexed { index, (stop, straight) ->
            // Null: the streets around are not known, so the straight line has to do.
            val alongStreets = walked?.get(index)
            when {
                alongStreets == null -> result += StopAccess(stop, WalkEstimate.seconds(straight))

                // A stop that looks close but lies across a river or a railway is not worth offering.
                alongStreets.isFinite() -> result += StopAccess(stop, WalkEstimate.secondsAlong(alongStreets))
            }
        }
        if (result.isEmpty() && near.isEmpty()) {
            // Nothing within reach: a long walk to the nearest stops beats no answer at all.
            distances
                .filter { it.second <= FALLBACK_MAX_METERS }
                .sortedBy { it.second }
                .take(FALLBACK_STOPS)
                .mapTo(result) { (stop, meters) -> StopAccess(stop, WalkEstimate.seconds(meters)) }
        }
        return result
    }

    private companion object {
        const val SEARCH_HORIZON_SECONDS = 24 * 3_600
        const val LOOKAHEAD_SECONDS = 1_800
        const val FALLBACK_STOPS = 3
        const val FALLBACK_MAX_METERS = 3_000.0
    }
}

/** Drops every journey that another one beats or matches on departure, arrival and number of rides alike. */
internal fun List<Journey>.withoutDominated(): List<Journey> =
    filterIndexed { index, journey ->
        withIndex().none { (otherIndex, other) ->
            otherIndex != index &&
                other.departure >= journey.departure &&
                other.arrival <= journey.arrival &&
                other.rides <= journey.rides &&
                // Of two equal journeys the first is kept.
                (
                    other.departure > journey.departure ||
                        other.arrival < journey.arrival ||
                        other.rides < journey.rides ||
                        otherIndex < index
                )
        }
    }
