package app.dojazd.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalResources
import app.dojazd.data.OfflineMap
import app.dojazd.model.GeoPoint
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

/** A small, still map with a pin at [point], to show where a place is. */
@Composable
fun PointPreviewMap(
    map: OfflineMap,
    point: GeoPoint,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val color = markerColor().toArgb()
    val pin = remember(resources, color) { MapMarkers(resources).end(color) }
    OfflineMapView(
        map = map,
        modifier = modifier,
        contentKey = point,
        onStyleReady = { libreMap, style, _ ->
            // Only a picture of the place: moving it around would fight with scrolling the dialog.
            libreMap.uiSettings.setAllGesturesEnabled(false)
            style.addImage(PIN, pin)
            style.addSource(GeoJsonSource(SOURCE, Point.fromLngLat(point.lon, point.lat)))
            style.addLayer(
                SymbolLayer(SOURCE, SOURCE).withProperties(
                    PropertyFactory.iconImage(PIN),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
            )
            libreMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), ZOOM))
        },
    )
}

private const val PIN = "preview-pin"
private const val SOURCE = "preview-point"
private const val ZOOM = 15.5
