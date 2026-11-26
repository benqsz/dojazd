package app.dojazd.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.dojazd.model.GeoPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The device's position, from the system's own location service: no Google
 * Play Services. Asks satellite and network positioning at once and takes
 * whichever answers first.
 */
class DeviceLocation(
    private val context: Context,
) {
    private val manager = context.getSystemService(LocationManager::class.java)

    /** True when the user allowed either precise or approximate location. */
    fun hasPermission(): Boolean =
        PERMISSIONS.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** False when location is switched off for the whole phone. */
    fun isEnabled(): Boolean = manager != null && LocationManagerCompat.isLocationEnabled(manager)

    /** Where the device is now, or null when that cannot be found out in reasonable time. */
    suspend fun current(): GeoPoint? {
        if (manager == null || !hasPermission()) return null
        val known = lastKnown()
        if (known != null && known.ageMillis() <= FRESH_MILLIS && known.accuracy <= GOOD_ACCURACY_METERS) return known.toPoint()
        val fix = withTimeoutOrNull(TIMEOUT_MILLIS) { firstFix(manager) }
        return (fix ?: known?.takeIf { it.ageMillis() <= STALE_MILLIS })?.toPoint()
    }

    private fun providers(manager: LocationManager): List<String> {
        val wanted =
            buildList {
                // The system's own blend of satellites and networks, where there is one.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
            }
        return wanted.filter { manager.isProviderEnabled(it) }
    }

    private fun lastKnown(): Location? =
        // The passive provider also keeps what other apps asked for lately.
        (providers(manager ?: return null) + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider ->
                try {
                    manager.getLastKnownLocation(provider)
                } catch (e: SecurityException) {
                    null
                }
            }.minByOrNull { it.ageMillis() }

    private suspend fun firstFix(manager: LocationManager): Location? =
        suspendCancellableCoroutine { continuation ->
            val providers = providers(manager)
            if (providers.isEmpty()) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val signals = providers.map { CancellationSignal() }
            continuation.invokeOnCancellation { signals.forEach(CancellationSignal::cancel) }
            // The answers all come on the main thread, so this needs no lock.
            var waiting = providers.size
            providers.forEachIndexed { index, provider ->
                try {
                    LocationManagerCompat.getCurrentLocation(
                        manager,
                        provider,
                        signals[index],
                        ContextCompat.getMainExecutor(context),
                    ) { location ->
                        if (!continuation.isActive) return@getCurrentLocation
                        waiting--
                        if (location != null) {
                            signals.forEach(CancellationSignal::cancel)
                            continuation.resume(location)
                        } else if (waiting == 0) {
                            continuation.resume(null)
                        }
                    }
                } catch (e: SecurityException) {
                    waiting--
                    if (waiting == 0 && continuation.isActive) continuation.resume(null)
                }
            }
        }

    private fun Location.ageMillis(): Long = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000

    private fun Location.toPoint() = GeoPoint(latitude, longitude)

    companion object {
        /** Precise first: on Android 12 and later the user may still allow only an approximate position. */
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        /** A position this recent and this exact is used at once, without asking for a new one. */
        private const val FRESH_MILLIS = 60_000L
        private const val GOOD_ACCURACY_METERS = 100f

        /** Satellites need a while to be found indoors, but nobody waits for them much longer than this... */
        private const val TIMEOUT_MILLIS = 20_000L

        /** ...and when they are not found, a position up to this old is better than none. */
        private const val STALE_MILLIS = 30 * 60_000L
    }
}
