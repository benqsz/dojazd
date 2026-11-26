package app.dojazd.realtime

import app.dojazd.model.GeoPoint

/**
 * The parts of a GTFS-RT feed the app uses. One feed usually carries one kind
 * of entity (trip updates, vehicle positions or alerts), but all are read.
 * Reference: https://gtfs.org/realtime/reference/
 */
class FeedMessage(
    /** When the producer created the feed, in epoch seconds; null when it does not say. */
    val timestamp: Long?,
    val tripUpdates: List<TripUpdate>,
    val vehicles: List<VehiclePosition>,
    val alerts: List<Alert>,
)

data class TripDescriptor(
    val tripId: String?,
    val routeId: String?,
    /** GTFS `YYYYMMDD`, or null when the producer leaves it out. */
    val startDate: String?,
    val relationship: TripRelationship,
)

enum class TripRelationship {
    SCHEDULED,
    ADDED,
    UNSCHEDULED,
    CANCELED,
    REPLACEMENT,
    DUPLICATED,
    DELETED,
    NEW,
    ;

    /** True when the run will not take place, so passengers should not wait for it. */
    val cancelled: Boolean get() = this == CANCELED || this == DELETED

    internal companion object {
        fun of(value: Int) =
            when (value) {
                1 -> ADDED
                2 -> UNSCHEDULED
                3 -> CANCELED
                5 -> REPLACEMENT
                6 -> DUPLICATED
                7 -> DELETED
                8 -> NEW
                else -> SCHEDULED
            }
    }
}

/** A predicted moment: [delay] in seconds against the timetable, or an absolute [time] in epoch seconds. */
data class StopTimeEvent(
    val delay: Int?,
    val time: Long?,
)

data class StopTimeUpdate(
    val stopSequence: Int?,
    val stopId: String?,
    val arrival: StopTimeEvent?,
    val departure: StopTimeEvent?,
    val relationship: StopRelationship,
)

enum class StopRelationship {
    SCHEDULED,

    /** The vehicle will not call at this stop. */
    SKIPPED,

    /** Nothing is known about this stop or, until the next update, the ones after it. */
    NO_DATA,
    UNSCHEDULED,
    ;

    internal companion object {
        fun of(value: Int) =
            when (value) {
                1 -> SKIPPED
                2 -> NO_DATA
                3 -> UNSCHEDULED
                else -> SCHEDULED
            }
    }
}

data class TripUpdate(
    val trip: TripDescriptor,
    /** What passengers can read on the vehicle (its fleet number), when known. */
    val vehicleLabel: String?,
    /** In order of stop sequence, as the specification requires. */
    val stopTimeUpdates: List<StopTimeUpdate>,
    val timestamp: Long?,
    /** Delay of the whole trip in seconds, for producers that give no per-stop updates. */
    val delay: Int?,
)

data class VehiclePosition(
    /** The vehicle's id in the feed, stable while it is in service. */
    val id: String,
    /** What passengers can read on the vehicle (its fleet number), when known. */
    val label: String?,
    val trip: TripDescriptor?,
    val position: GeoPoint,
    /** Degrees clockwise from north, when known. */
    val bearing: Float?,
    /** Metres per second, when known. */
    val speed: Float?,
    /** When the position was measured, in epoch seconds. */
    val timestamp: Long?,
    val currentStopSequence: Int?,
)

/** A text in several languages; [language] is a BCP 47 tag, or null for the producer's default. */
class TranslatedString(
    val translations: List<Pair<String?, String>>,
) {
    /** The text in [language], else the one without a language, else the first. */
    fun text(language: String): String? {
        val wanted = language.substringBefore('-').lowercase()
        return translations.firstOrNull { it.first?.substringBefore('-')?.lowercase() == wanted }?.second
            ?: translations.firstOrNull { it.first == null }?.second
            ?: translations.firstOrNull()?.second
    }

    val isEmpty: Boolean get() = translations.isEmpty()
}

/** What an alert is about. Fields left out mean "any"; a selector names at least one. */
data class InformedEntity(
    val routeId: String?,
    val routeType: Int?,
    val stopId: String?,
    val tripId: String?,
)

/** A span of time in epoch seconds; an open end is null. */
data class ActivePeriod(
    val start: Long?,
    val end: Long?,
) {
    operator fun contains(time: Long): Boolean = (start == null || time >= start) && (end == null || time < end)
}

data class Alert(
    val id: String,
    /** Empty when the alert applies for as long as it is in the feed. */
    val activePeriods: List<ActivePeriod>,
    val entities: List<InformedEntity>,
    val effect: AlertEffect,
    val url: TranslatedString,
    val header: TranslatedString,
    val description: TranslatedString,
) {
    fun isActiveAt(time: Long): Boolean = activePeriods.isEmpty() || activePeriods.any { time in it }
}

enum class AlertEffect {
    NO_SERVICE,
    REDUCED_SERVICE,
    SIGNIFICANT_DELAYS,
    DETOUR,
    ADDITIONAL_SERVICE,
    MODIFIED_SERVICE,
    OTHER_EFFECT,
    UNKNOWN_EFFECT,
    STOP_MOVED,
    NO_EFFECT,
    ACCESSIBILITY_ISSUE,
    ;

    internal companion object {
        fun of(value: Int) =
            when (value) {
                1 -> NO_SERVICE
                2 -> REDUCED_SERVICE
                3 -> SIGNIFICANT_DELAYS
                4 -> DETOUR
                5 -> ADDITIONAL_SERVICE
                6 -> MODIFIED_SERVICE
                7 -> OTHER_EFFECT
                9 -> STOP_MOVED
                10 -> NO_EFFECT
                11 -> ACCESSIBILITY_ISSUE
                else -> UNKNOWN_EFFECT
            }
    }
}

object GtfsRealtime {
    /** Parses a binary `FeedMessage`. @throws ProtoFormatException when [bytes] are not one. */
    fun parse(bytes: ByteArray): FeedMessage {
        var timestamp: Long? = null
        val tripUpdates = ArrayList<TripUpdate>()
        val vehicles = ArrayList<VehiclePosition>()
        val alerts = ArrayList<Alert>()
        val reader = ProtoReader(bytes)
        while (reader.next()) {
            when (reader.field) {
                1 -> timestamp = header(reader.message())
                2 -> entity(reader.message(), tripUpdates, vehicles, alerts)
                else -> reader.skip()
            }
        }
        return FeedMessage(timestamp, tripUpdates, vehicles, alerts)
    }

    private fun header(reader: ProtoReader): Long? {
        var timestamp: Long? = null
        while (reader.next()) {
            if (reader.field == 3) timestamp = reader.long() else reader.skip()
        }
        return timestamp
    }

    private fun entity(
        reader: ProtoReader,
        tripUpdates: MutableList<TripUpdate>,
        vehicles: MutableList<VehiclePosition>,
        alerts: MutableList<Alert>,
    ) {
        var id = ""
        var deleted = false
        var tripUpdate: TripUpdate? = null
        // The id may come after the content, so vehicles and alerts, which carry it, are read at the end.
        var vehicleReader: ProtoReader? = null
        var alertReader: ProtoReader? = null
        while (reader.next()) {
            when (reader.field) {
                1 -> id = reader.string()
                2 -> deleted = reader.bool()
                3 -> tripUpdate = tripUpdate(reader.message())
                4 -> vehicleReader = reader.message()
                5 -> alertReader = reader.message()
                else -> reader.skip()
            }
        }
        if (deleted) return
        tripUpdate?.let(tripUpdates::add)
        vehicleReader?.let { vehiclePosition(it, id) }?.let(vehicles::add)
        alertReader?.let { alerts += alert(it, id) }
    }

    private fun tripDescriptor(reader: ProtoReader): TripDescriptor {
        var tripId: String? = null
        var routeId: String? = null
        var startDate: String? = null
        var relationship = 0
        while (reader.next()) {
            when (reader.field) {
                1 -> tripId = reader.string()
                3 -> startDate = reader.string()
                4 -> relationship = reader.int()
                5 -> routeId = reader.string()
                else -> reader.skip()
            }
        }
        return TripDescriptor(
            tripId = tripId?.takeIf { it.isNotEmpty() },
            routeId = routeId?.takeIf { it.isNotEmpty() },
            startDate = startDate?.takeIf { it.isNotEmpty() },
            relationship = TripRelationship.of(relationship),
        )
    }

    private class VehicleDescriptor(
        val id: String?,
        val label: String?,
    )

    private fun vehicleDescriptor(reader: ProtoReader): VehicleDescriptor {
        var id: String? = null
        var label: String? = null
        while (reader.next()) {
            when (reader.field) {
                1 -> id = reader.string()
                2 -> label = reader.string()
                else -> reader.skip()
            }
        }
        return VehicleDescriptor(id?.takeIf { it.isNotEmpty() }, label?.takeIf { it.isNotEmpty() })
    }

    private fun tripUpdate(reader: ProtoReader): TripUpdate? {
        var trip: TripDescriptor? = null
        var vehicle: VehicleDescriptor? = null
        val updates = ArrayList<StopTimeUpdate>()
        var timestamp: Long? = null
        var delay: Int? = null
        while (reader.next()) {
            when (reader.field) {
                1 -> trip = tripDescriptor(reader.message())
                2 -> updates += stopTimeUpdate(reader.message())
                3 -> vehicle = vehicleDescriptor(reader.message())
                4 -> timestamp = reader.long()
                5 -> delay = reader.int()
                else -> reader.skip()
            }
        }
        return TripUpdate(
            trip = trip ?: return null,
            vehicleLabel = vehicle?.label ?: vehicle?.id,
            stopTimeUpdates = updates,
            timestamp = timestamp,
            delay = delay,
        )
    }

    private fun stopTimeUpdate(reader: ProtoReader): StopTimeUpdate {
        var sequence: Int? = null
        var stopId: String? = null
        var arrival: StopTimeEvent? = null
        var departure: StopTimeEvent? = null
        var relationship = 0
        while (reader.next()) {
            when (reader.field) {
                1 -> sequence = reader.int()
                2 -> arrival = stopTimeEvent(reader.message())
                3 -> departure = stopTimeEvent(reader.message())
                4 -> stopId = reader.string()
                5 -> relationship = reader.int()
                else -> reader.skip()
            }
        }
        return StopTimeUpdate(sequence, stopId?.takeIf { it.isNotEmpty() }, arrival, departure, StopRelationship.of(relationship))
    }

    private fun stopTimeEvent(reader: ProtoReader): StopTimeEvent {
        var delay: Int? = null
        var time: Long? = null
        while (reader.next()) {
            when (reader.field) {
                1 -> delay = reader.int()
                2 -> time = reader.long()
                else -> reader.skip()
            }
        }
        return StopTimeEvent(delay, time?.takeIf { it > 0 })
    }

    private fun vehiclePosition(
        reader: ProtoReader,
        entityId: String,
    ): VehiclePosition? {
        var trip: TripDescriptor? = null
        var vehicle: VehicleDescriptor? = null
        var lat: Float? = null
        var lon: Float? = null
        var bearing: Float? = null
        var speed: Float? = null
        var sequence: Int? = null
        var timestamp: Long? = null
        while (reader.next()) {
            when (reader.field) {
                1 -> {
                    trip = tripDescriptor(reader.message())
                }

                2 -> {
                    val position = reader.message()
                    while (position.next()) {
                        when (position.field) {
                            1 -> lat = position.float()
                            2 -> lon = position.float()
                            3 -> bearing = position.float()
                            5 -> speed = position.float()
                            else -> position.skip()
                        }
                    }
                }

                3 -> {
                    sequence = reader.int()
                }

                5 -> {
                    timestamp = reader.long()
                }

                8 -> {
                    vehicle = vehicleDescriptor(reader.message())
                }

                else -> {
                    reader.skip()
                }
            }
        }
        if (lat == null || lon == null || (lat == 0f && lon == 0f)) return null
        return VehiclePosition(
            id = vehicle?.id ?: entityId,
            label = vehicle?.label,
            trip = trip,
            position = GeoPoint(lat.toDouble(), lon.toDouble()),
            bearing = bearing,
            speed = speed,
            timestamp = timestamp,
            currentStopSequence = sequence,
        )
    }

    private fun alert(
        reader: ProtoReader,
        id: String,
    ): Alert {
        val periods = ArrayList<ActivePeriod>()
        val entities = ArrayList<InformedEntity>()
        var effect = 0
        var url = TranslatedString(emptyList())
        var header = TranslatedString(emptyList())
        var description = TranslatedString(emptyList())
        while (reader.next()) {
            when (reader.field) {
                1 -> periods += activePeriod(reader.message())
                5 -> informedEntity(reader.message())?.let(entities::add)
                7 -> effect = reader.int()
                8 -> url = translatedString(reader.message())
                10 -> header = translatedString(reader.message())
                11 -> description = translatedString(reader.message())
                else -> reader.skip()
            }
        }
        return Alert(id, periods, entities, AlertEffect.of(effect), url, header, description)
    }

    private fun activePeriod(reader: ProtoReader): ActivePeriod {
        var start: Long? = null
        var end: Long? = null
        while (reader.next()) {
            when (reader.field) {
                1 -> start = reader.long()
                2 -> end = reader.long()
                else -> reader.skip()
            }
        }
        // 0 is how producers that always write both fields say "open".
        return ActivePeriod(start?.takeIf { it > 0 }, end?.takeIf { it > 0 })
    }

    private fun informedEntity(reader: ProtoReader): InformedEntity? {
        var routeId: String? = null
        var routeType: Int? = null
        var stopId: String? = null
        var tripId: String? = null
        while (reader.next()) {
            when (reader.field) {
                2 -> routeId = reader.string()
                3 -> routeType = reader.int()
                4 -> tripId = tripDescriptor(reader.message()).tripId
                5 -> stopId = reader.string()
                else -> reader.skip()
            }
        }
        val entity =
            InformedEntity(
                routeId = routeId?.takeIf { it.isNotEmpty() },
                routeType = routeType,
                stopId = stopId?.takeIf { it.isNotEmpty() },
                tripId = tripId,
            )
        return entity.takeIf { it.routeId != null || it.routeType != null || it.stopId != null || it.tripId != null }
    }

    private fun translatedString(reader: ProtoReader): TranslatedString {
        val translations = ArrayList<Pair<String?, String>>()
        while (reader.next()) {
            if (reader.field != 1) {
                reader.skip()
                continue
            }
            val translation = reader.message()
            var text = ""
            var language: String? = null
            while (translation.next()) {
                when (translation.field) {
                    1 -> text = translation.string()
                    2 -> language = translation.string()
                    else -> translation.skip()
                }
            }
            if (text.isNotBlank()) translations += language?.takeIf { it.isNotEmpty() } to text.trim()
        }
        return TranslatedString(translations)
    }
}
