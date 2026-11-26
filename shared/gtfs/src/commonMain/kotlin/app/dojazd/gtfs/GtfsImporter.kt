package app.dojazd.gtfs

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.dojazd.gtfs.db.GtfsDatabase
import kotlin.math.roundToInt

/**
 * Loads a GTFS feed into an empty [GtfsDatabase] in a single transaction, so a
 * failed import leaves nothing behind.
 *
 * `stop_times.txt` is processed as a stream, one trip at a time, which keeps
 * memory use flat for large cities. That requires the rows of each trip to be
 * contiguous; every feed seen so far is written that way, and one that is not
 * is rejected with a [GtfsFormatException] rather than imported incorrectly.
 */
class GtfsImporter(
    private val db: GtfsDatabase,
    /**
     * The driver of [db]. With it the import is much faster: indexes are built
     * once at the end instead of row by row, and nothing waits for the disk until
     * the end, which is safe for a database that is thrown away if the import fails.
     */
    private val driver: SqlDriver? = null,
) {
    private val queries = db.gtfsQueries

    /**
     * @param meta key/value pairs stored alongside the data (see [Timetable.meta])
     * @param onProgress called periodically with the number of stop times imported so far
     */
    fun import(
        feed: GtfsFeed,
        meta: Map<String, String> = emptyMap(),
        onProgress: (stopTimesImported: Int) -> Unit = {},
    ) {
        val indexes = driver?.let(::dropIndexes).orEmpty()
        driver?.execute(null, "PRAGMA synchronous = OFF", 0)
        try {
            db.transaction {
                val routes = importRoutes(feed)
                val stops = importStops(feed)
                importTransfers(feed, stops)
                val services = importServices(feed)
                val shapes = importShapes(feed)
                val trips = readTrips(feed, routes, services, shapes)
                importStopTimes(feed, trips, readFrequencies(feed), stops, onProgress)
                meta.forEach { (key, value) -> queries.upsertMeta(key, value) }
                // Building an index over rows already in place is far quicker than keeping it up to date row by row.
                indexes.forEach { driver?.execute(null, it, 0) }
            }
        } catch (e: IllegalArgumentException) {
            // A value the feed got wrong (a time, a number, a file name): one kind of failure for every caller.
            throw GtfsFormatException(e.message ?: "Malformed value in the feed")
        }
    }

    /** Drops the indexes of the schema and returns the statements that create them again. */
    private fun dropIndexes(driver: SqlDriver): List<String> {
        val indexes =
            driver
                .executeQuery(
                    identifier = null,
                    sql = "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL",
                    mapper = { cursor ->
                        val found = ArrayList<Pair<String, String>>()
                        while (cursor.next().value) found += cursor.getString(0)!! to cursor.getString(1)!!
                        QueryResult.Value(found)
                    },
                    parameters = 0,
                ).value
        indexes.forEach { (name, _) -> driver.execute(null, "DROP INDEX $name", 0) }
        return indexes.map { it.second }
    }

    private fun importRoutes(feed: GtfsFeed): Map<String, Long> {
        val ids = HashMap<String, Long>()
        feed.table("routes.txt") { columns, records ->
            val id = columns.required("route_id")
            val shortName = columns.optional("route_short_name")
            val longName = columns.optional("route_long_name")
            val description = columns.optional("route_desc")
            val type = columns.required("route_type")
            val color = columns.optional("route_color")
            val textColor = columns.optional("route_text_color")
            for (record in records) {
                val gtfsId = record.field(id)
                if (gtfsId.isEmpty() || gtfsId in ids) continue
                val rowId = ids.size + 1L
                ids[gtfsId] = rowId
                queries.insertRoute(
                    id = rowId,
                    gtfs_id = gtfsId,
                    // GTFS requires at least one of the two names.
                    short_name = record.field(shortName).ifEmpty { record.field(longName) },
                    long_name = record.field(longName),
                    description = record.field(description),
                    type = record.field(type).toLongOrNull() ?: -1,
                    color = record.field(color).toLongOrNull(radix = 16),
                    text_color = record.field(textColor).toLongOrNull(radix = 16),
                )
            }
        } ?: throw GtfsFormatException("routes.txt is missing")
        return ids
    }

    private class StopRef(
        val id: Long,
        val name: String,
    )

    private fun importStops(feed: GtfsFeed): Map<String, StopRef> {
        val stops = HashMap<String, StopRef>()
        feed.table("stops.txt") { columns, records ->
            val id = columns.required("stop_id")
            val code = columns.optional("stop_code")
            val name = columns.optional("stop_name")
            val platform = columns.optional("platform_code")
            val lat = columns.optional("stop_lat")
            val lon = columns.optional("stop_lon")
            val locationType = columns.optional("location_type")
            for (record in records) {
                val gtfsId = record.field(id)
                // Only boarding points (location_type 0 or empty); stations, entrances etc. carry no departures.
                val isBoardingPoint = record.field(locationType).let { it.isEmpty() || it == "0" }
                if (gtfsId.isEmpty() || gtfsId in stops || !isBoardingPoint) continue
                val ref = StopRef(id = stops.size + 1L, name = record.field(name))
                stops[gtfsId] = ref
                queries.insertStop(
                    id = ref.id,
                    gtfs_id = gtfsId,
                    code = record.field(code).ifEmpty { gtfsId },
                    name = ref.name,
                    platform = record.field(platform),
                    lat = coordinate(record.field(lat), MAX_LATITUDE) ?: 0.0,
                    lon = coordinate(record.field(lon), MAX_LONGITUDE) ?: 0.0,
                )
            }
        } ?: throw GtfsFormatException("stops.txt is missing")
        return stops
    }

    /** `transfers.txt` is optional; without it the journey planner estimates transfers from distance. */
    private fun importTransfers(
        feed: GtfsFeed,
        stops: Map<String, StopRef>,
    ) {
        feed.table("transfers.txt") { columns, records ->
            val from = columns.required("from_stop_id")
            val to = columns.required("to_stop_id")
            val type = columns.optional("transfer_type")
            val seconds = columns.optional("min_transfer_time")
            val scoped = listOf("from_route_id", "to_route_id", "from_trip_id", "to_trip_id").map { columns.optional(it) }
            for (record in records) {
                val fromStop = stops[record.field(from)] ?: continue
                val toStop = stops[record.field(to)] ?: continue
                // A rule for particular routes or trips says nothing about the pair of stops in general.
                if (fromStop.id == toStop.id || scoped.any { record.field(it).isNotEmpty() }) continue
                val transferType = record.field(type).toIntOrNull() ?: 0
                // Types 4 and 5 describe staying on the same vehicle, not a change between stops.
                if (transferType > TRANSFER_NOT_POSSIBLE) continue
                queries.insertTransfer(
                    from_stop = fromStop.id,
                    to_stop = toStop.id,
                    possible = if (transferType == TRANSFER_NOT_POSSIBLE) 0 else 1,
                    seconds = record.field(seconds).toLongOrNull()?.takeIf { it >= 0 },
                )
            }
        }
    }

    private fun importServices(feed: GtfsFeed): Map<String, Long> {
        val ids = HashMap<String, Long>()

        val hasCalendar =
            feed.table("calendar.txt") { columns, records ->
                val id = columns.required("service_id")
                val days = WEEKDAY_COLUMNS.map { columns.required(it) }
                val start = columns.required("start_date")
                val end = columns.required("end_date")
                for (record in records) {
                    val gtfsId = record.field(id)
                    if (gtfsId.isEmpty() || gtfsId in ids) continue
                    val rowId = ids.size + 1L
                    ids[gtfsId] = rowId
                    var weekdays = 0L
                    days.forEachIndexed { bit, column -> if (record.field(column) == "1") weekdays = weekdays or (1L shl bit) }
                    queries.insertService(
                        id = rowId,
                        gtfs_id = gtfsId,
                        weekdays = weekdays,
                        start_date = record.field(start).toLongOrNull() ?: 0,
                        end_date = record.field(end).toLongOrNull() ?: 0,
                    )
                }
            } != null

        val hasCalendarDates =
            feed.table("calendar_dates.txt") { columns, records ->
                val id = columns.required("service_id")
                val date = columns.required("date")
                val type = columns.required("exception_type")
                for (record in records) {
                    val gtfsId = record.field(id)
                    val day = record.field(date).toLongOrNull()
                    if (gtfsId.isEmpty() || day == null) continue
                    // A service may exist only as a list of added dates, with no calendar.txt row.
                    val rowId =
                        ids.getOrPut(gtfsId) {
                            val newId = ids.size + 1L
                            queries.insertService(id = newId, gtfs_id = gtfsId, weekdays = 0, start_date = 0, end_date = 0)
                            newId
                        }
                    queries.insertServiceException(service = rowId, date = day, added = if (record.field(type) == "1") 1 else 0)
                }
            } != null

        if (!hasCalendar && !hasCalendarDates) {
            throw GtfsFormatException("Neither calendar.txt nor calendar_dates.txt is present")
        }
        return ids
    }

    /** `shapes.txt` is optional; without it journeys are drawn as straight lines from stop to stop. */
    private fun importShapes(feed: GtfsFeed): Map<String, Long> {
        class Draft {
            var sequences = LongArray(256)
            var positions = IntArray(512)
            var size = 0
        }

        // The points of a shape need not be together or in order in the file, so they are gathered first.
        val drafts = LinkedHashMap<String, Draft>()
        feed.table("shapes.txt") { columns, records ->
            val id = columns.required("shape_id")
            val lat = columns.required("shape_pt_lat")
            val lon = columns.required("shape_pt_lon")
            val sequence = columns.required("shape_pt_sequence")
            for (record in records) {
                val shapeId = record.field(id)
                val pointLat = coordinate(record.field(lat), MAX_LATITUDE)
                val pointLon = coordinate(record.field(lon), MAX_LONGITUDE)
                val pointSequence = record.field(sequence).toLongOrNull()
                if (shapeId.isEmpty() || pointLat == null || pointLon == null || pointSequence == null) continue
                val draft = drafts.getOrPut(shapeId) { Draft() }
                if (draft.size == draft.sequences.size) {
                    draft.sequences = draft.sequences.copyOf(draft.size * 2)
                    draft.positions = draft.positions.copyOf(draft.size * 4)
                }
                draft.sequences[draft.size] = pointSequence
                draft.positions[2 * draft.size] = (pointLat * 1e6).roundToInt()
                draft.positions[2 * draft.size + 1] = (pointLon * 1e6).roundToInt()
                draft.size++
            }
        }

        val ids = HashMap<String, Long>()
        for ((shapeId, draft) in drafts) {
            val order = (0 until draft.size).sortedBy { draft.sequences[it] }
            val packed = ByteArray(draft.size * ShapePoints.BYTES_PER_POINT)
            order.forEachIndexed { index, point ->
                ShapePoints.put(packed, index, draft.positions[2 * point], draft.positions[2 * point + 1])
            }
            val rowId = ids.size + 1L
            ids[shapeId] = rowId
            queries.insertShape(rowId, packed)
        }
        return ids
    }

    private class TripDraft(
        val id: Long,
        val gtfsId: String,
        val route: Long,
        val service: Long,
        val headsign: String,
        val direction: Long,
        val wheelchair: Long,
        val shape: Long?,
    ) {
        var imported = false
    }

    private fun readTrips(
        feed: GtfsFeed,
        routes: Map<String, Long>,
        services: Map<String, Long>,
        shapes: Map<String, Long>,
    ): Map<String, TripDraft> {
        val trips = HashMap<String, TripDraft>()
        feed.table("trips.txt") { columns, records ->
            val id = columns.required("trip_id")
            val route = columns.required("route_id")
            val service = columns.required("service_id")
            val headsign = columns.optional("trip_headsign")
            val direction = columns.optional("direction_id")
            val wheelchair = columns.optional("wheelchair_accessible")
            val shape = columns.optional("shape_id")
            for (record in records) {
                val gtfsId = record.field(id)
                val routeId = routes[record.field(route)]
                val serviceId = services[record.field(service)]
                if (gtfsId.isEmpty() || gtfsId in trips || routeId == null || serviceId == null) continue
                trips[gtfsId] =
                    TripDraft(
                        id = trips.size + 1L,
                        gtfsId = gtfsId,
                        route = routeId,
                        service = serviceId,
                        headsign = record.field(headsign),
                        direction = record.field(direction).toLongOrNull() ?: 0,
                        wheelchair = record.field(wheelchair).toLongOrNull() ?: 0,
                        shape = shapes[record.field(shape)],
                    )
            }
        } ?: throw GtfsFormatException("trips.txt is missing")
        return trips
    }

    private class StopTimeDraft(
        val seq: Long,
        val stop: StopRef,
        /** [NO_TIME] until worked out from the stops around, where the feed gives none. */
        var arrival: Long,
        var departure: Long,
        val pickup: Long,
        val dropOff: Long,
    )

    /** A stretch of the day over which a trip is run again and again, every [headway] seconds. */
    private class Frequency(
        val start: Long,
        val end: Long,
        val headway: Long,
    )

    /**
     * `frequencies.txt` is optional. A trip it names is a pattern rather than one
     * run: its stop times say how long each stretch takes, and it leaves at every
     * headway through the given hours.
     */
    private fun readFrequencies(feed: GtfsFeed): Map<String, List<Frequency>> {
        val frequencies = HashMap<String, MutableList<Frequency>>()
        feed.table("frequencies.txt") { columns, records ->
            val trip = columns.required("trip_id")
            val start = columns.required("start_time")
            val end = columns.required("end_time")
            val headway = columns.required("headway_secs")
            for (record in records) {
                val from = parseTime(record.field(start)) ?: continue
                val until = parseTime(record.field(end)) ?: continue
                val every = record.field(headway).toLongOrNull()?.takeIf { it > 0 } ?: continue
                frequencies.getOrPut(record.field(trip)) { ArrayList() } += Frequency(from, until, every)
            }
        }
        return frequencies
    }

    /**
     * Gives a time to the stops the feed gives none: between two timed stops in
     * equal steps, the feed saying nothing of the distances; before the first or
     * after the last, which the specification rules out, the nearest known time.
     * False when no stop of the trip has a time at all.
     */
    private fun fillMissingTimes(rows: List<StopTimeDraft>): Boolean {
        val timed = rows.indices.filter { rows[it].departure != NO_TIME }
        if (timed.isEmpty()) return false
        for (index in 0 until timed.first()) rows[index].setBoth(rows[timed.first()].arrival)
        for (index in timed.last() + 1 until rows.size) rows[index].setBoth(rows[timed.last()].departure)
        for ((before, after) in timed.zipWithNext()) {
            val from = rows[before].departure
            val span = rows[after].arrival - from
            for (index in before + 1 until after) rows[index].setBoth(from + span * (index - before) / (after - before))
        }
        return true
    }

    private fun StopTimeDraft.setBoth(time: Long) {
        arrival = time
        departure = time
    }

    private data class PatternKey(
        val route: Long,
        val direction: Long,
        val stops: List<Long>,
    )

    private class PatternDraft(
        val id: Long,
        val headsign: String,
    ) {
        var tripCount = 0L
    }

    private fun importStopTimes(
        feed: GtfsFeed,
        trips: Map<String, TripDraft>,
        frequencies: Map<String, List<Frequency>>,
        stops: Map<String, StopRef>,
        onProgress: (Int) -> Unit,
    ) {
        val patterns = LinkedHashMap<PatternKey, PatternDraft>()
        var imported = 0
        // Runs made from frequencies take row ids after those of the trips the feed lists.
        var nextTripId = trips.size + 1L
        // With the driver at hand, stop times go in many rows to a statement: by far the largest table, it is
        // where the cost of each statement (high on Android, where every one crosses into native code) adds up.
        val batch = driver?.let(::StopTimeBatch)

        fun flush(
            trip: TripDraft,
            rows: MutableList<StopTimeDraft>,
        ) {
            trip.imported = true
            rows.sortBy { it.seq }
            if (rows.isEmpty() || !fillMissingTimes(rows)) {
                rows.clear()
                return
            }
            val key = PatternKey(trip.route, trip.direction, rows.map { it.stop.id })
            // A trip without a headsign is signed with its final stop.
            val headsign = trip.headsign.ifEmpty { rows.last().stop.name }
            val pattern = patterns.getOrPut(key) { PatternDraft(id = patterns.size + 1L, headsign = headsign) }

            fun write(
                id: Long,
                shift: Long,
            ) {
                pattern.tripCount++
                queries.insertTrip(
                    id = id,
                    gtfs_id = trip.gtfsId,
                    route = trip.route,
                    service = trip.service,
                    pattern = pattern.id,
                    headsign = headsign,
                    wheelchair = trip.wheelchair,
                    shape = trip.shape,
                )
                rows.forEachIndexed { index, row ->
                    val last = if (index == rows.lastIndex) 1L else 0L
                    if (batch != null) {
                        batch.add(id, row.seq, row.stop.id, row.arrival + shift, row.departure + shift, row.pickup, row.dropOff, last)
                    } else {
                        queries.insertStopTime(
                            trip = id,
                            seq = row.seq,
                            stop = row.stop.id,
                            arrival = row.arrival + shift,
                            departure = row.departure + shift,
                            pickup = row.pickup,
                            drop_off = row.dropOff,
                            last = last,
                        )
                    }
                }
                imported += rows.size
            }

            val headways = frequencies[trip.gtfsId]
            if (headways == null) {
                write(trip.id, shift = 0)
            } else {
                // The times of such a trip only say how long each stretch takes; each run starts at its own time.
                val first = rows.first().departure
                var id = trip.id
                for (headway in headways) {
                    var start = headway.start
                    while (start < headway.end && imported < MAX_STOP_TIMES) {
                        write(id, shift = start - first)
                        id = nextTripId++
                        start += headway.headway
                    }
                }
            }
            rows.clear()
        }

        feed.table("stop_times.txt") { columns, records ->
            val tripColumn = columns.required("trip_id")
            val stopColumn = columns.required("stop_id")
            val seqColumn = columns.required("stop_sequence")
            val arrivalColumn = columns.optional("arrival_time")
            val departureColumn = columns.optional("departure_time")
            val pickupColumn = columns.optional("pickup_type")
            val dropOffColumn = columns.optional("drop_off_type")

            var currentId: String? = null
            var current: TripDraft? = null
            val rows = ArrayList<StopTimeDraft>()
            var lastReported = 0

            for (record in records) {
                val tripId = record.field(tripColumn)
                if (tripId != currentId) {
                    current?.let { flush(it, rows) }
                    currentId = tripId
                    current = trips[tripId]
                    if (current?.imported == true) {
                        throw GtfsFormatException(
                            "stop_times.txt: rows of trip '$tripId' are not contiguous; the file must be grouped by trip_id",
                        )
                    }
                    if (imported - lastReported >= PROGRESS_STEP) {
                        lastReported = imported
                        onProgress(imported)
                    }
                }
                // Rows of trips that were not imported (an unknown route or service) are skipped.
                if (current == null) continue
                val stop = stops[record.field(stopColumn)] ?: continue
                val seq = record.field(seqColumn).toLongOrNull() ?: continue

                // Times are optional between timepoints: one stands in for the other, and a stop with neither
                // is given its time from the stops around once the whole trip is read.
                val arrivalText = record.field(arrivalColumn)
                val departureText = record.field(departureColumn)
                val arrivalTime = parseTime(arrivalText)
                // Nearly every stop is left the moment it is reached, so the one time read serves for both.
                val departure = (if (departureText == arrivalText) arrivalTime else parseTime(departureText)) ?: arrivalTime ?: NO_TIME
                val arrival = arrivalTime ?: departure

                rows +=
                    StopTimeDraft(
                        seq = seq,
                        stop = stop,
                        arrival = arrival,
                        departure = departure,
                        pickup = record.field(pickupColumn).toLongOrNull() ?: 0,
                        dropOff = record.field(dropOffColumn).toLongOrNull() ?: 0,
                    )
            }
            current?.let { flush(it, rows) }
            batch?.finish()
        } ?: throw GtfsFormatException("stop_times.txt is missing")

        for ((key, pattern) in patterns) {
            queries.insertPattern(
                id = pattern.id,
                route = key.route,
                direction = key.direction,
                headsign = pattern.headsign,
                trip_count = pattern.tripCount,
            )
            key.stops.forEachIndexed { seq, stop -> queries.insertPatternStop(pattern.id, seq.toLong(), stop) }
        }
        onProgress(imported)
    }

    /** Stop times gathered into multi-row inserts of [BATCH_ROWS] rows. */
    private class StopTimeBatch(
        private val driver: SqlDriver,
    ) {
        private val values = LongArray(BATCH_ROWS * COLUMNS)
        private var rows = 0

        fun add(vararg row: Long) {
            row.copyInto(values, rows * COLUMNS)
            rows++
            if (rows == BATCH_ROWS) write()
        }

        /** Writes what is left over. */
        fun finish() {
            if (rows > 0) write()
        }

        private fun write() {
            val count = rows
            // A full batch always has the same text, so the driver can keep it prepared under one identifier.
            driver.execute(if (count == BATCH_ROWS) FULL_BATCH_ID else null, insertSql(count), count * COLUMNS) {
                for (i in 0 until count * COLUMNS) bindLong(i, values[i])
            }
            rows = 0
        }

        private companion object {
            const val COLUMNS = 8

            /** Rows per statement; 8 columns each stays under the 999 parameters older SQLite allows. */
            const val BATCH_ROWS = 100

            const val FULL_BATCH_ID = 0x5701_7E00

            fun insertSql(rows: Int) = "INSERT INTO stop_time VALUES " + List(rows) { "(?, ?, ?, ?, ?, ?, ?, ?)" }.joinToString(", ")
        }
    }

    private fun parseTime(text: String): Long? = if (text.isEmpty()) null else GtfsTime.parseSeconds(text).toLong()

    /** A latitude or longitude on the globe; null for anything else a feed may hold there (text, NaN, infinity, 999). */
    private fun coordinate(
        text: String,
        limit: Double,
    ): Double? = text.toDoubleOrNull()?.takeIf { it >= -limit && it <= limit }

    private fun GtfsFeed.table(
        fileName: String,
        block: (CsvColumns, Sequence<List<String>>) -> Unit,
    ): Unit? =
        useLines(fileName) { lines ->
            val records = lines.csvRecords().iterator()
            if (!records.hasNext()) throw GtfsFormatException("$fileName is empty")
            block(CsvColumns(fileName, records.next()), records.asSequence())
        }

    private companion object {
        const val PROGRESS_STEP = 10_000
        const val TRANSFER_NOT_POSSIBLE = 3
        const val NO_TIME = -1L

        /** Far beyond any city; a feed whose headways multiply past it is a mistake, not a timetable. */
        const val MAX_STOP_TIMES = 20_000_000
        const val MAX_LATITUDE = 90.0
        const val MAX_LONGITUDE = 180.0
        val WEEKDAY_COLUMNS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    }
}
