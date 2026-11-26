package app.dojazd.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dojazd.R
import app.dojazd.data.Live
import app.dojazd.data.LiveRepository
import app.dojazd.data.ServiceAlert
import app.dojazd.data.TimetableRepository
import app.dojazd.provider.CityConfig
import app.dojazd.ui.theme.IconSize
import app.dojazd.ui.theme.Spacing

/** The language the app shows its texts in, for picking translations of live data. */
@Composable
fun appLanguage(): String = LocalConfiguration.current.locales[0].language

/**
 * The service alerts of [city] in effect now, in the app's language; [Live.Ready]
 * once fetched. Re-resolved when the timetable is replaced.
 */
@Composable
fun rememberServiceAlerts(
    city: CityConfig,
    live: LiveRepository,
    repository: TimetableRepository,
): State<Live<List<ServiceAlert>>> {
    val feed by remember(city) { live.alerts(city) }.collectAsStateWithLifecycle(Live.Loading)
    val language = appLanguage()
    val version =
        rememberTimetableVersion(repository)
    return produceState<Live<List<ServiceAlert>>>(Live.Loading, feed, language, version) {
        value =
            when (val current = feed) {
                is Live.Ready -> {
                    val now = System.currentTimeMillis() / 1000
                    repository
                        .query { ServiceAlert.resolve(it, current.value, language, now) }
                        ?.let { Live.Ready(it, current.updatedAt, current.error) }
                        ?: Live.Loading
                }

                Live.Loading -> {
                    Live.Loading
                }

                Live.Off -> {
                    Live.Off
                }

                is Live.Failed -> {
                    current
                }
            }
    }
}

/** One service alert: its heading, and the rest when opened. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertCard(
    alert: ServiceAlert,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
) {
    var expanded by rememberSaveable(alert.id) { mutableStateOf(startExpanded) }
    val context = LocalContext.current
    val hasMore = alert.description != null || alert.url != null || alert.stopNames.isNotEmpty()
    OutlinedCard(
        onClick = { expanded = !expanded },
        enabled = hasMore,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.large), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                Icon(
                    painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.small),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    alert.header,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (alert.lines.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    alert.lines.forEach { LineBadge(it) }
                }
            }
            if (expanded) {
                alert.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (alert.stopNames.isNotEmpty()) {
                    Text(
                        stringResource(R.string.alert_stops, alert.stopNames.joinToString()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                alert.url?.let { url ->
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        } catch (e: ActivityNotFoundException) {
                            // No browser on the phone; the alert's own text has to do.
                        }
                    }) {
                        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, Modifier.size(IconSize.small))
                        Text(stringResource(R.string.alert_open), Modifier.padding(start = Spacing.small))
                    }
                }
            } else if (hasMore) {
                Text(
                    stringResource(R.string.alert_more),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** A small "live" mark: the broadcast icon in the colour live times are shown in. */
@Composable
fun LiveIcon(modifier: Modifier = Modifier) {
    Icon(
        painterResource(R.drawable.ic_sensors),
        contentDescription = stringResource(R.string.live_description),
        modifier = modifier.size(IconSize.tiny),
        tint = liveColor(),
    )
}

/** The colour of times that come from live data rather than the timetable. */
@Composable
fun liveColor() = MaterialTheme.colorScheme.tertiary

@Composable
internal fun LiveRow(content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) { content() }
}
