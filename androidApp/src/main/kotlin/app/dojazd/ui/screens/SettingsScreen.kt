package app.dojazd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import app.dojazd.R
import app.dojazd.data.AppSettings
import app.dojazd.data.CityDataRepository
import app.dojazd.data.Network
import app.dojazd.data.PlacesRepository
import app.dojazd.data.RecentJourneysRepository
import app.dojazd.data.RecentPlacesRepository
import app.dojazd.data.TimetableRepository
import app.dojazd.provider.CityConfig
import app.dojazd.ui.BackButton
import app.dojazd.ui.Screen
import app.dojazd.ui.linkStyles
import app.dojazd.ui.theme.Spacing
import kotlinx.serialization.Serializable

/** The settings are split into these; null stands for the list of them. */
@Serializable
enum class SettingsSection { APPEARANCE, PLACES, TIMETABLE, HISTORY, FETCH_LOG, ABOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** The part of the settings to show; null for the list of all parts. */
    section: SettingsSection?,
    city: CityConfig,
    settings: AppSettings,
    repository: TimetableRepository,
    cityData: CityDataRepository,
    places: PlacesRepository,
    recentPlaces: RecentPlacesRepository,
    recentJourneys: RecentJourneysRepository,
    network: Network,
    onOpen: (Screen) -> Unit,
    onBack: () -> Unit,
) {
    // Asked for from the top bar of the saved places, carried out inside them.
    var addingPlace by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(section?.title() ?: R.string.settings)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (section == SettingsSection.PLACES) {
                        IconButton(onClick = { addingPlace = true }) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.place_add))
                        }
                    }
                    if (section == SettingsSection.FETCH_LOG) {
                        val log by network.log.collectAsState()
                        TextButton(onClick = network::clearLog, enabled = log.isNotEmpty()) { Text(stringResource(R.string.clear)) }
                    }
                },
            )
        },
    ) { padding ->
        if (section == SettingsSection.FETCH_LOG) {
            // A long list of its own, so it scrolls by itself rather than inside the column below.
            FetchLog(network, Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
        ) {
            when (section) {
                null -> {
                    SettingsMenu(onOpen)
                }

                SettingsSection.APPEARANCE -> {
                    AppearanceSettings(settings)
                }

                SettingsSection.PLACES -> {
                    PlacesSection(
                        city = city,
                        timetables = repository,
                        cityData = cityData,
                        repository = places,
                        recentPlaces = recentPlaces,
                        adding = addingPlace,
                        onAddingEnd = { addingPlace = false },
                    )
                }

                SettingsSection.TIMETABLE -> {
                    TimetableSettings(city, settings, repository)
                }

                SettingsSection.HISTORY -> {
                    HistorySettings(city, settings, places, recentPlaces, recentJourneys)
                }

                SettingsSection.ABOUT -> {
                    AboutSettings()
                }

                // Shown above, outside this column.
                SettingsSection.FETCH_LOG -> {}
            }
        }
    }
}

private fun SettingsSection.title(): Int =
    when (this) {
        SettingsSection.APPEARANCE -> R.string.settings_appearance
        SettingsSection.PLACES -> R.string.section_saved_places
        SettingsSection.TIMETABLE -> R.string.settings_timetable
        SettingsSection.HISTORY -> R.string.settings_history
        SettingsSection.FETCH_LOG -> R.string.settings_fetch_log
        SettingsSection.ABOUT -> R.string.settings_about
    }

/** The parts of the settings. */
@Composable
private fun ColumnScope.SettingsMenu(onOpen: (Screen) -> Unit) {
    MenuItem(R.string.settings_appearance) { onOpen(Screen.Settings(SettingsSection.APPEARANCE)) }
    MenuItem(R.string.section_saved_places) { onOpen(Screen.Settings(SettingsSection.PLACES)) }
    MenuItem(R.string.settings_timetable) { onOpen(Screen.Settings(SettingsSection.TIMETABLE)) }
    MenuItem(R.string.settings_history) { onOpen(Screen.Settings(SettingsSection.HISTORY)) }
    MenuItem(R.string.settings_fetch_log) { onOpen(Screen.Settings(SettingsSection.FETCH_LOG)) }
    MenuItem(R.string.settings_about) { onOpen(Screen.Settings(SettingsSection.ABOUT)) }

    HorizontalDivider(Modifier.padding(top = Spacing.large))
    Text(
        stringResource(R.string.made_by, AUTHOR),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun MenuItem(
    title: Int,
    onClick: () -> Unit,
) {
    ListItem(modifier = Modifier.clickable(onClick = onClick)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    }
}

private const val AUTHOR = "benqsz"

internal const val SOURCE_URL = "https://github.com/benqsz/dojazd"
