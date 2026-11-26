package app.dojazd.storage

import app.dojazd.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class PlacesDocumentTest {
    private fun place(
        id: String,
        name: String,
        icon: String = "",
    ) = Place(id = id, name = name, icon = icon, address = "Polna 3, Szczecin", location = GeoPoint(53.4, 14.5))

    private val document = PlacesDocument(places = listOf(place("1", "Dom", "home"), place("2", "Basen"), place("3", "Biuro", "work")))

    private fun PlacesDocument.summary() = places.map { "${it.id} ${it.name} ${it.icon}".trim() }

    @Test
    fun addsANewPlaceAtTheEnd() {
        // when
        val changed = document.with(place("4", "Siłownia"))

        // then
        assertEquals(listOf("1", "2", "3", "4"), changed.places.map { it.id })
    }

    @Test
    fun anEditedPlaceKeepsItsPosition() {
        // when
        val changed = document.with(place("2", "Pływalnia", "pool"))

        // then
        assertEquals(listOf("1 Dom home", "2 Pływalnia pool", "3 Biuro work"), changed.summary())
    }

    @Test
    fun anyNumberOfPlacesMayShareAnIcon() {
        // when
        val changed = document.with(place("4", "Dom rodziców", "home"))

        // then
        assertEquals(listOf("1 Dom home", "2 Basen", "3 Biuro work", "4 Dom rodziców home"), changed.summary())
    }

    @Test
    fun removesAPlaceById() {
        assertEquals(listOf("1", "3"), document.without("2").places.map { it.id })
        assertEquals(document, document.without("unknown"))
    }

    @Test
    fun importKeepsWhatIsSavedAndTakesImportedValuesForTheSameId() {
        // given
        val imported = PlacesDocument(places = listOf(place("2", "Basen Floating Arena", "pool"), place("9", "Mieszkanie", "home")))

        // when
        val merged = document.merge(imported)

        // then
        assertEquals(listOf("1 Dom home", "2 Basen Floating Arena pool", "3 Biuro work", "9 Mieszkanie home"), merged.summary())
    }
}
