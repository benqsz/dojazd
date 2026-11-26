package app.dojazd.data

import android.content.Context
import app.dojazd.gtfs.Timetable
import app.dojazd.routing.Endpoint
import app.dojazd.routing.Footpaths
import app.dojazd.routing.JourneyPlanner
import app.dojazd.routing.Plan
import app.dojazd.routing.TransitNetwork
import app.dojazd.routing.WalkNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import java.io.File
import java.io.IOException

/**
 * Plans journeys over the open timetable. Building the network for a date
 * takes a moment, so the most recent one is kept for further searches, and so
 * are the walks between stops, which are the same on every date.
 */
class PlannerRepository(
    private val context: Context,
    private val timetables: TimetableRepository,
) {
    private class Loaded(
        val version: Int,
        val date: LocalDate,
        val walks: WalkNetwork?,
        val network: TransitNetwork,
        val planner: JourneyPlanner,
    )

    private class MeasuredFootpaths(
        val version: Int,
        val walks: WalkNetwork?,
        val footpaths: Footpaths,
    )

    private val mutex = Mutex()

    @Volatile private var loaded: Loaded? = null

    @Volatile private var footpaths: MeasuredFootpaths? = null

    /** Lets go of the network built for the last search; the next search builds it again. */
    fun trim() {
        loaded = null
        footpaths = null
    }

    /**
     * Journeys from [from] to [to] leaving at or after [earliestDeparture], in
     * seconds since midnight of [date]. With [walks], walks to and from stops
     * and between them are measured along streets. Null when no timetable is open.
     */
    suspend fun plan(
        date: LocalDate,
        from: Endpoint,
        to: Endpoint,
        earliestDeparture: Int,
        walks: WalkNetwork?,
    ): Plan? {
        val planner = planner(date, walks) ?: return null
        return withContext(Dispatchers.Default) { planner.plan(from, to, earliestDeparture) }
    }

    /** Builds the network for [date] ahead of the first search, so that the search itself is quick. */
    suspend fun prepare(
        date: LocalDate,
        walks: WalkNetwork?,
    ) {
        planner(date, walks)
    }

    private suspend fun planner(
        date: LocalDate,
        walks: WalkNetwork?,
    ): JourneyPlanner? =
        mutex.withLock {
            val version = timetables.status.value.version
            val current = loaded?.takeIf { it.version == version && it.date == date && it.walks === walks }
            if (current != null) return current.planner
            val network =
                timetables.query { timetable ->
                    val measured =
                        footpaths?.takeIf { it.version == version && it.walks === walks }
                            ?: MeasuredFootpaths(version, walks, storedFootpaths(timetable, walks)).also { footpaths = it }
                    TransitNetwork.load(timetable, date, measured.footpaths)
                } ?: return null
            JourneyPlanner(network, walks = walks).also { loaded = Loaded(version, date, walks, network, it) }
        }

    /**
     * The walks between stops, read from the file they were kept in last time when
     * nothing they depend on has changed since: the timetable (by when it was
     * imported) and the streets, which come with the app (by when it was installed).
     * Measuring them along streets takes seconds on a phone; reading them, a moment.
     */
    private fun storedFootpaths(
        timetable: Timetable,
        walks: WalkNetwork?,
    ): Footpaths {
        val city = timetables.status.value.cityId
        val imported = timetable.meta(Timetable.META_IMPORTED_AT)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        if (city == null || imported == null) return Footpaths.measure(timetable.stops(), walks)
        val folder = File(context.cacheDir, "footpaths")
        val name = "$city-$imported-$installed-${if (walks != null) "streets" else "straight"}.bin"
        val file = File(folder, name)
        try {
            if (file.exists()) return Footpaths.decode(file.readBytes())
        } catch (e: IOException) {
            // Measured again below.
        } catch (e: IllegalArgumentException) {
            // A damaged file: measured again below.
        }
        val measured = Footpaths.measure(timetable.stops(), walks)
        try {
            folder.mkdirs()
            // Only the newest is of any use; older ones would just take up room.
            folder.listFiles()?.filter { it.name.startsWith("$city-") && it.name != name }?.forEach { it.delete() }
            val incoming = File(folder, "$name.new")
            incoming.writeBytes(measured.encode())
            // Without the file the walks are simply measured again next time.
            if (!incoming.renameTo(file)) incoming.delete()
        } catch (e: IOException) {
            // Kept in memory only; measured again on the next start.
        }
        return measured
    }
}
