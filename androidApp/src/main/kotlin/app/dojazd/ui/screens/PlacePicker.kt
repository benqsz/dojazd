package app.dojazd.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dojazd.R
import app.dojazd.data.CityDataRepository
import app.dojazd.data.OfflineMap
import app.dojazd.data.PlacesRepository
import app.dojazd.data.RecentPlace
import app.dojazd.data.RecentPlacesRepository
import app.dojazd.data.TimetableRepository
import app.dojazd.geocode.AddressIndex
import app.dojazd.geocode.PlaceMatch
import app.dojazd.model.GeoPoint
import app.dojazd.model.SearchText
import app.dojazd.provider.CityConfig
import app.dojazd.storage.Place
import app.dojazd.ui.FullScreenDialog
import app.dojazd.ui.LoadingBox
import app.dojazd.ui.Locator
import app.dojazd.ui.PlaceDialog
import app.dojazd.ui.PlaceIcon
import app.dojazd.ui.PlanPoint
import app.dojazd.ui.SectionHeader
import app.dojazd.ui.StopGroup
import app.dojazd.ui.StopIcon
import app.dojazd.ui.map.PointPickerMap
import app.dojazd.ui.map.rememberPointPickerState
import app.dojazd.ui.rememberLocator
import app.dojazd.ui.rememberStopGroups
import app.dojazd.ui.sectionHeaderPadding
import app.dojazd.ui.showMessage
import app.dojazd.ui.theme.IconSize
import app.dojazd.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen search for one end of a journey. One list holds everything that
 * matches, told apart by icon: the user's saved places, stops, addresses and
 * named places. Before anything is typed it holds the user's own location and
 * the saved places, and below a line those chosen recently. The last entry
 * picks a point on the map instead.
 */
@Composable
fun PlacePickerDialog(
    title: String,
    city: CityConfig,
    timetables: TimetableRepository,
    cityData: CityDataRepository,
    places: PlacesRepository,
    recentPlaces: RecentPlacesRepository,
    onPick: (PlanPoint) -> Unit,
    onDismiss: () -> Unit,
    /** Opens where saved places are managed; null to offer no way there. */
    onEditPlaces: (() -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val key = SearchText.fold(query.trim())

    // While true, what is chosen becomes a favourite place instead of an end of the journey.
    var addingFavourite by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf<Place?>(null) }

    /** Saves the chosen point as a new favourite place, after asking for its label and icon. */
    fun addFavourite(point: PlanPoint) {
        addingFavourite = false
        query = ""
        saving = Place(places.newId(), point.name, address = point.name, location = GeoPoint(point.lat, point.lon))
    }

    /** Hands the choice back without remembering it, or makes it a favourite place while adding one. */
    fun choose(point: PlanPoint) {
        if (addingFavourite) addFavourite(point) else onPick(point)
    }

    /** Hands the choice back, and remembers it for the next time the search opens. */
    fun pick(
        point: PlanPoint,
        kind: RecentPlace.Kind,
    ) {
        if (addingFavourite) {
            addFavourite(point)
            return
        }
        recentPlaces.add(city.id, RecentPlace(point.name, point.lat, point.lon, kind))
        onPick(point)
    }

    val saved by places.places.collectAsState()
    // Places saved while in another city would only be in the way here.
    val matchingSaved =
        remember(saved, key, city.id) {
            saved.filter { it.location in city.bounds && (key.isEmpty() || key in SearchText.fold(it.name)) }
        }
    val stops by rememberStopGroups(timetables)
    val matchingStops =
        remember(stops, key) {
            // Names starting with what was typed go first. Nothing typed, nothing listed: the top of the alphabet says nothing.
            if (key.isEmpty()) {
                stops?.let {
                    emptyList()
                }
            } else {
                stops?.filter { key in it.searchKey }?.sortedBy { !it.searchKey.startsWith(key) }?.take(MAX_STOPS)
            }
        }
    val recent by recentPlaces.of(city.id).collectAsState()
    // Offered only before anything is typed, and not twice when a recent place is also a saved one.
    val shownRecent =
        remember(recent, key, matchingSaved) {
            if (key.isNotEmpty()) emptyList() else recent.filter { place -> matchingSaved.none { it.name == place.name } }
        }
    val index by produceState<AddressIndex?>(initialValue = null, city.id) { value = cityData.addressIndex(city) }
    val found by produceState(initialValue = emptyList<PlaceMatch>(), index, query) {
        val current = index
        if (current == null || query.isBlank()) {
            value = emptyList()
        } else {
            // Wait for a pause in typing, then search off the main thread.
            delay(SEARCH_DELAY_MILLIS)
            value = withContext(Dispatchers.Default) { current.search(query, MAX_PLACES) }
        }
    }
    val map by produceState<OfflineMap?>(initialValue = null, city.id) { value = cityData.map(city) }

    val locator = rememberLocator(city)
    val myLocation = stringResource(R.string.my_location)

    val context = LocalContext.current
    val savedMessage = stringResource(R.string.place_saved)
    saving?.let { draft ->
        PlaceDialog(
            title = stringResource(R.string.save_place),
            initialName = draft.name,
            initialIcon = draft.icon,
            address = draft.address,
            onSave = { name, icon ->
                places.save(draft.copy(name = name, icon = icon))
                saving = null
                context.showMessage(savedMessage)
            },
            onDismiss = { saving = null },
        )
    }

    var pickingOnMap by rememberSaveable { mutableStateOf(false) }
    val shownMap = map
    if (pickingOnMap && shownMap != null) {
        PointPickerDialog(
            title = title,
            city = city,
            map = shownMap,
            cityData = cityData,
            onPick = { pick(it, RecentPlace.Kind.POINT) },
            onDismiss = { pickingOnMap = false },
        )
    }

    FullScreenDialog(
        title = { Text(if (addingFavourite) stringResource(R.string.place_add) else title) },
        onDismiss = onDismiss,
        // While adding a favourite, closing goes back to the search, not out of it.
        onClose = { if (addingFavourite) addingFavourite = false else onDismiss() },
    ) {
        PlaceSearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(if (city.addresses != null) R.string.search_places else R.string.search_stops),
            locator = locator,
            // Where the user happens to be is not a place to offer again later, so it is not remembered.
            onLocate = { point -> choose(PlanPoint(myLocation, point.lat, point.lon, myLocation = true)) },
        )

        val foundStops = matchingStops
        if (foundStops == null) {
            LoadingBox()
            return@FullScreenDialog
        }
        // Kept clear of the keyboard, so that the end of the list can be reached while it is up.
        LazyColumn(Modifier.fillMaxSize().imePadding()) {
            // While searching, the way to edit saved places would only be in the way.
            val onEdit = onEditPlaces.takeIf { key.isEmpty() }
            // With no favourite places yet, adding the first one is offered in their place.
            val onAdd: (() -> Unit)? =
                if (key.isEmpty() && !addingFavourite && saved.none { it.location in city.bounds }) {
                    {
                        addingFavourite = true
                        query = ""
                    }
                } else {
                    null
                }
            if (addingFavourite) {
                item(key = "adding-hint") {
                    Text(
                        stringResource(R.string.place_add_hint),
                        modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.small),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (onAdd != null || matchingSaved.isNotEmpty() || onEdit != null) {
                item(key = "saved-header") { SectionHeader(stringResource(R.string.section_saved_places), Modifier.sectionHeaderPadding()) }
            }
            if (onAdd != null) {
                item(key = "add-place") { AddPlaceTile(onAdd) }
            } else if (!addingFavourite && (matchingSaved.isNotEmpty() || onEdit != null)) {
                item(key = "saved") {
                    // A saved place is always at hand; it does not need to be remembered as recent.
                    SavedPlacesGrid(matchingSaved, onEdit) { place ->
                        onPick(PlanPoint(place.name, place.location.lat, place.location.lon))
                    }
                }
            }
            // Saved places are always there; what was chosen lately changes, so it stands apart.
            if (shownRecent.isNotEmpty()) {
                item(key = "recent-header") { SectionHeader(stringResource(R.string.places_recent), Modifier.sectionHeaderPadding()) }
            }
            recentPlaces(shownRecent, stops, ::pick)
            if (foundStops.isNotEmpty()) {
                item(key = "stops-header") { SectionHeader(stringResource(R.string.places_stops), Modifier.sectionHeaderPadding()) }
            }
            stopResults(
                foundStops,
                onPick = { pick(it, RecentPlace.Kind.STOP) },
                // A stop has no address of its own; its name says where it is.
                onSave = { saving = Place(places.newId(), it.name, address = it.name, location = it.center) },
            )
            if (found.isNotEmpty()) {
                item(key = "addresses-header") { SectionHeader(stringResource(R.string.places_addresses), Modifier.sectionHeaderPadding()) }
            }
            addressResults(
                found,
                onPick = ::pick,
                onSave = { saving = Place(places.newId(), it.name, address = it.address(), location = it.location) },
            )
            if (key.isNotEmpty() && matchingSaved.isEmpty() && foundStops.isEmpty() && found.isEmpty()) {
                item(key = "nothing") {
                    Text(
                        stringResource(if (city.addresses != null) R.string.no_places_found else R.string.no_stops_found),
                        modifier = Modifier.padding(Spacing.extraLarge),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (map != null) {
                item(key = "map") {
                    HorizontalDivider()
                    ListItem(
                        modifier = Modifier.clickable { pickingOnMap = true },
                        leadingContent = { Icon(painterResource(R.drawable.ic_map), contentDescription = null) },
                    ) {
                        Text(stringResource(R.string.pick_on_map))
                    }
                }
            }
        }
    }
}

/** The field the search is typed into, with the way to the user's own position at its end while nothing is typed. */
@Composable
private fun PlaceSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    locator: Locator,
    onLocate: (GeoPoint) -> Unit,
) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val myLocation = stringResource(R.string.my_location)
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.large, vertical = Spacing.small)
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused },
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            when {
                query.isNotEmpty() -> {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(painterResource(R.drawable.ic_clear), contentDescription = stringResource(R.string.clear))
                    }
                }

                locator.locating -> {
                    CircularProgressIndicator(Modifier.size(IconSize.medium), strokeWidth = 2.dp)
                }

                focused -> {
                    IconButton(onClick = { locator.locate(onLocate) }) {
                        Icon(
                            painterResource(R.drawable.ic_my_location),
                            contentDescription = myLocation,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        // Said here, under the field, as well as in a passing message: what is going on, or why it found nothing.
        supportingText =
            when {
                locator.locating -> ({ Text(stringResource(R.string.locating)) })
                locator.problem != null && query.isEmpty() -> ({ Text(locator.problem, color = MaterialTheme.colorScheme.error) })
                else -> null
            },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

/** The places chosen lately; a stop among them is found again by its name, as its ids change with every timetable. */
private fun LazyListScope.recentPlaces(
    recent: List<RecentPlace>,
    stops: List<StopGroup>?,
    onPick: (PlanPoint, RecentPlace.Kind) -> Unit,
) {
    items(recent, key = { "recent-${it.kind}-${it.name}" }) { place ->
        val stop = stops?.firstOrNull { place.kind == RecentPlace.Kind.STOP && it.name == place.name }
        ListItem(
            modifier =
                Modifier.clickable {
                    val point =
                        if (stop != null) {
                            PlanPoint(stop.name, stop.center.lat, stop.center.lon, stop.stopIds)
                        } else {
                            PlanPoint(place.name, place.lat, place.lon)
                        }
                    onPick(point, place.kind)
                },
            leadingContent = {
                if (stop != null) {
                    StopIcon(stop.types)
                } else {
                    Icon(painterResource(place.kind.icon()), contentDescription = null)
                }
            },
        ) {
            Text(place.name)
        }
    }
}

private fun LazyListScope.stopResults(
    stops: List<StopGroup>,
    onPick: (PlanPoint) -> Unit,
    onSave: (StopGroup) -> Unit,
) {
    items(stops, key = { "stop-${it.name}" }) { group ->
        ListItem(
            modifier = Modifier.clickable { onPick(PlanPoint(group.name, group.center.lat, group.center.lon, group.stopIds)) },
            leadingContent = { StopIcon(group.types) },
            trailingContent = { SaveButton(onClick = { onSave(group) }) },
        ) {
            Text(group.name)
        }
    }
}

private fun LazyListScope.addressResults(
    found: List<PlaceMatch>,
    onPick: (PlanPoint, RecentPlace.Kind) -> Unit,
    onSave: (PlaceMatch) -> Unit,
) {
    items(found) { place ->
        val kind = if (place.kind == PlaceMatch.Kind.PLACE) RecentPlace.Kind.PLACE else RecentPlace.Kind.ADDRESS
        ListItem(
            modifier = Modifier.clickable { onPick(PlanPoint(place.name, place.location.lat, place.location.lon), kind) },
            leadingContent = { Icon(painterResource(kind.icon()), contentDescription = null) },
            supportingContent = place.detail.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
            trailingContent = { SaveButton(onClick = { onSave(place) }) },
        ) {
            Text(place.name)
        }
    }
}

/**
 * Saved places five to a row, each an icon over its label, with [onEdit] as
 * the last tile of the first row. When they do not all fit in that row, the
 * rest stay folded away until the user asks for them.
 */
@Composable
private fun SavedPlacesGrid(
    places: List<Place>,
    onEdit: (() -> Unit)?,
    onPick: (Place) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val firstRow = if (onEdit != null) PLACES_PER_ROW - 1 else PLACES_PER_ROW
    val foldable = places.size > firstRow
    val rest = if (expanded) places.drop(firstRow) else emptyList()
    Column(Modifier.padding(horizontal = Spacing.small, vertical = Spacing.extraSmall)) {
        GridRow {
            places.take(firstRow).forEach { place ->
                PlaceTile(place.name, onClick = { onPick(place) }, modifier = Modifier.weight(1f)) { PlaceIcon(place, it) }
            }
            if (onEdit != null) {
                // Pushed to the end of the row, however few places there are.
                repeat(firstRow - minOf(places.size, firstRow)) { Spacer(Modifier.weight(1f)) }
                PlaceTile(stringResource(R.string.places_edit), onClick = onEdit, modifier = Modifier.weight(1f), muted = true) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = null, modifier = it)
                }
            } else {
                repeat(PLACES_PER_ROW - minOf(places.size, PLACES_PER_ROW)) { Spacer(Modifier.weight(1f)) }
            }
        }
        rest.chunked(PLACES_PER_ROW).forEach { row ->
            GridRow {
                row.forEach { place ->
                    PlaceTile(place.name, onClick = { onPick(place) }, modifier = Modifier.weight(1f)) { PlaceIcon(place, it) }
                }
                // Empty cells keep the tiles of a short last row as wide as the others.
                repeat(PLACES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (foldable) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (expanded) stringResource(R.string.places_show_fewer) else stringResource(R.string.places_show_all, places.size))
            }
        }
    }
}

/** Stands in for the saved places while there are none: the way to add the first. */
@Composable
private fun AddPlaceTile(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.extraSmall),
        contentAlignment = Alignment.Center,
    ) {
        PlaceTile(stringResource(R.string.place_add), onClick = onClick, muted = true) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = it)
        }
    }
}

@Composable
private fun GridRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), content = content)
}

/**
 * An icon in a circle over a one-line label. [icon], last so it can trail the call, is given the modifier that sizes it.
 * A [muted] tile is an action rather than a place, and steps back from the places around it.
 */
@Composable
private fun PlaceTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    icon: @Composable (Modifier) -> Unit,
) {
    Column(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .padding(vertical = Spacing.small, horizontal = Spacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        val colors = MaterialTheme.colorScheme
        Surface(
            shape = CircleShape,
            color = if (muted) Color.Transparent else colors.secondaryContainer,
            contentColor = if (muted) colors.onSurfaceVariant else colors.onSecondaryContainer,
            border = if (muted) BorderStroke(1.dp, colors.outlineVariant) else null,
        ) {
            icon(Modifier.padding(Spacing.small).size(IconSize.medium))
        }
        Text(
            label,
            color = if (muted) colors.onSurfaceVariant else Color.Unspecified,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private const val PLACES_PER_ROW = 5

/** One icon per kind of place, the same wherever such a place is listed. */
private fun RecentPlace.Kind.icon(): Int =
    when (this) {
        RecentPlace.Kind.STOP -> R.drawable.ic_signpost
        RecentPlace.Kind.PLACE -> R.drawable.ic_location_city
        RecentPlace.Kind.ADDRESS, RecentPlace.Kind.POINT -> R.drawable.ic_location_on
        RecentPlace.Kind.LOCATION -> R.drawable.ic_my_location
    }

/** The text that says where a search result is, kept with a saved place for good. */
private fun PlaceMatch.address(): String =
    when {
        // A named place found by its name: the street address when known, otherwise the name itself.
        kind == PlaceMatch.Kind.PLACE -> detail.ifEmpty { name }

        detail.isEmpty() -> name

        else -> "$name, $detail"
    }

@Composable
private fun SaveButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_bookmark_border), contentDescription = stringResource(R.string.save_place))
    }
}

/** The city's map to pick a point on: the user moves it under a pin and confirms. The point is named after the nearest address. */
@Composable
private fun PointPickerDialog(
    title: String,
    city: CityConfig,
    map: OfflineMap,
    cityData: CityDataRepository,
    onPick: (PlanPoint) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberPointPickerState()
    val scope = rememberCoroutineScope()
    val unnamed = stringResource(R.string.point_on_map)

    FullScreenDialog(title = { Text(title) }, onDismiss = onDismiss) {
        PointPickerMap(map, start = city.center, state = state, modifier = Modifier.fillMaxSize()) {
            Button(
                onClick = {
                    val point = state.center() ?: return@Button
                    scope.launch {
                        val name = cityData.addressIndex(city)?.nearest(point)?.name ?: unnamed
                        onPick(PlanPoint(name, point.lat, point.lon))
                    }
                },
                enabled = state.ready,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = Spacing.huge),
            ) {
                Text(stringResource(R.string.pick_this_point))
            }
        }
    }
}

private const val SEARCH_DELAY_MILLIS = 150L
private const val MAX_STOPS = 8
private const val MAX_PLACES = 10
