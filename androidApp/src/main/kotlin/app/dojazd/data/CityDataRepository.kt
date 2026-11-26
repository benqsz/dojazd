package app.dojazd.data

import android.content.Context
import androidx.core.content.edit
import app.dojazd.geocode.AddressIndex
import app.dojazd.provider.CityConfig
import app.dojazd.provider.MapArchive
import app.dojazd.routing.WalkNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A city's map file, ready to be drawn. */
data class OfflineMap(
    val file: File,
    val archive: MapArchive,
)

/** The offline data of a city that ships with the app, beyond its timetable: its addresses, its map and its walkable streets. */
class CityDataRepository(
    private val context: Context,
) {
    private class Loaded(
        val cityId: String,
        val index: AddressIndex?,
    )

    // One lock for each kind of data: copying the map on first use must not hold up the addresses or the streets.
    private val addressesLock = Mutex()
    private val walksLock = Mutex()
    private val mapLock = Mutex()

    @Volatile private var loaded: Loaded? = null
    private var loadedMap: Pair<String, OfflineMap>? = null
    private val prefs = context.getSharedPreferences("citydata", Context.MODE_PRIVATE)

    private class LoadedWalks(
        val cityId: String,
        val network: WalkNetwork?,
    )

    @Volatile private var loadedWalks: LoadedWalks? = null

    /** The streets and paths of [city] that can be walked, read into memory on first use and kept; null when the city has none. */
    suspend fun walks(city: CityConfig): WalkNetwork? =
        withContext(Dispatchers.IO) {
            walksLock.withLock {
                loadedWalks?.takeIf { it.cityId == city.id }?.let { return@withLock it.network }
                val network =
                    city.walks?.let { file ->
                        try {
                            WalkNetwork.read(context.assets.open(file.path).use { it.readBytes() })
                        } catch (e: IOException) {
                            null
                        } catch (e: IllegalArgumentException) {
                            null
                        }
                    }
                loadedWalks = LoadedWalks(city.id, network)
                network
            }
        }

    /**
     * The map of [city], or null when the city has none. The map library reads
     * the file at arbitrary positions, which it cannot do inside the app package,
     * so the file is copied to the app's storage on first use and again after
     * every update of the app, which may bring a newer map.
     */
    suspend fun map(city: CityConfig): OfflineMap? =
        withContext(Dispatchers.IO) {
            val asset = city.map?.path ?: return@withContext null
            mapLock.withLock {
                loadedMap?.takeIf { it.first == city.id }?.let { return@withLock it.second }
                val file = File(context.filesDir, "maps/${city.id}.pmtiles")
                val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                val key = "map_copied_${city.id}"
                try {
                    if (!file.exists() || prefs.getLong(key, -1) != installed) {
                        file.parentFile?.mkdirs()
                        // Copied under another name and renamed, so an interrupted copy is never taken for a map.
                        val incoming = File(file.path + ".new")
                        context.assets.open(asset).use { input -> incoming.outputStream().use { input.copyTo(it) } }
                        if (!incoming.renameTo(file)) throw IOException("Cannot store the map")
                        prefs.edit { putLong(key, installed) }
                    }
                    val header = file.inputStream().use { input -> ByteArray(MapArchive.HEADER_SIZE).also { input.read(it) } }
                    OfflineMap(file, MapArchive.parse(header)).also { loadedMap = city.id to it }
                } catch (e: IOException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }

    /**
     * Lets go of the addresses and the streets, which are read again when next
     * needed: for when the phone is short of memory and the app is not on screen.
     */
    fun trim() {
        loaded = null
        loadedWalks = null
    }

    /** The addresses of [city], read into memory on first use and kept; null when the city has none. */
    suspend fun addressIndex(city: CityConfig): AddressIndex? =
        withContext(Dispatchers.IO) {
            addressesLock.withLock {
                loaded?.takeIf { it.cityId == city.id }?.let { return@withLock it.index }
                val index =
                    city.addresses?.let { file ->
                        try {
                            context.assets
                                .open(file.path)
                                .bufferedReader()
                                .useLines { AddressIndex.parse(it) }
                        } catch (e: IOException) {
                            null
                        } catch (e: IllegalArgumentException) {
                            null
                        }
                    }
                loaded = Loaded(city.id, index)
                index
            }
        }
}
