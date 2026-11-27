package app.dojazd.routing

import app.dojazd.gtfs.StopService
import app.dojazd.model.TripRef

/** A stop the journey can start or end at, and the walk between it and the origin or destination. */
class StopAccess(
    val stop: Int,
    val seconds: Int,
)

/**
 * Range RAPTOR (Delling, Pajor, Werneck: "Round-Based Public Transit Routing").
 *
 * Round k finds the earliest arrival at every stop using at most k vehicles, by
 * riding each route once from the stops improved in round k-1. A search is
 * repeated for a series of departure times, latest first, and the arrival times
 * are kept between repeats. An earlier departure then only gets further than a
 * stop if it arrives there sooner than every later departure did, which is
 * exactly when it is worth offering.
 *
 * What comes out is every journey that no other journey beats on all of:
 * leaving later, arriving earlier, changing less.
 *
 * Passengers walk at most once between two vehicles, and changing to another
 * vehicle at the same stop takes [changeSeconds].
 *
 * An instance holds the state of one query and is not reusable.
 */
internal class Raptor(
    private val network: TransitNetwork,
    private val access: List<StopAccess>,
    private val egress: List<StopAccess>,
    private val maxRides: Int,
    private val changeSeconds: Int,
) {
    private val stopCount = network.stops.size
    private val labelCount = (maxRides + 1) * stopCount

    // Labels are indexed by `round * stopCount + stop`.

    /** Earliest time a passenger is at the stop and free to board, using at most `round` vehicles. */
    private val ready = IntArray(labelCount) { NEVER }
    private val readyVia = ByteArray(labelCount)

    /** For a label made by walking: the stop walked from. */
    private val walkedFrom = IntArray(labelCount)

    /** The round a label was made in; lower than its own round when it was carried over. */
    private val madeInRound = ByteArray(labelCount)

    /** Earliest arrival by getting off the `round`-th vehicle at the stop, and the ride that achieves it. */
    private val alighted = IntArray(labelCount) { NEVER }
    private val rideRoute = IntArray(labelCount)
    private val rideTrip = IntArray(labelCount)
    private val rideBoardPosition = IntArray(labelCount)
    private val rideAlightPosition = IntArray(labelCount)

    /** Earliest arrival at the destination itself, per round. */
    private val destinationArrival = IntArray(maxRides + 1) { NEVER }

    private val accessSeconds = IntArray(stopCount) { NEVER }

    private var marked = StopSet(stopCount)
    private var markedNext = StopSet(stopCount)
    private val alightedNow = StopSet(stopCount)
    private val firstMarkedPosition = IntArray(network.routeCount) { NEVER }
    private val queuedRoutes = IntArray(network.routeCount)

    init {
        for (point in access) {
            if (point.seconds < accessSeconds[point.stop]) accessSeconds[point.stop] = point.seconds
        }
    }

    /**
     * Departure times worth searching from: every moment from [earliest] to
     * [latest] at which leaving the origin catches a vehicle with no waiting.
     * In ascending order, without duplicates.
     */
    fun departureTimes(
        earliest: Int,
        latest: Int,
    ): IntArray {
        val times = HashSet<Int>()
        for (stop in 0 until stopCount) {
            val walk = accessSeconds[stop]
            if (walk == NEVER) continue
            network.forEachRouteAt(stop) { routeIndex, position ->
                val route = network.routes[routeIndex]
                if (position < route.stops.size - 1) {
                    var trip = route.firstTripFrom(position, earliest + walk)
                    while (trip in 0 until route.tripCount && route.departure(trip, position) - walk <= latest) {
                        if (route.canBoard(trip, position)) times += route.departure(trip, position) - walk
                        trip++
                    }
                }
            }
        }
        return times.sorted().toIntArray()
    }

    /**
     * Searches from each of [departures], which must be in descending order.
     * Returns the journeys found, in no particular order.
     */
    fun search(departures: IntArray): List<Journey> {
        val journeys = ArrayList<Journey>()
        for (departure in departures) searchFrom(departure, journeys)
        return journeys
    }

    private fun searchFrom(
        departure: Int,
        journeys: MutableList<Journey>,
    ) {
        marked.clear()
        for (stop in 0 until stopCount) {
            val walk = accessSeconds[stop]
            if (walk != NEVER && departure + walk < ready[stop]) {
                ready[stop] = departure + walk
                readyVia[stop] = VIA_ACCESS
                madeInRound[stop] = 0
                marked.add(stop)
            }
        }

        for (round in 1..maxRides) {
            if (marked.size == 0) break
            val current = round * stopCount
            val previous = current - stopCount

            // Whatever was possible with fewer vehicles is still possible.
            for (stop in 0 until stopCount) {
                if (ready[previous + stop] < ready[current + stop]) {
                    ready[current + stop] = ready[previous + stop]
                    readyVia[current + stop] = readyVia[previous + stop]
                    walkedFrom[current + stop] = walkedFrom[previous + stop]
                    madeInRound[current + stop] = madeInRound[previous + stop]
                }
            }
            if (destinationArrival[round - 1] < destinationArrival[round]) {
                destinationArrival[round] = destinationArrival[round - 1]
            }
            // Reaching any stop later than the destination itself cannot lead to a better journey.
            val bound = destinationArrival[round]

            var queued = 0
            for (i in 0 until marked.size) {
                network.forEachRouteAt(marked[i]) { route, position ->
                    if (position < firstMarkedPosition[route]) {
                        if (firstMarkedPosition[route] == NEVER) queuedRoutes[queued++] = route
                        firstMarkedPosition[route] = position
                    }
                }
            }

            markedNext.clear()
            alightedNow.clear()
            for (i in 0 until queued) {
                val route = queuedRoutes[i]
                ride(route, firstMarkedPosition[route], round, bound)
                firstMarkedPosition[route] = NEVER
            }

            for (i in 0 until alightedNow.size) {
                val from = alightedNow[i]
                val arrival = alighted[current + from]
                network.forEachTransferFrom(from) { to, seconds ->
                    val time = arrival + seconds
                    if (time < bound && time < ready[current + to]) {
                        ready[current + to] = time
                        readyVia[current + to] = VIA_WALK
                        walkedFrom[current + to] = from
                        madeInRound[current + to] = round.toByte()
                        markedNext.add(to)
                    }
                }
            }

            var last: StopAccess? = null
            for (point in egress) {
                val arrival = alighted[current + point.stop]
                if (arrival != NEVER && arrival + point.seconds < destinationArrival[round]) {
                    destinationArrival[round] = arrival + point.seconds
                    last = point
                }
            }
            if (last != null) journeys += journeyEndingAt(last, round)

            val swap = marked
            marked = markedNext
            markedNext = swap
        }
    }

    /** Travels along [routeIndex] from [fromPosition], boarding the earliest trip that can be caught on the way. */
    private fun ride(
        routeIndex: Int,
        fromPosition: Int,
        round: Int,
        bound: Int,
    ) {
        val route = network.routes[routeIndex]
        val current = round * stopCount
        val previous = current - stopCount
        val lastPosition = route.stops.size - 1
        var trip = -1
        var boardPosition = -1

        for (position in fromPosition..lastPosition) {
            val stop = route.stops[position]

            if (trip >= 0 && route.canAlight(trip, position)) {
                val arrival = route.arrival(trip, position)
                if (arrival < bound && arrival < alighted[current + stop]) {
                    val label = current + stop
                    alighted[label] = arrival
                    rideRoute[label] = routeIndex
                    rideTrip[label] = trip
                    rideBoardPosition[label] = boardPosition
                    rideAlightPosition[label] = position
                    alightedNow.add(stop)
                    if (arrival + changeSeconds < ready[label]) {
                        ready[label] = arrival + changeSeconds
                        readyVia[label] = VIA_RIDE
                        madeInRound[label] = round.toByte()
                        markedNext.add(stop)
                    }
                }
            }

            if (position == lastPosition) break
            val readyAt = ready[previous + stop]
            if (readyAt == NEVER) continue
            if (trip < 0) {
                val first = route.firstTripFrom(position, readyAt)
                if (first >= 0) {
                    trip = first
                    boardPosition = position
                }
            } else {
                // Trips are in time order, so an earlier catchable one can only be just before the current.
                var earlier = trip
                var candidate = trip - 1
                while (candidate >= 0 && route.departure(candidate, position) >= readyAt) {
                    if (route.canBoard(candidate, position)) earlier = candidate
                    candidate--
                }
                if (earlier != trip) {
                    trip = earlier
                    boardPosition = position
                }
            }
        }
    }

    /** Follows the labels back from the destination to the origin. */
    private fun journeyEndingAt(
        last: StopAccess,
        lastRound: Int,
    ): Journey {
        val legs = ArrayList<Leg>()
        var stop = last.stop
        var round = lastRound

        if (last.seconds > 0) {
            val arrival = alighted[round * stopCount + stop]
            legs += Leg.Walk(from = network.stops[stop], to = null, departure = arrival, arrival = arrival + last.seconds)
        }
        while (true) {
            val label = round * stopCount + stop
            val route = network.routes[rideRoute[label]]
            val trip = rideTrip[label]
            val boardPosition = rideBoardPosition[label]
            val alightPosition = rideAlightPosition[label]
            val width = route.stops.size
            legs +=
                Leg.Ride(
                    line = route.line,
                    headsign = route.headsigns[trip],
                    calls =
                        (boardPosition..alightPosition).map { position ->
                            Call(
                                stop = network.stops[route.stops[position]],
                                time =
                                    if (position == alightPosition) {
                                        route.arrival(trip, position)
                                    } else {
                                        route.departure(trip, position)
                                    },
                                onRequest =
                                    route.pickups[trip * width + position] == StopService.ON_REQUEST ||
                                        route.dropOffs[trip * width + position] == StopService.ON_REQUEST,
                            )
                        },
                    boardOnRequest = route.pickups[trip * width + boardPosition] == StopService.ON_REQUEST,
                    alightOnRequest = route.dropOffs[trip * width + alightPosition] == StopService.ON_REQUEST,
                    shapeId = route.shapes[trip].takeIf { it >= 0 },
                    trip =
                        route.tripIds[trip]?.let { id ->
                            TripRef(
                                gtfsId = id,
                                serviceDate = route.serviceDates[trip],
                                stopSequence = route.sequences[trip * width + boardPosition],
                                stopGtfsId = network.stops[route.stops[boardPosition]].gtfsId.takeIf { it.isNotEmpty() },
                            )
                        },
                )

            val boardStop = route.stops[boardPosition]
            val boardTime = route.departure(trip, boardPosition)
            val before = (round - 1) * stopCount + boardStop
            when (readyVia[before]) {
                VIA_ACCESS -> {
                    // Leave as late as the first vehicle allows rather than wait at the stop.
                    val walk = accessSeconds[boardStop]
                    if (walk > 0) {
                        legs +=
                            Leg.Walk(
                                from = null,
                                to = network.stops[boardStop],
                                departure = boardTime - walk,
                                arrival = boardTime,
                            )
                    }
                    return Journey(legs.asReversed().toList())
                }

                VIA_WALK -> {
                    val from = walkedFrom[before]
                    round = madeInRound[before].toInt()
                    val start = alighted[round * stopCount + from]
                    legs +=
                        Leg.Walk(
                            from = network.stops[from],
                            to = network.stops[boardStop],
                            departure = start,
                            arrival = ready[before],
                        )
                    stop = from
                }

                else -> {
                    round = madeInRound[before].toInt()
                    stop = boardStop
                }
            }
        }
    }

    /** A set of stops that can be listed in insertion order and cleared in time proportional to its size. */
    private class StopSet(
        capacity: Int,
    ) {
        private val member = BooleanArray(capacity)
        private val items = IntArray(capacity)
        var size = 0
            private set

        operator fun get(index: Int) = items[index]

        fun add(stop: Int) {
            if (!member[stop]) {
                member[stop] = true
                items[size++] = stop
            }
        }

        fun clear() {
            for (i in 0 until size) member[items[i]] = false
            size = 0
        }
    }

    private companion object {
        const val NEVER = Int.MAX_VALUE
        const val VIA_ACCESS: Byte = 1
        const val VIA_RIDE: Byte = 2
        const val VIA_WALK: Byte = 3
    }
}
