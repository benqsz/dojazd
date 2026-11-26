package app.dojazd.data

import app.dojazd.provider.CityConfig
import app.dojazd.realtime.Alert
import app.dojazd.realtime.FeedMessage
import app.dojazd.realtime.GtfsRealtime
import app.dojazd.realtime.LiveTrips
import app.dojazd.realtime.ProtoFormatException
import app.dojazd.realtime.VehiclePosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import java.io.IOException

/** The state of one live feed as a screen sees it. */
sealed interface Live<out T> {
    /** Live data is switched off in the settings, or the city publishes none of this kind. */
    data object Off : Live<Nothing>

    data object Loading : Live<Nothing>

    /** Nothing could be fetched yet. */
    data class Failed(
        val message: String,
    ) : Live<Nothing>

    data class Ready<T>(
        val value: T,
        /** When the producer last updated the data (or, when it does not say, when it was fetched), in epoch milliseconds. */
        val updatedAt: Long,
        /** Set when the latest refresh failed; [value] is then from an earlier one. */
        val error: String? = null,
    ) : Live<T> {
        /** False when the data is too old to show as current: the producer or the connection has stopped. */
        fun isFresh(now: Long = System.currentTimeMillis()): Boolean = now - updatedAt <= STALE_MILLIS
    }

    companion object {
        const val STALE_MILLIS = 3 * 60_000L
    }
}

/**
 * Delays, vehicle positions and service alerts, read straight from the city's
 * GTFS-RT feeds. A feed is fetched only while some screen collects it, and
 * stops a few seconds after the last one goes away; nothing runs in the background.
 */
class LiveRepository(
    private val scope: CoroutineScope,
    private val network: Network,
    settings: AppSettings,
) {
    private val feeds = HashMap<String, Flow<Live<Any?>>>()

    /** False when the user switched live data off, or the whole app offline. */
    val enabled: Flow<Boolean> = settings.data.map { it.live && !it.offline }.distinctUntilChanged()

    /** Delays and cancellations of trips. */
    fun trips(city: CityConfig): Flow<Live<LiveTrips>> =
        feed(FetchKind.TRIP_UPDATES, city.realtime?.tripUpdates, TRIPS_INTERVAL_MILLIS) { LiveTrips(it.tripUpdates) }

    fun vehicles(city: CityConfig): Flow<Live<List<VehiclePosition>>> =
        feed(FetchKind.VEHICLES, city.realtime?.vehiclePositions, VEHICLES_INTERVAL_MILLIS) { it.vehicles }

    fun alerts(city: CityConfig): Flow<Live<List<Alert>>> =
        feed(FetchKind.ALERTS, city.realtime?.alerts, ALERTS_INTERVAL_MILLIS) { it.alerts }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun <T> feed(
        kind: FetchKind,
        url: String?,
        intervalMillis: Long,
        transform: (FeedMessage) -> T,
    ): Flow<Live<T>> {
        if (url == null) return flowOf(Live.Off)
        val shared =
            synchronized(feeds) {
                feeds.getOrPut(url) {
                    poll(kind, url, intervalMillis, transform).shareIn(
                        scope,
                        // What was fetched last is dropped with the last screen: the next one is not shown stale data first.
                        SharingStarted.WhileSubscribed(STOP_DELAY_MILLIS, replayExpirationMillis = 0),
                        replay = 1,
                    )
                }
            } as Flow<Live<T>>
        return enabled.flatMapLatest { on -> if (on) shared else flowOf(Live.Off) }
    }

    private fun <T> poll(
        kind: FetchKind,
        url: String,
        intervalMillis: Long,
        transform: (FeedMessage) -> T,
    ): Flow<Live<T>> =
        flow {
            var etag: String? = null
            var last: Live.Ready<T>? = null
            while (true) {
                val next: Live<T> =
                    try {
                        val response = withContext(Dispatchers.IO) { network.fetch(kind, url, etag.takeIf { last != null }) }
                        etag = response.etag
                        val bytes = response.bytes
                        val previous = last
                        if (bytes == null && previous != null) {
                            previous.copy(error = null)
                        } else {
                            withContext(Dispatchers.Default) {
                                val feed = GtfsRealtime.parse(bytes ?: ByteArray(0))
                                val now = System.currentTimeMillis()
                                // A clock running ahead on the producer's side must not make old data look new.
                                val updatedAt = feed.timestamp?.let { minOf(it * 1000, now) } ?: now
                                Live.Ready(transform(feed), updatedAt)
                            }
                        }
                    } catch (e: IOException) {
                        failure(last, e)
                    } catch (e: ProtoFormatException) {
                        failure(last, e)
                    }
                if (next is Live.Ready) last = next
                emit(next)
                delay(intervalMillis)
            }
        }

    private fun <T> failure(
        last: Live.Ready<T>?,
        error: Exception,
    ): Live<T> {
        val message = error.message ?: error.javaClass.simpleName
        return last?.copy(error = message) ?: Live.Failed(message)
    }

    private companion object {
        const val TRIPS_INTERVAL_MILLIS = 30_000L
        const val VEHICLES_INTERVAL_MILLIS = 15_000L
        const val ALERTS_INTERVAL_MILLIS = 5 * 60_000L

        /** Moving between two screens that show the same feed does not restart it. */
        const val STOP_DELAY_MILLIS = 5_000L
    }
}
