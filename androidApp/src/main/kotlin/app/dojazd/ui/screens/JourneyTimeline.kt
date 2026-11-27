package app.dojazd.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
import kotlinx.coroutines.launch

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
    /** The time live data expects instead of [time]; null when there is none. Shown under it when the two differ. */
    val liveTime: String? = null,
    /** Drawn on the line in place of a dot, such as the walker of a walk; null for none. */
    val icon: Int? = null,
    val content: @Composable ColumnScope.() -> Unit,
)

/** The journey step by step: where to walk, where to get on and off, with times. */
@Composable
internal fun Timeline(
    journey: Journey,
    destination: String,
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
                        dot = null,
                        icon = R.drawable.ic_directions_walk,
                    ) { WalkText(leg) }
            }

            is Leg.Ride -> {
                val color = lineColors(leg.line).background
                val ride = Segment(color, dotted = false)
                val prediction = predict(leg)
                // A delay carries on along the trip, so the one where the vehicle is boarded is the best guess at the arrival.
                val delay = (prediction as? Prediction.Delay)?.seconds
                val open = index in openRides
                // At a change, the time spent at the stop before the next vehicle comes.
                val waitMinutes = journey.legs.getOrNull(index - 1)?.let { (leg.departure - it.arrival) / 60 } ?: 0
                if (!first && waitMinutes >= 1) {
                    rows +=
                        TimelineRow(
                            time = null,
                            above = walkSegment,
                            below = walkSegment,
                            dot = null,
                            icon = R.drawable.ic_hourglass_empty,
                        ) { WaitText(waitMinutes) }
                }
                rows +=
                    TimelineRow(
                        time = departureClock(leg.departure),
                        above = if (first) null else walkSegment,
                        below = ride,
                        dot = color,
                        liveTime = delay?.let { departureClock(leg.departure + it) },
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
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                                ) {
                                    if (call.onRequest) OnRequestBadge()
                                    Text(call.stop.name, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                    }
                }
                rows +=
                    TimelineRow(
                        time = arrivalClock(leg.arrival),
                        above = ride,
                        below = if (last) null else walkSegment,
                        dot = color,
                        liveTime = delay?.let { arrivalClock(leg.arrival + it) },
                    ) {
                        // What is known is how the vehicle runs where it is boarded: a delay is a fair guess here too, anything else is not.
                        StopRow(leg.to, leg.alightOnRequest, prediction as? Prediction.Delay)
                    }
            }
        }
    }
    if (journey.legs.last() is Leg.Walk) {
        rows +=
            TimelineRow(time = arrivalClock(journey.arrival), above = walkSegment, below = null, dot = endColor) {
                Text(
                    destination,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
    }
    Column { rows.forEach { TimelineItem(it) } }
}

@Composable
private fun TimelineItem(row: TimelineRow) {
    // A walk or a wait stands apart from the stops around it, with room above and below.
    val top = if (row.icon != null) Spacing.small else 0.dp
    val dotOffset = top + if (row.minor) 8.dp else 11.dp
    val dotY = with(LocalDensity.current) { dotOffset.toPx() }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        val moved = row.liveTime != null && row.liveTime != row.time
        Column(Modifier.widthIn(min = TIME_WIDTH).padding(top = top + 1.dp)) {
            Text(
                row.time.orEmpty(),
                style = if (row.minor) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.minor) null else FontWeight.Bold,
                color = if (row.minor || moved) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                textDecoration = if (moved) TextDecoration.LineThrough else null,
            )
            if (moved) {
                Text(
                    row.liveTime,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = liveColor(),
                )
            }
        }
        val background = MaterialTheme.colorScheme.surface
        Box(Modifier.width(RAIL_WIDTH).fillMaxHeight()) {
            Canvas(Modifier.fillMaxSize()) {
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
            row.icon?.let { icon ->
                // Centred where a dot would be, on a patch of the background so that the line does not run through it.
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = dotOffset - RAIL_ICON / 2)
                            .size(RAIL_ICON)
                            .background(background),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(
                    start = Spacing.small,
                    top = top,
                    bottom =
                        when {
                            row.icon != null -> Spacing.extraLarge
                            row.minor -> Spacing.small
                            else -> Spacing.large
                        },
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
            content = row.content,
        )
    }
}

@Composable
private fun ColumnScope.Boarding(
    ride: Leg.Ride,
    prediction: Prediction?,
) {
    StopRow(ride.from, ride.boardOnRequest, prediction)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        LineBadge(ride.line, compact = true)
        Text(
            ride.headsign,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            duration(ride.departure, ride.arrival),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A stop where the passenger gets on or off, with what live data says about the vehicle under its name. */
@Composable
private fun StopRow(
    stop: Stop,
    onRequest: Boolean,
    prediction: Prediction?,
) {
    // The status stands under the name as the expected time stands under the scheduled one, the two level
    // across the line: its smaller text is centred in a line as tall as that of the time.
    val line =
        with(LocalDensity.current) {
            MaterialTheme.typography.bodyLarge.lineHeight
                .toDp()
        }
    Column(Modifier.padding(top = 1.dp)) {
        StopName(stop, onRequest)
        if (prediction != null) {
            Box(Modifier.height(line), contentAlignment = Alignment.Center) {
                if (prediction is Prediction.Delay) {
                    LiveRow {
                        LiveIcon()
                        Text(delayText(prediction.seconds), style = MaterialTheme.typography.bodySmall, color = liveColor())
                    }
                } else {
                    Text(
                        stringResource(R.string.live_cancelled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
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
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .then(if (between.isEmpty()) Modifier else Modifier.clickable(onClickLabel = label, onClick = onToggle))
                .padding(vertical = Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        Text(
            pluralStringResource(R.plurals.journey_stops, stops, stops),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
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
private fun StopName(
    stop: Stop,
    onRequest: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        if (onRequest) OnRequestBadge()
        Text(
            stop.name,
            modifier = Modifier.weight(1f).alignBy(LastBaseline),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        if (stop.platform.isNotEmpty()) {
            Text(
                stringResource(R.string.stop_post, stop.platform),
                modifier = Modifier.alignBy(LastBaseline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Marks a stop the vehicle calls at only when asked to; tapping it says so in full. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnRequestBadge() {
    val meaning = stringResource(R.string.on_request)
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(meaning) } },
        state = tooltip,
    ) {
        // Not a control of Material's, which would take a finger's room and push its row apart from the others.
        Surface(
            modifier =
                Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable { scope.launch { tooltip.show() } }
                    .semantics { contentDescription = meaning },
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(
                stringResource(R.string.on_request_short),
                modifier = Modifier.padding(horizontal = Spacing.extraSmall).clearAndSetSemantics {},
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** Waiting at a stop for the next vehicle, and for how long. The hourglass stands on the line beside it. */
@Composable
private fun WaitText(minutes: Int) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.journey_wait),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        Text(stringResource(R.string.duration_minutes, minutes), style = MaterialTheme.typography.bodyMedium, color = muted)
    }
}

/** A walk: about how far it is and, at the end of the row, how long it takes. The walker stands on the line beside it. */
@Composable
private fun WalkText(walk: Leg.Walk) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.walk_distance, walkMeters(walk)),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        Text(stringResource(R.string.duration_minutes, walkMinutes(walk)), style = MaterialTheme.typography.bodyMedium, color = muted)
    }
}

private val TIME_WIDTH = 52.dp

private val RAIL_WIDTH = 20.dp

private val RAIL_ICON = IconSize.small
