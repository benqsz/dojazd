package app.dojazd.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import app.dojazd.R
import app.dojazd.data.CityDataRepository
import app.dojazd.data.OfflineMap
import app.dojazd.data.PlacesRepository
import app.dojazd.data.RecentPlacesRepository
import app.dojazd.data.TimetableRepository
import app.dojazd.model.GeoPoint
import app.dojazd.provider.CityConfig
import app.dojazd.storage.Place
import app.dojazd.ui.PlaceDialog
import app.dojazd.ui.PlaceIcon
import app.dojazd.ui.hasOwnLabel
import app.dojazd.ui.listing
import app.dojazd.ui.map.PointPreviewMap
import app.dojazd.ui.showMessage
import app.dojazd.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Where a place being edited or added is, and what it is called, until it is saved. */
private class PlaceDraft(
    val place: Place,
    val isNew: Boolean,
)

@Composable
internal fun ColumnScope.PlacesSection(
    city: CityConfig,
    timetables: TimetableRepository,
    cityData: CityDataRepository,
    repository: PlacesRepository,
    recentPlaces: RecentPlacesRepository,
    /** True once the user asked to add a place: its address is asked for first. */
    adding: Boolean,
    onAddingEnd: () -> Unit,
) {
    val places by repository.places.collectAsState()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<PlaceDraft?>(null) }
    // True while a new address is chosen for the place being edited.
    var changingAddress by remember { mutableStateOf(false) }
    val map by produceState<OfflineMap?>(initialValue = null, city.id) { value = cityData.map(city) }

    fun toast(text: String) = context.showMessage(text)
    val exported = stringResource(R.string.places_exported)
    val exportFailed = stringResource(R.string.places_export_failed)
    val importFailed = stringResource(R.string.places_import_failed)

    // The system's own file picker: the app needs no storage permission and sees only the chosen file.
    val exportTo =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) scope.launch { toast(if (repository.exportTo(uri)) exported else exportFailed) }
        }
    val importFrom =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val count = repository.importFrom(uri)
                    toast(if (count == null) importFailed else resources.getString(R.string.places_imported, count))
                }
            }
        }

    editing?.let { draft ->
        val place = draft.place
        // Keyed by the place, so that a new address does not reset the label being typed.
        key(place.id) {
            PlaceDialog(
                title = stringResource(if (draft.isNew) R.string.place_add else R.string.edit_place),
                initialName = place.name,
                initialIcon = place.icon,
                address = place.address,
                onSave = { name, icon ->
                    repository.save(place.copy(name = name, icon = icon))
                    editing = null
                },
                onDismiss = { editing = null },
                onDelete =
                    if (draft.isNew) {
                        null
                    } else {
                        {
                            repository.delete(place.id)
                            editing = null
                        }
                    },
                onChangeAddress = { changingAddress = true },
                content = map?.let { shown -> { PointPreviewMap(shown, place.location, Modifier.fillMaxSize()) } },
            )
        }
    }

    if (adding || changingAddress) {
        val forNew = adding
        PlacePickerDialog(
            title = stringResource(R.string.place_choose_address),
            city = city,
            timetables = timetables,
            cityData = cityData,
            places = repository,
            recentPlaces = recentPlaces,
            onPick = { point ->
                val location = GeoPoint(point.lat, point.lon)
                val current = editing
                editing =
                    if (forNew || current == null) {
                        PlaceDraft(Place(repository.newId(), point.name, address = point.name, location = location), isNew = true)
                    } else {
                        // A place still called by its address takes the new one's name; a label of its own stays.
                        val name = if (current.place.hasOwnLabel()) current.place.name else point.name
                        PlaceDraft(current.place.copy(name = name, address = point.name, location = location), current.isNew)
                    }
                changingAddress = false
                onAddingEnd()
            },
            onDismiss = {
                changingAddress = false
                onAddingEnd()
            },
        )
    }

    if (places.isEmpty()) {
        Text(
            stringResource(R.string.places_empty),
            modifier = Modifier.padding(Spacing.large),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    places.forEach { place ->
        val (headline, supporting) = place.listing()
        ListItem(
            modifier = Modifier.clickable { editing = PlaceDraft(place, isNew = false) },
            leadingContent = { PlaceIcon(place) },
            supportingContent = supporting?.let { { Text(it) } },
            trailingContent = {
                IconButton(onClick = { editing = PlaceDraft(place, isNew = false) }) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.edit_place))
                }
            },
        ) {
            Text(headline)
        }
    }
    PlacesFileButtons(
        repository = repository,
        canExport = places.isNotEmpty(),
        onImport = { importFrom.launch(PLACES_FILE_TYPES) },
        onExport = { exportTo.launch(EXPORT_FILE_NAME) },
    )
}

/**
 * Import, export, and a backup file, one under another. The backup file is
 * written on every change, so the favourite places survive losing the phone or
 * the app; restoring from it is an import.
 */
@Composable
private fun ColumnScope.PlacesFileButtons(
    repository: PlacesRepository,
    canExport: Boolean,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    val backup by repository.backup.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupFailed = stringResource(R.string.places_backup_failed)

    val create =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                scope.launch {
                    if (!repository.setBackup(uri)) context.showMessage(backupFailed, long = true)
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.huge, vertical = Spacing.small),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.places_import))
        }
        OutlinedButton(onClick = onExport, enabled = canExport, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.places_export))
        }
        OutlinedButton(onClick = { create.launch(BACKUP_FILE_NAME) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.places_backup))
        }
    }

    val current = backup
    if (current == null) {
        Text(
            stringResource(R.string.places_backup_hint),
            modifier = Modifier.padding(start = Spacing.large, end = Spacing.large, bottom = Spacing.large),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    } else {
        ListItem(
            supportingContent = {
                if (current.reachable) {
                    Text(stringResource(R.string.places_backup_on))
                } else {
                    Text(stringResource(R.string.places_backup_unreachable), color = MaterialTheme.colorScheme.error)
                }
            },
            trailingContent = {
                TextButton(onClick = repository::removeBackup) { Text(stringResource(R.string.places_backup_off)) }
            },
        ) {
            Text(current.path, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(Spacing.large))
    }
}

/** Some file managers do not know JSON by its own type, so plain and unknown files are offered too. */
private val PLACES_FILE_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

private const val EXPORT_FILE_NAME = "dojazd-places.json"

private const val BACKUP_FILE_NAME = "dojazd-places-backup.json"
