package app.dojazd.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.dojazd.R
import app.dojazd.data.TimetableRepository
import app.dojazd.gtfs.LocalMoment
import app.dojazd.gtfs.StopTimetable
import app.dojazd.model.Line
import app.dojazd.model.Stop
import app.dojazd.provider.CityConfig
import app.dojazd.ui.LoadingBox
import app.dojazd.ui.MessageBox
import app.dojazd.ui.Screen
import app.dojazd.ui.rememberTimetableQuery
import app.dojazd.ui.theme.ControlSize
import app.dojazd.ui.theme.Spacing
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

private class LineAtStop(
    val line: Line,
    val stop: Stop,
    val date: LocalDate,
    val timetable: StopTimetable,
)

/** The full-day timetable of one line at one stop, for a chosen day. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LineAtStopScreen(
    lineId: Long,
    stopId: Long,
    city: CityConfig,
    repository: TimetableRepository,
    onOpen: (Screen) -> Unit,
    onBack: () -> Unit,
) {
    val today = rememberToday(city.timezone)
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    val date = today.plus(dayOffset, DateTimeUnit.DAY)

    val loaded by rememberTimetableQuery(repository, lineId, stopId, date) { timetable ->
        val line = timetable.line(lineId)
        val stop = timetable.stop(stopId)
        if (line == null || stop == null) {
            null
        } else {
            LineAtStop(line, stop, date, StopTimetable.of(timetable.lineDeparturesAtStop(lineId, stopId, date)))
        }
    }
    // While another day is loading, keep showing nothing rather than the previous day's times.
    val data = loaded?.takeIf { it.date == date }

    Scaffold(
        topBar = {
            LineHeader(loaded?.line, loaded?.stop?.name, onBack)
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            loaded?.stop?.let { stop ->
                FilledTonalButton(
                    onClick = { onOpen(Screen.StopDepartures(stop.name, listOf(stop.id))) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = Spacing.large, end = Spacing.large, bottom = Spacing.small)
                            .heightIn(min = ControlSize.compactButton),
                    contentPadding = PaddingValues(horizontal = Spacing.large),
                ) {
                    Text(stringResource(R.string.all_departures_from_stop), style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(
                modifier =
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.large),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                for (offset in 0 until DAYS_SHOWN) {
                    FilterChip(
                        selected = offset == dayOffset,
                        onClick = { dayOffset = offset },
                        label = { Text(dayLabel(today.plus(offset, DateTimeUnit.DAY), offset)) },
                    )
                }
            }
            when {
                data == null -> {
                    LoadingBox()
                }

                data.timetable.hours.isEmpty() -> {
                    MessageBox(stringResource(R.string.no_departures_that_day))
                }

                else -> {
                    LazyColumn(contentPadding = PaddingValues(vertical = Spacing.small)) {
                        items(data.timetable.hours) { hour -> HourRow(hour) }
                        // Below the times: what the small letters by some of them mean, such as runs to the depot.
                        items(data.timetable.legend) { variant ->
                            Text(
                                stringResource(R.string.variant_legend, variant.mark.toString(), variant.headsign),
                                modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.tiny),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HourRow(hour: StopTimetable.Hour) {
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.large, vertical = Spacing.medium),
        ) {
            Text(
                text = hour.hour.toString().padStart(2, '0'),
                modifier = Modifier.width(HOUR_WIDTH),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.large),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                hour.minutes.forEach { minute ->
                    // A run that goes elsewhere carries its letter small and raised, beside the minute.
                    Text(
                        text =
                            buildAnnotatedString {
                                append(minute.minute.toString().padStart(2, '0'))
                                minute.mark?.let { mark ->
                                    withStyle(
                                        SpanStyle(
                                            fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                            baselineShift = BaselineShift.Superscript,
                                            color = MaterialTheme.colorScheme.primary,
                                        ),
                                    ) { append(mark) }
                                }
                            },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = Spacing.large))
    }
}

/** The width of the column of hours, so that the minutes of every hour start in one line. */
private val HOUR_WIDTH = 48.dp

private const val DAYS_SHOWN = 7
