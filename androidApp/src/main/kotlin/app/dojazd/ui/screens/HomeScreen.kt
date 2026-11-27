package app.dojazd.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.dojazd.R
import app.dojazd.data.CityDataRepository
import app.dojazd.data.Live
import app.dojazd.data.LiveRepository
import app.dojazd.data.PlacesRepository
import app.dojazd.data.PlannerRepository
import app.dojazd.data.RecentJourneysRepository
import app.dojazd.data.RecentPlacesRepository
import app.dojazd.data.TimetableRepository
import app.dojazd.model.Line
import app.dojazd.model.SearchText
import app.dojazd.model.VehicleType
import app.dojazd.provider.CityConfig
import app.dojazd.ui.LoadingBox
import app.dojazd.ui.MessageBox
import app.dojazd.ui.Screen
import app.dojazd.ui.SectionHeader
import app.dojazd.ui.lineBorder
import app.dojazd.ui.lineColors
import app.dojazd.ui.rememberServiceAlerts
import app.dojazd.ui.rememberTimetableQuery
import app.dojazd.ui.theme.ControlSize
import app.dojazd.ui.theme.IconSize
import app.dojazd.ui.theme.Spacing
import app.dojazd.ui.vehicleIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    city: CityConfig,
    repository: TimetableRepository,
    planner: PlannerRepository,
    cityData: CityDataRepository,
    places: PlacesRepository,
    recentPlaces: RecentPlacesRepository,
    recentJourneys: RecentJourneysRepository,
    live: LiveRepository,
    onOpen: (Screen) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val status by repository.status.collectAsState()
    val liveEnabled by live.enabled.collectAsState(initial = false)
    val alerts by rememberServiceAlerts(city, live, repository)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(city.name) },
                    actions = {
                        if (liveEnabled && city.realtime?.alerts != null) {
                            val count = (alerts as? Live.Ready)?.value?.size ?: 0
                            IconButton(onClick = { onOpen(Screen.Alerts) }) {
                                BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
                                    Icon(painterResource(R.drawable.ic_warning), contentDescription = stringResource(R.string.alerts))
                                }
                            }
                        }
                        IconButton(onClick = { onOpen(Screen.Settings()) }) {
                            Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_journey)) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_lines)) })
                }
                // A newer timetable is being fetched in the background; the current one stays usable.
                if (status.work != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        // Taken off what is left for the content, so that room made for the keyboard does not count the bars twice.
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            when (tab) {
                0 -> PlannerTab(city, repository, planner, cityData, places, recentPlaces, recentJourneys, onOpen)
                else -> LinesTab(repository, onOpen)
            }
        }
    }
}

@Composable
private fun LinesTab(
    repository: TimetableRepository,
    onOpen: (Screen) -> Unit,
) {
    val lines by rememberTimetableQuery(repository) { it.lines() }
    var query by rememberSaveable { mutableStateOf("") }
    // Only lines of this kind of vehicle; null for all of them.
    var type by rememberSaveable { mutableStateOf<VehicleType?>(null) }
    // The kinds the city has, in the order its lines first show them; a filter is offered only when there is a choice.
    val types = remember(lines) { lines?.map { it.type }?.distinct().orEmpty() }
    // Groups appear in the order the operator lists them, e.g. express lines, trams, buses, night lines.
    val groups =
        remember(lines, query, type) {
            val key = SearchText.fold(query.trim())
            lines
                ?.filter { (type == null || it.type == type) && (key.isEmpty() || matches(it, key)) }
                ?.groupBy { it.type to it.category }
                ?.map { (group, groupLines) -> LineGroup(group.first, group.second, groupLines) }
        }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.large, end = Spacing.large, top = Spacing.small),
            placeholder = { Text(stringResource(R.string.search_lines)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(painterResource(R.drawable.ic_clear), contentDescription = stringResource(R.string.clear))
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
        )
        if (types.size > 1) {
            Row(
                modifier =
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = Spacing.large, end = Spacing.large, top = Spacing.small),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                types.forEach { option ->
                    FilterChip(
                        selected = type == option,
                        // Tapping the chosen kind again shows all lines once more.
                        onClick = { type = if (type == option) null else option },
                        label = { Text(vehicleTypeName(option)) },
                        leadingIcon = {
                            Icon(
                                painterResource(vehicleIcon(option)),
                                contentDescription = null,
                                Modifier.size(IconSize.small),
                            )
                        },
                    )
                }
            }
        }
        when {
            groups == null -> LoadingBox()
            groups.isEmpty() -> MessageBox(stringResource(R.string.no_lines_found))
            else -> LineGrid(groups, onOpen)
        }
    }
}

/**
 * True when [line] is what a passenger typing [key] (already folded) is after:
 * a number it starts with, or a place on its route.
 */
private fun matches(
    line: Line,
    key: String,
): Boolean = SearchText.fold(line.name).startsWith(key) || SearchText.fold(line.longName).contains(key)

/** The lines of one kind of vehicle that the operator files together, such as express or night buses. */
private class LineGroup(
    val type: VehicleType,
    val category: String,
    val lines: List<Line>,
)

@Composable
private fun LineGrid(
    groups: List<LineGroup>,
    onOpen: (Screen) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 64.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.large),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        groups.forEach { group ->
            item(key = "group-${group.type}-${group.category}", span = { GridItemSpan(maxLineSpan) }) {
                val typeName = vehicleTypeName(group.type)
                SectionHeader(
                    text = if (group.category.isEmpty()) typeName else "$typeName · ${group.category}",
                    modifier = Modifier.padding(top = Spacing.small),
                    icon = vehicleIcon(group.type),
                )
            }
            items(group.lines, key = { it.id }) { line ->
                LineTile(line, onClick = { onOpen(Screen.Line(line.id)) })
            }
        }
    }
}

@Composable
private fun LineTile(
    line: Line,
    onClick: () -> Unit,
) {
    val (background, content) = lineColors(line)
    Surface(
        onClick = onClick,
        modifier = Modifier.heightIn(min = ControlSize.touchTarget),
        shape = MaterialTheme.shapes.medium,
        color = background,
        contentColor = content,
        border = lineBorder(background),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(line.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun vehicleTypeName(type: VehicleType): String =
    stringResource(
        when (type) {
            VehicleType.TRAM -> R.string.vehicle_tram
            VehicleType.BUS -> R.string.vehicle_bus
            VehicleType.RAIL -> R.string.vehicle_rail
            VehicleType.METRO -> R.string.vehicle_metro
            VehicleType.FERRY -> R.string.vehicle_ferry
            VehicleType.OTHER -> R.string.vehicle_other
        },
    )
