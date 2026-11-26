package app.dojazd.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dojazd.R
import app.dojazd.data.CityDataRepository
import app.dojazd.data.Live
import app.dojazd.data.LiveRepository
import app.dojazd.data.OfflineMap
import app.dojazd.data.TimetableRepository
import app.dojazd.model.Line
import app.dojazd.model.LineDirection
import app.dojazd.model.RouteLayout
import app.dojazd.model.RouteRow
import app.dojazd.provider.CityConfig
import app.dojazd.ui.AlertCard
import app.dojazd.ui.Choices
import app.dojazd.ui.FullScreenDialog
import app.dojazd.ui.LoadingBox
import app.dojazd.ui.Screen
import app.dojazd.ui.lineColors
import app.dojazd.ui.map.LineMap
import app.dojazd.ui.map.LinePath
import app.dojazd.ui.rememberServiceAlerts
import app.dojazd.ui.rememberTimetableQuery
import app.dojazd.ui.theme.Spacing
import kotlinx.datetime.plus

private class LineDetails(
    val line: Line,
    val directions: List<LineDirection>,
)

/**
 * A line's route: its directions and the stops along each, drawn as a rail with
 * a dot per stop. Stops only some runs call at hang off it on darker branches,
 * which the user can hide. Alerts about the line come first; the route can be
 * seen on the map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineScreen(
    lineId: Long,
    city: CityConfig,
    repository: TimetableRepository,
    cityData: CityDataRepository,
    live: LiveRepository,
    onOpen: (Screen) -> Unit,
    onBack: () -> Unit,
) {
    val details by rememberTimetableQuery(repository, lineId) { timetable ->
        timetable.line(lineId)?.let { LineDetails(it, timetable.directions(lineId)) }
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val directions = details?.directions.orEmpty()
    val direction = directions.getOrNull(selected) ?: directions.firstOrNull()
    val alerts by rememberServiceAlerts(city, live, repository)
    val lineAlerts = (alerts as? Live.Ready)?.value.orEmpty().filter { it.names(lineId) }
    // The stops of other runs are folded away until asked for.
    var showVariants by rememberSaveable { mutableStateOf(false) }
    val map by produceState<OfflineMap?>(initialValue = null, city.id) { value = cityData.map(city) }
    var onMap by rememberSaveable { mutableStateOf(false) }

    // Laid out once for a direction, with and without the stops of its variants.
    val wholeRoute = remember(direction) { direction?.let { RouteLayout.rows(it.stops, it.variants.map { variant -> variant.stops }) } }
    val mainRoute = remember(direction) { direction?.let { RouteLayout.rows(it.stops, emptyList()) } }
    val hasBranches = remember(wholeRoute) { wholeRoute?.any { it is RouteRow.Branch } == true }
    val rows = if (showVariants) wholeRoute else mainRoute
    val lineColor = details?.line?.let { lineColors(it).background } ?: MaterialTheme.colorScheme.primary
    val branchColor = lerp(lineColor, Color.Black, BRANCH_DARKENING)

    val line = details?.line
    val shownMap = map
    if (onMap && line != null && direction != null && shownMap != null) {
        LineMapDialog(
            line = line,
            direction = direction,
            showVariants = showVariants,
            map = shownMap,
            repository = repository,
            lineColor = lineColor,
            branchColor = branchColor,
            onDismiss = { onMap = false },
        )
    }

    Scaffold(
        topBar = {
            Column {
                LineHeader(details?.line, details?.line?.longName, onBack) {
                    if (map != null && direction != null) {
                        IconButton(onClick = { onMap = true }) {
                            Icon(painterResource(R.drawable.ic_map), contentDescription = stringResource(R.string.show_on_map))
                        }
                    }
                }
                if (direction != null) {
                    DirectionBar(
                        headsign = direction.headsign,
                        onChange =
                            if (directions.size > 1) {
                                { selected = (directions.indexOf(direction) + 1) % directions.size }
                            } else {
                                null
                            },
                    )
                }
                // Only where some runs leave the usual route is there more than one route to show.
                if (hasBranches) {
                    Choices(
                        options =
                            listOf(
                                false to stringResource(R.string.route_main),
                                true to stringResource(R.string.route_all),
                            ),
                        selected = showVariants,
                        onSelect = { showVariants = it },
                        modifier = Modifier.padding(start = Spacing.large, end = Spacing.large, bottom = Spacing.small),
                    )
                }
            }
        },
    ) { padding ->
        if (rows == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.large),
        ) {
            items(lineAlerts, key = { "alert-${it.id}" }) { alert ->
                AlertCard(alert, Modifier.padding(horizontal = Spacing.large, vertical = Spacing.extraSmall))
            }
            // A loop line can serve the same stop twice, so the position is part of the key.
            itemsIndexed(rows, key = { index, row -> "$index-${row.stop.id}" }) { _, row ->
                RouteStopRow(row, lineColor, branchColor, onClick = { onOpen(Screen.LineAtStop(lineId, row.stop.id)) })
            }
        }
    }
}

/** How much darker than the line the branches of its variants are drawn. */
private const val BRANCH_DARKENING = 0.35f

/** One stop of the route: its place on the rail at the left, its name, and under it, smaller, its stand. */
@Composable
private fun RouteStopRow(
    row: RouteRow,
    lineColor: Color,
    branchColor: Color,
    onClick: () -> Unit,
) {
    val branch = row is RouteRow.Branch
    val surface = MaterialTheme.colorScheme.surface
    // Level with the middle of the name's first line.
    val dotY = if (branch) BRANCH_DOT_Y else MAIN_DOT_Y
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.large),
    ) {
        Canvas(Modifier.width(RAIL_WIDTH).fillMaxHeight()) {
            val main = RAIL_MAIN_X.toPx()
            val side = RAIL_BRANCH_X.toPx()
            val y = dotY.toPx()
            val mainWidth = 4.dp.toPx()
            val branchWidth = 3.dp.toPx()

            fun rail(
                x: Float,
                from: Float,
                to: Float,
                color: Color,
                width: Float,
            ) = drawLine(color, Offset(x, from), Offset(x, to), strokeWidth = width)

            fun curve(
                fromX: Float,
                fromY: Float,
                toX: Float,
                toY: Float,
            ) {
                val path =
                    Path().apply {
                        moveTo(fromX, fromY)
                        // Bends away from the stop sideways first, then runs on down (or comes in and turns into it).
                        quadraticTo(if (fromX == main) toX else fromX, if (fromX == main) fromY else toY, toX, toY)
                    }
                drawPath(path, branchColor, style = Stroke(branchWidth))
            }

            when (row) {
                is RouteRow.Main -> {
                    if (row.branchOut) curve(main, y, side, size.height)
                    if (row.branchIn) curve(side, 0f, main, y)
                    if (row.railAbove) rail(main, 0f, y, lineColor, mainWidth)
                    if (row.railBelow) rail(main, y, size.height, lineColor, mainWidth)
                    drawCircle(lineColor, radius = 7.dp.toPx(), center = Offset(main, y))
                    drawCircle(surface, radius = 3.5.dp.toPx(), center = Offset(main, y))
                }

                is RouteRow.Branch -> {
                    if (row.mainPasses) rail(main, 0f, size.height, lineColor, mainWidth)
                    if (row.railAbove) rail(side, 0f, y, branchColor, branchWidth)
                    if (row.railBelow) rail(side, y, size.height, branchColor, branchWidth)
                    drawCircle(branchColor, radius = 5.5.dp.toPx(), center = Offset(side, y))
                    drawCircle(surface, radius = 2.5.dp.toPx(), center = Offset(side, y))
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = Spacing.small, top = Spacing.medium, bottom = Spacing.medium)) {
            Text(
                row.stop.name,
                style = if (branch) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = if (branch) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
            row.stop.platform.takeIf { it.isNotEmpty() }?.let {
                Text(
                    stringResource(R.string.stop_post, it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Where the dot of a stop is: level with the middle of the first line of its name, which is larger for a usual stop. */
private val MAIN_DOT_Y = Spacing.medium + 12.dp
private val BRANCH_DOT_Y = Spacing.medium + 10.dp

private val RAIL_WIDTH = 34.dp

private val RAIL_MAIN_X = 10.dp

private val RAIL_BRANCH_X = 26.dp

/** One direction of the line on the city's map, over the whole screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineMapDialog(
    line: Line,
    direction: LineDirection,
    showVariants: Boolean,
    map: OfflineMap,
    repository: TimetableRepository,
    lineColor: Color,
    branchColor: Color,
    onDismiss: () -> Unit,
) {
    // The paths are looked up before the map opens, so that it is drawn once, complete.
    val paths by produceState<LinePaths?>(initialValue = null, direction, showVariants) {
        val variants = if (showVariants) direction.variants else emptyList()
        value =
            repository.query { timetable ->
                fun shape(id: Long?) = id?.let(timetable::shape)
                LinePaths(LinePath(direction.stops, shape(direction.shapeId)), variants.map { LinePath(it.stops, shape(it.shapeId)) })
            }
    }
    FullScreenDialog(title = { LineTitle(line, direction.headsign) }, onDismiss = onDismiss) {
        val shown = paths
        if (shown == null) {
            LoadingBox()
        } else {
            LineMap(
                map = map,
                main = shown.main,
                variants = shown.variants,
                lineColor = lineColor.toArgb(),
                branchColor = branchColor.toArgb(),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** What the map of a direction draws: its usual route and those of its variants. */
private class LinePaths(
    val main: LinePath,
    val variants: List<LinePath>,
)

/** Where the line goes in the direction shown and, when it runs both ways, the button that turns it round. */
@Composable
private fun DirectionBar(
    headsign: String,
    /** Null for a line that runs one way only. */
    onChange: (() -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.large, end = Spacing.small, bottom = Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.line_direction),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(headsign, style = MaterialTheme.typography.titleMedium)
        }
        if (onChange != null) {
            FilledTonalIconButton(onClick = onChange) {
                Icon(painterResource(R.drawable.ic_sync_alt), contentDescription = stringResource(R.string.line_change_direction))
            }
        }
    }
}
