package app.dojazd.routing

import app.dojazd.model.GeoPoint
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

/** A way on foot along streets and paths, from the first of [points] to the last. */
class WalkPath(
    val points: List<GeoPoint>,
    val meters: Double,
)

/**
 * The streets and paths of a city that can be walked, for finding how far it
 * really is on foot between two places and which way to go. Built by
 * `tools/citypack` from OpenStreetMap; see [read] for the file format.
 *
 * Places are tied to the network at the points nearest to them, and the bit
 * between a place and the network counts as a straight line.
 */
class WalkNetwork private constructor(
    /** Positions of the points, in millionths of a degree. */
    private val lats: IntArray,
    private val lons: IntArray,
    /** The points each point is joined to: those at `joinStart[point] until joinStart[point + 1]` in [joins]. */
    private val joinStart: IntArray,
    private val joins: IntArray,
) {
    val pointCount: Int get() = lats.size

    // Metres per millionth of a degree. One value for the east-west scale is exact enough across a city.
    private val metersPerLat = 0.11132
    private val metersPerLon: Double

    // Points sorted into a grid of cells about a hundred metres wide, to find those near a place.
    private val minLat: Int
    private val minLon: Int
    private val cellLat = 1_000
    private val cellLon: Int
    private val rows: Int
    private val columns: Int
    private val cellStart: IntArray
    private val cellPoints: IntArray

    init {
        minLat = lats.minOrNull() ?: 0
        minLon = lons.minOrNull() ?: 0
        val maxLat = lats.maxOrNull() ?: 0
        val maxLon = lons.maxOrNull() ?: 0
        val middle = (minLat + maxLat) / 2 / 1e6 * PI / 180
        metersPerLon = metersPerLat * cos(middle)
        cellLon = (cellLat / cos(middle).coerceAtLeast(0.01)).toInt()
        rows = (maxLat - minLat) / cellLat + 1
        columns = (maxLon - minLon) / cellLon + 1

        cellStart = IntArray(rows * columns + 1)
        for (point in lats.indices) cellStart[cellOf(lats[point], lons[point]) + 1]++
        for (cell in 0 until rows * columns) cellStart[cell + 1] += cellStart[cell]
        cellPoints = IntArray(lats.size)
        val next = cellStart.copyOf()
        for (point in lats.indices) cellPoints[next[cellOf(lats[point], lons[point])]++] = point
    }

    private fun cellOf(
        lat: Int,
        lon: Int,
    ) = (lat - minLat) / cellLat * columns + (lon - minLon) / cellLon

    private fun meters(
        latA: Int,
        lonA: Int,
        latB: Int,
        lonB: Int,
    ): Double {
        val north = (latB - latA) * metersPerLat
        val east = (lonB - lonA) * metersPerLon
        return sqrt(north * north + east * east)
    }

    /** A point of the network close to a place, and the distance between the two. */
    private class Tie(
        val point: Int,
        val meters: Double,
    )

    /**
     * The network points nearest to [place]. More than one, because the very
     * nearest may be a dead end behind a fence while the street runs a few metres further.
     */
    private fun ties(place: GeoPoint): List<Tie> {
        val lat = (place.lat * 1e6).toInt()
        val lon = (place.lon * 1e6).toInt()
        val reach = ceil(TIE_METERS / (cellLat * metersPerLat)).toInt()
        val row = (lat - minLat).floorDiv(cellLat)
        val column = (lon - minLon).floorDiv(cellLon)
        val found = ArrayList<Tie>()
        for (r in (row - reach).coerceAtLeast(0)..(row + reach).coerceAtMost(rows - 1)) {
            for (c in (column - reach).coerceAtLeast(0)..(column + reach).coerceAtMost(columns - 1)) {
                val cell = r * columns + c
                for (i in cellStart[cell] until cellStart[cell + 1]) {
                    val point = cellPoints[i]
                    val distance = meters(lat, lon, lats[point], lons[point])
                    if (distance <= TIE_METERS) found += Tie(point, distance)
                }
            }
        }
        if (found.isEmpty()) return found
        found.sortBy { it.meters }
        val nearest = found.first().meters
        return found.takeWhile { it.meters <= nearest + TIE_SPREAD_METERS }.take(MAX_TIES)
    }

    /**
     * Walking distances in metres from [from] to each of [targets], or null when
     * [from] is nowhere near the network. An entry is null when that target is
     * nowhere near the network, and infinite when it cannot be reached within [maxMeters].
     */
    fun distances(
        from: GeoPoint,
        targets: List<GeoPoint>,
        maxMeters: Double,
    ): List<Double?>? = Measurer().distances(from, targets, maxMeters)

    /** The shortest way on foot from [from] to [to], or null when there is none within [maxMeters] or either is off the network. */
    fun path(
        from: GeoPoint,
        to: GeoPoint,
        maxMeters: Double,
    ): WalkPath? = Measurer().path(from, to, maxMeters)

    /**
     * Measures walks one after another, reusing its memory instead of taking
     * new for each: for many searches in a row, such as from every stop to the
     * stops around it. Not to be shared between threads.
     */
    internal inner class Measurer {
        private val search = Search()

        /** As [WalkNetwork.path]. */
        fun path(
            from: GeoPoint,
            to: GeoPoint,
            maxMeters: Double,
        ): WalkPath? {
            val start = ties(from)
            val ends = ties(to)
            if (start.isEmpty() || ends.isEmpty()) return null
            search.spread(start, maxMeters)
            val end = ends.minBy { search.meters[it.point] + it.meters }
            val total = search.meters[end.point] + end.meters
            if (total > maxMeters) return null

            val points = ArrayList<GeoPoint>()
            points += to
            var point = end.point
            while (point >= 0) {
                points += GeoPoint(lats[point] / 1e6, lons[point] / 1e6)
                point = search.cameFrom[point]
            }
            points += from
            points.reverse()
            return WalkPath(points, total)
        }

        /** As [WalkNetwork.distances]. */
        fun distances(
            from: GeoPoint,
            targets: List<GeoPoint>,
            maxMeters: Double,
        ): List<Double?>? {
            val start = ties(from)
            if (start.isEmpty()) return null
            search.spread(start, maxMeters)
            return targets.map { target ->
                val ends = ties(target)
                if (ends.isEmpty()) {
                    null
                } else {
                    ends.minOf { search.meters[it.point] + it.meters }.takeIf { it <= maxMeters } ?: Double.POSITIVE_INFINITY
                }
            }
        }
    }

    /**
     * Dijkstra's algorithm outwards from a few points. Its arrays span the
     * whole network, so a search clears only the entries the previous one set.
     */
    private inner class Search {
        val meters = FloatArray(pointCount) { Float.POSITIVE_INFINITY }
        val cameFrom = IntArray(pointCount) { -1 }
        private val queue = PointQueue()
        private var touched = IntArray(256)
        private var touchedCount = 0

        private fun set(
            point: Int,
            distance: Float,
            from: Int,
        ) {
            if (meters[point] == Float.POSITIVE_INFINITY) {
                if (touchedCount == touched.size) touched = touched.copyOf(touchedCount * 2)
                touched[touchedCount++] = point
            }
            meters[point] = distance
            cameFrom[point] = from
        }

        /** Finds the distance to every point within [maxMeters] of [start]; every other point is left infinitely far. */
        fun spread(
            start: List<Tie>,
            maxMeters: Double,
        ) {
            for (i in 0 until touchedCount) {
                meters[touched[i]] = Float.POSITIVE_INFINITY
                cameFrom[touched[i]] = -1
            }
            touchedCount = 0

            for (tie in start) {
                if (tie.meters < meters[tie.point]) {
                    set(tie.point, tie.meters.toFloat(), -1)
                    queue.add(tie.point, tie.meters.toFloat())
                }
            }
            while (queue.isNotEmpty()) {
                val distance = queue.nearestDistance()
                val point = queue.removeNearest()
                // A point is queued again each time a shorter way to it turns up; the stale entries are skipped here.
                if (distance > meters[point]) continue
                for (i in joinStart[point] until joinStart[point + 1]) {
                    val next = joins[i]
                    val onward = distance + meters(lats[point], lons[point], lats[next], lons[next]).toFloat()
                    if (onward < meters[next] && onward <= maxMeters) {
                        set(next, onward, point)
                        queue.add(next, onward)
                    }
                }
            }
        }
    }

    /** A priority queue of points by distance, on plain arrays: boxing every entry would dominate the search. */
    private class PointQueue {
        private var points = IntArray(256)
        private var distances = FloatArray(256)
        private var size = 0

        fun isNotEmpty() = size > 0

        fun nearestDistance() = distances[0]

        fun add(
            point: Int,
            distance: Float,
        ) {
            if (size == points.size) {
                points = points.copyOf(size * 2)
                distances = distances.copyOf(size * 2)
            }
            var i = size++
            while (i > 0) {
                val parent = (i - 1) / 2
                if (distances[parent] <= distance) break
                points[i] = points[parent]
                distances[i] = distances[parent]
                i = parent
            }
            points[i] = point
            distances[i] = distance
        }

        fun removeNearest(): Int {
            val nearest = points[0]
            size--
            val point = points[size]
            val distance = distances[size]
            var i = 0
            while (true) {
                var child = 2 * i + 1
                if (child >= size) break
                if (child + 1 < size && distances[child + 1] < distances[child]) child++
                if (distances[child] >= distance) break
                points[i] = points[child]
                distances[i] = distances[child]
                i = child
            }
            points[i] = point
            distances[i] = distance
            return nearest
        }
    }

    companion object {
        /** A place further than this from every street and path is taken to be off the network. */
        private const val TIE_METERS = 300.0
        private const val TIE_SPREAD_METERS = 40.0
        private const val MAX_TIES = 6

        private val MAGIC =
            byteArrayOf('D'.code.toByte(), 'J'.code.toByte(), 'W'.code.toByte(), 'A'.code.toByte(), 'L'.code.toByte(), 'K'.code.toByte(), 1)

        /**
         * Reads a walk file. After the bytes `DJWALK` and the format version 1 come:
         *
         * - the number of points, then for each point its latitude and longitude in
         *   millionths of a degree, each as the difference from the previous point's;
         * - the number of ways, then for each way the number of points on it and
         *   those points in walking order, each as the difference from the previous point number.
         *
         * Every number is a variable-length integer (seven bits per byte, lowest
         * first, the top bit set when more bytes follow); differences are zigzag-encoded
         * so that small negative ones stay short.
         *
         * @throws IllegalArgumentException when [bytes] is not such a file
         */
        fun read(bytes: ByteArray): WalkNetwork {
            require(bytes.size > MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }) { "Not a walk file of format 1" }
            var position = MAGIC.size

            fun unsigned(): Long {
                var value = 0L
                var shift = 0
                while (true) {
                    require(position < bytes.size && shift < 64) { "The walk file is cut short" }
                    val byte = bytes[position++].toInt()
                    value = value or ((byte and 0x7F).toLong() shl shift)
                    if (byte and 0x80 == 0) return value
                    shift += 7
                }
            }

            fun signed(): Long = unsigned().let { (it ushr 1) xor -(it and 1) }

            val count = unsigned().toInt()
            require(count >= 0) { "The walk file is damaged" }
            val lats = IntArray(count)
            val lons = IntArray(count)
            var lat = 0L
            var lon = 0L
            for (point in 0 until count) {
                lat += signed()
                lon += signed()
                lats[point] = lat.toInt()
                lons[point] = lon.toInt()
            }

            // The ways are read twice: once to count how many joins each point has, once to store them.
            val waysAt = position

            fun forEachJoin(action: (Int, Int) -> Unit) {
                position = waysAt
                var previous = 0L
                repeat(unsigned().toInt()) {
                    val length = unsigned().toInt()
                    var before = -1
                    repeat(length) {
                        previous += signed()
                        val point = previous.toInt()
                        require(point in 0 until count) { "The walk file is damaged" }
                        if (before >= 0 && before != point) action(before, point)
                        before = point
                    }
                }
            }

            val joinStart = IntArray(count + 1)
            forEachJoin { a, b ->
                joinStart[a + 1]++
                joinStart[b + 1]++
            }
            for (point in 0 until count) joinStart[point + 1] += joinStart[point]
            val joins = IntArray(joinStart[count])
            val next = joinStart.copyOf()
            forEachJoin { a, b ->
                joins[next[a]++] = b
                joins[next[b]++] = a
            }
            return WalkNetwork(lats, lons, joinStart, joins)
        }
    }
}
