package app.dojazd.storage

import app.dojazd.model.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A location saved by the user, such as home or work. Never leaves the device. */
@Serializable
data class Place(
    val id: String,
    /** What the user calls the place: "Home", "Gym". */
    val name: String,
    /** Which of the app's place icons the user chose, such as "home" or "work"; empty for the plain one. */
    val icon: String = "",
    /** What was picked when the place was saved: an address or the name of a stop. Shown as it is, never edited. */
    val address: String = "",
    val location: GeoPoint,
)

/** The on-disk format of `places.json`; also the export/import format. */
@Serializable
data class PlacesDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val places: List<Place> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

/** Adds [place], or replaces the saved place with the same id, which then keeps its position in the list. */
fun PlacesDocument.with(place: Place): PlacesDocument {
    val position = places.indexOfFirst { it.id == place.id }
    return copy(places = if (position < 0) places + place else places.take(position) + place + places.drop(position + 1))
}

fun PlacesDocument.without(id: String): PlacesDocument = copy(places = places.filter { it.id != id })

/** Brings in the places of an imported file. Nothing saved is lost; a place with the same id takes the imported values. */
fun PlacesDocument.merge(imported: PlacesDocument): PlacesDocument = imported.places.fold(this) { document, place -> document.with(place) }

object PlacesCodec {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    fun encode(document: PlacesDocument): String = json.encodeToString(PlacesDocument.serializer(), document)

    fun decode(text: String): PlacesDocument = json.decodeFromString(PlacesDocument.serializer(), text)
}
