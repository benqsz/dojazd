package app.dojazd.routing

import app.dojazd.gtfs.StopService
import app.dojazd.gtfs.StopTransfer
import app.dojazd.model.GeoPoint
import app.dojazd.model.TripRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JourneyPlannerTest {
    private val city = TestCity()

    // Far enough apart that nothing can be walked unless a test places stops closer.
    private val a = city.stop("A", 0.0)
    private val b = city.stop("B", 5_000.0)
    private val c = city.stop("C", 10_000.0)
    private val d = city.stop("D", 15_000.0)

    private fun plan(
        from: Endpoint,
        to: Endpoint,
        time: Int,
        options: JourneyPlanner.Options = JourneyPlanner.Options(),
    ) = city
        .planner(options)
        .plan(from, to, time)
        .journeys
        .map { it.describe() }

    @Test
    fun ridesDirectly() {
        // given
        city.trip("1", a at at(8), b at at(8, 10), c at at(8, 20))

        // when
        val journeys = plan(endpoint(a), endpoint(c), at(7, 30))

        // then
        assertEquals(listOf("[1 A 08:00-08:20 C]"), journeys)
    }

    @Test
    fun ridesNameTheRunAndCallWhereThePassengerGetsOn() {
        // given: the feed numbers the stops of the trip in tens
        city.trip("1", a at at(8), b at at(8, 10), c at at(8, 20), gtfsId = "t1", sequences = intArrayOf(10, 20, 30))
        city.trip("2", c at at(8, 30), d at at(8, 40), dayOffset = 0)

        // when
        val rides =
            city
                .planner()
                .plan(endpoint(b), endpoint(d), at(8))
                .journeys
                .single()
                .legs
                .filterIsInstance<Leg.Ride>()

        // then
        assertEquals(TripRef("t1", 20260302, 20), rides[0].trip)
        assertNull(rides[1].trip)
    }

    @Test
    fun listsEveryDepartureInTheWindowButNotThoseBeforeTheSearchTime() {
        // given
        for (hour in 6..12) city.trip("1", a at at(hour), b at at(hour, 10))

        // when
        val journeys = plan(endpoint(a), endpoint(b), at(8), JourneyPlanner.Options(minDepartureTimes = 1))

        // then: an hour's window from 08:00
        assertEquals(listOf("[1 A 08:00-08:10 B]", "[1 A 09:00-09:10 B]"), journeys)
    }

    @Test
    fun reachesIntoTheMorningWhenTheWindowIsEmpty() {
        // given
        city.trip("1", a at at(5), b at at(5, 10), dayOffset = 1)

        // when: nothing runs for the rest of the day
        val result = city.planner().plan(endpoint(a), endpoint(b), at(23))

        // then
        assertEquals(listOf("[1 A 29:00-29:10 B]"), result.journeys.map { it.describe() })
        assertEquals(at(29), result.searchedUntil)
    }

    @Test
    fun includesTripsOfThePreviousServiceDayStillRunning() {
        // given: a night run of yesterday's service, 24:30 on its own clock
        city.trip("N", a at at(24, 30), b at at(24, 45), dayOffset = -1)

        // when
        val journeys = plan(endpoint(a), endpoint(b), at(0, 10))

        // then
        assertEquals(listOf("[N A 00:30-00:45 B]"), journeys)
    }

    @Test
    fun changesAtTheSameStopOnlyWithEnoughTime() {
        // given: line 2 leaves B at 08:10:30 and at 08:20; line 1 arrives at 08:10
        city.trip("1", a at at(8), b at at(8, 10))
        city.trip("2", b at at(8, 10) + 30, c at at(8, 15))
        city.trip("2", b at at(8, 20), c at at(8, 25))

        // when
        val journeys = plan(endpoint(a), endpoint(c), at(7, 30))

        // then: 30 seconds is not enough to change
        assertEquals(listOf("[1 A 08:00-08:10 B, 2 B 08:20-08:25 C]"), journeys)
    }

    @Test
    fun leavesAsLateAsTheConnectionAllows() {
        // given: both runs of line 1 connect to the only run of line 2
        city.trip("1", a at at(8), b at at(8, 10))
        city.trip("1", a at at(8, 20), b at at(8, 30))
        city.trip("2", b at at(8, 40), c at at(8, 50))

        // when
        val journeys = plan(endpoint(a), endpoint(c), at(7, 30))

        // then: taking the 08:00 would only mean waiting longer at B
        assertEquals(listOf("[1 A 08:20-08:30 B, 2 B 08:40-08:50 C]"), journeys)
    }

    @Test
    fun offersBothTheFasterAndTheSimplerJourney() {
        // given: a slow direct line and a faster connection leaving at the same time
        city.trip("1", a at at(8), d at at(9))
        city.trip("2", a at at(8), b at at(8, 10))
        city.trip("3", b at at(8, 15), d at at(8, 30))

        // when
        val journeys = plan(endpoint(a), endpoint(d), at(7, 30))

        // then
        assertEquals(
            listOf("[2 A 08:00-08:10 B, 3 B 08:15-08:30 D]", "[1 A 08:00-09:00 D]"),
            journeys,
        )
    }

    @Test
    fun skipsAChangeThatSavesOnlyAMoment() {
        // given: changing at B instead of riding line 1 all the way means leaving two minutes later
        city.trip("1", a at at(8), b at at(8, 10), d at at(8, 40))
        city.trip("2", a at at(8, 2), b at at(8, 8))

        // when
        val journeys = plan(endpoint(a), endpoint(d), at(7, 30))

        // then
        assertEquals(listOf("[1 A 08:00-08:40 D]"), journeys)
    }

    @Test
    fun walksBetweenNearbyStopsToChange() {
        // given: B2 is 200 m from B
        val b2 = city.stop("B2", 5_200.0)
        city.trip("1", a at at(8), b at at(8, 10))
        city.trip("2", b2 at at(8, 15), c at at(8, 25))

        // when
        val journeys = plan(endpoint(a), endpoint(c), at(7, 30))

        // then: 200 m take 208 seconds
        assertEquals(listOf("[1 A 08:00-08:10 B, walk B-B2 08:10-08:13:28, 2 B2 08:15-08:25 C]"), journeys)
    }

    @Test
    fun feedTransfersOverrideTheEstimate() {
        // given: the feed says the 200 m take ten minutes, and that B to B3 cannot be walked
        val b2 = city.stop("B2", 5_200.0)
        val b3 = city.stop("B3", 4_900.0)
        city.transfers += StopTransfer(from = b.id, to = b2.id, possible = true, seconds = 600)
        city.transfers += StopTransfer(from = b.id, to = b3.id, possible = false, seconds = null)
        city.trip("1", a at at(8), b at at(8, 10))
        city.trip("2", b2 at at(8, 15), c at at(8, 25))
        city.trip("2", b2 at at(8, 45), c at at(8, 55))
        city.trip("3", b3 at at(8, 15), c at at(8, 20))

        // when
        val journeys = plan(endpoint(a), endpoint(c), at(7, 30))

        // then
        assertEquals(listOf("[1 A 08:00-08:10 B, walk B-B2 08:10-08:20, 2 B2 08:45-08:55 C]"), journeys)
    }

    @Test
    fun timesAChangeAlongStreets() {
        // given: B2 is 200 m from B, but the street between them goes round a block: 200 + 200 + 200 m
        val b2 = city.stop("B2", 5_200.0)
        val streets =
            walkNetwork(
                points = listOf(b.location, spot(5_000.0, 200.0), spot(5_200.0, 200.0), b2.location),
                ways = listOf(listOf(0, 1, 2, 3)),
            )
        city.trip("1", a at at(8), b at at(8, 10))
        city.trip("2", b2 at at(8, 15), c at at(8, 25))
        city.trip("2", b2 at at(8, 30), c at at(8, 40))

        // when
        val journeys = JourneyPlanner(city.network(streets)).plan(endpoint(a), endpoint(c), at(7, 30)).journeys

        // then: 600 m at 1.25 m/s take 480 seconds, too long for the run at 08:15 the straight line (208 s) would allow
        assertEquals(listOf("[1 A 08:00-08:10 B, walk B-B2 08:10-08:18, 2 B2 08:30-08:40 C]"), journeys.map { it.describe() })
    }

    @Test
    fun doesNotChangeBetweenStopsSeparatedByWater() {
        // given: B2 is 200 m from B as the crow flies, but on a street of its own that never meets B's
        val b2 = city.stop("B2", 5_200.0)
        val streets =
            walkNetwork(
                points = listOf(b.location, spot(5_000.0, 200.0), b2.location, spot(5_200.0, 200.0)),
                ways = listOf(listOf(0, 1), listOf(2, 3)),
            )
        city.trip("1", a at at(8), b at at(8, 10))
        city.trip("2", b2 at at(8, 15), c at at(8, 25))

        // when
        val journeys = JourneyPlanner(city.network(streets)).plan(endpoint(a), endpoint(c), at(7, 30)).journeys

        // then
        assertTrue(journeys.isEmpty(), journeys.joinToString { it.describe() })
    }

    @Test
    fun respectsStopsWithoutBoardingOrAlighting() {
        // given: the first run does not let passengers off at B, the second does not take any at A
        city.trip("1", a at at(8), TestCall(b, at(8, 10), dropOff = StopService.NONE), c at at(8, 20))
        city.trip("1", TestCall(a, at(8, 30), pickup = StopService.NONE), b at at(8, 40), c at at(8, 50))
        city.trip("1", a at at(9), b at at(9, 10), c at at(9, 20))

        // when
        val journeys = plan(endpoint(a), endpoint(b), at(7, 30), JourneyPlanner.Options(windowSeconds = 7_200))

        // then
        assertEquals(listOf("[1 A 09:00-09:10 B]"), journeys)
    }

    @Test
    fun marksRequestStops() {
        // given
        city.trip("1", TestCall(a, at(8), pickup = StopService.ON_REQUEST), b at at(8, 10))

        // when
        val ride =
            city
                .planner()
                .plan(endpoint(a), endpoint(b), at(7, 30))
                .journeys
                .single()
                .legs
                .single() as Leg.Ride

        // then
        assertTrue(ride.boardOnRequest)
        assertTrue(!ride.alightOnRequest)
    }

    @Test
    fun findsATripThatOvertakesAnEarlierOne() {
        // given: the express leaves later and arrives sooner
        city.trip("1", a at at(8), b at at(8, 30), c at at(9))
        city.trip("1", a at at(8, 5), b at at(8, 15), c at at(8, 25))

        // when
        val journeys = plan(endpoint(a), endpoint(c), at(7, 30))

        // then: the slow run is never worth taking
        assertEquals(listOf("[1 A 08:05-08:25 C]"), journeys)
    }

    @Test
    fun ridesAllStopsBetweenBoardingAndAlighting() {
        // given
        city.trip("1", a at at(8), TestCall(b, at(8, 10), departure = at(8, 12)), c at at(8, 20), d at at(8, 30))

        // when
        val ride =
            city
                .planner()
                .plan(endpoint(a), endpoint(c), at(7, 30))
                .journeys
                .single()
                .legs
                .single() as Leg.Ride

        // then: departure times on the way, the arrival time at the end
        assertEquals(listOf("A" to at(8), "B" to at(8, 12), "C" to at(8, 20)), ride.calls.map { it.stop.name to it.time })
    }

    @Test
    fun walksToAndFromStopsForAddresses() {
        // given: 300 m before A and 150 m past B
        val home = Endpoint(GeoPoint(a.location.lat - 300.0 / 111_320.0, a.location.lon))
        val work = Endpoint(GeoPoint(b.location.lat + 150.0 / 111_320.0, b.location.lon))
        city.trip("1", a at at(8), b at at(8, 10))

        // when
        val journey =
            city
                .planner()
                .plan(home, work, at(7))
                .journeys
                .single()

        // then: leaves home just in time for the bus
        assertEquals("[walk origin-A 07:54:48-08:00, 1 A 08:00-08:10 B, walk B-destination 08:10-08:12:36]", journey.describe())
        assertEquals(1, journey.rides)
    }

    @Test
    fun measuresTheWalkToAStopAlongStreets() {
        // given: home is 300 m south of A, but the only street leaves it eastwards and comes round: 200 + 300 + 200 m
        val home = spot(-300.0)
        val streets =
            walkNetwork(
                points = listOf(home, spot(-300.0, 200.0), spot(0.0, 200.0), a.location),
                ways = listOf(listOf(0, 1, 2, 3)),
            )
        city.trip("1", a at at(8), b at at(8, 10))

        // when
        val journey = JourneyPlanner(city.network(), walks = streets).plan(Endpoint(home), endpoint(b), at(7)).journeys.single()

        // then: 700 m at 1.25 m/s is 560 seconds, where the straight line would have suggested 312
        val walk = journey.legs.first() as Leg.Walk
        assertEquals("A", walk.to?.name)
        assertTrue(walk.arrival - walk.departure in 555..565, journey.describe())
    }

    @Test
    fun leavesOutAStopThatCannotBeWalkedTo() {
        // given: X is 200 m from home as the crow flies but across the water; A is 600 m away along the street
        val home = spot(-600.0)
        val x = city.stop("X", -600.0, 200.0)
        val streets =
            walkNetwork(
                points = listOf(home, a.location, x.location, spot(-600.0, 260.0)),
                ways = listOf(listOf(0, 1), listOf(2, 3)),
            )
        city.trip("1", a at at(8), b at at(8, 10))
        city.trip("2", x at at(8), b at at(8, 5))

        // when
        val journeys = JourneyPlanner(city.network(), walks = streets).plan(Endpoint(home), endpoint(b), at(7)).journeys

        // then: only the journey from A, although line 2 from X would arrive sooner
        assertEquals(
            listOf("1"),
            journeys.map { journey ->
                journey.legs
                    .filterIsInstance<Leg.Ride>()
                    .single()
                    .line.name
            },
        )
    }

    @Test
    fun usesTheNearestStopsWhenNoneIsWithinWalkingDistance() {
        // given: 2.5 km from A
        val farAway = Endpoint(GeoPoint(a.location.lat - 2_500.0 / 111_320.0, a.location.lon))
        city.trip("1", a at at(8), b at at(8, 10))

        // when
        val journey =
            city
                .planner()
                .plan(farAway, endpoint(b), at(7))
                .journeys
                .single()

        // then: 2.5 km take about 2600 seconds
        val walk = journey.legs.first() as Leg.Walk
        assertEquals("A", walk.to?.name)
        assertEquals(at(8), walk.arrival)
        assertTrue(walk.arrival - walk.departure in 2_590..2_610, journey.describe())
    }

    @Test
    fun offersWalkingAllTheWayWhenClose() {
        // given: E is 1 km from A; the bus takes longer than the walk, the tram does not
        val e = city.stop("E", 1_000.0)
        city.trip("slow", a at at(8), e at at(8, 30))
        city.trip("fast", a at at(8, 10), e at at(8, 13))

        // when
        val journeys = city.planner().plan(endpoint(a), endpoint(e), at(8)).journeys

        // then: 1 km takes about 1040 seconds
        val walk = journeys[0].legs.single() as Leg.Walk
        assertNull(walk.from)
        assertNull(walk.to)
        assertEquals(at(8), walk.departure)
        assertTrue(walk.arrival - walk.departure in 1_035..1_045, journeys[0].describe())
        assertEquals(listOf("[fast A 08:10-08:13 E]"), journeys.drop(1).map { it.describe() })
    }

    @Test
    fun onlyWalksWhenTheWalkIsShort() {
        // given: E is 200 m from A, about three and a half minutes on foot, and a tram would take one
        val e = city.stop("E", 200.0)
        city.trip("fast", a at at(8), e at at(8, 1))

        // when
        val plan = city.planner().plan(endpoint(a), endpoint(e), at(8))

        // then: walking is the only answer, and there is nothing later to look for
        val walk =
            plan.journeys
                .single()
                .legs
                .single() as Leg.Walk
        assertNull(walk.from)
        assertNull(walk.to)
        assertNull(plan.searchedUntil)
    }

    @Test
    fun reportsNothingWhenThereIsNoConnection() {
        // given
        city.trip("1", a at at(8), b at at(8, 10))

        // when
        val toUnserved = city.planner().plan(endpoint(a), endpoint(c), at(7))
        val afterLastRun = city.planner().plan(endpoint(a), endpoint(b), at(9))

        // then
        assertTrue(toUnserved.journeys.isEmpty())
        assertTrue(afterLastRun.journeys.isEmpty())
        assertNull(afterLastRun.searchedUntil)
    }

    @Test
    fun continuesAfterTheSearchedWindow() {
        // given
        for (hour in 6..12) city.trip("1", a at at(hour), b at at(hour, 10))
        val planner = city.planner(JourneyPlanner.Options(minDepartureTimes = 1))

        // when
        val first = planner.plan(endpoint(a), endpoint(b), at(8))
        val second = planner.plan(endpoint(a), endpoint(b), first.searchedUntil!! + 1)

        // then
        assertEquals(at(9), first.searchedUntil)
        assertEquals(listOf("[1 A 10:00-10:10 B]"), second.journeys.map { it.describe() })
    }
}
