package app.dojazd.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.dojazd.R
import app.dojazd.gtfs.GtfsTime
import app.dojazd.routing.Leg

internal const val DAY_SECONDS = 86_400

/** A departure rounded down and an arrival rounded up, so the times shown are always safe to rely on. */
internal fun departureClock(seconds: Int) = GtfsTime.format(seconds)

internal fun arrivalClock(seconds: Int) = GtfsTime.format(seconds + 59)

@Composable
internal fun duration(
    departure: Int,
    arrival: Int,
): String {
    val minutes = (arrival + 59) / 60 - departure / 60
    return if (minutes < 60) {
        stringResource(R.string.duration_minutes, minutes)
    } else {
        stringResource(R.string.duration_hours, minutes / 60, minutes % 60)
    }
}

internal fun walkMinutes(walk: Leg.Walk) = ((walk.arrival - walk.departure + 59) / 60).coerceAtLeast(1)
