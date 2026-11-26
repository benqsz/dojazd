package app.dojazd.data

import androidx.core.text.HtmlCompat
import app.dojazd.gtfs.Timetable
import app.dojazd.model.Line
import app.dojazd.realtime.Alert
import app.dojazd.realtime.AlertEffect

/** A service alert in one language, with what it is about found in the open timetable. */
class ServiceAlert private constructor(
    val id: String,
    val header: String,
    val description: String?,
    val url: String?,
    val effect: AlertEffect,
    /** The lines the alert names, in timetable order. */
    val lines: List<Line>,
    /** The names of the stops the alert names, each once. */
    val stopNames: List<String>,
    private val targets: List<Target>,
) {
    /** One thing the alert is about: a line, a stop, or a line at a stop. */
    private class Target(
        val lineId: Long?,
        val stopId: Long?,
    )

    /** True when the alert is about any of [stopIds], or any of [lineIds] anywhere or at one of those stops. */
    fun affects(
        stopIds: Collection<Long>,
        lineIds: Collection<Long>,
    ): Boolean =
        targets.any { target ->
            when {
                target.stopId != null -> target.stopId in stopIds && (target.lineId == null || target.lineId in lineIds)
                target.lineId != null -> target.lineId in lineIds
                else -> false
            }
        }

    fun names(lineId: Long): Boolean = targets.any { it.lineId == lineId }

    companion object {
        /**
         * The alerts of [alerts] in effect at [now] (epoch seconds), in [language]
         * where the producer translated them. Ids of lines and stops the timetable
         * does not know are left out of what an alert is about.
         */
        fun resolve(
            timetable: Timetable,
            alerts: List<Alert>,
            language: String,
            now: Long,
        ): List<ServiceAlert> =
            // A feed may hold an alert twice; a list on screen tells its rows apart by the id.
            alerts.filter { it.isActiveAt(now) }.distinctBy { it.id }.mapNotNull { alert ->
                val header =
                    alert.header.text(language)?.let(::plain)
                        ?: alert.description.text(language)?.let(::plain)
                        ?: return@mapNotNull null
                val targets =
                    alert.entities.map { entity ->
                        Target(
                            lineId = entity.routeId?.let { timetable.lineByGtfsId(it)?.id ?: UNKNOWN },
                            stopId = entity.stopId?.let { timetable.stopByGtfsId(it)?.id ?: UNKNOWN },
                        )
                    }
                val lineIds = targets.mapNotNull { it.lineId }.toSet()
                ServiceAlert(
                    id = alert.id,
                    header = header,
                    description =
                        alert.description
                            .text(language)
                            ?.let(::plain)
                            ?.takeIf { it != header },
                    url = alert.url.text(language)?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                    effect = alert.effect,
                    lines = timetable.lines().filter { it.id in lineIds },
                    stopNames = targets.mapNotNull { target -> target.stopId?.let(timetable::stop)?.name }.distinct(),
                    targets = targets,
                )
            }

        /** Producers write alerts as HTML or with HTML entities; they are shown as plain text. */
        private fun plain(text: String): String? =
            HtmlCompat
                .fromHtml(text, HtmlCompat.FROM_HTML_MODE_COMPACT)
                .toString()
                .trim()
                .takeIf { it.isNotEmpty() }

        /** Stands for an id the timetable does not know, so that it matches nothing. */
        private const val UNKNOWN = -1L
    }
}
