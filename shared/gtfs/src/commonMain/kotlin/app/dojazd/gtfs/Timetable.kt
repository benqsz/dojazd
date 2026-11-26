package app.dojazd.gtfs

import app.cash.sqldelight.db.QueryResult
import app.dojazd.gtfs.db.GtfsDatabase
import app.dojazd.model.Departure
import app.dojazd.model.GeoPoint
import app.dojazd.model.Line
import app.dojazd.model.LineDirection
import app.dojazd.model.LineVariant
import app.dojazd.model.Stop
import app.dojazd.model.TripRef
import app.dojazd.model.VehicleType
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Read-only timetable queries over a database filled by [GtfsImporter]. */
class Timetable(
    db: GtfsDatabase,
) {
    private val queries = db.gtfsQueries

    // Lines, stops and the calendar are small (hundreds to a few thousand rows) and
    // needed by almost every query, so they are loaded once.
    private val linesById: Map<Long, Line> by lazy {
        queries.selectRoutes().executeAsList().associate { route ->
            route.id to
                Line(
                    id = route.id,
                    name = route.short_name,
                    longName = route.long_name,
                    category = route.description,
                    type = VehicleType.fromGtfsRouteType(route.type.toInt()),
                    color = route.color?.toInt(),
                    textColor = route.text_color?.toInt(),
                )
        }
    }

    private val lineIdsByGtfsId: Map<String, Long> by lazy {
        queries.selectRoutes().executeAsList().associate { it.gtfs_id to it.id }
    }

    private val stopIdsByGtfsId: Map<String, Long> by lazy {
        queries.selectStops().executeAsList().associate { it.gtfs_id to it.id }
    }

    private val stopsById: Map<Long, Stop> by lazy {
        queries.selectStops().executeAsList().associate { stop ->
            stop.id to
                Stop(
                    id = stop.id,
                    code = stop.code,
                    name = stop.name,
                    platform = stop.platform,
                    location = GeoPoint(stop.lat, stop.lon),
                    gtfsId = stop.gtfs_id,
                )
        }
    }

    private val calendar: ServiceCalendar by lazy {
        ServiceCalendar(
            rules =
                queries.selectServices().executeAsList().map {
                    ServiceCalendar.Rule(it.id, it.weekdays.toInt(), it.start_date.toInt(), it.end_date.toInt())
                },
            changesByDate =
                queries.selectServiceExceptions().executeAsList().groupBy(
                    keySelector = { it.date.toInt() },
                    valueTransform = { ServiceCalendar.Change(it.service, added = it.added == 1L) },
                ),
        )
    }

    /** Lines in the order the feed lists them. */
    fun lines(): List<Line> = linesById.values.toList()

    fun line(id: Long): Line? = linesById[id]

    fun stops(): List<Stop> = stopsById.values.toList()

    fun stop(id: Long): Stop? = stopsById[id]

    /** The line with `route_id` [gtfsId] in the feed, as live data names it. */
    fun lineByGtfsId(gtfsId: String): Line? = lineIdsByGtfsId[gtfsId]?.let(linesById::get)

    /** The stop with `stop_id` [gtfsId] in the feed, as live data names it. */
    fun stopByGtfsId(gtfsId: String): Stop? = stopIdsByGtfsId[gtfsId]?.let(stopsById::get)

    /**
     * Stops a passenger can actually leave from. Excludes technical stops and
     * arrival-only points, which would only ever show an empty departure board.
     */
    fun boardingStops(): List<Stop> = boardingStops

    private val boardingStops: List<Stop> by lazy {
        val ids = queries.selectBoardingStops().executeAsList().toSet()
        stopsById.values.filter { it.id in ids }
    }

    /** The kinds of vehicle that call at each stop, by stop id; stops nothing calls at are left out. */
    fun vehicleTypesByStop(): Map<Long, Set<VehicleType>> {
        val types = HashMap<Long, MutableSet<VehicleType>>()
        queries.selectStopRouteTypes().executeAsList().forEach { row ->
            types.getOrPut(row.stop) { HashSet() } += VehicleType.fromGtfsRouteType(row.type.toInt())
        }
        return types
    }

    fun meta(key: String): String? = queries.selectMeta(key).executeAsOneOrNull()

    /** One entry per direction, using the stop sequence served by the most trips. */
    fun directions(lineId: Long): List<LineDirection> {
        val shapes = queries.selectPatternShapes(lineId).executeAsList().associate { it.pattern to it.shape }
        return queries
            .selectPatterns(lineId)
            .executeAsList()
            .groupBy { it.direction }
            .values
            .map { patterns ->
                val main = patterns.first()
                LineDirection(
                    direction = main.direction.toInt(),
                    headsign = main.headsign,
                    stops = patternStops(main.id),
                    shapeId = shapes[main.id],
                    variants =
                        patterns.drop(1).map { pattern ->
                            LineVariant(pattern.headsign, patternStops(pattern.id), pattern.trip_count.toInt(), shapes[pattern.id])
                        },
                )
            }
    }

    private fun patternStops(pattern: Long): List<Stop> = queries.selectPatternStops(pattern).executeAsList().mapNotNull { stopsById[it] }

    /**
     * The next departures from any of [stopIds], starting at [fromSeconds] after
     * midnight of [date]. Includes trips of the previous service day that are
     * still running after midnight and, if needed to fill [limit], the next day.
     */
    fun upcomingDepartures(
        stopIds: Collection<Long>,
        date: LocalDate,
        fromSeconds: Int,
        limit: Int = 40,
    ): List<Departure> {
        val result = ArrayList<Departure>()
        result += departures(stopIds, date.minus(1, DateTimeUnit.DAY), fromSeconds + DAY_SECONDS, -DAY_SECONDS, limit)
        result += departures(stopIds, date, fromSeconds, 0, limit)
        if (result.size < limit) {
            result += departures(stopIds, date.plus(1, DateTimeUnit.DAY), 0, DAY_SECONDS, limit)
        }
        return result.sortedBy { it.time }.take(limit)
    }

    private fun departures(
        stopIds: Collection<Long>,
        serviceDate: LocalDate,
        fromSeconds: Int,
        timeOffset: Int,
        limit: Int,
    ): List<Departure> {
        val services = calendar.activeOn(serviceDate)
        if (services.isEmpty() || stopIds.isEmpty()) return emptyList()
        return queries
            .selectDepartures(
                stops = stopIds,
                services = services,
                fromSeconds = fromSeconds.toLong(),
                limit = limit.toLong(),
            ).executeAsList()
            .mapNotNull {
                val trip = TripRef(it.gtfs_id, serviceDate.toGtfsDate(), it.seq.toInt(), stopsById[it.stop]?.gtfsId)
                toDeparture(it.departure + timeOffset, it.pickup, it.stop, it.route, it.headsign, it.wheelchair, trip)
            }
    }

    /** Every departure of one line from one stop on the service day [date], in time order. */
    fun lineDeparturesAtStop(
        lineId: Long,
        stopId: Long,
        date: LocalDate,
    ): List<Departure> {
        val services = calendar.activeOn(date)
        if (services.isEmpty()) return emptyList()
        return queries
            .selectRouteDeparturesAtStop(
                stop = stopId,
                route = lineId,
                services = services,
            ).executeAsList()
            .mapNotNull {
                val trip = TripRef(it.gtfs_id, date.toGtfsDate(), it.seq.toInt(), stopsById[it.stop]?.gtfsId)
                toDeparture(it.departure, it.pickup, it.stop, it.route, it.headsign, it.wheelchair, trip)
            }
    }

    /** Every distinct stop sequence in the feed; [ScheduledTrip.patternId] refers to these. */
    fun patterns(): List<TripPattern> {
        val stopsByPattern = HashMap<Long, MutableList<Stop>>()
        queries.selectAllPatternStops().executeAsList().forEach { row ->
            stopsById[row.stop]?.let { stopsByPattern.getOrPut(row.pattern) { ArrayList() } += it }
        }
        return queries.selectAllPatterns().executeAsList().mapNotNull { pattern ->
            TripPattern(
                id = pattern.id,
                line = linesById[pattern.route] ?: return@mapNotNull null,
                stops = stopsByPattern[pattern.id] ?: return@mapNotNull null,
            )
        }
    }

    /**
     * The last day the timetable has any service on, as a GTFS `YYYYMMDD` number;
     * null when it names none. After it every board is empty.
     */
    fun lastServiceDate(): Int? {
        val byRule = queries.selectLastServiceDate().executeAsOne().MAX ?: 0
        val byException = queries.selectLastAddedDate().executeAsOne().MAX ?: 0
        return maxOf(byRule, byException).toInt().takeIf { it > 0 }
    }

    /**
     * Every trip of the service day [date] with all its stop times; the input of
     * the journey planner. With [endingAfter], only the trips still on their way
     * after that many seconds, which spares reading the rest of the day.
     */
    fun tripsOn(
        date: LocalDate,
        endingAfter: Int? = null,
    ): List<ScheduledTrip> {
        val services = calendar.activeOn(date)
        if (services.isEmpty()) return emptyList()

        class Header(
            val pattern: Long,
            val headsign: String,
            val shape: Long?,
            val gtfsId: String,
        )
        val headers =
            queries
                .selectTripsOfServices(services)
                .executeAsList()
                .associate { it.id to Header(it.pattern, it.headsign, it.shape, it.gtfs_id) }
        val serviceDate = date.toGtfsDate()

        val trips = ArrayList<ScheduledTrip>(headers.size)
        // Read through the cursor: a service day has hundreds of thousands of stop times.
        val stopTimes =
            if (endingAfter == null) {
                queries.selectStopTimesOfServices(services)
            } else {
                queries.selectStopTimesOfServicesEndingAfter(services, endingAfter.toLong())
            }
        stopTimes.execute { cursor ->
            var tripId = -1L
            var arrivals = IntArray(64)
            var departures = IntArray(64)
            var pickups = ByteArray(64)
            var dropOffs = ByteArray(64)
            var sequences = IntArray(64)
            var size = 0

            fun flush() {
                val header = headers[tripId]
                if (size > 0 && header != null) {
                    trips +=
                        ScheduledTrip(
                            patternId = header.pattern,
                            headsign = header.headsign,
                            arrivals = arrivals.copyOf(size),
                            departures = departures.copyOf(size),
                            pickups = pickups.copyOf(size),
                            dropOffs = dropOffs.copyOf(size),
                            shapeId = header.shape,
                            gtfsId = header.gtfsId,
                            serviceDate = serviceDate,
                            sequences = sequences.copyOf(size),
                        )
                }
                size = 0
            }

            while (cursor.next().value) {
                val rowTrip = cursor.getLong(0)!!
                if (rowTrip != tripId) {
                    flush()
                    tripId = rowTrip
                }
                if (size == arrivals.size) {
                    arrivals = arrivals.copyOf(size * 2)
                    departures = departures.copyOf(size * 2)
                    pickups = pickups.copyOf(size * 2)
                    dropOffs = dropOffs.copyOf(size * 2)
                    sequences = sequences.copyOf(size * 2)
                }
                arrivals[size] = cursor.getLong(1)!!.toInt()
                departures[size] = cursor.getLong(2)!!.toInt()
                pickups[size] = cursor.getLong(3)!!.toByte()
                dropOffs[size] = cursor.getLong(4)!!.toByte()
                sequences[size] = cursor.getLong(5)!!.toInt()
                size++
            }
            flush()
            QueryResult.Unit
        }
        return trips
    }

    /** The path of the shape with id [id] (see [ScheduledTrip.shapeId]), in travel order; empty when there is none. */
    fun shape(id: Long): List<GeoPoint> =
        queries
            .selectShape(id)
            .executeAsOneOrNull()
            ?.let(ShapePoints::unpack)
            .orEmpty()

    /** Transfers the feed describes explicitly. Pairs it does not mention are left to the planner. */
    fun transfers(): List<StopTransfer> =
        queries.selectTransfers().executeAsList().map {
            StopTransfer(from = it.from_stop, to = it.to_stop, possible = it.possible == 1L, seconds = it.seconds?.toInt())
        }

    private fun toDeparture(
        time: Long,
        pickup: Long,
        stop: Long,
        route: Long,
        headsign: String,
        wheelchair: Long,
        trip: TripRef,
    ): Departure? {
        return Departure(
            time = time.toInt(),
            line = linesById[route] ?: return null,
            headsign = headsign,
            stop = stopsById[stop] ?: return null,
            onRequest = pickup == PICKUP_ON_REQUEST,
            lowFloor =
                when (wheelchair) {
                    1L -> true
                    2L -> false
                    else -> null
                },
            trip = trip,
        )
    }

    companion object {
        /** Bump whenever Gtfs.sq or the importer changes what ends up in the database. */
        const val DB_FORMAT = 6

        const val META_ETAG = "etag"
        const val META_IMPORTED_AT = "imported_at"

        private const val DAY_SECONDS = 86_400
        private const val PICKUP_ON_REQUEST = 3L
    }
}
