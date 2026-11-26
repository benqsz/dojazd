package app.dojazd.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.dojazd.R
import app.dojazd.data.FetchKind
import app.dojazd.data.FetchLogEntry
import app.dojazd.data.Network
import app.dojazd.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Every request the app made to the network lately, newest first. */
@Composable
internal fun FetchLog(
    network: Network,
    modifier: Modifier = Modifier,
) {
    val log by network.log.collectAsState()
    LazyColumn(modifier.fillMaxSize()) {
        item(key = "hint") {
            Text(
                pluralStringResource(R.plurals.fetch_log_hint, Network.MAX_ENTRIES, Network.MAX_ENTRIES),
                modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.small),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (log.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.fetch_log_empty),
                    modifier = Modifier.padding(Spacing.large),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        items(log, key = { it.id }) { entry -> FetchLogRow(entry) }
    }
}

@Composable
private fun FetchLogRow(entry: FetchLogEntry) {
    val colors = MaterialTheme.colorScheme
    val details =
        buildList {
            add(formatLogTime(entry.at))
            entry.bytes?.let { add(formatBytes(it)) }
            entry.millis?.let { add(stringResource(R.string.fetch_log_millis, it)) }
        }.joinToString(" · ")
    ListItem(
        supportingContent = {
            Column {
                Text(details)
                entry.error?.let { Text(it, color = colors.error) }
                Text(entry.url, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        },
        trailingContent = {
            Text(
                when (entry.outcome) {
                    FetchLogEntry.Outcome.OK -> stringResource(R.string.fetch_log_ok, entry.status ?: 0)
                    FetchLogEntry.Outcome.NOT_MODIFIED -> stringResource(R.string.fetch_log_not_modified)
                    FetchLogEntry.Outcome.FAILED -> entry.status?.let { "HTTP $it" } ?: stringResource(R.string.fetch_log_failed)
                },
                style = MaterialTheme.typography.labelLarge,
                color =
                    when (entry.outcome) {
                        FetchLogEntry.Outcome.FAILED -> colors.error
                        else -> colors.primary
                    },
            )
        },
    ) {
        Text(
            stringResource(
                when (entry.kind) {
                    FetchKind.TIMETABLE -> R.string.fetch_kind_timetable
                    FetchKind.TIMETABLE_SIZE -> R.string.fetch_kind_timetable_size
                    FetchKind.TRIP_UPDATES -> R.string.fetch_kind_trip_updates
                    FetchKind.VEHICLES -> R.string.fetch_kind_vehicles
                    FetchKind.ALERTS -> R.string.fetch_kind_alerts
                },
            ),
        )
    }
}

/** The time alone for today, the date as well for anything older. */
private fun formatLogTime(epochMillis: Long): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val today = time.toLocalDate() == LocalDate.now(ZoneId.systemDefault())
    return if (today) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).format(time)
    } else {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM).format(time)
    }
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes < KILOBYTE -> "$bytes B"
        bytes < KILOBYTE * KILOBYTE -> "%.1f kB".format(Locale.getDefault(), bytes / KILOBYTE.toDouble())
        else -> "%.1f MB".format(Locale.getDefault(), bytes / (KILOBYTE * KILOBYTE).toDouble())
    }

private const val KILOBYTE = 1024L
