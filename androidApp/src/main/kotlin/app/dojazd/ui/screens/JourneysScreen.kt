package app.dojazd.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dojazd.R
import app.dojazd.data.CityDataRepository
import app.dojazd.data.Live
import app.dojazd.data.LiveRepository
import app.dojazd.data.OfflineMap
import app.dojazd.data.PlannerRepository
import app.dojazd.data.TimetableRepository
import app.dojazd.gtfs.GtfsTime
import app.dojazd.gtfs.LocalMoment
import app.dojazd.gtfs.serviceDayStart
import app.dojazd.model.GeoPoint
import app.dojazd.provider.CityConfig
import app.dojazd.realtime.LiveTrips
import app.dojazd.realtime.Prediction
import app.dojazd.realtime.VehiclePosition
import app.dojazd.routing.Endpoint
import app.dojazd.routing.Journey
import app.dojazd.routing.Leg
import app.dojazd.routing.WalkNetwork
import app.dojazd.ui.FullScreenDialog
import app.dojazd.ui.LoadingBox
import app.dojazd.ui.MessageBox
import app.dojazd.ui.PlanPoint
import app.dojazd.ui.RouteText
import app.dojazd.ui.Screen
import app.dojazd.ui.WrappingTopBar
import app.dojazd.ui.expiredTimetableNote
import app.dojazd.ui.map.JourneyMap
import app.dojazd.ui.map.JourneyVehicle
import app.dojazd.ui.theme.IconSize
import app.dojazd.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlin.math.roundToInt

/**
 * What has been found so far; [searchedUntil] is where a search for later journeys continues.
 * Walking all the way, when offered, is kept apart in [walk] and listed once. [start] is
 * the time searched from.
 */
private class Results(
    val rides: List<Journey>,
    val walk: Journey?,
    val searchedUntil: Int?,
    val start: Int,
) {
    val journeys: List<Journey> =
        run {
            val firstVehicle = rides.mapNotNull { journey -> journey.legs.firstOrNull { it is Leg.Ride }?.departure }.minOrNull()
            when {
                walk == null -> rides

                // With nothing else to offer, even a long walk is an answer.
                rides.isEmpty() -> listOf(walk)

                walk.arrival - walk.departure >= WALK_MAX_SECONDS -> rides

                // Something leaves soon: that comes first, walking right after it.
                firstVehicle != null && firstVehicle - start <= SOON_SECONDS -> listOf(rides.first(), walk) + rides.drop(1)

                else -> rides + walk
            }
        }
}

/** Walking all the way is offered only when it is shorter than this... */
private const val WALK_MAX_SECONDS = 15 * 60

/** ...and listed second when a vehicle comes within this, otherwise last. */
private const val SOON_SECONDS = 10 * 60

private const val CLOCK_REFRESH_MILLIS = 20_000L

/** Journeys between two points, leaving at or after a given time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneysScreen(
    request: Screen.Journeys,
    city: CityConfig,
    timetables: TimetableRepository,
    planner: PlannerRepository,
    cityData: CityDataRepository,
    live: LiveRepository,
    onBack: () -> Unit,
) {
    // "Now" is fixed when the screen first opens, so the list does not shift under the user.
    val start =
        rememberSaveable {
            if (request.epochDay != null && request.seconds != null) {
                longArrayOf(request.epochDay, request.seconds.toLong())
            } else {
                val now = LocalMoment.now(city.timezone)
                longArrayOf(now.date.toEpochDays(), now.secondsOfDay.toLong())
            }
        }
    val date = remember { LocalDate.fromEpochDays(start[0].toInt()) }
    val startSeconds = start[1].toInt()
    val from = remember { request.from.toEndpoint() }
    val to = remember { request.to.toEndpoint() }

    val search = remember { JourneySearch(planner, cityData, city, date, from, to, startSeconds) }
    val loadingEarlier = search.loadingEarlier
    // How far the list has been pulled down past its top, in pixels, while the finger is on it...
    var pulled by remember { mutableFloatStateOf(0f) }
    var pulling by remember { mutableStateOf(false) }

    // ...and where it rests once let go: above the loading circle while earlier journeys load, then back at the top.
    val resting = remember { Animatable(0f) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val map by produceState<OfflineMap?>(initialValue = null, city.id) { value = cityData.map(city) }
    val walks by produceState<WalkNetwork?>(initialValue = null, city.id) { value = cityData.walks(city) }
    var onMap by remember { mutableStateOf<Journey?>(null) }
    val scope = rememberCoroutineScope()

    val trips by remember(city) { live.trips(city) }.collectAsStateWithLifecycle(Live.Loading)
    val liveTrips = (trips as? Live.Ready)?.takeIf { it.isFresh() }?.value
    onMap?.let { shown ->
        ShownOnMap(
            journey = shown,
            request = request,
            from = from,
            to = to,
            city = city,
            map = map,
            walks = walks,
            liveTrips = liveTrips,
            timetables = timetables,
            live = live,
            onDismiss = { onMap = null },
        )
    }

    LaunchedEffect(search) { search.start() }

    val expired = expiredTimetableNote(timetables, city)

    // Live delays of the vehicles to get on, for journeys leaving today.
    val midnight = remember(date) { serviceDayStart(date, city.timezone) }

    fun predict(ride: Leg.Ride): Prediction? = ride.trip?.let { liveTrips?.prediction(it, midnight + ride.departure) }

    // Seconds since midnight of the searched date, for "in 5 min"; refreshed while the screen is open.
    val nowSeconds by produceState(initialValue = secondsSince(date, city.timezone)) {
        while (true) {
            delay(CLOCK_REFRESH_MILLIS)
            value = secondsSince(date, city.timezone)
        }
    }

    Scaffold(
        topBar = {
            JourneysHeader(
                from = request.from.name,
                to = request.to.name,
                departure =
                    stringResource(
                        R.string.journeys_departure,
                        dayLabel(date, LocalMoment.now(city.timezone).date.daysUntil(date)),
                        GtfsTime.format(startSeconds),
                    ),
                onBack = onBack,
            )
        },
    ) { padding ->
        val current = search.results
        if (current == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }

        fun loadLater() {
            scope.launch { search.loadLater() }
        }

        fun loadEarlier() {
            scope.launch {
                val added = search.loadEarlier() ?: return@launch
                // Then the list settles back and glides to the journey just before those on screen.
                launch { resting.animateTo(0f) }
                if (added > 0) listState.animateScrollToItem(added - 1)
            }
        }

        // Reaching the end of the list, where the loading circle shows, loads later journeys.
        LaunchedEffect(listState, current.searchedUntil) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == LOADING_LATER } }
                .distinctUntilChanged()
                .filter { it }
                .collect { loadLater() }
        }

        // Pulling down at the top of the list and letting go loads earlier journeys. While pulling, a circle
        // fills up; letting go once it is full starts the loading, letting go before that does nothing.
        val onRelease by rememberUpdatedState(::loadEarlier)
        val threshold = with(LocalDensity.current) { PULL_THRESHOLD.toPx() }
        val pastTop =
            remember(threshold) {
                object : NestedScrollConnection {
                    override fun onPreScroll(
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        // Pushing back up first takes back the pull, before the list scrolls.
                        if (source != NestedScrollSource.UserInput || available.y >= 0f || !pulling || pulled <= 0f) return Offset.Zero
                        val taken = maxOf(available.y, -pulled)
                        pulled += taken
                        return Offset(0f, taken)
                    }

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (source != NestedScrollSource.UserInput || available.y <= 0f || loadingEarlier) return Offset.Zero
                        pulling = true
                        // Half the finger's way, so that the pull feels held back.
                        pulled = (pulled + available.y / 2).coerceAtMost(threshold * 1.5f)
                        return Offset(0f, available.y)
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (!pulling) return Velocity.Zero
                        val wasPulled = pulled
                        // The list carries on from where the finger left it, without a jump.
                        resting.snapTo(wasPulled)
                        pulling = false
                        pulled = 0f
                        if (wasPulled >= threshold) {
                            onRelease()
                            scope.launch { resting.animateTo(threshold) }
                        } else {
                            scope.launch { resting.animateTo(0f) }
                        }
                        return available
                    }
                }
            }

        // Where the top of the list is: following the finger while pulling, resting while loading.
        val listTop = { if (pulling) pulled else resting.value }
        val indicatorSpace = with(LocalDensity.current) { (INDICATOR_SIZE + Spacing.small).toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // The circle comes out from behind the top edge rather than over the bar above.
                .clipToBounds(),
        ) {
            // In the gap the pulled list leaves, just above the first journey and never on it:
            // filling up while pulling, turning while loading.
            val indicator =
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, (listTop() - indicatorSpace).roundToInt()) }
                    .size(INDICATOR_SIZE)
            when {
                loadingEarlier -> CircularProgressIndicator(indicator)
                pulling && pulled > 0f -> CircularProgressIndicator(progress = { (pulled / threshold).coerceIn(0f, 1f) }, indicator)
            }
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(pastTop)
                        .offset { IntOffset(0, listTop().roundToInt()) },
                contentPadding = PaddingValues(horizontal = Spacing.large, vertical = Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                if (current.journeys.isEmpty()) {
                    item { MessageBox(expired ?: stringResource(R.string.journeys_none), Modifier.fillParentMaxHeight(0.5f)) }
                }
                items(current.journeys, key = ::journeyKey) { journey ->
                    val key = journeyKey(journey)
                    // Gone when the first vehicle has left, by live data where there is any.
                    val departed =
                        (journey.legs.firstOrNull { it is Leg.Ride } as Leg.Ride?)?.let { ride ->
                            val delay = (predict(ride) as? Prediction.Delay)?.seconds ?: 0
                            ride.departure + delay < nowSeconds
                        } ?: false
                    JourneyCard(
                        journey = journey,
                        departed = departed,
                        nowSeconds = nowSeconds,
                        predict = ::predict,
                        expanded = key == expanded,
                        onClick = { expanded = if (key == expanded) null else key },
                        onShowMap = if (map != null) ({ onMap = journey }) else null,
                    )
                }
                // When walking is the only answer, later vehicles would all be slower than it: there is nothing to load.
                if (current.searchedUntil != null && (current.rides.isNotEmpty() || current.walk == null)) {
                    item(key = LOADING_LATER) { LoadingRow() }
                }
            }
        }
    }
}

/**
 * One search for journeys and what it has found so far. It starts at the time
 * asked for; journeys leaving later or earlier are added when the user reaches
 * for them, and those already listed stay where they are.
 */
private class JourneySearch(
    private val planner: PlannerRepository,
    private val cityData: CityDataRepository,
    private val city: CityConfig,
    private val date: LocalDate,
    private val from: Endpoint,
    private val to: Endpoint,
    private val startSeconds: Int,
) {
    var results by mutableStateOf<Results?>(null)
        private set
    var loadingLater by mutableStateOf(false)
        private set
    var loadingEarlier by mutableStateOf(false)
        private set

    /** The earliest time searched so far; asking for earlier journeys searches the hour before it. */
    private var searchedFrom = startSeconds

    suspend fun start() {
        val plan = planner.plan(date, from, to, startSeconds, cityData.walks(city))
        val journeys = plan?.journeys.orEmpty()
        results = Results(journeys.filter { it.rides > 0 }, journeys.firstOrNull { it.rides == 0 }, plan?.searchedUntil, startSeconds)
    }

    /** Adds the journeys leaving after the last time searched. */
    suspend fun loadLater() {
        val current = results ?: return
        val until = current.searchedUntil ?: return
        if (loadingLater) return
        loadingLater = true
        try {
            val started = System.currentTimeMillis()
            val plan = planner.plan(date, from, to, until + 1, cityData.walks(city))
            waitAtLeast(started)
            // Walking all the way is listed once.
            val later = plan?.journeys.orEmpty().filter { it.rides > 0 }
            val latest = results ?: current
            results = Results(distinctJourneys(latest.rides + later), latest.walk, plan?.searchedUntil, latest.start)
        } finally {
            loadingLater = false
        }
    }

    /**
     * Adds the journeys of the hour before the earliest time searched, above
     * those listed. Returns how many it added, or null when there was nothing to do.
     */
    suspend fun loadEarlier(): Int? {
        val current = results ?: return null
        if (loadingEarlier || searchedFrom <= 0) return null
        loadingEarlier = true
        try {
            val started = System.currentTimeMillis()
            val firstRide = current.rides.firstOrNull()?.departure ?: searchedFrom
            val earlierFrom = (searchedFrom - EARLIER_WINDOW_SECONDS).coerceAtLeast(0)
            val plan = planner.plan(date, from, to, earlierFrom, cityData.walks(city))
            waitAtLeast(started)
            val earlier = plan?.journeys.orEmpty().filter { it.rides > 0 && it.departure < firstRide }
            val latest = results ?: current
            // The journeys on screen are kept in place by their keys as the new ones go in above them.
            results = Results(distinctJourneys(earlier + latest.rides), latest.walk, latest.searchedUntil, latest.start)
            searchedFrom = earlierFrom
            return earlier.size
        } finally {
            loadingEarlier = false
        }
    }
}

/** The map of the journey the user asked to see, with the vehicles of its rides where they are now. */
@Composable
private fun ShownOnMap(
    journey: Journey,
    request: Screen.Journeys,
    from: Endpoint,
    to: Endpoint,
    city: CityConfig,
    map: OfflineMap?,
    walks: WalkNetwork?,
    liveTrips: LiveTrips?,
    timetables: TimetableRepository,
    live: LiveRepository,
    onDismiss: () -> Unit,
) {
    // The paths of the rides are looked up before the map opens, so that it is drawn once, complete.
    val shapes by produceState<Map<Long, List<GeoPoint>>?>(initialValue = null, journey) {
        value = null
        val ids =
            journey.legs
                .filterIsInstance<Leg.Ride>()
                .mapNotNull { it.shapeId }
                .distinct()
        value = timetables.query { timetable -> ids.associateWith(timetable::shape) }.orEmpty()
    }
    // Where the vehicles of the journey's rides are now; fetched only while the map is open.
    val vehicleFeed by remember(city) { live.vehicles(city) }.collectAsStateWithLifecycle(Live.Loading)
    val vehicles = remember(vehicleFeed, journey, liveTrips) { journeyVehicles(journey, vehicleFeed, liveTrips) }
    val shownShapes = shapes
    if (map == null || shownShapes == null) return
    JourneyMapDialog(
        from = request.from.name,
        to = request.to.name,
        map = map,
        journey = journey,
        // A journey that begins or ends on board starts at the very stop, not in the middle of all its stands.
        origin = (journey.legs.first() as? Leg.Ride)?.from?.location ?: from.location,
        destination = (journey.legs.last() as? Leg.Ride)?.to?.location ?: to.location,
        walks = walks,
        shapes = shownShapes,
        vehicles = vehicles,
        timezone = city.timezone,
        onDismiss = onDismiss,
    )
}

/**
 * The bar over the journeys: where from and where to, each on a line of its own
 * and never cut short, however long the names, and when the search starts.
 */
@Composable
private fun JourneysHeader(
    from: String,
    to: String,
    departure: String,
    onBack: () -> Unit,
) {
    WrappingTopBar(onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
            HeaderEnd(R.drawable.ic_trip_origin, from)
            HeaderEnd(R.drawable.ic_location_on, to)
            Text(
                departure,
                modifier = Modifier.padding(start = HEADER_ICON + Spacing.small),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeaderEnd(
    icon: Int,
    name: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.padding(top = Spacing.tiny).size(HEADER_ICON),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(name, style = MaterialTheme.typography.titleMedium)
    }
}

private val HEADER_ICON = IconSize.small

/** Lets a loading circle show for a moment at least, even when the search is quick, so that it reads as loading. */
internal suspend fun waitAtLeast(started: Long) {
    val remaining = MIN_LOADING_MILLIS - (System.currentTimeMillis() - started)
    if (remaining > 0) delay(remaining)
}

private const val MIN_LOADING_MILLIS = 700L

/** How far the list has to be pulled down before letting go loads earlier journeys. */
private val PULL_THRESHOLD = 72.dp

private val INDICATOR_SIZE = 36.dp

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.padding(Spacing.small))
    }
}

private const val LOADING_LATER = "loading-later"

/** What tells journeys apart in the list: when and with which vehicles they go. */
private fun journeyKey(journey: Journey): String =
    journey.legs.joinToString("|") { leg ->
        when (leg) {
            is Leg.Ride -> "${leg.line.id}:${leg.departure}:${leg.from.id}"
            is Leg.Walk -> "w:${leg.departure}:${leg.arrival}"
        }
    }

/** [journeys] with the same journey listed only once, as searches over neighbouring times can both find it. */
internal fun distinctJourneys(journeys: List<Journey>): List<Journey> = journeys.distinctBy(::journeyKey)

/** How far back each pull for earlier journeys looks. */
private const val EARLIER_WINDOW_SECONDS = 3_600

/** The journey drawn on the city's map, over the whole screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JourneyMapDialog(
    from: String,
    to: String,
    map: OfflineMap,
    journey: Journey,
    origin: GeoPoint,
    destination: GeoPoint,
    walks: WalkNetwork?,
    shapes: Map<Long, List<GeoPoint>>,
    vehicles: List<JourneyVehicle>,
    timezone: String,
    onDismiss: () -> Unit,
) {
    FullScreenDialog(title = { RouteText(from, to) }, onDismiss = onDismiss) {
        JourneyMap(map, journey, origin, destination, from, to, walks, shapes, vehicles, timezone, Modifier.fillMaxSize())
    }
}

private fun PlanPoint.toEndpoint() = Endpoint(GeoPoint(lat, lon), stopIds)

/** The vehicles running the rides of [journey], by the latest [feed]. */
private fun journeyVehicles(
    journey: Journey?,
    feed: Live<List<VehiclePosition>>,
    trips: LiveTrips?,
): List<JourneyVehicle> {
    val positions = (feed as? Live.Ready)?.takeIf { it.isFresh() }?.value ?: return emptyList()
    val now = System.currentTimeMillis() / 1000
    return journey?.legs.orEmpty().filterIsInstance<Leg.Ride>().mapNotNull { ride ->
        val trip = ride.trip ?: return@mapNotNull null
        val vehicle =
            positions.firstOrNull { vehicle ->
                val running = vehicle.trip ?: return@firstOrNull false
                running.tripId == trip.gtfsId &&
                    (running.startDate == null || running.startDate?.toIntOrNull() == trip.serviceDate) &&
                    // A vehicle that has not reported for a while is no guide to where it is.
                    vehicle.timestamp.let { it == null || now - it <= VEHICLE_MAX_AGE_SECONDS }
            } ?: return@mapNotNull null
        JourneyVehicle(
            id = vehicle.id,
            line = ride.line,
            headsign = ride.headsign,
            position = vehicle.position,
            label = vehicle.label,
            measuredAt = vehicle.timestamp,
            delay = trips?.delay(trip.gtfsId, trip.serviceDate, vehicle.currentStopSequence),
        )
    }
}

private const val VEHICLE_MAX_AGE_SECONDS = 5 * 60

/** Seconds from midnight of [date] to now; negative before that day, above a day's worth after it. */
private fun secondsSince(
    date: LocalDate,
    timezone: String,
): Int {
    val now = LocalMoment.now(timezone)
    return date.daysUntil(now.date) * DAY_SECONDS + now.secondsOfDay
}
