package app.dojazd.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dojazd.R
import app.dojazd.data.Live
import app.dojazd.data.LiveRepository
import app.dojazd.data.TimetableRepository
import app.dojazd.gtfs.GtfsTime
import app.dojazd.gtfs.LocalMoment
import app.dojazd.gtfs.serviceDayStart
import app.dojazd.provider.CityConfig
import app.dojazd.realtime.LiveBoard
import app.dojazd.realtime.LiveDeparture
import app.dojazd.realtime.LiveTrips
import app.dojazd.realtime.Prediction
import app.dojazd.ui.AlertCard
import app.dojazd.ui.BackButton
import app.dojazd.ui.LineBadge
import app.dojazd.ui.LiveIcon
import app.dojazd.ui.LiveRow
import app.dojazd.ui.LoadingBox
import app.dojazd.ui.MessageBox
import app.dojazd.ui.expiredTimetableNote
import app.dojazd.ui.liveColor
import app.dojazd.ui.rememberServiceAlerts
import app.dojazd.ui.rememberTimetableVersion
import app.dojazd.ui.theme.Spacing
import kotlinx.coroutines.delay

private class Board(
    val now: LocalMoment,
    val departures: List<LiveDeparture>,
    val lineIds: Set<Long>,
)

private const val REFRESH_MILLIS = 20_000L
private const val SHOWN = 60

/** Late vehicles are looked for among departures timetabled up to this long ago... */
private const val LATE_WINDOW_SECONDS = 30 * 60

/** ...of which a busy stop can have many. */
private const val QUERY_LIMIT = 300

/** Upcoming departures from one stop, or from all boarding points that share its name, with live times where known. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopDeparturesScreen(
    title: String,
    stopIds: List<Long>,
    city: CityConfig,
    repository: TimetableRepository,
    live: LiveRepository,
    onBack: () -> Unit,
) {
    val version =
        rememberTimetableVersion(repository)
    val trips by remember(city) { live.trips(city) }.collectAsStateWithLifecycle(Live.Loading)
    val liveTrips: LiveTrips? = (trips as? Live.Ready)?.takeIf { it.isFresh() }?.value

    val board by produceState<Board?>(initialValue = null, version, stopIds, liveTrips) {
        while (true) {
            val now = LocalMoment.now(city.timezone)
            val midnight = serviceDayStart(now.date, city.timezone)
            // Without live data a departure that is due is gone; with it, a late one may still come.
            val from = if (liveTrips != null) now.secondsOfDay - LATE_WINDOW_SECONDS else now.secondsOfDay
            value =
                repository.query { it.upcomingDepartures(stopIds, now.date, from, limit = QUERY_LIMIT) }?.let { departures ->
                    Board(
                        now = now,
                        departures = LiveBoard.merge(departures, liveTrips, midnight, now.secondsOfDay, SHOWN),
                        lineIds = departures.mapTo(HashSet()) { it.line.id },
                    )
                }
            delay(REFRESH_MILLIS)
        }
    }

    val expired = expiredTimetableNote(repository, city)
    val alerts by rememberServiceAlerts(city, live, repository)
    val current = board
    val stopAlerts =
        remember(alerts, current?.lineIds, stopIds) {
            (alerts as? Live.Ready)?.value.orEmpty().filter { it.affects(stopIds, current?.lineIds.orEmpty()) }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LiveStatus(trips, isLive = liveTrips != null)
                    }
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        when {
            current == null -> {
                LoadingBox(Modifier.padding(padding))
            }

            current.departures.isEmpty() && stopAlerts.isEmpty() -> {
                MessageBox(expired ?: stringResource(R.string.no_departures), Modifier.padding(padding))
            }

            else -> {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                ) {
                    items(stopAlerts, key = { "alert-${it.id}" }) { alert ->
                        AlertCard(alert, Modifier.padding(horizontal = Spacing.large, vertical = Spacing.extraSmall))
                    }
                    if (current.departures.isEmpty()) {
                        item { MessageBox(expired ?: stringResource(R.string.no_departures)) }
                    }
                    // Told apart by run and call, so that a refresh keeps each row, and the place scrolled to, where it was.
                    items(current.departures, key = { it.key() }) { departure ->
                        DepartureRow(departure, current.now, showPlatform = stopIds.size > 1)
                    }
                }
            }
        }
    }
}

/** What a departure is on the board; one without a known run is told by what it shows. */
private fun LiveDeparture.key(): String =
    departure.trip?.let { "${it.gtfsId}/${it.serviceDate}/${it.stopSequence}" }
        ?: "${departure.line.id}/${departure.stop.id}/${departure.time}/${departure.headsign}"

/** Under the title: whether the times are live or only timetabled. */
@Composable
private fun LiveStatus(
    trips: Live<*>,
    isLive: Boolean,
) {
    val style = MaterialTheme.typography.labelMedium
    when {
        isLive -> {
            LiveRow {
                LiveIcon()
                Text(stringResource(R.string.live_note), style = style, color = liveColor())
            }
        }

        // Still connecting, or the user does not want live data: the timetable is all there is, which is no fault.
        trips is Live.Loading || trips is Live.Off -> {
            Text(stringResource(R.string.scheduled_note), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        else -> {
            Text(stringResource(R.string.live_unavailable), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DepartureRow(
    item: LiveDeparture,
    now: LocalMoment,
    showPlatform: Boolean,
) {
    val departure = item.departure
    val prediction = item.prediction
    val platform = departure.stop.platform.takeIf { showPlatform && it.isNotEmpty() }
    val delay = (prediction as? Prediction.Delay)?.seconds
    val faded = MaterialTheme.colorScheme.onSurfaceVariant

    ListItem(
        leadingContent = { LineBadge(departure.line) },
        supportingContent = {
            val parts =
                buildList {
                    platform?.let { add(stringResource(R.string.stop_post, it)) }
                }
            Column {
                if (prediction != null) {
                    LiveRow {
                        if (delay != null) LiveIcon()
                        Text(
                            liveText(prediction, departure.time, item.time),
                            color = if (item.runs) liveColor() else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    GtfsTime.format(item.time),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color =
                        when {
                            !item.runs -> faded
                            delay != null -> liveColor()
                            else -> Color.Unspecified
                        },
                    textDecoration = if (item.runs) null else TextDecoration.LineThrough,
                )
                val minutes = (item.time - now.secondsOfDay) / 60
                val hint =
                    when {
                        !item.runs -> null
                        minutes <= 0 -> stringResource(R.string.departs_now)
                        minutes < 60 -> stringResource(R.string.departs_in_minutes, minutes)
                        item.time >= DAY_SECONDS -> stringResource(R.string.departs_tomorrow)
                        else -> null
                    }
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
    ) {
        Text(departure.headsign)
    }
}

/** What live data says about a departure timetabled at [scheduled], in words. */
@Composable
private fun liveText(
    prediction: Prediction,
    scheduled: Int,
    expected: Int,
): String =
    when (prediction) {
        Prediction.Cancelled -> {
            stringResource(R.string.live_cancelled)
        }

        Prediction.Skipped -> {
            stringResource(R.string.live_skipped)
        }

        is Prediction.Delay -> {
            // The difference between the two times as shown, so the words never contradict them.
            val minutes = Math.floorDiv(expected, 60) - Math.floorDiv(scheduled, 60)
            val state =
                when {
                    minutes >= 1 -> stringResource(R.string.live_late, minutes)
                    minutes <= -1 -> stringResource(R.string.live_early, -minutes)
                    else -> stringResource(R.string.live_on_time)
                }
            if (minutes == 0) state else state + " · " + stringResource(R.string.live_scheduled_at, GtfsTime.format(scheduled))
        }
    }
