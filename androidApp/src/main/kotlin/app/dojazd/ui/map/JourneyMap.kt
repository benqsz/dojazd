package app.dojazd.ui.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.dojazd.R
import app.dojazd.data.OfflineMap
import app.dojazd.gtfs.ShapeSection
import app.dojazd.model.GeoPoint
import app.dojazd.model.Line
import app.dojazd.model.Stop
import app.dojazd.routing.Journey
import app.dojazd.routing.Leg
import app.dojazd.routing.WalkEstimate
import app.dojazd.routing.WalkNetwork
import app.dojazd.ui.ArgbColors
import app.dojazd.ui.Colors
import app.dojazd.ui.LoadingBox
import app.dojazd.ui.liveColor
import app.dojazd.ui.rememberLineColorsArgb
import app.dojazd.ui.theme.LocalDarkTheme
import app.dojazd.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.hypot

/** What gets drawn for a journey, worked out before the map is ready. */
private class Route(
    val lines: FeatureCollection,
    val stops: FeatureCollection,
    /** Line numbers and the two ends of the journey, as pictures from [images]. */
    val markers: FeatureCollection,
    val images: Map<String, Bitmap>,
    val points: List<LatLng>,
    /** How far the labels of the ends reach above them, in pixels: the camera leaves that much room above the route. */
    val labelHeight: Int,
    /** The two ends of the journey and the stops where the passenger changes, whose names show when they are tapped. */
    val ends: List<EndMark>,
)

/** One end of the journey: where it is and what it is called. */
private class EndMark(
    val point: GeoPoint,
    val name: String,
)

/**
 * The city's offline map. [onStyleReady] is called when the map can be drawn on
 * and moved: at first, after a change between the light and dark theme, and
 * whenever [contentKey] changes. [content] is laid over the map.
 */
@Composable
fun OfflineMapView(
    map: OfflineMap,
    onStyleReady: (MapLibreMap, Style, MapView) -> Unit,
    modifier: Modifier = Modifier,
    contentKey: Any? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val dark = LocalDarkTheme.current
    var failure by remember { mutableStateOf<String?>(null) }
    val currentOnStyleReady by rememberUpdatedState(onStyleReady)

    // The style ships with the app; only the place of the map file on this device has to be filled in.
    val style =
        remember(dark, map) {
            context.assets
                .open(if (dark) "map/style-dark.json" else "map/style-light.json")
                .bufferedReader()
                .use { it.readText() }
                .replace("{pmtiles}", "pmtiles://file://${map.file.absolutePath}")
        }

    val mapView =
        remember {
            MapLibre.getInstance(context)
            // Everything the map draws ships with the app. This keeps the map library off the network for good,
            // so that the app's only way online stays the one in data/Network.
            MapLibre.setConnected(false)
            // A texture view composes like any other view; the default surface view punches a hole in the window,
            // which goes wrong inside dialogs and animated screens.
            val options =
                MapLibreMapOptions
                    .createFromAttributes(context)
                    .textureMode(true)
                    .logoEnabled(false)
                    .attributionEnabled(false)
                    .compassEnabled(false)
            MapView(context, options).apply {
                onCreate(null)
                addOnDidFailLoadingMapListener { message -> failure = message }
            }
        }

    // The map view has to be told about the screen's life cycle itself.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        mapView.onStart()
                        started = true
                    }

                    Lifecycle.Event.ON_RESUME -> {
                        mapView.onResume()
                        resumed = true
                    }

                    Lifecycle.Event.ON_PAUSE -> {
                        mapView.onPause()
                        resumed = false
                    }

                    Lifecycle.Event.ON_STOP -> {
                        mapView.onStop()
                        started = false
                    }

                    else -> {}
                }
            }
        // The map keeps drawn tiles in memory; when the phone runs short, it lets go of what it can draw again.
        val memory =
            object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                @Deprecated("Still how older Android versions say that memory is short")
                override fun onLowMemory() = mapView.onLowMemory()

                override fun onTrimMemory(level: Int) = mapView.onLowMemory()
            }
        context.applicationContext.registerComponentCallbacks(memory)
        // Adding the observer replays the events that already happened, so the map starts right away.
        lifecycle.addObserver(observer)
        onDispose {
            context.applicationContext.unregisterComponentCallbacks(memory)
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView, style, contentKey) {
        mapView.getMapAsync { libreMap ->
            libreMap.restrictTo(map)
            libreMap.setStyle(Style.Builder().fromJson(style)) { loaded ->
                failure = null
                currentOnStyleReady(libreMap, loaded, mapView)
            }
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        content()
        Text(
            stringResource(R.string.map_attribution),
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.small)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = Spacing.small, vertical = Spacing.tiny),
            style = MaterialTheme.typography.labelSmall,
        )
        failure?.let { message ->
            Text(
                stringResource(R.string.map_failed, message),
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(Spacing.extraLarge)
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                        .padding(Spacing.medium),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** A vehicle of one of the journey's rides, where live data last saw it. */
class JourneyVehicle(
    /** The vehicle's id in the live feed. */
    val id: String,
    val line: Line,
    val headsign: String,
    val position: GeoPoint,
    /** What passengers can read on the vehicle (its fleet number), when known. */
    val label: String?,
    /** When the position was measured, in epoch seconds, when known. */
    val measuredAt: Long?,
    /** Seconds late (early when negative), when known. */
    val delay: Int?,
)

/**
 * The map with one journey drawn on it: rides in the colours of their lines,
 * walks dashed, a dot at every stop the vehicles call at (tapping one shows its
 * name) and the [vehicles] of the rides where they are now. [origin] and
 * [destination] are the two ends of the journey, which the journey itself only
 * knows as "where the first walk starts".
 */
@Composable
fun JourneyMap(
    map: OfflineMap,
    journey: Journey,
    origin: GeoPoint,
    destination: GeoPoint,
    /** Written next to the two ends: the address, stop or place the user chose. */
    originName: String,
    destinationName: String,
    /** When given, walks are drawn along streets; otherwise as straight lines. */
    walks: WalkNetwork?,
    /** The paths of the journey's rides by [Leg.Ride.shapeId]; a ride without one is drawn straight from stop to stop. */
    shapes: Map<Long, List<GeoPoint>>,
    vehicles: List<JourneyVehicle>,
    /** The city's time zone, for the time a vehicle's position is from. */
    timezone: String,
    modifier: Modifier = Modifier,
) {
    val colorsOf = rememberLineColorsArgb()
    val rideColors = journey.legs.filterIsInstance<Leg.Ride>().map { colorsOf(it.line) }
    val walkColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val endColor = markerColor().toArgb()
    val labelColors = Colors(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface).toArgb()
    val liveColor = liveColor().toArgb()
    val resources = LocalResources.current
    val markers = remember(resources) { MapMarkers(resources) }
    val names =
        Ends(
            stringResource(R.string.map_start),
            originName,
            stringResource(R.string.map_end),
            destinationName,
        )
    // Finding the walks along streets takes a moment, which must not hold up the screen.
    val built by produceState<Route?>(
        null,
        journey,
        origin,
        destination,
        names,
        walks,
        shapes,
        rideColors,
        walkColor,
        endColor,
        labelColors,
        markers,
    ) {
        value =
            withContext(Dispatchers.Default) {
                buildRoute(journey, origin, destination, names, walks, shapes, rideColors, walkColor, endColor, labelColors, markers)
            }
    }
    val route =
        built ?: run {
            LoadingBox(modifier)
            return
        }
    var style by remember { mutableStateOf<Style?>(null) }
    val clickHandled = remember { arrayOfNulls<MapLibreMap>(1) }
    val currentLabelColors by rememberUpdatedState(labelColors)
    val currentRoute by rememberUpdatedState(route)
    val currentVehicles by rememberUpdatedState(vehicles)
    // The vehicle whose details are shown above it; they follow it as it moves.
    var selectedVehicle by remember { mutableStateOf<String?>(null) }
    val vehicleDetails = vehicleDetailsText(timezone)

    OfflineMapView(
        map = map,
        modifier = modifier,
        contentKey = route,
        onStyleReady = { libreMap, loaded, view ->
            loaded.draw(route)
            style = loaded
            // The camera can only frame the route once the view has a size.
            view.post { libreMap.show(route.points, view, labelHeight = route.labelHeight) }
            if (clickHandled[0] !== libreMap) {
                clickHandled[0] = libreMap
                libreMap.addOnMapClickListener { point ->
                    val current = libreMap.style ?: return@addOnMapClickListener false
                    val screen = libreMap.projection.toScreenLocation(point)
                    val reach = TAP_REACH_DP * view.resources.displayMetrics.density
                    val area = RectF(screen.x - reach, screen.y - reach, screen.x + reach, screen.y + reach)
                    // A tapped vehicle tells about itself: what it is, where it goes, how late, and how recent that is.
                    val vehicle =
                        libreMap.queryRenderedFeatures(area, VEHICLE_LAYER).firstNotNullOfOrNull { feature ->
                            currentVehicles.firstOrNull { it.id == feature.getStringProperty(ID) }
                        }
                    selectedVehicle = vehicle?.id
                    if (vehicle != null) {
                        current.showVehicle(vehicle, vehicleDetails(vehicle), markers, currentLabelColors)
                        return@addOnMapClickListener true
                    }
                    // A tapped end of the journey shows its name above its own label.
                    val end =
                        currentRoute.ends.firstOrNull { end ->
                            val at = libreMap.projection.toScreenLocation(LatLng(end.point.lat, end.point.lon))
                            hypot(at.x - screen.x, at.y - screen.y) <= reach
                        }
                    if (end != null) {
                        current.showEndName(end, currentRoute.labelHeight, markers, currentLabelColors)
                        return@addOnMapClickListener true
                    }
                    // The stop where the journey starts or ends is told by the end's own label.
                    val stop =
                        libreMap
                            .queryRenderedFeatures(
                                RectF(screen.x - reach, screen.y - reach, screen.x + reach, screen.y + reach),
                                STOP_LAYER,
                                PASSED_STOP_LAYER,
                            ).firstOrNull { !it.hasProperty(JOURNEY_END) || !it.getBooleanProperty(JOURNEY_END) }
                    current.showStopName(stop, markers, currentLabelColors)
                    stop != null
                }
            }
        },
    )

    LaunchedEffect(style, vehicles, liveColor) {
        val current = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        val features =
            vehicles.map { vehicle ->
                val image = "vehicle-${vehicle.line.id}"
                if (current.getImage(image) == null) {
                    val (background, content) = colorsOf(vehicle.line)
                    current.addImage(image, markers.vehicle(vehicle.line.type, background, content, liveColor))
                }
                Feature.fromGeometry(Point.fromLngLat(vehicle.position.lon, vehicle.position.lat)).apply {
                    addStringProperty(IMAGE, image)
                    addStringProperty(ID, vehicle.id)
                }
            }
        current.getSourceAs<GeoJsonSource>(VEHICLES)?.setGeoJson(FeatureCollection.fromFeatures(features))
        // The details of the chosen vehicle move with it, and show its newest report.
        vehicles.firstOrNull { it.id == selectedVehicle }?.let { current.showVehicle(it, vehicleDetails(it), markers, labelColors) }
    }
}

/** The lines of text told about a tapped vehicle under its line and direction, in the app's language and the city's time. */
@Composable
private fun vehicleDetailsText(timezone: String): (JourneyVehicle) -> List<String> {
    val resources = LocalResources.current
    return remember(resources, timezone) {
        { vehicle ->
            buildList {
                vehicle.label?.let { add(resources.getString(R.string.map_vehicle_number, it)) }
                vehicle.delay?.let { delay ->
                    val minutes = delay / 60
                    add(
                        when {
                            minutes >= 1 -> resources.getString(R.string.live_late, minutes)
                            minutes <= -1 -> resources.getString(R.string.live_early, -minutes)
                            else -> resources.getString(R.string.live_on_time)
                        },
                    )
                }
                vehicle.measuredAt?.let { seconds ->
                    val clock = Instant.ofEpochSecond(seconds).atZone(ZoneId.of(timezone)).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    add(resources.getString(R.string.map_vehicle_updated, clock))
                }
            }
        }
    }
}

/** Shows the line and direction of [vehicle], and [lines] about it, in a box above it, in place of any name shown before. */
private fun Style.showVehicle(
    vehicle: JourneyVehicle,
    lines: List<String>,
    markers: MapMarkers,
    colors: ArgbColors,
) {
    val source = getSourceAs<GeoJsonSource>(STOP_NAME) ?: return
    val raise = markers.dp(MapMarkers.VEHICLE_RADIUS_DP + LABEL_SPACING_DP + 2.5f)
    addImage(STOP_NAME_IMAGE, markers.card(vehicle.line.name, vehicle.headsign, lines, colors.background, colors.content, raise))
    source.setGeoJson(
        Feature.fromGeometry(Point.fromLngLat(vehicle.position.lon, vehicle.position.lat)).apply {
            addStringProperty(ANCHOR, Property.ICON_ANCHOR_BOTTOM)
        },
    )
}

/** The captions and names written at the two ends of a journey. */
private data class Ends(
    val startCaption: String,
    val start: String,
    val endCaption: String,
    val end: String,
)

/** Writes the name of the tapped [stop] above it, or clears the name shown when [stop] is null. */
private fun Style.showStopName(
    stop: Feature?,
    markers: MapMarkers,
    colors: ArgbColors,
) {
    val source = getSourceAs<GeoJsonSource>(STOP_NAME) ?: return
    val name = stop?.getStringProperty(NAME)
    val point = stop?.geometry() as? Point
    if (name == null || point == null) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        return
    }
    // Above the stop, clear of its dot, so that the route either side of it stays in view.
    val raise = markers.dp(STOP_DOT_RADIUS_DP + LABEL_SPACING_DP)
    addImage(STOP_NAME_IMAGE, markers.label(name, colors.background, colors.content, raise = raise))
    source.setGeoJson(Feature.fromGeometry(point).apply { addStringProperty(ANCHOR, Property.ICON_ANCHOR_BOTTOM) })
}

/** Writes the name of [end] just above the label that says which end it is, on the same side of it. */
private fun Style.showEndName(
    end: EndMark,
    labelHeight: Int,
    markers: MapMarkers,
    colors: ArgbColors,
) {
    val source = getSourceAs<GeoJsonSource>(STOP_NAME) ?: return
    // The end's label stands above its dot; the name stands on top of that, with a little space between.
    val raise = labelHeight + markers.dp(LABEL_SPACING_DP)
    addImage(STOP_NAME_IMAGE, markers.label(end.name, colors.background, colors.content, raise = raise))
    source.setGeoJson(
        Feature.fromGeometry(Point.fromLngLat(end.point.lon, end.point.lat)).apply {
            addStringProperty(ANCHOR, Property.ICON_ANCHOR_BOTTOM)
        },
    )
}

private fun buildRoute(
    journey: Journey,
    origin: GeoPoint,
    destination: GeoPoint,
    names: Ends,
    walks: WalkNetwork?,
    shapes: Map<Long, List<GeoPoint>>,
    /** Background and text colour of each ride's line, in order. */
    rideColors: List<ArgbColors>,
    walkColor: Int,
    endColor: Int,
    /** Background and text colour of the names of the two ends. */
    labelColors: ArgbColors,
    markers: MapMarkers,
): Route {
    val lines = ArrayList<Feature>()
    val stops = ArrayList<Feature>()
    val pictures = ArrayList<Feature>()
    val images = HashMap<String, Bitmap>()
    val points = ArrayList<LatLng>()

    fun picture(
        point: GeoPoint,
        image: String,
        bitmap: () -> Bitmap,
        order: Int,
        anchor: String = Property.ICON_ANCHOR_CENTER,
    ) {
        images.getOrPut(image, bitmap)
        pictures +=
            Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)).apply {
                addStringProperty(IMAGE, image)
                addNumberProperty(ORDER, order)
                addStringProperty(ANCHOR, anchor)
            }
    }

    fun line(
        path: List<GeoPoint>,
        color: Int,
        walk: Boolean,
    ) {
        lines +=
            Feature.fromGeometry(LineString.fromLngLats(path.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addStringProperty(COLOR, hex(color))
                addBooleanProperty(WALK, walk)
            }
        path.forEach { points += LatLng(it.lat, it.lon) }
    }

    fun dot(
        stop: Stop,
        color: Int,
        passed: Boolean,
        journeyEnd: Boolean,
    ) {
        stops +=
            Feature.fromGeometry(Point.fromLngLat(stop.location.lon, stop.location.lat)).apply {
                addStringProperty(COLOR, hex(color))
                addStringProperty(NAME, stop.name)
                addBooleanProperty(PASSED, passed)
                addBooleanProperty(JOURNEY_END, journeyEnd)
            }
    }

    var ride = 0
    for ((legIndex, leg) in journey.legs.withIndex()) {
        when (leg) {
            is Leg.Walk -> {
                val from = leg.from?.location ?: origin
                val to = leg.to?.location ?: destination
                // The same search the planner timed the walk with; a straight line where the streets are not known.
                val path = walks?.path(from, to, WalkEstimate.maxAlongStreets(from.distanceMetersTo(to)))
                line(path?.points ?: listOf(from, to), walkColor, walk = true)
            }

            is Leg.Ride -> {
                val (color, textColor) = rideColors[ride++]
                // Along the streets or tracks the vehicle takes, when the timetable says where they are.
                val along = leg.shapeId?.let(shapes::get)?.let { ShapeSection.between(it, leg.from.location, leg.to.location) }
                val path = along ?: leg.calls.map { it.stop.location }
                line(path, color, walk = false)
                leg.calls.forEachIndexed { index, call ->
                    // A journey that begins or ends on board has its start or end right at this stop.
                    val journeyEnd =
                        (legIndex == 0 && index == 0) ||
                            (legIndex == journey.legs.lastIndex && index == leg.calls.lastIndex)
                    dot(call.stop, color, passed = index != 0 && index != leg.calls.lastIndex, journeyEnd = journeyEnd)
                }
                // The line's number halfway along the ride, so that every ride on the map says which line it is.
                val image = MapMarkers.badgeImage(leg.line)
                picture(midpoint(path), image, { markers.lineBadge(leg.line.name, color, textColor) }, order = BADGE_ORDER)
            }
        }
    }
    val (labelBackground, labelText) = labelColors
    // Only which end it is, always above its dot; the name of the place shows when the end is tapped.
    val raise = markers.dp(END_DOT_RADIUS_DP + LABEL_SPACING_DP)
    val startLabel = markers.label(names.startCaption, labelBackground, labelText, raise = raise)
    val endLabel = markers.label(names.endCaption, labelBackground, labelText, raise = raise)
    // Both ends are marked alike; their labels say which is which.
    picture(origin, END_DOT, { markers.start(endColor) }, order = END_ORDER)
    picture(origin, START_LABEL, { startLabel }, order = LABEL_ORDER, anchor = Property.ICON_ANCHOR_BOTTOM)
    picture(destination, END_DOT, { markers.start(endColor) }, order = END_ORDER)
    picture(destination, END_LABEL, { endLabel }, order = LABEL_ORDER, anchor = Property.ICON_ANCHOR_BOTTOM)
    // Where one ride ends and another follows, the passenger changes: two arrows above the stop of getting off.
    val changes =
        journey.legs
            .filterIsInstance<Leg.Ride>()
            .dropLast(1)
            .map { it.to }
    val changeLabel = markers.label("", labelBackground, labelText, raise = raise, icon = R.drawable.ic_sync_alt)
    changes.forEach { stop ->
        picture(stop.location, CHANGE_LABEL, { changeLabel }, order = LABEL_ORDER, anchor = Property.ICON_ANCHOR_BOTTOM)
    }
    return Route(
        lines = FeatureCollection.fromFeatures(lines),
        stops = FeatureCollection.fromFeatures(stops),
        markers = FeatureCollection.fromFeatures(pictures),
        images = images,
        points = points,
        labelHeight = maxOf(startLabel.height, endLabel.height, changeLabel.height),
        ends = listOf(EndMark(origin, names.start), EndMark(destination, names.end)) + changes.map { EndMark(it.location, it.name) },
    )
}

/** The radius of the dots at the two ends of the journey (see [MapMarkers.start]). */
private const val END_DOT_RADIUS_DP = 10f

/** The point halfway along [path], measured along it. */
private fun midpoint(path: List<GeoPoint>): GeoPoint {
    if (path.size < 2) return path.first()
    val lengths = path.zipWithNext { a, b -> a.distanceMetersTo(b) }
    var remaining = lengths.sum() / 2
    for ((index, length) in lengths.withIndex()) {
        if (remaining <= length && length > 0) {
            val a = path[index]
            val b = path[index + 1]
            val share = remaining / length
            return GeoPoint(a.lat + (b.lat - a.lat) * share, a.lon + (b.lon - a.lon) * share)
        }
        remaining -= length
    }
    return path.last()
}

/** Keeps the camera over the area the map file covers, at zoom levels where it has something to show. */
private fun MapLibreMap.restrictTo(map: OfflineMap) {
    val bounds = map.archive.bounds
    setLatLngBoundsForCameraTarget(LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west))
    setMinZoomPreference(MIN_ZOOM)
    // Beyond the file's last level the same data is simply drawn larger.
    setMaxZoomPreference(map.archive.maxZoom + EXTRA_ZOOM)
    uiSettings.isRotateGesturesEnabled = false
    uiSettings.isTiltGesturesEnabled = false
}

private fun Style.draw(route: Route) {
    addSource(GeoJsonSource(LINES, route.lines))
    addSource(GeoJsonSource(STOPS, route.stops))
    addSource(GeoJsonSource(MARKERS, route.markers))
    addSource(GeoJsonSource(VEHICLES))
    addSource(GeoJsonSource(STOP_NAME))
    route.images.forEach { (name, bitmap) -> addImage(name, bitmap) }

    val isWalk = Expression.eq(Expression.get(WALK), true)
    val isRide = Expression.eq(Expression.get(WALK), false)
    val color = Expression.toColor(Expression.get(COLOR))

    // A white edge keeps a line readable whatever is under it; night lines are black, like the dark map.
    addLayer(
        LineLayer("route-ride-edge", LINES)
            .withProperties(
                PropertyFactory.lineColor("#FFFFFF"),
                PropertyFactory.lineWidth(8f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).apply { setFilter(isRide) },
    )
    addLayer(
        LineLayer("route-ride", LINES)
            .withProperties(
                PropertyFactory.lineColor(color),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).apply { setFilter(isRide) },
    )
    addLayer(
        LineLayer("route-walk", LINES)
            .withProperties(
                PropertyFactory.lineColor(color),
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineDasharray(arrayOf(1f, 2f)),
            ).apply { setFilter(isWalk) },
    )
    // Stops passed on the way are smaller than those where the passenger gets on or off.
    addLayer(
        CircleLayer(PASSED_STOP_LAYER, STOPS)
            .withProperties(
                PropertyFactory.circleColor("#FFFFFF"),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor(color),
                PropertyFactory.circleStrokeWidth(3f),
            ).apply {
                setFilter(Expression.eq(Expression.get(PASSED), true))
                // Zoomed out, the stops passed on the way would only crowd the line.
                minZoom = PASSED_STOPS_ZOOM
            },
    )
    addLayer(
        CircleLayer(STOP_LAYER, STOPS)
            .withProperties(
                PropertyFactory.circleColor("#FFFFFF"),
                // Smaller when zoomed out, so that the stops of a change do not run into one another.
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(FAR_ZOOM, STOP_DOT_RADIUS_DP / 2),
                        Expression.stop(PASSED_STOPS_ZOOM, STOP_DOT_RADIUS_DP),
                    ),
                ),
                PropertyFactory.circleStrokeColor(color),
                PropertyFactory.circleStrokeWidth(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(FAR_ZOOM, 2f),
                        Expression.stop(PASSED_STOPS_ZOOM, 4f),
                    ),
                ),
            ).apply { setFilter(Expression.eq(Expression.get(PASSED), false)) },
    )
    // Everything here must stay visible, even where pictures or the map's own labels overlap.
    addLayer(
        SymbolLayer("route-markers", MARKERS).withProperties(
            PropertyFactory.iconImage(Expression.get(IMAGE)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Expression.get(ANCHOR)),
            PropertyFactory.symbolSortKey(Expression.get(ORDER)),
        ),
    )
    addLayer(
        SymbolLayer("route-stop-name", STOP_NAME).withProperties(
            PropertyFactory.iconImage(STOP_NAME_IMAGE),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Expression.get(ANCHOR)),
        ),
    )
    // The vehicles over everything else: they move, and are what the passenger is looking for.
    addLayer(
        SymbolLayer(VEHICLE_LAYER, VEHICLES).withProperties(
            PropertyFactory.iconImage(Expression.get(IMAGE)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ),
    )
}

/** Moves the camera so that all of [points] are in view, with room around them. */
internal fun MapLibreMap.show(
    points: List<LatLng>,
    view: MapView,
    /** How far labels reach above the points, in pixels: that much more room is left at the top. */
    labelHeight: Int = 0,
) {
    if (points.isEmpty() || view.width == 0 || view.height == 0) return
    val padding = minOf(view.width, view.height) / 12
    // Never so much room that nothing is left for the route itself.
    val top = (padding + labelHeight).coerceAtMost(view.height / 3)
    val south = points.minOf { it.latitude }
    val north = points.maxOf { it.latitude }
    val west = points.minOf { it.longitude }
    val east = points.maxOf { it.longitude }
    val update =
        if (north - south < TINY_DEGREES && east - west < TINY_DEGREES) {
            // Bounds without any extent cannot be framed.
            CameraUpdateFactory.newLatLngZoom(points.first(), CLOSE_ZOOM)
        } else {
            CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(north, east, south, west), padding, top, padding, padding)
        }
    moveCamera(update)
}

internal fun hex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)

private const val LINES = "route-lines"
private const val STOPS = "route-stops-source"
private const val COLOR = "color"
private const val WALK = "walk"
private const val MARKERS = "route-markers-source"
private const val IMAGE = "image"
private const val ORDER = "order"
private const val ANCHOR = "anchor"
private const val NAME = "name"
private const val PASSED = "passed"
private const val JOURNEY_END = "journey-end"
private const val VEHICLES = "route-vehicles-source"
private const val STOP_NAME = "route-stop-name-source"
private const val STOP_NAME_IMAGE = "route-stop-name"
private const val STOP_LAYER = "route-stops"
private const val PASSED_STOP_LAYER = "route-passed-stops"

/** From this zoom on, the stops passed on the way are shown, and the stops where the passenger gets on or off are full size. */
private const val PASSED_STOPS_ZOOM = 14f

/** At this zoom and further out, the stops where the passenger gets on or off are at their smallest. */
private const val FAR_ZOOM = 11f

/** The radius of the larger stop dots, where the passenger gets on or off. */
private const val STOP_DOT_RADIUS_DP = 9f

/** The space between an end's label and its name above it. */
private const val LABEL_SPACING_DP = 4f

/** How far from a stop's dot a tap still counts as on it. */
private const val TAP_REACH_DP = 14f
private const val END_DOT = "journey-end-dot"
private const val START_LABEL = "journey-start-label"
private const val END_LABEL = "journey-end-label"
private const val CHANGE_LABEL = "journey-change-label"
private const val VEHICLE_LAYER = "route-vehicles"
private const val ID = "id"

/** Pictures with a higher order are drawn over those with a lower one: the ends and their names over line numbers. */
private const val BADGE_ORDER = 1
private const val LABEL_ORDER = 2
private const val END_ORDER = 3
private const val MIN_ZOOM = 9.0
private const val EXTRA_ZOOM = 4.0
private const val CLOSE_ZOOM = 15.0
private const val TINY_DEGREES = 0.0002

/**
 * The colour of pins and of the ends of a journey. The dark theme's primary
 * colour is a pale tint that hardly stands out from the white edge around it
 * and the labels of the map, so there the stronger tone of the light theme is used.
 */
@Composable
internal fun markerColor(): Color =
    if (LocalDarkTheme.current) MaterialTheme.colorScheme.inversePrimary else MaterialTheme.colorScheme.primary
