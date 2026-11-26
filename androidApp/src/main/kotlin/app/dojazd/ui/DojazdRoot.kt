package app.dojazd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.dojazd.AppContainer
import app.dojazd.data.TimetableRepository.UpdatePolicy
import app.dojazd.provider.CityConfig
import app.dojazd.ui.screens.AlertsScreen
import app.dojazd.ui.screens.CitySelectScreen
import app.dojazd.ui.screens.HomeScreen
import app.dojazd.ui.screens.JourneysScreen
import app.dojazd.ui.screens.LineAtStopScreen
import app.dojazd.ui.screens.LineScreen
import app.dojazd.ui.screens.SettingsScreen
import app.dojazd.ui.screens.SettingsSection
import app.dojazd.ui.screens.StopDeparturesScreen
import app.dojazd.ui.screens.TimetableSetupScreen
import app.dojazd.ui.theme.DojazdTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Destinations inside a city. Ids refer to rows of the currently open timetable. */
@Serializable
sealed interface Screen {
    @Serializable
    data object Home : Screen

    @Serializable
    data class Line(
        val lineId: Long,
    ) : Screen

    @Serializable
    data class StopDepartures(
        val title: String,
        val stopIds: List<Long>,
    ) : Screen

    @Serializable
    data class LineAtStop(
        val lineId: Long,
        val stopId: Long,
    ) : Screen

    @Serializable
    data class Journeys(
        val from: PlanPoint,
        val to: PlanPoint,
        /** The day to leave on, as days since 1970-01-01, with [seconds] after its midnight; both null to leave now. */
        val epochDay: Long? = null,
        val seconds: Int? = null,
    ) : Screen

    /** The list of settings, or one [section] of them. */
    @Serializable
    data class Settings(
        val section: SettingsSection? = null,
    ) : Screen

    @Serializable
    data object Alerts : Screen
}

/** One end of a planned journey. Stop ids refer to rows of the currently open timetable. */
@Serializable
data class PlanPoint(
    val name: String,
    val lat: Double,
    val lon: Double,
    /** The boarding points of the stop when the point is a stop; empty for any other place. */
    val stopIds: List<Long> = emptyList(),
    /** True when the point is where the user was when choosing it. */
    val myLocation: Boolean = false,
)

@Composable
fun DojazdRoot(container: AppContainer) {
    val theme by container.settings.theme.collectAsState()
    val cityId by container.settings.cityId.collectAsState()
    val city = cityId?.let(container.registry::byId)

    val privacy by container.settings.privacy.collectAsState()

    DojazdTheme(theme) {
        KeyboardPrivacy(learning = privacy.keyboardLearning) {
            if (city == null) {
                val data by container.settings.data.collectAsState()
                CitySelectScreen(
                    cities = container.registry.cities,
                    offline = data.offline,
                    onOfflineChange = { container.settings.setData(data.copy(offline = it)) },
                    onSelect = { container.settings.selectCity(it.id) },
                )
            } else {
                CityContent(container, city)
            }
        }
    }
}

@Composable
private fun CityContent(
    container: AppContainer,
    city: CityConfig,
) {
    val status by container.timetables.status.collectAsState()
    val data by container.settings.data.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(city.id, data, lifecycleOwner) {
        val policy = UpdatePolicy(automatic = data.automatic, intervalSeconds = data.refreshMinutes * 60L)
        // Re-evaluated every minute while the app is on screen, so short refresh intervals
        // take effect without restarting it. The repository decides whether a check is due.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                container.timetables.activate(city, policy)
                delay(RECHECK_MILLIS)
            }
        }
    }

    // For the moment it takes to open the stored timetable nothing is shown, rather than the screen
    // that asks to download one, which would flash by at every start.
    if (status.cityId != city.id || status.opening) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    if (!status.ready) {
        TimetableSetupScreen(
            city = city,
            status = status,
            manualDownload = !data.automatic,
            offline = data.offline,
            onOfflineChange = { container.settings.setData(data.copy(offline = it)) },
            repository = container.timetables,
            onChangeCity = { container.settings.selectCity(null) },
        )
        return
    }

    val backStack = rememberSaveable(saver = backStackSaver) { mutableStateListOf(Screen.Home) }
    val stateHolder = rememberSaveableStateHolder()

    // Row ids change when a new timetable is imported, so deeper screens would point at the wrong rows.
    // Told apart by when it was imported, which holds across restarts of the app: a counter would start anew.
    var seenTimetable by rememberSaveable { mutableStateOf(status.stamp) }
    LaunchedEffect(status.stamp) {
        if (seenTimetable != status.stamp) {
            seenTimetable = status.stamp
            backStack.drop(1).forEach { stateHolder.removeState(it.toString()) }
            backStack.removeRange(1, backStack.size)
        }
    }

    // Which way the next change of screen goes, so that it can move the right way.
    var forward by remember { mutableStateOf(true) }

    fun open(screen: Screen) {
        forward = true
        backStack.add(screen)
    }

    fun back() {
        forward = false
        if (backStack.size > 1) stateHolder.removeState(backStack.removeAt(backStack.lastIndex).toString())
    }

    BackHandler(enabled = backStack.size > 1) { back() }

    AnimatedContent(
        targetState = backStack.last(),
        // Material's shared axis: the new screen comes in from the side it lies on, the old one hands over
        // quickly, and both always stand on the same background, so nothing shows through in between.
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        transitionSpec = {
            val direction = if (forward) 1 else -1
            val enter =
                fadeIn(tween(ENTER_MILLIS, delayMillis = EXIT_MILLIS, easing = LinearOutSlowInEasing)) +
                    slideInHorizontally(tween(SLIDE_MILLIS, easing = FastOutSlowInEasing)) { direction * it / SLIDE_SHARE }
            val exit =
                fadeOut(tween(EXIT_MILLIS, easing = FastOutLinearInEasing)) +
                    slideOutHorizontally(tween(SLIDE_MILLIS, easing = FastOutSlowInEasing)) { -direction * it / SLIDE_SHARE }
            enter togetherWith exit
        },
        label = "screen",
    ) { screen ->
        stateHolder.SaveableStateProvider(key = screen.toString()) {
            ScreenContent(screen, container, city, onOpen = ::open, onBack = ::back)
        }
    }
}

/** What [screen] shows, handed what it needs of the app. */
@Composable
private fun ScreenContent(
    screen: Screen,
    container: AppContainer,
    city: CityConfig,
    onOpen: (Screen) -> Unit,
    onBack: () -> Unit,
) {
    when (screen) {
        Screen.Home -> {
            HomeScreen(
                city = city,
                repository = container.timetables,
                planner = container.planner,
                cityData = container.cityData,
                places = container.places,
                recentPlaces = container.recentPlaces,
                recentJourneys = container.recentJourneys,
                live = container.live,
                onOpen = onOpen,
            )
        }

        is Screen.Journeys -> {
            JourneysScreen(
                request = screen,
                city = city,
                timetables = container.timetables,
                planner = container.planner,
                cityData = container.cityData,
                live = container.live,
                onBack = onBack,
            )
        }

        is Screen.Line -> {
            LineScreen(
                lineId = screen.lineId,
                city = city,
                repository = container.timetables,
                cityData = container.cityData,
                live = container.live,
                onOpen = onOpen,
                onBack = onBack,
            )
        }

        is Screen.StopDepartures -> {
            StopDeparturesScreen(
                title = screen.title,
                stopIds = screen.stopIds,
                city = city,
                repository = container.timetables,
                live = container.live,
                onBack = onBack,
            )
        }

        is Screen.LineAtStop -> {
            LineAtStopScreen(
                lineId = screen.lineId,
                stopId = screen.stopId,
                city = city,
                repository = container.timetables,
                onOpen = onOpen,
                onBack = onBack,
            )
        }

        is Screen.Settings -> {
            SettingsScreen(
                section = screen.section,
                city = city,
                settings = container.settings,
                repository = container.timetables,
                cityData = container.cityData,
                places = container.places,
                recentPlaces = container.recentPlaces,
                recentJourneys = container.recentJourneys,
                network = container.network,
                onOpen = onOpen,
                onBack = onBack,
            )
        }

        Screen.Alerts -> {
            AlertsScreen(
                city = city,
                repository = container.timetables,
                live = container.live,
                onBack = onBack,
            )
        }
    }
}

private const val RECHECK_MILLIS = 60_000L

private const val SLIDE_MILLIS = 300
private const val EXIT_MILLIS = 90
private const val ENTER_MILLIS = 210

/** Screens slide by this fraction of their width: enough to show the direction, not so much that it drags. */
private const val SLIDE_SHARE = 10

private val backStackSaver =
    listSaver<SnapshotStateList<Screen>, String>(
        save = { stack -> stack.map { Json.encodeToString(Screen.serializer(), it) } },
        restore = { saved -> saved.map { Json.decodeFromString(Screen.serializer(), it) }.toMutableStateList() },
    )
