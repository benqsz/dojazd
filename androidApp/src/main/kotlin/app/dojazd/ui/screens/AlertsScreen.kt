package app.dojazd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.dojazd.R
import app.dojazd.data.Live
import app.dojazd.data.LiveRepository
import app.dojazd.data.TimetableRepository
import app.dojazd.provider.CityConfig
import app.dojazd.ui.AlertCard
import app.dojazd.ui.BackButton
import app.dojazd.ui.LoadingBox
import app.dojazd.ui.MessageBox
import app.dojazd.ui.rememberServiceAlerts
import app.dojazd.ui.theme.Spacing

/** Every service alert of the city in effect now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    city: CityConfig,
    repository: TimetableRepository,
    live: LiveRepository,
    onBack: () -> Unit,
) {
    val alerts by rememberServiceAlerts(city, live, repository)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alerts)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (val current = alerts) {
            Live.Loading -> {
                LoadingBox(modifier)
            }

            Live.Off, is Live.Failed -> {
                MessageBox(stringResource(R.string.alerts_unavailable), modifier)
            }

            is Live.Ready -> {
                if (current.value.isEmpty()) {
                    MessageBox(stringResource(R.string.alerts_none), modifier)
                } else {
                    LazyColumn(
                        modifier = modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Spacing.large),
                        verticalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        items(current.value, key = { it.id }) { alert -> AlertCard(alert) }
                    }
                }
            }
        }
    }
}
