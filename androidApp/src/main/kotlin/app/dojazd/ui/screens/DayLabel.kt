package app.dojazd.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import app.dojazd.R
import app.dojazd.gtfs.LocalMoment
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.time.format.DateTimeFormatter

/** Today's date in [timezone], kept current: a screen left open past midnight moves on to the new day. */
@Composable
internal fun rememberToday(timezone: String): LocalDate {
    val today by produceState(LocalMoment.now(timezone).date, timezone) {
        while (true) {
            value = LocalMoment.now(timezone).date
            delay(TODAY_REFRESH_MILLIS)
        }
    }
    return today
}

private const val TODAY_REFRESH_MILLIS = 30_000L

@Composable
internal fun dayLabel(
    date: LocalDate,
    offset: Int,
): String =
    when (offset) {
        0 -> stringResource(R.string.day_today)

        1 -> stringResource(R.string.day_tomorrow)

        // Built on each call: a cached formatter would keep the language it was created with.
        else -> date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("EEE d.MM"))
    }
