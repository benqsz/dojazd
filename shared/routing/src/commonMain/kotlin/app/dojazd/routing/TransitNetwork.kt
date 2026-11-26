package app.dojazd.routing

import app.dojazd.gtfs.ScheduledTrip
import app.dojazd.gtfs.StopService
import app.dojazd.gtfs.StopTransfer
import app.dojazd.gtfs.Timetable
import app.dojazd.gtfs.TripPattern
import app.dojazd.model.Line
import app.dojazd.model.Stop
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** The trips of one service day, shifted by [offsetSeconds] onto the network's time axis. */
class ServiceDay(
    val offsetSeconds: Int,
    val trips: List<ScheduledTrip>,
)

/**
 * The timetable around one date, laid out in flat arrays for [Raptor]. Times are
 * seconds since midnight of that date. Stops are referred to by their index in [stops].
 *
 * It is immutable, so one instance can serve any number of searches.
 */
class TransitNetwork private constructor(
    val stops: List<Stop>,
    internal val routes: List<Route>,
    private val stopRouteStart: IntArray,
    private val stopRouteIds: IntArray,
    private val stopRoutePositions: IntArray,
    private val transferStart: IntArray,
    private val transferTargets: IntArray,
    private val transferSeconds: IntArray,
) {
    /**
     * Trips that call at the same stops in the same order, take and let off
     * passengers at the same ones and never overtake one another, sorted by time.
     * That is what lets a search board "the first trip I can catch here" without
     * looking at the others.
     */
    internal class Route(
        val line: Line,
        val stops: IntArray,
        val tripCount: Int,
        /** Indexed by `trip * stops.size + position`, as are the three arrays below. */
        val arrivals: IntArray,
        val departures: IntArray,
        val pickups: ByteArray,
        val dropOffs: ByteArray,
        val headsigns: Array<String>,
        /** For each trip the id of the path it follows, or -1 when the feed gives none. */
        val shapes: LongArray,
        /** For each trip its `trip_id`, or null when not known. */
        val tripIds: Array<String?>,
        /** For each trip its service day, as a GTFS `YYYYMMDD` integer. */
        val serviceDates: IntArray,
        /** `stop_sequence` of each call, indexed like [arrivals]. */
        val sequences: IntArray,
    ) {
        fun departure(
            trip: Int,
            position: Int,
        ) = departures[trip * stops.size + position]

        fun arrival(
            trip: Int,
            position: Int,
        ) = arrivals[trip * stops.size + position]

        fun canBoard(
            trip: Int,
            position: Int,
        ) = pickups[trip * stops.size + position] != StopService.NONE

        fun canAlight(
            trip: Int,
            position: Int,
        ) = dropOffs[trip * stops.size + position] != StopService.NONE

        /** The first trip leaving [position] at or after [time] that takes passengers there, or -1. */
        fun firstTripFrom(
            position: Int,
            time: Int,
        ): Int {
            var low = 0
            var high = tripCount
            while (low < high) {
                val middle = (low + high) ushr 1
                if (departure(middle, position) < time) low = middle + 1 else high = middle
            }
            while (low < tripCount && !canBoard(low, position)) low++
            return if (low < tripCount) low else -1
        }
    }

    private val indexByStopId: Map<Long, Int> = stops.withIndex().associate { (index, stop) -> stop.id to index }

    fun indexOf(stopId: Long): Int? = indexByStopId[stopId]

    internal val routeCount: Int get() = routes.size

    /** Calls [action] with every route through [stop] and the stop's position on it. */
    internal inline fun forEachRouteAt(
        stop: Int,
        action: (route: Int, position: Int) -> Unit,
    ) {
        for (i in stopRouteStart[stop] until stopRouteStart[stop + 1]) action(stopRouteIds[i], stopRoutePositions[i])
    }

    /** Calls [action] with every stop that can be walked to from [stop] to change vehicles. */
    internal inline fun forEachTransferFrom(
        stop: Int,
        action: (target: Int, seconds: Int) -> Unit,
    ) {
        for (i in transferStart[stop] until transferStart[stop + 1]) action(transferTargets[i], transferSeconds[i])
    }

    /** True when at least one trip in the network takes passengers from or to [stop]. */
    fun isServed(stop: Int): Boolean = stopRouteStart[stop] < stopRouteStart[stop + 1]

    companion object {
        private const val DAY_SECONDS = 86_400

        /**
         * Builds the network for journeys starting on [date]: that service day,
         * the trips of the previous one still running after midnight, and the
         * following day so that late-evening searches have somewhere to go.
         * Changes on foot between stops take the times of [footpaths]; without
         * them, times estimated from straight-line distance.
         */
        fun load(
            timetable: Timetable,
            date: LocalDate,
            footpaths: Footpaths? = null,
        ): TransitNetwork {
            val yesterday = timetable.tripsOn(date.minus(1, DateTimeUnit.DAY), endingAfter = DAY_SECONDS)
            return build(
                stops = timetable.stops(),
                patterns = timetable.patterns(),
                days =
                    listOf(
                        ServiceDay(-DAY_SECONDS, yesterday),
                        ServiceDay(0, timetable.tripsOn(date)),
                        ServiceDay(DAY_SECONDS, timetable.tripsOn(date.plus(1, DateTimeUnit.DAY))),
                    ),
                transfers = timetable.transfers(),
                footpaths = footpaths,
            )
        }

        fun build(
            stops: List<Stop>,
            patterns: List<TripPattern>,
            days: List<ServiceDay>,
            transfers: List<StopTransfer> = emptyList(),
            footpaths: Footpaths? = null,
        ): TransitNetwork {
            val indexByStopId = stops.withIndex().associate { (index, stop) -> stop.id to index }
            val routes = buildRoutes(patterns, days, indexByStopId)

            // Routes through each stop, in compressed sparse row form.
            val routesAtStop = Array(stops.size) { ArrayList<Int>() }
            routes.forEachIndexed { route, it ->
                it.stops.forEachIndexed { position, stop ->
                    routesAtStop[stop] += route
                    routesAtStop[stop] += position
                }
            }
            val stopRouteStart = IntArray(stops.size + 1)
            for (stop in stops.indices) stopRouteStart[stop + 1] = stopRouteStart[stop] + routesAtStop[stop].size / 2
            val stopRouteIds = IntArray(stopRouteStart.last())
            val stopRoutePositions = IntArray(stopRouteStart.last())
            for (stop in stops.indices) {
                val pairs = routesAtStop[stop]
                for (i in 0 until pairs.size / 2) {
                    stopRouteIds[stopRouteStart[stop] + i] = pairs[2 * i]
                    stopRoutePositions[stopRouteStart[stop] + i] = pairs[2 * i + 1]
                }
            }

            val served = BooleanArray(stops.size) { routesAtStop[it].isNotEmpty() }
            val walksToChange = buildTransfers(stops, served, footpaths ?: Footpaths.measure(stops), transfers, indexByStopId)
            val transferStart = IntArray(stops.size + 1)
            for (stop in stops.indices) transferStart[stop + 1] = transferStart[stop] + walksToChange[stop].size
            val transferTargets = IntArray(transferStart.last())
            val transferSeconds = IntArray(transferStart.last())
            for (stop in stops.indices) {
                var i = transferStart[stop]
                for ((target, seconds) in walksToChange[stop]) {
                    transferTargets[i] = target
                    transferSeconds[i] = seconds
                    i++
                }
            }

            return TransitNetwork(
                stops,
                routes,
                stopRouteStart,
                stopRouteIds,
                stopRoutePositions,
                transferStart,
                transferTargets,
                transferSeconds,
            )
        }

        private class TripTimes(
            val trip: ScheduledTrip,
            offset: Int,
        ) {
            val arrivals = IntArray(trip.arrivals.size) { trip.arrivals[it] + offset }
            val departures = IntArray(trip.departures.size) { trip.departures[it] + offset }

            /** Where this trip does not take or let off passengers. */
            val restrictions: List<Boolean> =
                trip.pickups.map { it == StopService.NONE } + trip.dropOffs.map { it == StopService.NONE }

            /** True when this trip is at or ahead of [other] at every stop, so the two never swap order. */
            fun neverBehind(other: TripTimes): Boolean {
                for (i in arrivals.indices) {
                    if (arrivals[i] > other.arrivals[i] || departures[i] > other.departures[i]) return false
                }
                return true
            }
        }

        private fun buildRoutes(
            patterns: List<TripPattern>,
            days: List<ServiceDay>,
            indexByStopId: Map<Long, Int>,
        ): List<Route> {
            val tripsByPattern = HashMap<Long, MutableList<TripTimes>>()
            for (day in days) {
                for (trip in day.trips) {
                    tripsByPattern.getOrPut(trip.patternId) { ArrayList() } += TripTimes(trip, day.offsetSeconds)
                }
            }

            val routes = ArrayList<Route>()
            for (pattern in patterns) {
                val stopIndexes = IntArray(pattern.stops.size) { indexByStopId[pattern.stops[it].id] ?: -1 }
                if (stopIndexes.size < 2 || stopIndexes.any { it < 0 }) continue
                val trips =
                    tripsByPattern[pattern.id]
                        ?.filter { it.arrivals.size == stopIndexes.size }
                        ?.sortedWith(compareBy({ it.departures[0] }, { it.arrivals.last() }))
                        ?: continue

                // Within a route the earliest trip must be the best one to be on. So a trip that
                // overtakes another (an express run, say) cannot share a route with it, and neither
                // can trips that differ in where passengers may get on and off.
                val groups = ArrayList<MutableList<TripTimes>>()
                for (trip in trips) {
                    val group =
                        groups.firstOrNull { it.last().restrictions == trip.restrictions && it.last().neverBehind(trip) }
                            ?: ArrayList<TripTimes>().also { groups += it }
                    group += trip
                }
                for (group in groups) routes += toRoute(pattern.line, stopIndexes, group)
            }
            return routes
        }

        private fun toRoute(
            line: Line,
            stops: IntArray,
            trips: List<TripTimes>,
        ): Route {
            val width = stops.size
            val arrivals = IntArray(trips.size * width)
            val departures = IntArray(trips.size * width)
            val pickups = ByteArray(trips.size * width)
            val dropOffs = ByteArray(trips.size * width)
            val sequences = IntArray(trips.size * width)
            trips.forEachIndexed { index, times ->
                val own = times.trip.sequences
                for (position in 0 until width) sequences[index * width + position] = own?.getOrNull(position) ?: (position + 1)
                times.arrivals.copyInto(arrivals, index * width)
                times.departures.copyInto(departures, index * width)
                times.trip.pickups.copyInto(pickups, index * width)
                times.trip.dropOffs.copyInto(dropOffs, index * width)
            }
            return Route(
                line = line,
                stops = stops,
                tripCount = trips.size,
                arrivals = arrivals,
                departures = departures,
                pickups = pickups,
                dropOffs = dropOffs,
                headsigns = Array(trips.size) { trips[it].trip.headsign },
                shapes = LongArray(trips.size) { trips[it].trip.shapeId ?: -1 },
                tripIds = Array(trips.size) { trips[it].trip.gtfsId },
                serviceDates = IntArray(trips.size) { trips[it].trip.serviceDate },
                sequences = sequences,
            )
        }

        /**
         * For each stop, the stops passengers can walk to in order to change:
         * the [footpaths] between served stops, corrected by what the feed
         * itself says about particular pairs.
         */
        private fun buildTransfers(
            stops: List<Stop>,
            served: BooleanArray,
            footpaths: Footpaths,
            fromFeed: List<StopTransfer>,
            indexByStopId: Map<Long, Int>,
        ): Array<HashMap<Int, Int>> {
            val result = Array(stops.size) { HashMap<Int, Int>() }
            for (from in stops.indices) {
                if (!served[from]) continue
                for (footpath in footpaths.from(stops[from].id)) {
                    val to = indexByStopId[footpath.to] ?: continue
                    if (to != from && served[to]) result[from][to] = footpath.seconds
                }
            }

            for (transfer in fromFeed) {
                val from = indexByStopId[transfer.from] ?: continue
                val to = indexByStopId[transfer.to] ?: continue
                if (from == to || !served[from] || !served[to]) continue
                val seconds = transfer.seconds
                when {
                    !transfer.possible -> {
                        result[from].remove(to)
                    }

                    seconds != null -> {
                        result[from][to] = seconds
                    }

                    to !in result[from] -> {
                        result[from][to] =
                            WalkEstimate.seconds(stops[from].location.distanceMetersTo(stops[to].location))
                    }
                }
            }
            return result
        }
    }
}
