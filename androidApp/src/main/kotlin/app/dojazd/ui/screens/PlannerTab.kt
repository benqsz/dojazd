package app.dojazd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dojazd.R
import app.dojazd.data.CityDataRepository
import app.dojazd.data.PlacesRepository
import app.dojazd.data.PlannerRepository
import app.dojazd.data.RecentJourney
import app.dojazd.data.RecentJourneysRepository
import app.dojazd.data.RecentPlace
import app.dojazd.data.RecentPlacesRepository
import app.dojazd.data.TimetableRepository
import app.dojazd.gtfs.GtfsTime
import app.dojazd.gtfs.LocalMoment
import app.dojazd.model.GeoPoint
import app.dojazd.provider.CityConfig
import app.dojazd.storage.Place
import app.dojazd.ui.PlanPoint
import app.dojazd.ui.ROUTE_END_ICON
import app.dojazd.ui.RouteText
import app.dojazd.ui.Screen
import app.dojazd.ui.SectionHeader
import app.dojazd.ui.StopGroup
import app.dojazd.ui.iconRes
import app.dojazd.ui.rememberLocator
import app.dojazd.ui.rememberStopGroups
import app.dojazd.ui.rememberTimetableVersion
import app.dojazd.ui.theme.ControlSize
import app.dojazd.ui.theme.IconSize
import app.dojazd.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json

private enum class Picking { FROM, TO }

/** The journey planner's form: where from, where to and when. */
@Composable
fun PlannerTab(
    city: CityConfig,
    repository: TimetableRepository,
    planner: PlannerRepository,
    cityData: CityDataRepository,
    places: PlacesRepository,
    recentPlaces: RecentPlacesRepository,
    recentJourneys: RecentJourneysRepository,
    onOpen: (Screen) -> Unit,
) {
    // Stop ids are only valid for the timetable they were picked from, so a new one clears the choice.
    val version =
        rememberTimetableVersion(repository)
    var from by rememberSaveable(version, stateSaver = PlanPointSaver) { mutableStateOf(null) }
    var to by rememberSaveable(version, stateSaver = PlanPointSaver) { mutableStateOf(null) }

    // A negative day means "leave now".
    var day by rememberSaveable { mutableIntStateOf(NOW) }
    var seconds by rememberSaveable { mutableIntStateOf(0) }
    var picking by rememberSaveable { mutableStateOf<Picking?>(null) }
    var choosingTime by rememberSaveable { mutableStateOf(false) }

    val today = rememberToday(city.timezone)
    val date = today.plus(day.coerceAtLeast(0), DateTimeUnit.DAY)
    // Getting the timetable ready takes a moment; start while the user is still filling in the form.
    LaunchedEffect(version, date) { planner.prepare(date, cityData.walks(city)) }

    val recent by recentJourneys.of(city.id).collectAsState()
    val saved by places.places.collectAsState()
    val stops by rememberStopGroups(repository)
    val locator = rememberLocator(city)
    val myLocation = stringResource(R.string.my_location)
    var locatingFor by remember { mutableStateOf<RecentJourney?>(null) }

    /** Opens the journeys from [start] to [destination] at the chosen time, and remembers the search. */
    fun search(
        start: PlanPoint,
        destination: PlanPoint,
    ) {
        recentJourneys.add(city.id, RecentJourney(start.toRecent(), destination.toRecent()))
        onOpen(
            Screen.Journeys(
                from = start,
                to = destination,
                epochDay = if (day == NOW) null else date.toEpochDays(),
                seconds = if (day == NOW) null else seconds,
            ),
        )
    }

    /** Fills the form with a recent journey and searches it again; the user's location is found anew. */
    fun searchAgain(journey: RecentJourney) {
        fun go(here: GeoPoint?) {
            val start = journey.from.toPlanPoint(here, stops, myLocation)
            val destination = journey.to.toPlanPoint(here, stops, myLocation)
            from = start
            to = destination
            search(start, destination)
        }

        if (journey.from.kind == RecentPlace.Kind.LOCATION || journey.to.kind == RecentPlace.Kind.LOCATION) {
            locatingFor = journey
            locator.locate(::go)
        } else {
            go(null)
        }
    }

    // The search for an end of the journey is typed into its own field of the form, and what it finds
    // takes the place of the rest of the form under the two fields. Keyed, so that choosing the
    // destination right after the start begins with an empty search.
    val picker = key(picking) { rememberPlacePickerState() }
    // Its own, so that finding the position for the search and for a recent journey do not show as each other's.
    val searchLocator = rememberLocator(city)
    BackHandler(enabled = picking != null) {
        // While adding a favourite, going back returns to the search, not out of it.
        if (picker.addingFavourite) picker.addingFavourite = false else picking = null
    }
    val fromLabel = stringResource(R.string.planner_from)
    val toLabel = stringResource(R.string.planner_to)
    val addLabel = stringResource(R.string.place_add)

    /** The search typed into the field of [which] end, while that is the one being chosen. */
    fun searchOf(which: Picking): PointSearch? =
        if (picking != which) {
            null
        } else {
            PointSearch(
                text = picker.text,
                locating = searchLocator.locating,
                onLocate = { searchLocator.locate { picker.located = it } },
                onClose = { picking = null },
            )
        }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(Spacing.large), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            val placeholder = stringResource(if (city.addresses != null) R.string.search_places else R.string.search_stops)
            PointField(
                icon = R.drawable.ic_trip_origin,
                label = if (picking == Picking.FROM && picker.addingFavourite) addLabel else fromLabel,
                point = from,
                placeholder = placeholder,
                search = searchOf(Picking.FROM),
                onClick = { picking = Picking.FROM },
            )
            PointField(
                icon = R.drawable.ic_location_on,
                label = if (picking == Picking.TO && picker.addingFavourite) addLabel else toLabel,
                point = to,
                placeholder = placeholder,
                search = searchOf(Picking.TO),
                onClick = { picking = Picking.TO },
            ) {
                IconButton(
                    onClick = {
                        val previousFrom = from
                        from = to
                        to = previousFrom
                    },
                    modifier = Modifier.size(SWAP_BUTTON),
                ) {
                    Icon(painterResource(R.drawable.ic_swap_vert), contentDescription = stringResource(R.string.planner_swap))
                }
            }
            if (picking != null && searchLocator.locating) {
                Text(
                    stringResource(R.string.locating),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                (if (picking != null) searchLocator.problem else locator.problem)?.let { problem ->
                    Text(problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        val which = picking
        if (which != null) {
            // Keyed like the search itself, so that a map or a dialog open for one end does not stay up for the other.
            key(which) {
                PlacePickerResults(
                    state = picker,
                    title = if (which == Picking.FROM) fromLabel else toLabel,
                    city = city,
                    timetables = repository,
                    cityData = cityData,
                    places = places,
                    recentPlaces = recentPlaces,
                    onPick = { point ->
                        if (which == Picking.FROM) {
                            from = point
                            // The destination is what comes next, so its search opens straight away.
                            picking = if (to == null) Picking.TO else null
                        } else {
                            to = point
                            picking = null
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onEditPlaces = {
                        picking = null
                        onOpen(Screen.Settings(SettingsSection.PLACES))
                    },
                )
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = Spacing.large, end = Spacing.large, bottom = Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                DepartureChip(
                    label = if (day > 0) "${dayLabel(date, day)} ${GtfsTime.format(seconds)}" else null,
                    day = day,
                    seconds = seconds,
                    timezone = city.timezone,
                    onClick = { choosingTime = true },
                )

                val start = from
                val destination = to
                Button(
                    onClick = { if (start != null && destination != null) search(start, destination) },
                    enabled = start != null && destination != null && start != destination,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.planner_search))
                }

                if (recent.isNotEmpty()) {
                    RecentJourneys(
                        journeys = recent,
                        saved = saved,
                        myLocation = myLocation,
                        locatingFor = locatingFor.takeIf { locator.locating },
                        onClick = { if (!locator.locating) searchAgain(it) },
                    )
                }
            }
        }
    }

    if (choosingTime) {
        DepartureTimeDialog(
            today = today,
            initialDay = day.coerceAtLeast(0),
            // Opening the dialog from "now" starts at the current time.
            initialSeconds = if (day == NOW) LocalMoment.now(city.timezone).secondsOfDay else seconds,
            onConfirm = { chosenDay, chosenSeconds ->
                day = chosenDay
                seconds = chosenSeconds
                choosingTime = false
            },
            onNow = {
                day = NOW
                choosingTime = false
            },
            onDismiss = { choosingTime = false },
        )
    }
}

/**
 * This end of a past journey as an end of a new one. The user's location is the
 * position found now, [here]; a stop is looked up again by its name, as its ids
 * change with every timetable; anything else is where it was.
 */
private fun RecentPlace.toPlanPoint(
    here: GeoPoint?,
    stops: List<StopGroup>?,
    myLocation: String,
): PlanPoint {
    if (kind == RecentPlace.Kind.LOCATION && here != null) return PlanPoint(myLocation, here.lat, here.lon, myLocation = true)
    val stop = stops?.firstOrNull { kind == RecentPlace.Kind.STOP && it.name == name }
    return if (stop != null) PlanPoint(stop.name, stop.center.lat, stop.center.lon, stop.stopIds) else PlanPoint(name, lat, lon)
}

/** One button for when: "Now" with the time it is, the time alone today, or [label] with the day on another day. */
@Composable
private fun DepartureChip(
    label: String?,
    day: Int,
    seconds: Int,
    timezone: String,
    onClick: () -> Unit,
) {
    val clock by produceState(LocalMoment.now(timezone).secondsOfDay, timezone) {
        while (true) {
            value = LocalMoment.now(timezone).secondsOfDay
            delay(CLOCK_REFRESH_MILLIS)
        }
    }
    FilterChip(
        selected = day != NOW,
        onClick = onClick,
        label = {
            Text(
                when {
                    day == NOW -> stringResource(R.string.planner_now_at, GtfsTime.format(clock))
                    label != null -> label
                    else -> GtfsTime.format(seconds)
                },
            )
        },
        leadingIcon = { Icon(painterResource(R.drawable.ic_schedule), contentDescription = null, Modifier.size(IconSize.small)) },
    )
}

/** The journeys searched for lately, each to be searched again with one tap. */
@Composable
private fun RecentJourneys(
    journeys: List<RecentJourney>,
    saved: List<Place>,
    myLocation: String,
    /** The journey the user's position is being found for, when one is. */
    locatingFor: RecentJourney?,
    onClick: (RecentJourney) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        SectionHeader(stringResource(R.string.planner_recent), Modifier.padding(top = Spacing.medium))
        Column {
            for (journey in journeys) {
                RecentJourneyRow(
                    from = journey.from.shownName(myLocation),
                    to = journey.to.shownName(myLocation),
                    fromIcon = journey.from.favouriteIn(saved),
                    toIcon = journey.to.favouriteIn(saved),
                    locating = locatingFor == journey,
                    onClick = { onClick(journey) },
                )
            }
        }
    }
}

@Composable
private fun RecentJourneyRow(
    from: String,
    to: String,
    fromIcon: Int?,
    toIcon: Int?,
    locating: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .padding(vertical = Spacing.medium, horizontal = Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.large),
    ) {
        Icon(painterResource(R.drawable.ic_history), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        RouteText(
            from,
            to,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            fromIcon = fromIcon,
            toIcon = toIcon,
        )
        if (locating) CircularProgressIndicator(Modifier.size(IconSize.medium), strokeWidth = 2.dp)
    }
}

/**
 * The icon of the favourite place this end of a past journey was, when that
 * place is still saved: same name, and where it was. Null otherwise.
 */
private fun RecentPlace.favouriteIn(saved: List<Place>): Int? =
    saved
        .firstOrNull { place ->
            place.name == name && place.location.distanceMetersTo(GeoPoint(lat, lon)) <= SAME_PLACE_METERS
        }?.iconRes()

/** Two points closer than this are taken to be the same place, rounding aside. */
private const val SAME_PLACE_METERS = 30.0

/** How a journey's end is remembered: a stop by its name, the user's location as such, anything else by where it is. */
private fun PlanPoint.toRecent(): RecentPlace =
    RecentPlace(
        name = name,
        // Where the user was is not written down: searching again finds the position anew.
        lat = if (myLocation) 0.0 else lat,
        lon = if (myLocation) 0.0 else lon,
        kind =
            when {
                myLocation -> RecentPlace.Kind.LOCATION
                stopIds.isNotEmpty() -> RecentPlace.Kind.STOP
                else -> RecentPlace.Kind.POINT
            },
    )

/** The user's location is named in the current language, not the one it was remembered in. */
private fun RecentPlace.shownName(myLocation: String) = if (kind == RecentPlace.Kind.LOCATION) myLocation else name

/** The search typed into a field of the form while its end of the journey is being chosen. */
private class PointSearch(
    val text: TextFieldState,
    val locating: Boolean,
    val onLocate: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * One end of the journey: "From" or "To" alone until chosen, then the small label over the place's name.
 * While it is being chosen, with a [search], the field grows a little and is typed into; what the
 * search finds is listed under the form.
 */
@Composable
private fun PointField(
    /** Which end of the journey this is: the circle of the start or the pin of the destination. */
    icon: Int,
    label: String,
    point: PlanPoint?,
    placeholder: String,
    search: PointSearch?,
    onClick: () -> Unit,
    /** Drawn inside the field at its end while it is not being typed into, such as a button; null for nothing. */
    content: (@Composable () -> Unit)? = null,
) {
    val height by animateDpAsState(if (search != null) FIELD_HEIGHT_SEARCHING else FIELD_HEIGHT, label = "field")
    val row: @Composable () -> Unit = {
        Row(Modifier.heightIn(min = height), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.padding(start = Spacing.large).size(ROUTE_END_ICON),
                tint = MaterialTheme.colorScheme.primary,
            )
            if (search == null) {
                PointFieldText(label, point, Modifier.weight(1f))
                content?.invoke()
            } else {
                PointSearchText(label, placeholder, search, Modifier.weight(1f))
                when {
                    search.text.text.isNotEmpty() -> {
                        IconButton(onClick = { search.text.clearText() }) {
                            Icon(painterResource(R.drawable.ic_clear), contentDescription = stringResource(R.string.clear))
                        }
                    }

                    search.locating -> {
                        Box(Modifier.size(ControlSize.touchTarget), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(IconSize.medium), strokeWidth = 2.dp)
                        }
                    }

                    else -> {
                        IconButton(onClick = search.onLocate) {
                            Icon(
                                painterResource(R.drawable.ic_my_location),
                                contentDescription = stringResource(R.string.my_location),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                IconButton(onClick = search.onClose) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
                }
            }
        }
    }
    if (search == null) {
        OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) { row() }
    } else {
        // Not a button while typed into, and outlined in the accent so that it is plain which end is being chosen.
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(SEARCHING_BORDER, MaterialTheme.colorScheme.primary),
        ) { row() }
    }
}

/** The label of a field over the search typed into it; the keyboard comes up as soon as it shows. */
@Composable
private fun PointSearchText(
    label: String,
    placeholder: String,
    search: PointSearch,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        modifier =
            modifier.padding(
                start = Spacing.medium,
                end = Spacing.extraSmall,
                top = Spacing.extraSmall,
                bottom = Spacing.extraSmall,
            ),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        val text = search.text
        BasicTextField(
            state = text,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            lineLimits = TextFieldLineLimits.SingleLine,
            decorator = { field ->
                Box {
                    if (text.text.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    field()
                }
            },
        )
    }
}

@Composable
private fun PointFieldText(
    label: String,
    point: PlanPoint?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(start = Spacing.medium, end = Spacing.large, top = Spacing.extraSmall, bottom = Spacing.extraSmall),
        verticalArrangement = Arrangement.Center,
    ) {
        if (point == null) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = point.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Fits the label and the name, so a field keeps its height once a place is chosen. */
private val FIELD_HEIGHT = ControlSize.touchTarget

private val FIELD_HEIGHT_SEARCHING = 64.dp

private val SEARCHING_BORDER = 2.dp

/** The swap button inside the lower field. */
private val SWAP_BUTTON = ControlSize.touchTarget

/** How often the time next to "Now" moves on. */
private const val CLOCK_REFRESH_MILLIS = 10_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DepartureTimeDialog(
    today: LocalDate,
    initialDay: Int,
    initialSeconds: Int,
    onConfirm: (day: Int, seconds: Int) -> Unit,
    /** Back to leaving now, whatever was chosen before. */
    onNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    var day by rememberSaveable { mutableIntStateOf(initialDay) }
    val time =
        rememberTimePickerState(
            initialHour = initialSeconds / 3600 % 24,
            initialMinute = initialSeconds % 3600 / 60,
            is24Hour = true,
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.planner_departure_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.large),
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    for (offset in 0 until DAYS_OFFERED) {
                        FilterChip(
                            selected = offset == day,
                            onClick = { day = offset },
                            label = { Text(dayLabel(today.plus(offset, DateTimeUnit.DAY), offset)) },
                        )
                    }
                }
                TimePicker(time)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(day, time.hour * 3600 + time.minute * 60) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onNow) { Text(stringResource(R.string.planner_now)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private val PlanPointSaver =
    Saver<PlanPoint?, String>(
        // An empty string stands for "nothing chosen", because a saver cannot store null.
        save = { point -> point?.let { Json.encodeToString(PlanPoint.serializer(), it) }.orEmpty() },
        restore = { saved -> saved.takeIf { it.isNotEmpty() }?.let { Json.decodeFromString(PlanPoint.serializer(), it) } },
    )

private const val NOW = -1
private const val DAYS_OFFERED = 7
