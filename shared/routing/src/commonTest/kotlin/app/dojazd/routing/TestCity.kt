package app.dojazd.routing

import app.dojazd.gtfs.ScheduledTrip
import app.dojazd.gtfs.StopTransfer
import app.dojazd.gtfs.TripPattern
import app.dojazd.model.GeoPoint
import app.dojazd.model.Line
import app.dojazd.model.Stop
import app.dojazd.model.VehicleType

fun at(
    hours: Int,
    minutes: Int = 0,
) = hours * 3600 + minutes * 60

/** One stop of a test trip: where, when, and whether passengers may get on and off. */
class TestCall(
    val stop: Stop,
    val arrival: Int,
    val departure: Int = arrival,
    val pickup: Byte = 0,
    val dropOff: Byte = 0,
)

infix fun Stop.at(time: Int) = TestCall(this, time)

/** Collects stops, lines and trips for a test and turns them into a [TransitNetwork]. */
class TestCity {
    val stops = ArrayList<Stop>()
    val patterns = ArrayList<TripPattern>()
    val transfers = ArrayList<StopTransfer>()
    private val lines = HashMap<String, Line>()
    private val trips = HashMap<Int, MutableList<ScheduledTrip>>()

    /** Stops are placed on a line running north; 0.001 degrees of latitude is about 111 m. */
    fun stop(
        name: String,
        metersNorth: Double,
        metersEast: Double = 0.0,
    ): Stop {
        val location = GeoPoint(53.0 + metersNorth / 111_320.0, 14.0 + metersEast / 66_990.0)
        return Stop(id = 100L + stops.size, code = name, name = name, platform = "", location = location).also { stops += it }
    }

    fun line(name: String): Line =
        lines.getOrPut(name) {
            Line(id = lines.size + 1L, name = name, longName = "", category = "", type = VehicleType.BUS, color = null, textColor = null)
        }

    /** Adds a trip to the service day starting [dayOffset] days from the network's date. */
    fun trip(
        line: String,
        vararg calls: TestCall,
        headsign: String = calls.last().stop.name,
        dayOffset: Int = 0,
        gtfsId: String? = null,
        sequences: IntArray? = null,
    ) {
        val stopsOfTrip = calls.map { it.stop }
        val pattern =
            patterns.firstOrNull { it.line.name == line && it.stops == stopsOfTrip }
                ?: TripPattern(id = patterns.size + 1L, line = line(line), stops = stopsOfTrip).also { patterns += it }
        trips.getOrPut(dayOffset) { ArrayList() } +=
            ScheduledTrip(
                patternId = pattern.id,
                headsign = headsign,
                arrivals = IntArray(calls.size) { calls[it].arrival },
                departures = IntArray(calls.size) { calls[it].departure },
                pickups = ByteArray(calls.size) { calls[it].pickup },
                dropOffs = ByteArray(calls.size) { calls[it].dropOff },
                gtfsId = gtfsId,
                serviceDate = 20260302 + dayOffset,
                sequences = sequences,
            )
    }

    fun days(): List<ServiceDay> = trips.map { (offset, dayTrips) -> ServiceDay(offset * 86_400, dayTrips) }

    /** With [walks], changes between stops are timed along its streets. */
    fun network(walks: WalkNetwork? = null): TransitNetwork =
        TransitNetwork.build(stops, patterns, days(), transfers, Footpaths.measure(stops, walks))

    fun planner(options: JourneyPlanner.Options = JourneyPlanner.Options()) = JourneyPlanner(network(), options)
}

fun endpoint(stop: Stop) = Endpoint(stop.location, listOf(stop.id))

/** A compact rendering of a journey for assertions, e.g. `[1 A 08:00-08:10 B, walk B-C 08:10-08:13]`. */
fun Journey.describe(): String =
    legs.joinToString(prefix = "[", postfix = "]") { leg ->
        when (leg) {
            is Leg.Ride -> {
                "${leg.line.name} ${leg.from.name} ${clock(leg.departure)}-${clock(leg.arrival)} ${leg.to.name}"
            }

            is Leg.Walk -> {
                val from = leg.from?.name ?: "origin"
                val to = leg.to?.name ?: "destination"
                "walk $from-$to ${clock(leg.departure)}-${clock(leg.arrival)}"
            }
        }
    }

private fun clock(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val rest = seconds % 60
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}" +
        if (rest == 0) "" else ":${rest.toString().padStart(2, '0')}"
}
