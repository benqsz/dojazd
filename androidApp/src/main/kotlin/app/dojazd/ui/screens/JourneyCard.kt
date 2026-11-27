package app.dojazd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dojazd.R
import app.dojazd.realtime.Prediction
import app.dojazd.routing.Journey
import app.dojazd.routing.Leg
import app.dojazd.ui.LineBadge
import app.dojazd.ui.LiveIcon
import app.dojazd.ui.liveColor
import app.dojazd.ui.theme.ControlSize
import app.dojazd.ui.theme.IconSize
import app.dojazd.ui.theme.Spacing
import app.dojazd.ui.vehicleIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter

@Composable
internal fun JourneyCard(
    journey: Journey,
    destination: String,
    /** True when its first vehicle has already left: it is shown faded, for reference only. */
    departed: Boolean,
    /** Seconds since midnight of the searched date. */
    nowSeconds: Int,
    /** What live data says about getting on a ride; null when it says nothing. */
    predict: (Leg.Ride) -> Prediction?,
    expanded: Boolean,
    onClick: () -> Unit,
    /** Null when the city has no map to show the journey on. */
    onShowMap: (() -> Unit)?,
) {
    val firstRide = journey.legs.firstOrNull { it is Leg.Ride } as Leg.Ride?
    val lastRide = journey.legs.lastOrNull { it is Leg.Ride } as Leg.Ride?
    val firstPrediction = firstRide?.let(predict)
    val firstDelay = (firstPrediction as? Prediction.Delay)?.seconds
    // A delay carries on along the trip, so the last vehicle's delay where it is boarded is the best guess at the arrival.
    val lastDelay = (lastRide?.let(predict) as? Prediction.Delay)?.seconds
    val cancelled = firstPrediction is Prediction.Cancelled || firstPrediction is Prediction.Skipped
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().alpha(if (departed) DEPARTED_ALPHA else 1f),
    ) {
        Column(Modifier.padding(Spacing.large), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
            // When to go out matters only for a journey near enough, one a day or more away is planned by its date,
            // and only while the walk can still be made in time.
            val setOff = journey.departure + (firstDelay ?: 0)
            if (firstRide != null &&
                journey.legs.first() is Leg.Walk &&
                !departed &&
                !cancelled &&
                setOff - nowSeconds in -TOO_LATE_SECONDS until DAY_SECONDS
            ) {
                SetOff(leaves = setOff, nowSeconds = nowSeconds)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                LeavesIn(
                    journey = journey,
                    firstRide = firstRide,
                    delay = firstDelay,
                    departed = departed,
                    cancelled = cancelled,
                    nowSeconds = nowSeconds,
                    modifier = Modifier.widthIn(min = LEAVES_WIDTH),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { LegChain(journey) }
                        Text(
                            duration(journey.departure, journey.arrival),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    JourneyTimes(journey, firstRide, lastRide, firstDelay, lastDelay)
                }
            }

            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = Spacing.extraSmall))
                Timeline(journey, destination, predict)
                if (onShowMap != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        FilledTonalButton(
                            onClick = onShowMap,
                            modifier = Modifier.heightIn(min = ControlSize.compactButton),
                            contentPadding = PaddingValues(horizontal = Spacing.large),
                        ) {
                            Icon(painterResource(R.drawable.ic_map), contentDescription = null, Modifier.size(IconSize.small))
                            Spacer(Modifier.width(Spacing.small))
                            Text(stringResource(R.string.show_on_map), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

/** The legs in a row: each line with the kind of vehicle it is, and the walks worth mentioning. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegChain(journey: Journey) {
    // The walks of a journey by vehicle are in its timeline; the summary shows walking only for a journey made on foot alone.
    val shown = journey.legs.filter { it is Leg.Ride || journey.legs.size == 1 }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEach { leg ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                when (leg) {
                    is Leg.Ride -> {
                        Icon(
                            painterResource(vehicleIcon(leg.line.type)),
                            contentDescription = null,
                            Modifier.size(CHAIN_ICON),
                            tint = muted,
                        )
                        LineBadge(leg.line, compact = true)
                    }

                    is Leg.Walk -> {
                        Icon(
                            painterResource(R.drawable.ic_directions_walk),
                            contentDescription = stringResource(R.string.journey_on_foot),
                            modifier = Modifier.size(CHAIN_ICON),
                            tint = muted,
                        )
                        Text(
                            stringResource(R.string.duration_minutes, walkMinutes(leg)),
                            style = MaterialTheme.typography.labelLarge,
                            color = muted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The column at the left of a journey: what kind of time it is in small print
 * ("Leaves in", "Leaves", "Tomorrow"…) and, large under it, the minutes until
 * the first vehicle leaves, or its clock time when that is an hour or more away.
 */
@Composable
private fun LeavesIn(
    journey: Journey,
    firstRide: Leg.Ride?,
    delay: Int?,
    departed: Boolean,
    cancelled: Boolean,
    nowSeconds: Int,
    modifier: Modifier = Modifier,
) {
    val leaves = firstRide?.let { it.departure + (delay ?: 0) }
    val minutes = leaves?.let { (it - nowSeconds).floorDiv(60) }
    val label: String
    val value: String
    // Only a count of minutes is written large; a clock time, a word or hours and minutes would be too wide.
    var inMinutes = false
    when {
        firstRide == null || leaves == null || minutes == null -> {
            label = stringResource(R.string.journey_on_foot)
            value = duration(journey.departure, journey.arrival)
        }

        cancelled -> {
            label = stringResource(R.string.journey_cancelled_label)
            value = departureClock(firstRide.departure)
        }

        departed -> {
            label = stringResource(R.string.journey_departed_label)
            value = departureClock(leaves)
        }

        leaves >= DAY_SECONDS -> {
            label = stringResource(R.string.day_tomorrow)
            value = departureClock(leaves)
        }

        minutes <= 0 -> {
            label = stringResource(R.string.journey_leaves)
            value = stringResource(R.string.departs_now)
        }

        minutes < 60 -> {
            inMinutes = true
            label = stringResource(R.string.journey_leaves_in)
            value = stringResource(R.string.duration_minutes, minutes)
        }

        else -> {
            label = stringResource(R.string.journey_leaves)
            value = departureClock(leaves)
        }
    }
    val isLive = delay != null && !departed && !cancelled
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isLive) LiveIcon()
        }
        // The number is what is read at a glance; a unit after it is written small, so the column stays narrow.
        val unit = MaterialTheme.typography.titleMedium.fontSize
        val number = if (inMinutes) value.takeWhile(Char::isDigit) else ""
        Text(
            buildAnnotatedString {
                append(value)
                if (number.isNotEmpty()) addStyle(SpanStyle(fontSize = unit), number.length, value.length)
            },
            // Minutes are written large; a clock time or a word would be too wide at that size.
            style =
                MaterialTheme.typography.headlineMedium.let {
                    if (number.isEmpty()) it else it.copy(fontSize = it.fontSize * LEAVES_SCALE, lineHeight = it.lineHeight * LEAVES_SCALE)
                },
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            color = if (cancelled) MaterialTheme.colorScheme.error else Color.Unspecified,
            textDecoration = if (cancelled) TextDecoration.LineThrough else null,
        )
    }
}

/** When to set out for the first stop, written above a journey that starts with a walk. */
@Composable
private fun SetOff(
    leaves: Int,
    nowSeconds: Int,
) {
    val minutes = (leaves - nowSeconds).floorDiv(60)
    val value: String
    val text: String
    when {
        minutes <= 0 -> {
            value = stringResource(R.string.departs_now)
            text = stringResource(R.string.journey_set_off, value)
        }

        minutes < 60 -> {
            value = stringResource(R.string.duration_minutes, minutes)
            text = stringResource(R.string.journey_set_off_in, value)
        }

        else -> {
            value = departureClock(leaves)
            text = stringResource(R.string.journey_set_off_at, value)
        }
    }
    val start = text.indexOf(value)
    Text(
        buildAnnotatedString {
            append(text)
            if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + value.length)
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The journey in a row: the walk to the first stop, when the first vehicle
 * leaves, how long the ride takes, when the last vehicle arrives, and the walk
 * from there. With live data the expected times are shown, in the live colour.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JourneyTimes(
    journey: Journey,
    firstRide: Leg.Ride?,
    lastRide: Leg.Ride?,
    departureDelay: Int?,
    arrivalDelay: Int?,
) {
    val departure = firstRide?.departure ?: journey.departure
    val arrival = lastRide?.arrival ?: journey.arrival
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (firstRide != null) (journey.legs.first() as? Leg.Walk)?.let { WalkTime(it) }
        TimeBadge(departureClock(departure + (departureDelay ?: 0)), live = departureDelay != null)
        // On foot all the way the duration is said beside the walker above; here a dash joins the two times.
        Text(
            if (firstRide != null) duration(departure, arrival) else "–",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TimeBadge(arrivalClock(arrival + (arrivalDelay ?: 0)), live = arrivalDelay != null)
        if (lastRide != null) (journey.legs.last() as? Leg.Walk)?.let { WalkTime(it) }
    }
}

@Composable
private fun WalkTime(walk: Leg.Walk) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
        Icon(
            painterResource(R.drawable.ic_directions_walk),
            contentDescription = stringResource(R.string.journey_on_foot),
            modifier = Modifier.size(IconSize.tiny),
            tint = muted,
        )
        Text(stringResource(R.string.duration_minutes, walkMinutes(walk)), style = MaterialTheme.typography.labelMedium, color = muted)
    }
}

@Composable
private fun TimeBadge(
    time: String,
    live: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
        Text(
            time,
            modifier = Modifier.padding(horizontal = Spacing.extraSmall, vertical = Spacing.tiny),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (live) liveColor() else Color.Unspecified,
        )
    }
}

/** The size of the vehicle and walking icons in the summary of a journey, level with the compact line numbers. */
private val CHAIN_ICON = IconSize.medium

private const val LEAVES_SCALE = 1.5f

/** How long after the time to set off the card still says to go now, before it stops saying anything. */
private const val TOO_LATE_SECONDS = 60

/** The width of the column that says when a journey leaves. */
private val LEAVES_WIDTH = 80.dp

/** How faded a journey whose vehicle has left is drawn. */
private const val DEPARTED_ALPHA = 0.45f
