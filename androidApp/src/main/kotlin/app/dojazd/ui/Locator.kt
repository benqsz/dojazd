package app.dojazd.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.dojazd.R
import app.dojazd.data.DeviceLocation
import app.dojazd.model.GeoPoint
import app.dojazd.provider.CityConfig
import kotlinx.coroutines.launch

/** Finds where the user is, asking for permission first when needed. */
class Locator internal constructor(
    /** True while a position is being looked for. */
    val locating: Boolean,
    /** Why the last search found nothing, to be shown where it was asked for; null when it did, or none has run. */
    val problem: String?,
    private val start: (onFound: (GeoPoint) -> Unit) -> Unit,
) {
    /**
     * Calls [onFound] with the user's position once known. Whatever stands in
     * the way (no permission, location switched off, no position found, a
     * position outside the city) is put in [problem] instead, and said in a short message.
     */
    fun locate(onFound: (GeoPoint) -> Unit) = start(onFound)
}

/** A [Locator] for [city]; the search stops when the caller leaves the screen. */
@Composable
fun rememberLocator(city: CityConfig): Locator {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val device = remember { DeviceLocation(context.applicationContext) }
    var locating by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    // Waits here while the system asks the user for permission.
    var pending by remember { mutableStateOf<((GeoPoint) -> Unit)?>(null) }

    val locationOff = stringResource(R.string.location_off)
    val locationUnknown = stringResource(R.string.location_unknown)
    val outsideCity = stringResource(R.string.location_outside_city, city.name)
    val locationDenied = stringResource(R.string.location_denied)

    fun fail(text: String) {
        problem = text
        context.showMessage(text, long = true)
    }

    fun run(onFound: (GeoPoint) -> Unit) {
        problem = null
        if (!device.isEnabled()) {
            fail(locationOff)
            try {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                // Some systems have no such screen; the message has to do.
            }
            return
        }
        locating = true
        scope.launch {
            try {
                val point = device.current()
                when {
                    point == null -> fail(locationUnknown)
                    point !in city.bounds -> fail(outsideCity)
                    else -> onFound(point)
                }
            } finally {
                locating = false
            }
        }
    }

    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val onFound = pending
            pending = null
            when {
                onFound == null -> Unit
                granted.values.any { it } -> run(onFound)
                else -> fail(locationDenied)
            }
        }

    return Locator(locating, problem) { onFound ->
        when {
            locating -> {}

            device.hasPermission() -> {
                run(onFound)
            }

            else -> {
                problem = null
                pending = onFound
                permission.launch(DeviceLocation.PERMISSIONS)
            }
        }
    }
}
