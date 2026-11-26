package app.dojazd.model

import kotlin.test.Test
import kotlin.test.assertEquals

class TransitTest {
    @Test
    fun mapsBasicAndExtendedRouteTypes() {
        assertEquals(VehicleType.TRAM, VehicleType.fromGtfsRouteType(0))
        assertEquals(VehicleType.TRAM, VehicleType.fromGtfsRouteType(900))
        assertEquals(VehicleType.BUS, VehicleType.fromGtfsRouteType(3))
        assertEquals(VehicleType.BUS, VehicleType.fromGtfsRouteType(700))
        assertEquals(VehicleType.RAIL, VehicleType.fromGtfsRouteType(109))
        assertEquals(VehicleType.OTHER, VehicleType.fromGtfsRouteType(6))
    }

    @Test
    fun foldingIgnoresCaseAndPolishDiacritics() {
        assertEquals("lekno", SearchText.fold("Łękno"))
        assertEquals("zolnierza polskiego", SearchText.fold("Żołnierza Polskiego"))
    }
}
