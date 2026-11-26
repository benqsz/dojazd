package app.dojazd.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import app.dojazd.R
import app.dojazd.data.TimetableFailure
import app.dojazd.data.TimetableRepository
import app.dojazd.data.TimetableRepository.Status
import app.dojazd.data.TimetableRepository.Work
import app.dojazd.provider.CityConfig
import app.dojazd.ui.Choices
import app.dojazd.ui.LinkText
import app.dojazd.ui.SectionHeader
import app.dojazd.ui.sectionHeaderPadding
import app.dojazd.ui.theme.Spacing
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitySelectScreen(
    cities: List<CityConfig>,
    /** Chosen here already, so that a user who wants no network use has none from the start. */
    offline: Boolean,
    onOfflineChange: (Boolean) -> Unit,
    onSelect: (CityConfig) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            item(key = "mode") {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.small),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    SectionHeader(stringResource(R.string.mode_title))
                    Choices(
                        options =
                            listOf(
                                false to stringResource(R.string.mode_online),
                                true to stringResource(R.string.mode_offline),
                            ),
                        selected = offline,
                        onSelect = onOfflineChange,
                    )
                    Text(
                        stringResource(if (offline) R.string.mode_offline_hint else R.string.mode_online_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(top = Spacing.small))
            }
            item(key = "cities") {
                SectionHeader(stringResource(R.string.city_select_title), Modifier.sectionHeaderPadding())
            }
            items(cities, key = { it.id }) { city ->
                ListItem(
                    modifier = Modifier.clickable { onSelect(city) },
                    supportingContent = { Text(city.operator) },
                ) {
                    Text(city.name)
                }
            }
        }
    }
}

/** Switches the whole app off the network; the timetable then comes from a file the user imports. */
@Composable
fun OfflineSwitch(
    offline: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.toggleable(value = offline, role = Role.Switch, onValueChange = onChange),
        supportingContent = { Text(stringResource(R.string.offline_hint)) },
        trailingContent = { Switch(checked = offline, onCheckedChange = null) },
    ) {
        Text(stringResource(R.string.offline_mode))
    }
}

/** Where the city's timetable file can be downloaded by hand, as a link, and below it, unless left out, the operator's API documentation. */
@Composable
fun TimetableFileLink(
    city: CityConfig,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
    withApiDocs: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        LinkText(city.gtfs.url, city.gtfs.url, Modifier.fillMaxWidth(), textAlign)
        if (withApiDocs) ApiDocsLink(city, Modifier.fillMaxWidth(), textAlign)
    }
}

/** A link to the operator's documentation of its open data, when the city has one. */
@Composable
fun ApiDocsLink(
    city: CityConfig,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    val url = city.attribution.apiDocs ?: return
    LinkText(stringResource(R.string.api_docs), url, modifier, textAlign)
}

/** Asks for a GTFS archive and imports it. */
@Composable
fun rememberTimetableImport(repository: TimetableRepository): () -> Unit {
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) repository.importFile(uri)
        }
    // Some file managers do not know ZIP archives by their own type.
    return { pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
}

/** What went wrong with a timetable, said in the user's language and without the machine's words for it. */
@Composable
fun failureText(failure: TimetableFailure): String =
    when (failure.kind) {
        TimetableFailure.Kind.NO_CONNECTION -> stringResource(R.string.failure_no_connection)
        TimetableFailure.Kind.SERVER -> stringResource(R.string.failure_server, failure.status ?: 0)
        TimetableFailure.Kind.NO_SPACE -> stringResource(R.string.failure_no_space)
        TimetableFailure.Kind.BAD_FILE -> stringResource(R.string.failure_bad_file)
        TimetableFailure.Kind.OTHER -> stringResource(R.string.failure_other)
    }

/** Shown until the city's timetable has been downloaded and imported for the first time. */
@Composable
fun TimetableSetupScreen(
    city: CityConfig,
    status: Status,
    manualDownload: Boolean,
    offline: Boolean,
    onOfflineChange: (Boolean) -> Unit,
    repository: TimetableRepository,
    onChangeCity: () -> Unit,
) {
    val importFile = rememberTimetableImport(repository)
    var confirmDownload by remember { mutableStateOf(false) }
    if (confirmDownload) {
        DownloadConfirmDialog(
            downloadSize = repository::downloadSize,
            title = stringResource(R.string.download_dialog_title),
            text = stringResource(R.string.download_dialog_text),
            confirmLabel = stringResource(R.string.download),
            onConfirm = {
                confirmDownload = false
                repository.refresh()
            },
            onDismiss = { confirmDownload = false },
        )
    }

    Scaffold { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(Spacing.huge),
            verticalArrangement = Arrangement.spacedBy(Spacing.large, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(city.name, style = MaterialTheme.typography.headlineMedium)

            val work = status.work
            // Only what went wrong with a file the user picked; in offline mode nothing is downloaded that could fail.
            val importError: @Composable () -> Unit = {
                status.importError?.let { error ->
                    Text(
                        stringResource(R.string.timetable_import_failed, failureText(error)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (offline && work == null) {
                // Nothing is downloaded: the user fetches the file elsewhere and brings it in.
                Text(stringResource(R.string.offline_setup_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.offline_setup_text), textAlign = TextAlign.Center)
                TimetableFileLink(city)
                importError()
                Button(onClick = importFile) { Text(stringResource(R.string.timetable_import)) }
                TextButton(onClick = { onOfflineChange(false) }) { Text(stringResource(R.string.offline_turn_off)) }
                TextButton(onClick = onChangeCity) { Text(stringResource(R.string.change_city)) }
            } else if (work == null && (status.downloadError != null || (status.importError != null && !manualDownload))) {
                status.downloadError?.let { error ->
                    Text(stringResource(R.string.setup_failed), style = MaterialTheme.typography.titleMedium)
                    Text(
                        failureText(error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                importError()
                Button(onClick = repository::refresh) { Text(stringResource(R.string.retry)) }
                TextButton(onClick = importFile) { Text(stringResource(R.string.timetable_import)) }
                TextButton(onClick = onChangeCity) { Text(stringResource(R.string.change_city)) }
            } else if (manualDownload && work == null) {
                // With automatic refresh off nothing is downloaded unasked, so the first download needs a go-ahead.
                Text(stringResource(R.string.setup_manual_no_data), textAlign = TextAlign.Center)
                importError()
                Button(onClick = { confirmDownload = true }) { Text(stringResource(R.string.setup_download_button)) }
                TextButton(onClick = importFile) { Text(stringResource(R.string.timetable_import)) }
                TextButton(onClick = onChangeCity) { Text(stringResource(R.string.change_city)) }
            } else {
                when (work) {
                    is Work.Downloading -> {
                        Text(stringResource(R.string.setup_downloading))
                        if (work.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { work.bytes.toFloat() / work.totalBytes },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(
                                    R.string.setup_download_progress,
                                    megabytes(work.bytes),
                                    megabytes(work.totalBytes),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }

                    is Work.Importing -> {
                        Text(stringResource(R.string.setup_importing))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            stringResource(
                                R.string.setup_import_progress,
                                NumberFormat.getIntegerInstance().format(work.stopTimes),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    else -> {
                        Text(stringResource(R.string.setup_checking))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
