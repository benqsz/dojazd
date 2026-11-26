package app.dojazd.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GtfsRealtimeTest {
    @Test
    fun readsTripUpdates() {
        // given
        val bytes =
            feed(timestamp = 1_795_536_693) {
                message(2) {
                    string(1, "e1")
                    message(3) {
                        tripDescriptor(1, "521498_POWS", startDate = "20261124")
                        message(3) {
                            string(1, "6")
                            string(2, "1053")
                        }
                        stopTimeUpdate(sequence = 3, delay = 44)
                        stopTimeUpdate(sequence = 4, delay = -90)
                        stopTimeUpdate(sequence = 5, relationship = 1)
                    }
                }
            }

        // when
        val feed = GtfsRealtime.parse(bytes)

        // then
        assertEquals(1_795_536_693, feed.timestamp)
        val update = feed.tripUpdates.single()
        assertEquals("521498_POWS", update.trip.tripId)
        assertEquals("20261124", update.trip.startDate)
        assertEquals("1053", update.vehicleLabel)
        assertEquals(listOf(44, -90, null), update.stopTimeUpdates.map { it.departure?.delay })
        assertEquals(StopRelationship.SKIPPED, update.stopTimeUpdates[2].relationship)
    }

    @Test
    fun readsVehiclePositions() {
        // given
        val bytes =
            feed {
                message(2) {
                    string(1, "7")
                    message(4) {
                        tripDescriptor(1, "521488_POWS", startDate = "20261124")
                        message(2) {
                            float(1, 53.4378f)
                            float(2, 14.5731f)
                            float(3, 90f)
                            float(5, 6.9f)
                        }
                        int(3, 31)
                        varint(5, 1_795_536_675)
                        message(8) {
                            string(1, "7")
                            string(2, "1054")
                        }
                    }
                }
                // A vehicle without a known position is left out.
                message(2) {
                    string(1, "8")
                    message(4) { tripDescriptor(1, "x") }
                }
            }

        // when
        val vehicle = GtfsRealtime.parse(bytes).vehicles.single()

        // then
        assertEquals("7", vehicle.id)
        assertEquals("1054", vehicle.label)
        assertEquals("521488_POWS", vehicle.trip?.tripId)
        assertEquals(53.4378, vehicle.position.lat, 1e-4)
        assertEquals(14.5731, vehicle.position.lon, 1e-4)
        assertEquals(90f, vehicle.bearing)
        assertEquals(31, vehicle.currentStopSequence)
        assertEquals(1_795_536_675, vehicle.timestamp)
    }

    @Test
    fun readsAlertsWithTranslations() {
        // given
        val bytes =
            feed {
                message(2) {
                    string(1, "1055")
                    message(5) {
                        message(1) { varint(1, 1_787_090_460) }
                        message(5) { string(5, "101") }
                        message(5) { string(2, "75") }
                        int(7, 9)
                        message(10) {
                            message(1) {
                                string(1, "Przeniesiony przystanek")
                                string(2, "pl")
                            }
                            message(1) {
                                string(1, "Stop moved")
                                string(2, "en")
                            }
                        }
                    }
                }
            }

        // when
        val alert = GtfsRealtime.parse(bytes).alerts.single()

        // then
        assertEquals("1055", alert.id)
        assertEquals(AlertEffect.STOP_MOVED, alert.effect)
        assertEquals(listOf("101", null), alert.entities.map { it.stopId })
        assertEquals(listOf(null, "75"), alert.entities.map { it.routeId })
        assertEquals("Stop moved", alert.header.text("en-GB"))
        assertEquals("Przeniesiony przystanek", alert.header.text("pl"))
        assertEquals("Przeniesiony przystanek", alert.header.text("de"))
        assertTrue(alert.description.isEmpty)
        assertTrue(alert.isActiveAt(1_787_090_460))
        assertTrue(!alert.isActiveAt(1_787_090_459))
    }

    @Test
    fun skipsDeletedEntitiesAndUnknownFields() {
        // given
        val bytes =
            feed {
                int(99, 7)
                message(2) {
                    string(1, "gone")
                    varint(2, 1)
                    message(3) { tripDescriptor(1, "t1") }
                }
                message(2) {
                    string(1, "kept")
                    message(3) {
                        tripDescriptor(1, "t2")
                        string(42, "something new")
                    }
                }
            }

        // when
        val feed = GtfsRealtime.parse(bytes)

        // then
        assertEquals(listOf("t2"), feed.tripUpdates.map { it.trip.tripId })
    }

    @Test
    fun rejectsTruncatedData() {
        // given
        val bytes = feed { message(2) { string(1, "e1") } }

        // when / then
        assertFailsWith<ProtoFormatException> { GtfsRealtime.parse(bytes.copyOf(bytes.size - 2)) }
    }
}
