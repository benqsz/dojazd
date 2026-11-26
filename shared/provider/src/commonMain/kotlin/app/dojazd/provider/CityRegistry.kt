package app.dojazd.provider

import app.dojazd.model.GeoBounds
import app.dojazd.model.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The list of supported cities, loaded from `data/cities.json`. */
@Serializable
data class CityRegistry(
    val schemaVersion: Int,
    val cities: List<CityConfig>,
) {
    fun byId(id: String): CityConfig? = cities.firstOrNull { it.id == id }

    companion object {
        // Unknown keys are ignored so an older app can read a newer registry.
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): CityRegistry = json.decodeFromString(serializer(), text)
    }
}

/** Everything needed to support a city that publishes standard GTFS and, optionally, GTFS-RT. */
@Serializable
data class CityConfig(
    val id: String,
    val name: String,
    val operator: String,
    val timezone: String,
    val center: GeoPoint,
    val bounds: GeoBounds,
    val gtfs: GtfsSource,
    val realtime: RealtimeSource? = null,
    /** Streets, house numbers and named places for searching on the device; null when not built for this city. */
    val addresses: BundledFile? = null,
    /** Vector map of the city (a PMTiles archive) for showing journeys without a connection; null when not built. */
    val map: BundledFile? = null,
    /** The streets and paths that can be walked, for measuring and drawing walks; null when not built. */
    val walks: BundledFile? = null,
    val attribution: Attribution,
)

@Serializable
data class GtfsSource(
    val url: String,
)

/** A file built by `tools/citypack` and shipped with the app; [path] is relative to the `data` directory. */
@Serializable
data class BundledFile(
    val path: String,
)

@Serializable
data class RealtimeSource(
    val tripUpdates: String? = null,
    val vehiclePositions: String? = null,
    val alerts: String? = null,
)

@Serializable
data class Attribution(
    val text: String,
    val url: String,
    val license: String,
    /** The operator's documentation of its open data and API, for those fetching it themselves; null when there is none. */
    val apiDocs: String? = null,
)
