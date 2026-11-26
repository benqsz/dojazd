package app.dojazd

import android.app.Application
import android.content.Context
import app.dojazd.data.AppSettings
import app.dojazd.data.CityDataRepository
import app.dojazd.data.LiveRepository
import app.dojazd.data.Network
import app.dojazd.data.PlacesRepository
import app.dojazd.data.PlannerRepository
import app.dojazd.data.RecentJourneysRepository
import app.dojazd.data.RecentPlacesRepository
import app.dojazd.data.TimetableRepository
import app.dojazd.provider.CityRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class DojazdApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * Once the app has left the screen and the phone wants memory back, what can
     * be built again from the files on the phone is let go: the street network,
     * the addresses and the planner's timetable for the day, some twenty megabytes.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            container.cityData.trim()
            container.planner.trim()
        }
    }
}

/** Process-wide objects, created once and handed to the UI. */
class AppContainer(
    context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val registry: CityRegistry =
        context.assets
            .open("cities.json")
            .bufferedReader()
            .use { it.readText() }
            .let(CityRegistry::parse)

    val settings = AppSettings(context)

    // Identifies the app to data providers, as their terms of use ask.
    private val userAgent =
        "Dojazd/${context.packageManager.getPackageInfo(context.packageName, 0).versionName} " +
            "(+https://github.com/benqsz/dojazd)"

    /** Every request to the network goes through here. */
    val network = Network(File(context.filesDir, "fetch-log.json"), scope, userAgent) { settings.data.value.offline }

    val timetables = TimetableRepository(context, scope, network, settings)

    val cityData = CityDataRepository(context)

    val places = PlacesRepository(context, scope)

    val recentPlaces = RecentPlacesRepository(context) { settings.privacy.value.history }

    val recentJourneys = RecentJourneysRepository(context) { settings.privacy.value.history }

    val planner = PlannerRepository(context, timetables)

    val live = LiveRepository(scope, network, settings)
}
