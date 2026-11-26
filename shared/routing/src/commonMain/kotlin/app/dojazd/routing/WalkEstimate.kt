package app.dojazd.routing

import app.dojazd.model.GeoPoint
import kotlin.math.roundToInt

/**
 * Walking time, either along a path of known length or estimated from
 * straight-line distance where the streets are not known.
 */
object WalkEstimate {
    /** Real walking paths are longer than the straight line between two points. */
    const val DETOUR_FACTOR = 1.3

    /** 4.5 km/h. */
    const val SPEED_METERS_PER_SECOND = 1.25

    /** How much longer than the straight line a walk along streets may be before the place counts as out of reach... */
    const val MAX_DETOUR = 2.0

    /** ...plus this, so that a place next door behind a block is not out of reach either. */
    const val DETOUR_ALLOWANCE_METERS = 300.0

    /** The longest walk along streets still worth taking between two places [straightLineMeters] apart. */
    fun maxAlongStreets(straightLineMeters: Double): Double = straightLineMeters * MAX_DETOUR + DETOUR_ALLOWANCE_METERS

    fun seconds(
        from: GeoPoint,
        to: GeoPoint,
    ): Int = seconds(from.distanceMetersTo(to))

    /** Walking time along a known path of [meters], where no allowance for detours is needed. */
    fun secondsAlong(meters: Double): Int {
        require(meters >= 0) { "Distance must not be negative: $meters" }
        return (meters / SPEED_METERS_PER_SECOND).roundToInt()
    }

    fun seconds(straightLineMeters: Double): Int {
        require(straightLineMeters >= 0) { "Distance must not be negative: $straightLineMeters" }
        return (straightLineMeters * DETOUR_FACTOR / SPEED_METERS_PER_SECOND).roundToInt()
    }
}
