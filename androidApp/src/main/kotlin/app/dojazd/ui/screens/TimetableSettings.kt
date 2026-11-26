package app.dojazd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import app.dojazd.R
import app.dojazd.data.AppSettings
import app.dojazd.data.DataSettings
import app.dojazd.data.TimetableRepository
import app.dojazd.provider.CityConfig
import app.dojazd.ui.Choices
import app.dojazd.ui.SectionHeader
import app.dojazd.ui.expiredTimetableNote
import app.dojazd.ui.sectionHeaderPadding
import app.dojazd.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ColumnScope.TimetableSettings(
    city: CityConfig,
    settings: AppSettings,
    repository: TimetableRepository,
) {
    val data by settings.data.collectAsState()
    val status by repository.status.collectAsState()
    val importFile = rememberTimetableImport(repository)

    ListItem(
        supportingContent = { Text(city.operator) },
        trailingContent = {
            TextButton(onClick = { settings.selectCity(null) }) { Text(stringResource(R.string.change_city)) }
        },
    ) {
        Text(city.name)
    }

    OfflineSwitch(data.offline) { settings.setData(data.copy(offline = it)) }

    if (!data.offline) {
        if (city.realtime != null) {
            ListItem(
                modifier =
                    Modifier.toggleable(
                        value = data.live,
                        role = Role.Switch,
                        onValueChange = { settings.setData(data.copy(live = it)) },
                    ),
                supportingContent = { Text(stringResource(R.string.settings_live_hint)) },
                trailingContent = { Switch(checked = data.live, onCheckedChange = null) },
            ) {
                Text(stringResource(R.string.settings_live_switch))
            }
        }

        SectionHeader(stringResource(R.string.settings_refresh), Modifier.sectionHeaderPadding())
        Choices(
            modifier = Modifier.padding(horizontal = Spacing.large),
            options =
                listOf(
                    DataSettings.NEVER to stringResource(R.string.refresh_never),
                    DataSettings.QUARTER_HOUR to stringResource(R.string.refresh_15_min),
                    DataSettings.DAILY to stringResource(R.string.refresh_daily),
                    DataSettings.WEEKLY to stringResource(R.string.refresh_weekly),
                ),
            selected = data.refreshMinutes,
            onSelect = { minutes -> settings.setData(data.copy(refreshMinutes = minutes)) },
        )
    }
    Column(
        modifier = Modifier.padding(start = Spacing.large, end = Spacing.large, top = Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        status.importedAt?.let { importedAt ->
            Text(stringResource(R.string.timetable_imported_at, formatDateTime(importedAt)))
        }
        status.storageBytes?.let { bytes ->
            Text(stringResource(R.string.timetable_storage, megabytes(bytes)))
        }
        expiredTimetableNote(repository, city)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (status.work != null) {
            Text(stringResource(R.string.timetable_updating))
        }
        // Offline nothing is downloaded, so only a file that could not be imported is worth a word.
        status.downloadError?.takeUnless { data.offline }?.let { error ->
            Text(
                stringResource(R.string.timetable_update_failed, failureText(error)),
                color = MaterialTheme.colorScheme.error,
            )
        }
        status.importError?.let { error ->
            Text(
                stringResource(R.string.timetable_import_failed, failureText(error)),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (data.offline) {
            Text(stringResource(R.string.offline_settings_text))
            TimetableFileLink(city, textAlign = TextAlign.Start, withApiDocs = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            if (!data.offline) {
                OutlinedButton(onClick = repository::refresh, enabled = status.work == null) {
                    Text(stringResource(R.string.check_for_update))
                }
            }
            OutlinedButton(onClick = importFile, enabled = status.work == null) {
                Text(stringResource(R.string.timetable_import))
            }
        }
        Text(
            stringResource(R.string.data_attribution, city.attribution.text, city.attribution.license),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Always here, whichever way the timetable comes, so that it does not move about.
        ApiDocsLink(city)
        Spacer(Modifier.height(Spacing.large))
    }
}

private fun formatDateTime(epochSeconds: Long): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))
