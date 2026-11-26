package app.dojazd.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A place the user recently chose as one end of a journey. */
@Serializable
data class RecentPlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val kind: Kind,
) {
    /**
     * What was chosen, which decides the icon; a stop is found again by its
     * name, as its ids change with the timetable, and the user's location is
     * found anew each time.
     */
    @Serializable
    enum class Kind { STOP, ADDRESS, PLACE, POINT, LOCATION }
}

/** A journey the user recently searched for. */
@Serializable
data class RecentJourney(
    val from: RecentPlace,
    val to: RecentPlace,
)

/** Remembers, per city, the last few things of one sort the user chose. Kept on the device only. */
open class RecentRepository<T>(
    context: Context,
    name: String,
    itemSerializer: KSerializer<T>,
    private val maxItems: Int,
    /** False while the user wants no history kept: nothing is added then. */
    private val enabled: () -> Boolean,
    /** True when the two stand for the same thing, so that only the newer is kept. */
    private val same: (T, T) -> Boolean,
) {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(itemSerializer)
    private val flows = HashMap<String, MutableStateFlow<List<T>>>()

    /** What was recently chosen in the city with id [cityId], latest first. */
    @Synchronized
    fun of(cityId: String): StateFlow<List<T>> = flow(cityId).asStateFlow()

    /** Puts [item] at the top of the city's list, dropping the oldest when the list is full. */
    @Synchronized
    fun add(
        cityId: String,
        item: T,
    ) {
        if (!enabled()) return
        val flow = flow(cityId)
        val updated = (listOf(item) + flow.value.filterNot { same(it, item) }).take(maxItems)
        flow.value = updated
        prefs.edit { putString(cityId, json.encodeToString(serializer, updated)) }
    }

    /** Forgets everything remembered here, in every city. */
    @Synchronized
    fun clear() {
        prefs.edit { clear() }
        flows.values.forEach { it.value = emptyList() }
    }

    private fun flow(cityId: String) =
        flows.getOrPut(cityId) {
            val stored = prefs.getString(cityId, null)
            val items =
                try {
                    stored?.let { json.decodeFromString(serializer, it) }.orEmpty()
                } catch (e: SerializationException) {
                    emptyList()
                } catch (e: IllegalArgumentException) {
                    emptyList()
                }
            MutableStateFlow(items)
        }
}

/** The last few places chosen in the journey planner. */
class RecentPlacesRepository(
    context: Context,
    enabled: () -> Boolean,
) : RecentRepository<RecentPlace>(
        context = context,
        name = "recent_places",
        itemSerializer = RecentPlace.serializer(),
        maxItems = 8,
        enabled = enabled,
        same = { a, b -> a.name == b.name && a.kind == b.kind },
    )

/** The last few journeys searched for, to search again with one tap. */
class RecentJourneysRepository(
    context: Context,
    enabled: () -> Boolean,
) : RecentRepository<RecentJourney>(
        context = context,
        name = "recent_journeys",
        itemSerializer = RecentJourney.serializer(),
        maxItems = 5,
        enabled = enabled,
        same = { a, b -> a.from.sameEnd(b.from) && a.to.sameEnd(b.to) },
    )

internal fun RecentPlace.sameEnd(other: RecentPlace) = kind == other.kind && (kind == RecentPlace.Kind.LOCATION || name == other.name)
