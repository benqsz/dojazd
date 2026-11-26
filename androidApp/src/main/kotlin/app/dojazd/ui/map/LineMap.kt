package app.dojazd.ui.map

import android.graphics.RectF
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalResources
import app.dojazd.data.OfflineMap
import app.dojazd.model.GeoPoint
import app.dojazd.model.Stop
import app.dojazd.ui.ArgbColors
import app.dojazd.ui.Colors
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
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

/** One stop sequence of a line to draw, along the path its vehicles take when that is known. */
class LinePath(
    val stops: List<Stop>,
    /** Null or empty when the feed does not say; the stops are then joined by straight lines. */
    val shape: List<GeoPoint>?,
)

/**
 * One direction of a line on the map: its usual route in the line's colour and,
 * in [branchColor] under it, where its [variants] leave that route, with a dot
 * at every stop. Tapping a stop shows its name.
 */
@Composable
fun LineMap(
    map: OfflineMap,
    main: LinePath,
    variants: List<LinePath>,
    lineColor: Int,
    branchColor: Int,
    modifier: Modifier = Modifier,
) {
    val labelColors = Colors(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface).toArgb()
    val resources = LocalResources.current
    val markers = remember(resources) { MapMarkers(resources) }
    val drawing = remember(main, variants, lineColor, branchColor) { LineDrawing.of(main, variants, lineColor, branchColor) }
    val currentLabelColors by rememberUpdatedState(labelColors)
    val clickHandled = remember { arrayOfNulls<MapLibreMap>(1) }

    OfflineMapView(
        map = map,
        modifier = modifier,
        contentKey = drawing,
        onStyleReady = { libreMap, loaded, view ->
            loaded.draw(drawing)
            loaded.markEnds(main.stops, lineColor, markers, currentLabelColors)
            view.post { libreMap.show(drawing.focus, view) }
            if (clickHandled[0] !== libreMap) {
                clickHandled[0] = libreMap
                libreMap.addOnMapClickListener { point ->
                    val style = libreMap.style ?: return@addOnMapClickListener false
                    val screen = libreMap.projection.toScreenLocation(point)
                    val reach = TAP_REACH_DP * view.resources.displayMetrics.density
                    val stop =
                        libreMap
                            .queryRenderedFeatures(
                                RectF(screen.x - reach, screen.y - reach, screen.x + reach, screen.y + reach),
                                STOP_LAYER,
                            ).firstOrNull()
                    style.showName(stop, markers, currentLabelColors)
                    stop != null
                }
            }
        },
    )
}

/** What is drawn for a line, worked out before the map is ready. */
private class LineDrawing(
    val lines: FeatureCollection,
    val stops: FeatureCollection,
    /** What the camera frames: the usual route. */
    val focus: List<LatLng>,
) {
    companion object {
        fun of(
            main: LinePath,
            variants: List<LinePath>,
            lineColor: Int,
            branchColor: Int,
        ): LineDrawing {
            fun path(of: LinePath) = of.shape?.takeIf { it.size >= 2 } ?: of.stops.map { it.location }

            fun line(
                of: LinePath,
                color: Int,
                branch: Boolean,
            ) = Feature.fromGeometry(LineString.fromLngLats(path(of).map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addStringProperty(COLOR, hex(color))
                addBooleanProperty(BRANCH, branch)
            }

            fun dot(
                stop: Stop,
                color: Int,
            ) = Feature.fromGeometry(Point.fromLngLat(stop.location.lon, stop.location.lat)).apply {
                addStringProperty(COLOR, hex(color))
                addStringProperty(NAME, stop.name)
            }

            // The variants go first, so the usual route covers them wherever they run along it.
            val lines =
                variants.filter { path(it).size >= 2 }.map { line(it, branchColor, branch = true) } + line(main, lineColor, branch = false)
            val mainIds = main.stops.mapTo(HashSet()) { it.id }
            val branchStops = variants.flatMap { it.stops }.filter { it.id !in mainIds }.distinctBy { it.id }
            val stops = branchStops.map { dot(it, branchColor) } + main.stops.map { dot(it, lineColor) }
            return LineDrawing(
                lines = FeatureCollection.fromFeatures(lines),
                stops = FeatureCollection.fromFeatures(stops),
                focus = path(main).map { LatLng(it.lat, it.lon) },
            )
        }
    }
}

private fun Style.draw(drawing: LineDrawing) {
    addSource(GeoJsonSource(LINES, drawing.lines))
    addSource(GeoJsonSource(STOPS, drawing.stops))
    addSource(GeoJsonSource(NAME_SOURCE))
    val color = Expression.toColor(Expression.get(COLOR))
    val isBranch = Expression.eq(Expression.get(BRANCH), true)
    val isMain = Expression.eq(Expression.get(BRANCH), false)

    addLayer(
        LineLayer("line-branch", LINES)
            .withProperties(
                PropertyFactory.lineColor(color),
                PropertyFactory.lineWidth(3.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).apply { setFilter(isBranch) },
    )
    // A white edge keeps the route readable whatever is under it.
    addLayer(
        LineLayer("line-main-edge", LINES)
            .withProperties(
                PropertyFactory.lineColor("#FFFFFF"),
                PropertyFactory.lineWidth(8f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).apply { setFilter(isMain) },
    )
    addLayer(
        LineLayer("line-main", LINES)
            .withProperties(
                PropertyFactory.lineColor(color),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).apply { setFilter(isMain) },
    )
    addLayer(
        CircleLayer(STOP_LAYER, STOPS).withProperties(
            PropertyFactory.circleColor("#FFFFFF"),
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(FAR_ZOOM, 3f),
                    Expression.stop(NEAR_ZOOM, 6f),
                ),
            ),
            PropertyFactory.circleStrokeColor(color),
            PropertyFactory.circleStrokeWidth(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(FAR_ZOOM, 1.5f),
                    Expression.stop(NEAR_ZOOM, 3f),
                ),
            ),
        ),
    )
    addLayer(
        SymbolLayer(STOP_NAME_LAYER, NAME_SOURCE).withProperties(
            PropertyFactory.iconImage(NAME_IMAGE),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
        ),
    )
}

/**
 * The two ends of the route marked as the ends of a journey are, but with the
 * names of their stops written above them, always in view.
 */
private fun Style.markEnds(
    stops: List<Stop>,
    lineColor: Int,
    markers: MapMarkers,
    colors: ArgbColors,
) {
    val first = stops.firstOrNull() ?: return
    val last = stops.last()
    addImage(END_DOT, markers.start(lineColor))
    val raise = markers.dp(END_DOT_RADIUS_DP + LABEL_SPACING_DP)
    addImage(FIRST_LABEL, markers.label(first.name, colors.background, colors.content, raise = raise))
    addImage(LAST_LABEL, markers.label(last.name, colors.background, colors.content, raise = raise))

    fun mark(
        stop: Stop,
        image: String,
        anchor: String,
    ) = Feature.fromGeometry(Point.fromLngLat(stop.location.lon, stop.location.lat)).apply {
        addStringProperty(IMAGE, image)
        addStringProperty(ANCHOR, anchor)
    }
    val features =
        listOf(
            mark(first, END_DOT, Property.ICON_ANCHOR_CENTER),
            mark(last, END_DOT, Property.ICON_ANCHOR_CENTER),
            mark(first, FIRST_LABEL, Property.ICON_ANCHOR_BOTTOM),
            mark(last, LAST_LABEL, Property.ICON_ANCHOR_BOTTOM),
        )
    addSource(GeoJsonSource(ENDS, FeatureCollection.fromFeatures(features)))
    // Under the name of a tapped stop, over everything else.
    addLayerBelow(
        SymbolLayer("line-ends", ENDS).withProperties(
            PropertyFactory.iconImage(Expression.get(IMAGE)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Expression.get(ANCHOR)),
        ),
        STOP_NAME_LAYER,
    )
}

/** Writes the name of the tapped [stop] above it, or clears the name shown when [stop] is null. */
private fun Style.showName(
    stop: Feature?,
    markers: MapMarkers,
    colors: ArgbColors,
) {
    val source = getSourceAs<GeoJsonSource>(NAME_SOURCE) ?: return
    val name = stop?.getStringProperty(NAME)
    val point = stop?.geometry() as? Point
    if (name == null || point == null) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        return
    }
    addImage(NAME_IMAGE, markers.label(name, colors.background, colors.content, raise = markers.dp(NAME_RAISE_DP)))
    source.setGeoJson(Feature.fromGeometry(point))
}

private const val LINES = "line-lines"
private const val ENDS = "line-ends-source"
private const val END_DOT = "line-end-dot"
private const val FIRST_LABEL = "line-first-label"
private const val LAST_LABEL = "line-last-label"
private const val STOP_NAME_LAYER = "line-stop-name"
private const val IMAGE = "image"
private const val ANCHOR = "anchor"

/** The radius of the dots at the ends (see [MapMarkers.start]), and the room between them and their names. */
private const val END_DOT_RADIUS_DP = 10f
private const val LABEL_SPACING_DP = 4f
private const val STOPS = "line-stops-source"
private const val NAME_SOURCE = "line-stop-name-source"
private const val NAME_IMAGE = "line-stop-name"
private const val STOP_LAYER = "line-stops"
private const val COLOR = "color"
private const val BRANCH = "branch"
private const val NAME = "name"
private const val FAR_ZOOM = 11f
private const val NEAR_ZOOM = 15f
private const val NAME_RAISE_DP = 10f
private const val TAP_REACH_DP = 14f
