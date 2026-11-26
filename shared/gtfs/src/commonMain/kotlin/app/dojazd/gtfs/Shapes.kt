package app.dojazd.gtfs

import app.dojazd.model.GeoPoint

/** How the points of a shape are packed into the database: see the `shape` table in Gtfs.sq. */
internal object ShapePoints {
    const val BYTES_PER_POINT = 8

    fun put(
        packed: ByteArray,
        index: Int,
        latE6: Int,
        lonE6: Int,
    ) {
        putInt(packed, index * BYTES_PER_POINT, latE6)
        putInt(packed, index * BYTES_PER_POINT + 4, lonE6)
    }

    fun unpack(packed: ByteArray): List<GeoPoint> =
        List(packed.size / BYTES_PER_POINT) { index ->
            GeoPoint(getInt(packed, index * BYTES_PER_POINT) / 1e6, getInt(packed, index * BYTES_PER_POINT + 4) / 1e6)
        }

    private fun putInt(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        for (i in 0..3) bytes[offset + i] = (value shr (8 * i)).toByte()
    }

    private fun getInt(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        var value = 0
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[offset + i].toInt() and 0xFF)
        return value
    }
}

object ShapeSection {
    /** A stop further than this from the path is taken not to be on it. */
    private const val MAX_OFFSET_METERS = 150.0

    /**
     * The part of [shape] a vehicle travels from the stop at [from] to the stop
     * at [to], or null when the shape does not pass both of them in that order.
     * A shape describes a whole trip, from its first stop to its last.
     */
    fun between(
        shape: List<GeoPoint>,
        from: GeoPoint,
        to: GeoPoint,
    ): List<GeoPoint>? {
        if (shape.size < 2) return null

        fun nearest(
            point: GeoPoint,
            indices: IntRange,
        ): Int? =
            indices.minByOrNull { shape[it].distanceMetersTo(point) }?.takeIf { shape[it].distanceMetersTo(point) <= MAX_OFFSET_METERS }

        // Usually the point nearest to the first stop is where the passenger gets on. On a line that
        // passes the same place twice it may be the wrong pass, so the search is tried from the other end too.
        val start = nearest(from, shape.indices)
        val end = start?.let { nearest(to, it..shape.lastIndex) }
        if (start != null && end != null && end > start) return shape.subList(start, end + 1)

        val lastEnd = nearest(to, shape.indices) ?: return null
        val earlierStart = nearest(from, 0..lastEnd) ?: return null
        return if (earlierStart < lastEnd) shape.subList(earlierStart, lastEnd + 1) else null
    }
}
