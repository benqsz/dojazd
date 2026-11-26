package app.dojazd.routing

import app.dojazd.gtfs.StopService
import app.dojazd.model.Stop
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Checks [Raptor] against a search that is slow but too simple to get wrong:
 * it tries every trip from every stop, with none of RAPTOR's shortcuts, on
 * small random cities full of overtaking trips, loops and skipped stops.
 */
class RaptorRandomTest {
    private data class Outcome(
        val departure: Int,
        val arrival: Int,
        val rides: Int,
    )

    @Test
    fun matchesExhaustiveSearchOnRandomCities() {
        var journeysChecked = 0
        repeat(CITIES) { seed ->
            val random = Random(seed)
            val city = randomCity(random)
            val network = city.network()

            repeat(QUERIES_PER_CITY) {
                val (access, egress) = randomEndpoints(random, network)
                val raptor = Raptor(network, access, egress, MAX_RIDES, CHANGE_SECONDS)
                val times = raptor.departureTimes(0, Int.MAX_VALUE / 2)

                // when
                val journeys = raptor.search(times.reversedArray())

                // then
                val context = "seed $seed, from ${access.describe(network)} to ${egress.describe(network)}"
                journeys.forEach { assertFeasible(it, city, network, access, egress, context) }
                val expected = exhaustiveSearch(city, network, access, egress, times)
                val actual = journeys.map { Outcome(it.departure, it.arrival, it.rides) }.toSet()
                assertEquals(expected, actual, context)
                assertEquals(journeys.size, journeys.withoutDominated().size, "dominated journey returned, $context")
                journeysChecked += journeys.size
            }
        }
        // Guards against a generator that produces cities where nothing connects.
        assertTrue(journeysChecked > CITIES * QUERIES_PER_CITY, "only $journeysChecked journeys checked")
    }

    private fun randomCity(random: Random): TestCity {
        val city = TestCity()
        // A loose grid: neighbours are sometimes within walking distance of each other, sometimes not.
        repeat(STOPS) { index ->
            city.stop(
                "S$index",
                metersNorth = index / 4 * 350.0 + random.nextInt(200),
                metersEast = index % 4 * 350.0 + random.nextInt(200),
            )
        }
        repeat(LINES) { line ->
            // Sampling with replacement makes some lines call at a stop twice.
            val stops =
                List(random.nextInt(2, 6)) { city.stops[random.nextInt(STOPS)] }
                    .zipWithNext()
                    .filter { (a, b) -> a != b }
                    .flatMap { listOf(it.first, it.second) }
                    .fold(ArrayList<Stop>()) { list, stop ->
                        if (list.lastOrNull() != stop) list += stop
                        list
                    }
            if (stops.size < 2) return@repeat
            repeat(random.nextInt(1, 7)) {
                var time = random.nextInt(0, 120) * 60
                val calls =
                    stops.map { stop ->
                        val arrival = time
                        val departure = arrival + random.nextInt(0, 3) * 30
                        // Different running times per trip make trips overtake each other.
                        time = departure + random.nextInt(1, 12) * 60
                        TestCall(
                            stop = stop,
                            arrival = arrival,
                            departure = departure,
                            pickup = if (random.nextInt(10) == 0) StopService.NONE else 0,
                            dropOff = if (random.nextInt(10) == 0) StopService.NONE else 0,
                        )
                    }
                city.trip("L$line", *calls.toTypedArray())
            }
        }
        return city
    }

    private fun randomEndpoints(
        random: Random,
        network: TransitNetwork,
    ): Pair<List<StopAccess>, List<StopAccess>> {
        val stops = network.stops.indices.shuffled(random)
        val accessCount = random.nextInt(1, 4)
        val egressCount = random.nextInt(1, 4)

        fun walk() = if (random.nextBoolean()) 0 else random.nextInt(1, 10) * 45
        return stops.take(accessCount).map { StopAccess(it, walk()) } to
            stops.drop(accessCount).take(egressCount).map { StopAccess(it, walk()) }
    }

    private fun List<StopAccess>.describe(network: TransitNetwork) =
        joinToString(prefix = "{", postfix = "}") { "${network.stops[it.stop].name}+${it.seconds}s" }

    /** Earliest arrival for every departure time and number of rides, reduced to the outcomes nothing else beats. */
    private fun exhaustiveSearch(
        city: TestCity,
        network: TransitNetwork,
        access: List<StopAccess>,
        egress: List<StopAccess>,
        departures: IntArray,
    ): Set<Outcome> {
        val patternsById = city.patterns.associateBy { it.id }
        val stopCount = network.stops.size
        val outcomes = ArrayList<Outcome>()

        for (departure in departures) {
            var ready = IntArray(stopCount) { NEVER }
            access.forEach { ready[it.stop] = minOf(ready[it.stop], departure + it.seconds) }
            var best = NEVER

            for (rides in 1..MAX_RIDES) {
                val alighted = IntArray(stopCount) { NEVER }
                for (day in city.days()) {
                    for (trip in day.trips) {
                        val stops = patternsById.getValue(trip.patternId).stops.map { network.indexOf(it.id)!! }
                        for (board in 0 until stops.size - 1) {
                            if (trip.pickups[board] == StopService.NONE) continue
                            if (trip.departures[board] + day.offsetSeconds < ready[stops[board]]) continue
                            for (alight in board + 1 until stops.size) {
                                if (trip.dropOffs[alight] == StopService.NONE) continue
                                val arrival = trip.arrivals[alight] + day.offsetSeconds
                                alighted[stops[alight]] = minOf(alighted[stops[alight]], arrival)
                            }
                        }
                    }
                }
                val next = ready.copyOf()
                for (stop in 0 until stopCount) {
                    if (alighted[stop] == NEVER) continue
                    next[stop] = minOf(next[stop], alighted[stop] + CHANGE_SECONDS)
                    network.forEachTransferFrom(stop) { to, seconds -> next[to] = minOf(next[to], alighted[stop] + seconds) }
                }
                ready = next

                val arrival = egress.minOf { if (alighted[it.stop] == NEVER) NEVER else alighted[it.stop] + it.seconds }
                if (arrival < best) {
                    best = arrival
                    outcomes += Outcome(departure, arrival, rides)
                }
            }
        }
        return outcomes
            .filter { outcome ->
                outcomes.none { other ->
                    other != outcome &&
                        other.departure >= outcome.departure &&
                        other.arrival <= outcome.arrival &&
                        other.rides <= outcome.rides
                }
            }.toSet()
    }

    /** Fails unless a passenger could really travel [journey] as described. */
    private fun assertFeasible(
        journey: Journey,
        city: TestCity,
        network: TransitNetwork,
        access: List<StopAccess>,
        egress: List<StopAccess>,
        context: String,
    ) {
        fun check(
            condition: Boolean,
            problem: String,
        ) {
            if (!condition) fail("$problem in ${journey.describe()}, $context")
        }
        val legs = journey.legs
        check(legs.isNotEmpty() && legs.any { it is Leg.Ride }, "no ride")

        legs.forEachIndexed { index, leg ->
            val previous = legs.getOrNull(index - 1)
            when (leg) {
                is Leg.Ride -> {
                    check(rideExists(leg, city), "ride not in the timetable")
                    val from = network.indexOf(leg.from.id)!!
                    when (previous) {
                        null -> {
                            check(
                                access.any { it.stop == from && it.seconds == 0 },
                                "starts at a stop that is not the origin",
                            )
                        }

                        is Leg.Ride -> {
                            check(previous.to == leg.from, "rides do not meet")
                            check(leg.departure >= previous.arrival + CHANGE_SECONDS, "too little time to change")
                        }

                        is Leg.Walk -> {
                            check(previous.to == leg.from, "walk ends elsewhere")
                            check(leg.departure >= previous.arrival, "ride leaves before the walk ends")
                        }
                    }
                    if (index == legs.lastIndex) {
                        val to = network.indexOf(leg.to.id)!!
                        check(egress.any { it.stop == to && it.seconds == 0 }, "ends at a stop that is not the destination")
                    }
                }

                is Leg.Walk -> {
                    val duration = leg.arrival - leg.departure
                    val from = leg.from?.let { network.indexOf(it.id)!! }
                    val to = leg.to?.let { network.indexOf(it.id)!! }
                    when {
                        from == null -> {
                            check(index == 0 && to != null, "walk from the origin in the wrong place")
                            check(access.any { it.stop == to && it.seconds == duration }, "wrong walk from the origin")
                        }

                        to == null -> {
                            check(index == legs.lastIndex && previous is Leg.Ride, "walk to the destination in the wrong place")
                            check(egress.any { it.stop == from && it.seconds == duration }, "wrong walk to the destination")
                            check(leg.departure == previous!!.arrival, "walk to the destination starts at the wrong time")
                        }

                        else -> {
                            check(previous is Leg.Ride && previous.to == leg.from, "walk does not follow a ride")
                            check(leg.departure == previous!!.arrival, "walk starts at the wrong time")
                            var seconds = -1
                            network.forEachTransferFrom(from) { target, time -> if (target == to) seconds = time }
                            check(seconds == duration, "walk is not a transfer of the network")
                        }
                    }
                }
            }
        }
    }

    private fun rideExists(
        ride: Leg.Ride,
        city: TestCity,
    ): Boolean =
        city.days().any { day ->
            day.trips.any { trip ->
                val pattern = city.patterns.first { it.id == trip.patternId }
                val size = ride.calls.size
                pattern.line == ride.line &&
                    (0..pattern.stops.size - size).any { start ->
                        trip.pickups[start] != StopService.NONE &&
                            trip.dropOffs[start + size - 1] != StopService.NONE &&
                            ride.calls.withIndex().all { (offset, call) ->
                                val position = start + offset
                                val time = if (offset == size - 1) trip.arrivals[position] else trip.departures[position]
                                pattern.stops[position] == call.stop && time + day.offsetSeconds == call.time
                            }
                    }
            }
        }

    private companion object {
        const val CITIES = 300
        const val QUERIES_PER_CITY = 8
        const val STOPS = 12
        const val LINES = 7
        const val MAX_RIDES = 4
        const val CHANGE_SECONDS = 60
        const val NEVER = Int.MAX_VALUE
    }
}
