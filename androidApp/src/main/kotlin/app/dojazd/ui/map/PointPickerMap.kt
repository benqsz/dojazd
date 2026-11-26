package app.dojazd.ui.map

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.dojazd.R
import app.dojazd.data.OfflineMap
import app.dojazd.model.GeoPoint
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/** What a [PointPickerMap] is looking at, for its caller to ask which point the pin is on. */
@Stable
class PointPickerState internal constructor() {
    internal var map by mutableStateOf<MapLibreMap?>(null)

    /** False until the map is on screen and can be asked for [center]. */
    val ready: Boolean get() = map != null

    /** The point under the pin, or null while the map is not [ready]. */
    fun center(): GeoPoint? = map?.cameraPosition?.target?.let { GeoPoint(it.latitude, it.longitude) }
}

@Composable
fun rememberPointPickerState(): PointPickerState = remember { PointPickerState() }

/**
 * The city's map with a pin fixed in the middle of it: the user moves the map
 * under the pin, and [state] tells which point it is on. The map starts at
 * [start]; [content] is laid over it.
 */
@Composable
fun PointPickerMap(
    map: OfflineMap,
    start: GeoPoint,
    state: PointPickerState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    OfflineMapView(
        map = map,
        modifier = modifier,
        onStyleReady = { ready, _, _ ->
            // Only the first time: a change of theme reloads the style but must not move the map.
            if (state.map == null) ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(start.lat, start.lon), START_ZOOM))
            state.map = ready
        },
    ) {
        // Lifted by half its height, so that the tip of the pin marks the middle of the map.
        Icon(
            painterResource(R.drawable.ic_location_on),
            contentDescription = null,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .size(PIN_SIZE)
                    .offset(y = -PIN_SIZE / 2),
            tint = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

private const val START_ZOOM = 13.0
private val PIN_SIZE = 48.dp
