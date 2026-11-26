package app.dojazd.gtfs

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.dojazd.gtfs.db.GtfsDatabase
import app.dojazd.model.GeoPoint
import app.dojazd.model.TripRef
import app.dojazd.model.VehicleType
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimetableTest {
    private val monday = LocalDate(2026, 3, 2)
    private val tuesday = LocalDate(2026, 3, 3)
    private val holiday = LocalDate(2026, 5, 1)

    private fun import(
        feed: GtfsFeed,
        meta: Map<String, String> = emptyMap(),
    ): Timetable {
        val db = newInMemoryDatabase()
        GtfsImporter(db).import(feed, meta)
        return Timetable(db)
    }

    private val timetable = import(testFeed, meta = mapOf(Timetable.META_ETAG to "\"abc\""))

    private fun stopId(code: String) = timetable.stops().first { it.code == code }.id

    private val line75 = timetable.lines().first { it.name == "75" }

    private fun at(
        hours: Int,
        minutes: Int = 0,
    ) = hours * 3600 + minutes * 60

    @Test
    fun importsLinesInFeedOrderWithTypeAndColour() {
        // when
        val lines = timetable.lines()

        // then
        assertEquals(listOf("1", "75"), lines.map { it.name })
        assertEquals(VehicleType.TRAM, lines[0].type)
        assertNull(lines[0].color)
        assertEquals(VehicleType.BUS, line75.type)
        assertEquals(0xB3040F, line75.color)
        assertEquals("Linia zwykła", line75.category)
    }

    @Test
    fun importsOnlyBoardingPoints() {
        assertEquals(listOf("Alfa", "Beta", "Gamma", "Delta"), timetable.stops().map { it.name })
    }

    @Test
    fun boardingStopsExcludeNoBoardingAndArrivalOnlyStops() {
        // given: Beta never allows boarding, Delta is only ever a terminus, Gamma is not served
        val feed =
            testFeed.with(
                "stop_times.txt",
                """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type
                t1,08:00:00,08:00:00,100,1,0
                t1,08:05:00,08:05:00,200,2,1
                t1,08:20:00,08:20:00,400,3,0
                """.trimIndent(),
            )

        // when
        val imported = import(feed)

        // then
        assertEquals(listOf("Alfa"), imported.boardingStops().map { it.name })
        assertEquals(4, imported.stops().size)
    }

    @Test
    fun storesMeta() {
        assertEquals("\"abc\"", timetable.meta(Timetable.META_ETAG))
        assertNull(timetable.meta("unknown"))
    }

    @Test
    fun directionUsesTheMostFrequentStopSequence() {
        // when
        val directions = timetable.directions(line75.id)

        // then: t1 and t2 share a pattern; the short t3 and night t5 runs are variants
        assertEquals(listOf(0, 1), directions.map { it.direction })
        assertEquals("Delta", directions[0].headsign)
        assertEquals(listOf("Alfa", "Beta", "Gamma", "Delta"), directions[0].stops.map { it.name })
        assertEquals(listOf("Delta", "Gamma", "Alfa"), directions[1].stops.map { it.name })
        assertEquals(
            listOf(listOf("Alfa", "Beta", "Delta"), listOf("Alfa", "Beta")),
            directions[0].variants.map { variant -> variant.stops.map { it.name } },
        )
        assertTrue(directions[1].variants.isEmpty())
    }

    @Test
    fun upcomingDeparturesContinueIntoTheNextDay() {
        // when
        val departures = timetable.upcomingDepartures(listOf(stopId("s1")), monday, fromSeconds = at(8, 30))

        // then: rest of Monday, the night run, then Tuesday offset by a day
        assertEquals(
            listOf(at(9), at(10), at(24, 30), at(24 + 8), at(24 + 9)),
            departures.take(5).map { it.time },
        )
        assertEquals(listOf("Delta", "Beta", "Delta"), departures.take(3).map { it.headsign })
    }

    @Test
    fun upcomingDeparturesIncludeTripsOfThePreviousServiceDayAfterMidnight() {
        // when: 00:10 on Tuesday
        val departures = timetable.upcomingDepartures(listOf(stopId("s1")), tuesday, fromSeconds = at(0, 10))

        // then: Monday's night run leaves at 00:30
        assertEquals(at(0, 30), departures.first().time)
        assertEquals(at(8), departures[1].time)
    }

    @Test
    fun finalStopAndNoBoardingStopsAreNotDepartures() {
        // when
        val fromDelta = timetable.upcomingDepartures(listOf(stopId("s4")), monday, fromSeconds = 0, limit = 1)
        val fromGamma = timetable.lineDeparturesAtStop(line75.id, stopId("s3"), monday)

        // then: Delta is the terminus of direction 0, so only the return trip leaves from it
        assertEquals(listOf(at(15)), fromDelta.map { it.time })
        // and t1 does not allow boarding at Gamma
        assertEquals(listOf(at(9, 10), at(15, 10)), fromGamma.map { it.time })
    }

    @Test
    fun departuresCarryRequestStopAndAccessibility() {
        // when
        val fromBeta = timetable.lineDeparturesAtStop(line75.id, stopId("s2"), monday)

        // then: t1 regular and low-floor, t2 on request and high-floor, t5 night run
        assertEquals(listOf(false, true, false), fromBeta.map { it.onRequest })
        assertEquals(listOf(true, false, true), fromBeta.map { it.lowFloor })
    }

    @Test
    fun calendarExceptionsReplaceTheRegularService() {
        // when
        val departures = timetable.lineDeparturesAtStop(line75.id, stopId("s1"), holiday)

        // then: only the holiday trip, signed with its final stop because it has no headsign
        assertEquals(listOf(at(12)), departures.map { it.time })
        assertEquals("Delta", departures.single().headsign)
    }

    @Test
    fun stopTimetableGroupsByHourAndMarksVariants() {
        // when
        val table = StopTimetable.of(timetable.lineDeparturesAtStop(line75.id, stopId("s1"), monday))

        // then
        assertEquals(listOf(8, 9, 10, 0), table.hours.map { it.hour })
        assertEquals(listOf(0, 0, 0, 30), table.hours.map { it.minutes.single().minute })
        assertEquals(listOf(null, null, 'a', null), table.hours.map { it.minutes.single().mark })
        assertEquals(listOf(StopTimetable.Variant('a', "Beta")), table.legend)
    }

    @Test
    fun tripsOfADayCarryEveryStopTimeInTravelOrder() {
        // when
        val trips = timetable.tripsOn(monday)
        val patterns = timetable.patterns().associateBy { it.id }

        // then: t1 to t5 run on weekdays
        assertEquals(5, trips.size)
        val t1 = trips.first { it.departures.first() == at(8) }
        assertEquals(listOf("Alfa", "Beta", "Gamma", "Delta"), patterns.getValue(t1.patternId).stops.map { it.name })
        assertEquals("75", patterns.getValue(t1.patternId).line.name)
        assertEquals(listOf(at(8), at(8, 5), at(8, 10), at(8, 20)), t1.arrivals.toList())
        assertEquals(listOf<Byte>(0, 0, StopService.NONE, 0), t1.pickups.toList())
        assertEquals("Delta", t1.headsign)
        assertTrue(timetable.tripsOn(LocalDate(2026, 3, 8)).isEmpty(), "nothing runs on Sundays")
    }

    @Test
    fun importsDropOffTypesAndStopTransfers() {
        // given: t1 lets nobody off at Beta; transfers include a forbidden one and rows the importer must skip
        val feed =
            testFeed
                .with(
                    "stop_times.txt",
                    """
                    trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type
                    t1,08:00:00,08:00:00,100,1,0,0
                    t1,08:05:00,08:05:00,200,2,0,1
                    t1,08:20:00,08:20:00,400,3,0,3
                    """.trimIndent(),
                ).with(
                    "transfers.txt",
                    """
                    from_stop_id,to_stop_id,transfer_type,min_transfer_time,from_route_id
                    100,200,2,180,
                    200,100,3,,
                    100,300,0,,
                    100,100,2,60,
                    100,400,2,90,B75
                    100,999,2,60,
                    """.trimIndent(),
                )

        // when
        val imported = import(feed)

        // then
        assertEquals(
            listOf<Byte>(0, StopService.NONE, StopService.ON_REQUEST),
            imported
                .tripsOn(monday)
                .single()
                .dropOffs
                .toList(),
        )
        val names = imported.stops().associate { it.id to it.name }
        assertEquals(
            setOf("Alfa-Beta true 180", "Beta-Alfa false null", "Alfa-Gamma true null"),
            imported.transfers().map { "${names[it.from]}-${names[it.to]} ${it.possible} ${it.seconds}" }.toSet(),
        )
        assertTrue(timetable.transfers().isEmpty(), "a feed without transfers.txt has none")
    }

    @Test
    fun importsThePathOfATripWhateverTheOrderInTheFile() {
        // given: t1 follows shape "main", whose points are listed out of order and mixed with another shape's
        val feed =
            testFeed
                .with(
                    "trips.txt",
                    """
                    route_id,service_id,trip_id,trip_headsign,direction_id,shape_id
                    B75,WD,t1,Delta,0,main
                    B75,WD,t2,Delta,0,
                    """.trimIndent(),
                ).with(
                    "shapes.txt",
                    """
                    shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence
                    main,53.430000,14.530000,30
                    other,53.000000,14.000000,1
                    main,53.400000,14.500000,10
                    main,53.415000,14.512345,20
                    """.trimIndent(),
                )

        // when
        val imported = import(feed)
        val trips = imported.tripsOn(monday).sortedBy { it.departures.first() }

        // then
        val shapeId = trips[0].shapeId
        assertTrue(shapeId != null)
        assertEquals(
            listOf(GeoPoint(53.4, 14.5), GeoPoint(53.415, 14.512345), GeoPoint(53.43, 14.53)),
            imported.shape(shapeId),
        )
        assertNull(trips[1].shapeId)
        assertNull(timetable.tripsOn(monday).first().shapeId, "a feed without shapes.txt has none")
        assertTrue(imported.shape(-1).isEmpty())
    }

    @Test
    fun rejectsStopTimesNotGroupedByTrip() {
        // given
        val feed =
            testFeed.with(
                "stop_times.txt",
                """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                t1,08:00:00,08:00:00,100,1
                t2,09:00:00,09:00:00,100,1
                t1,08:05:00,08:05:00,200,2
                """.trimIndent(),
            )

        // then
        val error = assertFailsWith<GtfsFormatException> { import(feed) }
        assertTrue("t1" in error.message.orEmpty(), error.message)
    }

    @Test
    fun rejectsMalformedValuesAsAFormatError() {
        // given a time that is no time, and a quote that is never closed
        val badTime =
            testFeed.with(
                "stop_times.txt",
                "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nt1,8 o'clock,08:00:00,100,1",
            )
        val strayQuote = testFeed.with("stops.txt", "stop_id,stop_name\n100,\"Alfa\n" + "200,Beta\n".repeat(200_000))

        // then both fail the same way as any other feed that cannot be read
        assertFailsWith<GtfsFormatException> { import(badTime) }
        assertFailsWith<GtfsFormatException> { import(strayQuote) }
    }

    @Test
    fun leavesOutCoordinatesThatAreNotOnTheGlobe() {
        // given
        val feed =
            testFeed.with(
                "stops.txt",
                """
                stop_id,stop_code,stop_name,stop_lat,stop_lon
                100,s1,Alfa,Infinity,14.5
                200,s2,Beta,91.0,NaN
                300,s3,Gamma,53.4,14.6
                400,s4,Delta,53.5,14.7
                """.trimIndent(),
            )

        // when
        val stops = import(feed).stops()

        // then
        assertEquals(listOf(GeoPoint(0.0, 14.5), GeoPoint(0.0, 0.0), GeoPoint(53.4, 14.6)), stops.take(3).map { it.location })
    }

    @Test
    fun spreadsTimesOverStopsThatHaveNone() {
        // given a trip timed only at its first and last stop
        val feed =
            testFeed.with(
                "stop_times.txt",
                """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                t1,08:00:00,08:00:00,100,1
                t1,,,200,2
                t1,,,300,3
                t1,08:30:00,08:30:00,400,4
                """.trimIndent(),
            )

        // when
        val trip = import(feed).tripsOn(monday).single()

        // then
        assertEquals(listOf(at(8), at(8, 10), at(8, 20), at(8, 30)), trip.departures.toList())
    }

    @Test
    fun runsATripOfFrequenciesAtEveryHeadway() {
        // given a trip that says how long the run takes, and when it is run
        val feed =
            testFeed
                .with(
                    "stop_times.txt",
                    """
                    trip_id,arrival_time,departure_time,stop_id,stop_sequence
                    t1,00:00:00,00:00:00,100,1
                    t1,00:10:00,00:10:00,400,2
                    t7,07:00:00,07:00:00,100,1
                    t7,07:30:00,07:30:00,400,2
                    """.trimIndent(),
                ).with(
                    "frequencies.txt",
                    """
                    trip_id,start_time,end_time,headway_secs
                    t1,06:00:00,07:00:00,1200
                    """.trimIndent(),
                )

        // when
        val trips = import(feed).tripsOn(monday)

        // then it leaves at 6:00, 6:20 and 6:40, taking ten minutes each time
        assertEquals(listOf(at(6), at(6, 20), at(6, 40)), trips.map { it.departures.first() }.sorted())
        assertTrue(trips.all { it.arrivals.last() - it.departures.first() == at(0, 10) })
    }

    @Test
    fun knowsTheLastDayOfService() {
        assertEquals(20261231, timetable.lastServiceDate())
    }

    @Test
    fun rejectsFeedWithMissingFileOrColumn() {
        assertFailsWith<GtfsFormatException> { import(testFeed.without("trips.txt")) }
        assertFailsWith<GtfsFormatException> { import(testFeed.with("stops.txt", "stop_name\nAlfa")) }
        assertFailsWith<GtfsFormatException> {
            import(testFeed.without("calendar.txt").without("calendar_dates.txt"))
        }
    }

    @Test
    fun failedImportLeavesDatabaseEmpty() {
        // given
        val db = newInMemoryDatabase()

        // when
        assertFailsWith<GtfsFormatException> { GtfsImporter(db).import(testFeed.without("stop_times.txt")) }

        // then
        assertTrue(Timetable(db).lines().isEmpty())
    }

    @Test
    fun formatsTimesWrappingPastMidnight() {
        assertEquals("08:05", GtfsTime.format(at(8, 5)))
        assertEquals("00:30", GtfsTime.format(at(24, 30)))
        assertEquals("23:50", GtfsTime.format(-at(0, 10)))
    }

    @Test
    fun departuresNameTheirTripRunForLiveData() {
        // when: just after midnight on Tuesday, yesterday's night run t5 is still to leave Beta
        val departures = timetable.upcomingDepartures(listOf(stopId("s2")), tuesday, fromSeconds = at(0, 35), limit = 2)

        // then
        assertEquals(
            listOf(TripRef("t5", monday.toGtfsDate(), 2, "200"), TripRef("t1", tuesday.toGtfsDate(), 2, "200")),
            departures.map { it.trip },
        )
    }

    @Test
    fun findsStopsAndLinesByFeedIds() {
        // then
        assertEquals("Beta", timetable.stopByGtfsId("200")?.name)
        assertEquals(line75, timetable.lineByGtfsId("B75"))
        assertNull(timetable.stopByGtfsId("900"))
    }

    @Test
    fun knowsWhichVehiclesCallAtEachStop() {
        // when
        val types = timetable.vehicleTypesByStop()

        // then: tram 1 and bus 75 both serve Alfa and Delta, only the bus serves Beta and Gamma
        assertEquals(setOf(VehicleType.TRAM, VehicleType.BUS), types[stopId("s1")])
        assertEquals(setOf(VehicleType.BUS), types[stopId("s2")])
        assertEquals(setOf(VehicleType.BUS), types[stopId("s3")])
        assertEquals(setOf(VehicleType.TRAM, VehicleType.BUS), types[stopId("s4")])
    }

    @Test
    fun importWithTheDriverKeepsTheIndexes() {
        // given
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GtfsDatabase.Schema.create(driver)
        val db = GtfsDatabase(driver)

        // when: indexes are dropped for the import and built again at its end
        GtfsImporter(db, driver).import(testFeed)

        // then
        val indexes =
            driver
                .executeQuery(null, "SELECT name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL", { cursor ->
                    val names = ArrayList<String>()
                    while (cursor.next().value) names += cursor.getString(0)!!
                    QueryResult.Value(names)
                }, 0)
                .value
        assertEquals(setOf("pattern_route", "stop_time_stop"), indexes.toSet())
        assertEquals(2, Timetable(db).upcomingDepartures(listOf(stopId("s1")), monday, at(8, 30), limit = 2).size)
    }
}
