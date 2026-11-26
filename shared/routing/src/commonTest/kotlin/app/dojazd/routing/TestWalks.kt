package app.dojazd.routing

import app.dojazd.model.GeoPoint

/** A point given in metres north and east of the corner of the test city (see [TestCity.stop]). */
fun spot(
    metersNorth: Double,
    metersEast: Double = 0.0,
) = GeoPoint(53.0 + metersNorth / 111_320.0, 14.0 + metersEast / 66_990.0)

/** Encodes a walk file the way `tools/citypack` does; [ways] hold positions in [points]. */
fun walkFile(
    points: List<GeoPoint>,
    ways: List<List<Int>>,
): ByteArray {
    val out = ArrayList<Byte>()
    "DJWALK".forEach { out += it.code.toByte() }
    out += 1

    fun unsigned(number: Long) {
        var value = number
        while (value >= 0x80) {
            out += ((value and 0x7F) or 0x80).toByte()
            value = value ushr 7
        }
        out += value.toByte()
    }

    fun signed(number: Long) = unsigned((number shl 1) xor (number shr 63))

    unsigned(points.size.toLong())
    var lat = 0L
    var lon = 0L
    for (point in points) {
        val nextLat = Math.round(point.lat * 1e6)
        val nextLon = Math.round(point.lon * 1e6)
        signed(nextLat - lat)
        signed(nextLon - lon)
        lat = nextLat
        lon = nextLon
    }
    unsigned(ways.size.toLong())
    var previous = 0L
    for (way in ways) {
        unsigned(way.size.toLong())
        for (position in way) {
            signed(position - previous)
            previous = position.toLong()
        }
    }
    return out.toByteArray()
}

fun walkNetwork(
    points: List<GeoPoint>,
    ways: List<List<Int>>,
) = WalkNetwork.read(walkFile(points, ways))
