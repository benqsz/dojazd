package app.dojazd.ui.screens

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.dojazd.R
import app.dojazd.data.AppSettings
import app.dojazd.data.PlacesRepository
import app.dojazd.data.RecentJourneysRepository
import app.dojazd.data.RecentPlacesRepository
import app.dojazd.provider.CityConfig
import app.dojazd.ui.theme.Spacing

/** What can be forgotten in the history settings. */
private enum class HistoryPart { JOURNEYS, SEARCHES, PLACES }

/** Forgetting what the app remembers about where the user goes, each kind on its own and only after asking. */
@Composable
internal fun ColumnScope.HistorySettings(
    city: CityConfig,
    settings: AppSettings,
    places: PlacesRepository,
    recentPlaces: RecentPlacesRepository,
    recentJourneys: RecentJourneysRepository,
) {
    val journeys by recentJourneys.of(city.id).collectAsState()
    val searches by recentPlaces.of(city.id).collectAsState()
    val saved by places.places.collectAsState()
    var confirming by rememberSaveable { mutableStateOf<HistoryPart?>(null) }
    val privacy by settings.privacy.collectAsState()

    SwitchRow(R.string.history_switch, R.string.history_switch_hint, privacy.history) { on ->
        settings.setPrivacy(privacy.copy(history = on))
        // Wanting no history kept means wanting none: what was remembered so far goes too.
        if (!on) {
            recentJourneys.clear()
            recentPlaces.clear()
        }
    }
    SwitchRow(R.string.keyboard_learning_switch, R.string.keyboard_learning_hint, privacy.keyboardLearning) { on ->
        settings.setPrivacy(privacy.copy(keyboardLearning = on))
    }

    Text(
        stringResource(R.string.history_hint),
        modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.small),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HistoryRow(R.string.history_journeys, R.string.history_journeys_hint, enabled = journeys.isNotEmpty()) {
        confirming = HistoryPart.JOURNEYS
    }
    HistoryRow(R.string.history_searches, R.string.history_searches_hint, enabled = searches.isNotEmpty()) {
        confirming = HistoryPart.SEARCHES
    }
    HistoryRow(R.string.history_places, R.string.history_places_hint, enabled = saved.isNotEmpty()) {
        confirming = HistoryPart.PLACES
    }

    confirming?.let { part ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = {
                Text(
                    stringResource(
                        when (part) {
                            HistoryPart.JOURNEYS -> R.string.history_journeys_confirm
                            HistoryPart.SEARCHES -> R.string.history_searches_confirm
                            HistoryPart.PLACES -> R.string.history_places_confirm
                        },
                    ),
                )
            },
            text = { Text(stringResource(R.string.history_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (part) {
                            HistoryPart.JOURNEYS -> recentJourneys.clear()
                            HistoryPart.SEARCHES -> recentPlaces.clear()
                            HistoryPart.PLACES -> places.deleteAll()
                        }
                        confirming = null
                    },
                ) { Text(stringResource(R.string.clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SwitchRow(
    title: Int,
    hint: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        supportingContent = { Text(stringResource(hint)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    ) {
        Text(stringResource(title))
    }
}

@Composable
private fun HistoryRow(
    title: Int,
    hint: Int,
    enabled: Boolean,
    onClear: () -> Unit,
) {
    ListItem(
        supportingContent = { Text(stringResource(hint)) },
        trailingContent = { OutlinedButton(onClick = onClear, enabled = enabled) { Text(stringResource(R.string.clear)) } },
    ) {
        Text(stringResource(title))
    }
}
