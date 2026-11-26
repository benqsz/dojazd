package app.dojazd.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dojazd.R
import app.dojazd.model.Stop
import app.dojazd.realtime.Prediction
import app.dojazd.routing.Journey
import app.dojazd.routing.Leg
import app.dojazd.ui.LineBadge
import app.dojazd.ui.LiveIcon
import app.dojazd.ui.LiveRow
import app.dojazd.ui.lineColors
import app.dojazd.ui.liveColor
import app.dojazd.ui.theme.IconSize
import app.dojazd.ui.theme.Spacing

@Composable
private fun delayText(seconds: Int): String {
    val minutes = seconds / 60
    return when {
        minutes >= 1 -> stringResource(R.string.live_late, minutes)
        minutes <= -1 -> stringResource(R.string.live_early, -minutes)
        else -> stringResource(R.string.live_on_time)
    }
}

/** A piece of the line drawn down the left of the timeline: solid in a line's colour, or dotted for walking. */
private class Segment(
    val color: Color,
    val dotted: Boolean,
)

/** One row of the timeline: a time, the line beside it, and what happens there. */
private class TimelineRow(
    val time: String?,
    val above: Segment?,
    val below: Segment?,
    /** A dot at the row's first line of text, or null for none. */
    val dot: Color?,
    /** A stop passed on the way: a smaller dot and smaller text. */
    val minor: Boolean = false,
    val content: @Composable ColumnScope.() -> Unit,
)

/** The journey step by step: where to walk, where to get on and off, with times. */
@Composable
internal fun Timeline(
    journey: Journey,
    predict: (Leg.Ride) -> Prediction?,
) {
    val walkSegment = Segment(MaterialTheme.colorScheme.outline, dotted = true)
    val endColor = MaterialTheme.colorScheme.primary
    val rows = ArrayList<TimelineRow>()
    // Which rides show the stops they pass, by position in the journey.
    var openRides by rememberSaveable { mutableStateOf(emptySet<Int>()) }
    journey.legs.forEachIndexed { index, leg ->
        val first = index == 0
        val last = index == journey.legs.lastIndex
        when (leg) {
            is Leg.Walk -> {
                rows +=
                    TimelineRow(
                        time = if (first) departureClock(leg.departure) else null,
                        above = if (first) null else walkSegment,
                        below = walkSegment,
                        dot = if (first) endColor else null,
                    ) { WalkText(leg) }
            }

            is Leg.Ride -> {
                val color = lineColors(leg.line).background
                val ride = Segment(color, dotted = false)
                val prediction = predict(leg)
                val open = index in openRides
                // At a change, the time spent at the stop before the next vehicle comes.
                val waitMinutes = journey.legs.getOrNull(index - 1)?.let { (leg.departure - it.arrival) / 60 } ?: 0
                if (!first && waitMinutes >= 1) {
                    rows +=
                        TimelineRow(time = null, above = walkSegment, below = walkSegment, dot = null) {
                            WaitText(leg, waitMinutes)
                        }
                }
                rows +=
                    TimelineRow(
                        time = departureClock(leg.departure),
                        above = if (first) null else walkSegment,
                        below = ride,
                        dot = color,
                    ) {
                        Boarding(leg, prediction)
                        PassedStops(leg, open) { openRides = if (open) openRides - index else openRides + index }
                    }
                if (open) {
                    // Each stop passed on the way gets its own dot on the line.
                    for (call in leg.calls.subList(1, leg.calls.lastIndex)) {
                        rows +=
                            TimelineRow(
                                time = departureClock(call.time),
                                above = ride,
                                below = ride,
                                dot = color,
                                minor = true,
                            ) { Text(call.stop.name, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                rows +=
                    TimelineRow(
                        time = arrivalClock(leg.arrival),
                        above = ride,
                        below = if (last) null else walkSegment,
                        dot = color,
                    ) {
                        StopName(leg.to, leg.alightOnRequest)
                        Text(
                            stringResource(R.string.journey_get_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
    }
    if (journey.legs.last() is Leg.Walk) {
        rows +=
            TimelineRow(time = arrivalClock(journey.arrival), above = walkSegment, below = null, dot = endColor) {
                Text(
                    stringResource(R.string.journey_destination),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
    }
    Column { rows.forEach { TimelineItem(it) } }
}

@Composable
private fun TimelineItem(row: TimelineRow) {
    val dotY = with(LocalDensity.current) { (if (row.minor) 8.dp else 11.dp).toPx() }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Text(
            row.time.orEmpty(),
            modifier = Modifier.widthIn(min = TIME_WIDTH).padding(top = 1.dp),
            style = if (row.minor) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
            fontWeight = if (row.minor) null else FontWeight.Bold,
            color = if (row.minor) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        )
        val background = MaterialTheme.colorScheme.surface
        Canvas(Modifier.width(RAIL_WIDTH).fillMaxHeight()) {
            val x = size.width / 2
            val width = 4.dp.toPx()

            fun segment(
                part: Segment,
                from: Float,
                to: Float,
            ) {
                drawLine(
                    color = part.color,
                    start = Offset(x, from),
                    end = Offset(x, to),
                    strokeWidth = if (part.dotted) width * 0.75f else width,
                    cap = if (part.dotted) StrokeCap.Round else StrokeCap.Butt,
                    pathEffect = if (part.dotted) PathEffect.dashPathEffect(floatArrayOf(0.1f, width * 1.6f)) else null,
                )
            }
            row.above?.let { segment(it, 0f, dotY) }
            row.below?.let { segment(it, dotY, size.height) }
            row.dot?.let { color ->
                if (row.minor) {
                    drawCircle(background, radius = 4.dp.toPx(), center = Offset(x, dotY))
                    drawCircle(color, radius = 4.dp.toPx(), center = Offset(x, dotY), style = Stroke(2.dp.toPx()))
                } else {
                    drawCircle(color, radius = 7.dp.toPx(), center = Offset(x, dotY))
                    drawCircle(background, radius = 3.5.dp.toPx(), center = Offset(x, dotY))
                }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = Spacing.small, bottom = if (row.minor) Spacing.small else Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.tiny),
            content = row.content,
        )
    }
}

@Composable
private fun ColumnScope.Boarding(
    ride: Leg.Ride,
    prediction: Prediction?,
) {
    StopName(ride.from, ride.boardOnRequest)
    Row(
        modifier = Modifier.padding(vertical = Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        LineBadge(ride.line)
        Text(ride.headsign, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    when (prediction) {
        null -> {}

        is Prediction.Delay -> {
            LiveRow {
                LiveIcon()
                Text(
                    stringResource(R.string.live_departs_at, departureClock(ride.departure + prediction.seconds)) +
                        " · " +
                        delayText(prediction.seconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = liveColor(),
                )
            }
        }

        else -> {
            Text(
                stringResource(R.string.live_cancelled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** How many stops the ride takes; tapping it shows or hides the stops passed on the way. */
@Composable
private fun PassedStops(
    ride: Leg.Ride,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val stops = ride.calls.size - 1
    val between = ride.calls.subList(1, ride.calls.lastIndex)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(if (open) R.string.journey_hide_stops else R.string.journey_show_stops)
    Row(
        modifier =
            Modifier
                .clip(MaterialTheme.shapes.small)
                .then(if (between.isEmpty()) Modifier else Modifier.clickable(onClickLabel = label, onClick = onToggle))
                .padding(vertical = Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        Text(
            pluralStringResource(R.plurals.journey_stops, stops, stops) + " · " + duration(ride.departure, ride.arrival),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        if (between.isNotEmpty()) {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(IconSize.small).rotate(if (open) -90f else 90f),
                tint = muted,
            )
        }
    }
}

@Composable
private fun ColumnScope.StopName(
    stop: Stop,
    onRequest: Boolean,
) {
    Text(stopLabel(stop), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    if (onRequest) {
        Text(
            stringResource(R.string.on_request),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Waiting at a stop for the next vehicle. */
@Composable
private fun WaitText(
    ride: Leg.Ride,
    minutes: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Icon(
            painterResource(R.drawable.ic_hourglass_empty),
            contentDescription = null,
            modifier = Modifier.size(IconSize.small),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.journey_wait, ride.line.name, minutes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WalkText(walk: Leg.Walk) {
    val from = walk.from
    val to = walk.to
    val text =
        when {
            from == null && to == null -> stringResource(R.string.walk_all_the_way)
            to == null -> stringResource(R.string.walk_to_destination)
            from == null -> stringResource(R.string.walk_to_stop, stopLabel(to))
            from.name == to.name && to.platform.isNotEmpty() -> stringResource(R.string.walk_change_post, to.platform)
            else -> stringResource(R.string.walk_change_stop, stopLabel(to))
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Icon(
            painterResource(R.drawable.ic_directions_walk),
            contentDescription = null,
            modifier = Modifier.size(IconSize.small),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text + " · " + stringResource(R.string.duration_minutes, walkMinutes(walk)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun stopLabel(stop: Stop): String =
    if (stop.platform.isEmpty()) stop.name else stringResource(R.string.stop_with_post, stop.name, stop.platform)

private val TIME_WIDTH = 52.dp

private val RAIL_WIDTH = 20.dp
