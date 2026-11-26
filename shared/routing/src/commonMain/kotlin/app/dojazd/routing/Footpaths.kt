package app.dojazd.routing

import app.dojazd.model.Stop
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/**
 * The walks between stops close enough to each other to change vehicles, with
 * the time each takes. They depend only on where the stops are, not on the
 * date, so they are measured once per timetable and serve the network of every date.
 */
class Footpaths private constructor(
    private val byStop: Map<Long, List<Footpath>>,
) {
    /** A walk to the stop with id [to], taking [seconds]. */
    class Footpath(
        val to: Long,
        val seconds: Int,
    )

    /** The walks that start at the stop with id [stopId]. */
    fun from(stopId: Long): List<Footpath> = byStop[stopId].orEmpty()

    /** Number of walks, counting each direction. */
    val size: Int get() = byStop.values.sumOf { it.size }

    /**
     * The walks as bytes, for keeping them between runs of the app: measuring them
     * along streets takes a while, and they change only with the stops and the streets.
     * Each walk is 20 bytes, low byte first: the two stop ids and the seconds.
     */
    fun encode(): ByteArray {
        val bytes = ByteArray(4 + size * BYTES_PER_WALK)
        var at = putInt(bytes, 0, size)
        for ((from, walks) in byStop) {
            for (walk in walks) {
                at = putLong(bytes, at, from)
                at = putLong(bytes, at, walk.to)
                at = putInt(bytes, at, walk.seconds)
            }
        }
        return bytes
    }

    companion object {
        private const val BYTES_PER_WALK = 20

        /** Walks written by [encode]. @throws IllegalArgumentException when [bytes] are not such walks. */
        fun decode(bytes: ByteArray): Footpaths {
            require(bytes.size >= 4) { "Too short for footpaths" }
            val count = getInt(bytes, 0)
            require(count >= 0 && bytes.size == 4 + count * BYTES_PER_WALK) { "Not footpaths: ${bytes.size} bytes for $count walks" }
            val result = HashMap<Long, MutableList<Footpath>>()
            var at = 4
            repeat(count) {
                val from = getLong(bytes, at)
                val to = getLong(bytes, at + 8)
                val seconds = getInt(bytes, at + 16)
                at += BYTES_PER_WALK
                result.getOrPut(from) { ArrayList() } += Footpath(to, seconds)
            }
            return Footpaths(result)
        }

        private fun putInt(
            bytes: ByteArray,
            at: Int,
            value: Int,
        ): Int {
            for (i in 0..3) bytes[at + i] = (value shr (8 * i)).toByte()
            return at + 4
        }

        private fun putLong(
            bytes: ByteArray,
            at: Int,
            value: Long,
        ): Int {
            for (i in 0..7) bytes[at + i] = (value shr (8 * i)).toByte()
            return at + 8
        }

        private fun getInt(
            bytes: ByteArray,
            at: Int,
        ): Int {
            var value = 0
            for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
            return value
        }

        private fun getLong(
            bytes: ByteArray,
            at: Int,
        ): Long {
            var value = 0L
            for (i in 7 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
            return value
        }

        /** Stops further apart than this in a straight line are not offered as a change, unless the feed lists them. */
        const val DEFAULT_MAX_METERS = 300.0

        /**
         * Pairs every stop with the others within [maxMeters] in a straight line.
         * With [walks] each walk is measured along streets, and a stop that is close
         * but cut off (across a river, say) is left out; without, and for stops away
         * from every street, the time is estimated from the straight line.
         */
        fun measure(
            stops: List<Stop>,
            walks: WalkNetwork? = null,
            maxMeters: Double = DEFAULT_MAX_METERS,
        ): Footpaths {
            val located = stops.filter { it.hasLocation() }
            val result = HashMap<Long, MutableList<Footpath>>()

            fun add(
                from: Stop,
                to: Stop,
                seconds: Int,
            ) {
                result.getOrPut(from.id) { ArrayList() } += Footpath(to.id, seconds)
                result.getOrPut(to.id) { ArrayList() } += Footpath(from.id, seconds)
            }

            // Stops are bucketed into a grid of cells at least maxMeters wide, so
            // only the surrounding 3x3 cells need to be compared.
            val cellLat = maxMeters / METERS_PER_DEGREE
            // A degree of longitude is shortest at the stop furthest from the equator.
            val furthestLat = located.maxOfOrNull { abs(it.location.lat) } ?: 0.0
            val cellLon = cellLat / cos(furthestLat * PI / 180).coerceAtLeast(0.01)

            fun cellOf(stop: Stop) = floor(stop.location.lat / cellLat).toLong() to floor(stop.location.lon / cellLon).toLong()

            val grid = HashMap<Pair<Long, Long>, MutableList<Int>>()
            located.forEachIndexed { index, stop -> grid.getOrPut(cellOf(stop)) { ArrayList() } += index }

            val measurer = walks?.Measurer()
            for ((index, from) in located.withIndex()) {
                // Walks are the same both ways, so each pair is measured once, from the stop that comes first.
                val (row, column) = cellOf(from)
                val near = ArrayList<Stop>()
                val straight = ArrayList<Double>()
                for (r in row - 1..row + 1) {
                    for (c in column - 1..column + 1) {
                        for (other in grid[r to c].orEmpty()) {
                            if (other <= index) continue
                            val meters = from.location.distanceMetersTo(located[other].location)
                            if (meters <= maxMeters) {
                                near += located[other]
                                straight += meters
                            }
                        }
                    }
                }
                if (near.isEmpty()) continue

                val alongStreets =
                    measurer?.distances(
                        from = from.location,
                        targets = near.map { it.location },
                        maxMeters = WalkEstimate.maxAlongStreets(straight.max()),
                    )
                near.forEachIndexed { i, to ->
                    // Null: the streets around are not known, so the straight line has to do.
                    val walked = alongStreets?.get(i)
                    when {
                        walked == null -> add(from, to, WalkEstimate.seconds(straight[i]))
                        walked <= WalkEstimate.maxAlongStreets(straight[i]) -> add(from, to, WalkEstimate.secondsAlong(walked))
                    }
                }
            }
            return Footpaths(result)
        }

        // The importer stores 0,0 for a stop the feed gives no coordinates for.
        private fun Stop.hasLocation() = location.lat != 0.0 || location.lon != 0.0

        private const val METERS_PER_DEGREE = 111_320.0
    }
}
