package app.dojazd.realtime

import app.dojazd.model.Departure
import app.dojazd.model.GeoPoint
import app.dojazd.model.Line
import app.dojazd.model.Stop
import app.dojazd.model.TripRef
import app.dojazd.model.VehicleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveTripsTest {
    private val today = 20261124
    private val yesterday = 20261123

    private fun update(
        tripId: String,
        startDate: String? = today.toString(),
        relationship: TripRelationship = TripRelationship.SCHEDULED,
        delay: Int? = null,
        vararg stops: StopTimeUpdate,
    ) = TripUpdate(
        trip = TripDescriptor(tripId, null, startDate, relationship),
        vehicleLabel = null,
        stopTimeUpdates = stops.toList(),
        timestamp = null,
        delay = delay,
    )

    private fun stop(
        sequence: Int,
        delay: Int? = null,
        time: Long? = null,
        relationship: StopRelationship = StopRelationship.SCHEDULED,
    ) = StopTimeUpdate(sequence, null, null, StopTimeEvent(delay, time).takeIf { delay != null || time != null }, relationship)

    @Test
    fun delayCarriesOnToLaterStops() {
        // given
        val trips = LiveTrips(listOf(update("t1", stops = arrayOf(stop(2, delay = 60), stop(5, delay = 180)))))

        // then
        assertNull(trips.prediction(TripRef("t1", today, 1)))
        assertEquals(Prediction.Delay(60), trips.prediction(TripRef("t1", today, 2)))
        assertEquals(Prediction.Delay(60), trips.prediction(TripRef("t1", today, 4)))
        assertEquals(Prediction.Delay(180), trips.prediction(TripRef("t1", today, 9)))
    }

    @Test
    fun skippedAndUnknownStops() {
        // given
        val trips =
            LiveTrips(
                listOf(
                    update(
                        "t1",
                        stops =
                            arrayOf(
                                stop(2, delay = 60),
                                stop(3, relationship = StopRelationship.SKIPPED),
                                stop(6, relationship = StopRelationship.NO_DATA),
                            ),
                    ),
                ),
            )

        // then
        assertEquals(Prediction.Skipped, trips.prediction(TripRef("t1", today, 3)))
        // A skipped stop says nothing about the delay, so the one before it still holds.
        assertEquals(Prediction.Delay(60), trips.prediction(TripRef("t1", today, 4)))
        assertNull(trips.prediction(TripRef("t1", today, 6)))
        assertNull(trips.prediction(TripRef("t1", today, 7)))
    }

    @Test
    fun cancelledRunsAndTripDelay() {
        // given
        val trips =
            LiveTrips(
                listOf(
                    update("gone", relationship = TripRelationship.CANCELED),
                    update("late", delay = 240),
                ),
            )

        // then
        assertEquals(Prediction.Cancelled, trips.prediction(TripRef("gone", today, 1)))
        assertEquals(Prediction.Delay(240), trips.prediction(TripRef("late", today, 1)))
        assertNull(trips.prediction(TripRef("unknown", today, 1)))
    }

    @Test
    fun matchesTheRunOfTheRightServiceDay() {
        // given: the same trip of yesterday is still running after midnight
        val trips =
            LiveTrips(
                listOf(
                    update("night", startDate = yesterday.toString(), delay = 120),
                    update("night", startDate = today.toString(), delay = 0),
                    update("undated", startDate = null, delay = 30),
                ),
            )

        // then
        assertEquals(Prediction.Delay(120), trips.prediction(TripRef("night", yesterday, 1)))
        assertEquals(Prediction.Delay(0), trips.prediction(TripRef("night", today, 1)))
        assertEquals(Prediction.Delay(30), trips.prediction(TripRef("undated", today, 1)))
    }

    @Test
    fun callsNamedByStopAloneAreMatchedByStop() {
        // given a producer that gives no stop sequences
        val byStop = StopTimeUpdate(null, "s7", null, StopTimeEvent(120, null), StopRelationship.SCHEDULED)
        val trips = LiveTrips(listOf(update("t1", stops = arrayOf(byStop))))

        // then
        assertEquals(Prediction.Delay(120), trips.prediction(TripRef("t1", today, 4, stopGtfsId = "s7")))
        assertNull(trips.prediction(TripRef("t1", today, 4, stopGtfsId = "s8")))
        assertNull(trips.prediction(TripRef("t1", today, 4)))
    }

    @Test
    fun absoluteTimesBecomeDelays() {
        // given
        val trips = LiveTrips(listOf(update("t1", stops = arrayOf(stop(3, time = 10_090)))))

        // then
        assertEquals(Prediction.Delay(90), trips.prediction(TripRef("t1", today, 3), scheduledEpoch = 10_000))
        assertNull(trips.prediction(TripRef("t1", today, 3)))
    }

    @Test
    fun vehicleDelayAtItsCurrentStop() {
        // given
        val trips = LiveTrips(listOf(update("t1", stops = arrayOf(stop(2, delay = 60), stop(5, delay = 180)))))

        // then
        assertEquals(60, trips.delay("t1", today, 4))
        assertEquals(180, trips.delay("t1", today, 5))
        assertEquals(180, trips.delay("t1", today, null))
    }

    @Test
    fun boardShowsLateVehiclesAndDropsGoneOnes() {
        // given: it is 12:00
        val now = 12 * 3600
        val departures =
            listOf(
                departure("late", now - 300),
                departure("left", now - 300),
                departure("onTime", now + 60),
                departure("early", now + 120),
                departure("cancelledPast", now - 60),
                departure("cancelled", now + 600),
                departure("plain", now + 300),
            )
        val trips =
            LiveTrips(
                listOf(
                    update("late", delay = 420),
                    update("left", delay = 120),
                    update("onTime", delay = 0),
                    update("early", delay = -150),
                    update("cancelledPast", relationship = TripRelationship.CANCELED),
                    update("cancelled", relationship = TripRelationship.CANCELED),
                ),
            )

        // when
        val board = LiveBoard.merge(departures, trips, midnightEpoch = 0, now = now, limit = 10)

        // then
        assertEquals(listOf("early", "onTime", "late", "plain", "cancelled"), board.map { it.departure.headsign })
        assertEquals(now - 30, board[0].time)
        assertEquals(false, board.last().runs)
    }

    private val line = Line(1, "75", "", "", VehicleType.BUS, null, null)
    private val stop = Stop(1, "s1", "Alfa", "", GeoPoint(53.4, 14.5))

    private fun departure(
        trip: String,
        time: Int,
    ) = Departure(
        time = time,
        line = line,
        headsign = trip,
        stop = stop,
        onRequest = false,
        lowFloor = null,
        trip = TripRef(trip, today, 1),
    )
}
