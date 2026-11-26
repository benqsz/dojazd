package app.dojazd.model

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_008.8

@Serializable
data class GeoPoint(
    val lat: Double,
    val lon: Double,
) {
    /** Great-circle (haversine) distance in meters. */
    fun distanceMetersTo(other: GeoPoint): Double {
        val lat1 = lat.toRadians()
        val lat2 = other.lat.toRadians()
        val dLat = lat2 - lat1
        val dLon = (other.lon - lon).toRadians()
        val a =
            sin(dLat / 2) *
                sin(dLat / 2) +
                cos(lat1) *
                cos(lat2) *
                sin(dLon / 2) *
                sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
    }
}

@Serializable
data class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    operator fun contains(point: GeoPoint): Boolean = point.lat in south..north && point.lon in west..east
}

private fun Double.toRadians(): Double = this * PI / 180.0
