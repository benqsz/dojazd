package app.dojazd.routing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.dojazd.gtfs.GtfsImporter
import app.dojazd.gtfs.ShapeSection
import app.dojazd.gtfs.Timetable
import app.dojazd.gtfs.ZipGtfsFeed
import app.dojazd.gtfs.db.GtfsDatabase
import app.dojazd.model.GeoPoint
import app.dojazd.model.Stop
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/**
 * Plans journeys over a real GTFS archive and prints what it finds. Skipped unless a path is supplied:
 * `./gradlew :shared:routing:jvmTest -Pdojazd.gtfsZip=/path/to/gtfs.zip`
 */
class RealFeedPlannerTest {
    @Test
    fun plansJourneysOverRealFeed() {
        val path = System.getProperty("dojazd.gtfsZip") ?: return println("RealFeedPlannerTest skipped: no dojazd.gtfsZip")

        // given
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        GtfsDatabase.Schema.create(driver)
        val db = GtfsDatabase(driver)
        GtfsImporter(db).import(ZipGtfsFeed(File(path)))
        val timetable = Timetable(db)

        // The first Wednesday the feed has service on, so the test does not depend on today's date.
        val start = LocalDate(2020, 1, 1)
        val date =
            generateSequence(start) { it.plus(1, DateTimeUnit.DAY) }
                .take(5_000)
                .firstOrNull { it.dayOfWeek == kotlinx.datetime.DayOfWeek.WEDNESDAY && timetable.tripsOn(it).isNotEmpty() }
                ?: return println("RealFeedPlannerTest skipped: no service found")

        // when
        val walks = WalkNetwork.read(File(System.getProperty("dojazd.walks")).readBytes())
        val stops = timetable.stops()
        val estimated = Footpaths.measure(stops)
        val (footpaths, footpathTime) = measureTimedValue { Footpaths.measure(stops, walks) }
        println("${footpaths.size} walks between stops measured along streets in $footpathTime; ${estimated.size} by straight line")
        val slower =
            stops.sumOf { stop ->
                footpaths.from(stop.id).count { walk -> walk.seconds > (estimated.from(stop.id).first { it.to == walk.to }.seconds) }
            }
        println("$slower of them take longer than the straight-line estimate said")
        val (network, buildTime) = measureTimedValue { TransitNetwork.load(timetable, date, footpaths) }
        val planner = JourneyPlanner(network, walks = walks)
        println("Network for $date: ${network.stops.size} stops, ${network.routeCount} routes, built in $buildTime")

        val served = network.stops.filterIndexed { index, _ -> network.isServed(index) }
        val random = Random(1)
        var found = 0
        var rides = 0
        var ridesOnTheirPath = 0
        val shapes = HashMap<Long, List<GeoPoint>>()
        val queries = 200
        val searchTime =
            measureTime {
                repeat(queries) { query ->
                    val from = served.random(random)
                    val to = served.random(random)
                    val time = random.nextInt(5 * 3600, 23 * 3600)
                    val plan = planner.plan(from.asAddress(), to.asAddress(), time)
                    if (plan.journeys.isNotEmpty()) found++
                    if (query < 5) {
                        println("${from.name} -> ${to.name} from ${time / 3600}:${(time % 3600 / 60).toString().padStart(2, '0')}")
                        plan.journeys.forEach { println("  ${it.describe()}") }
                    }
                    plan.journeys.flatMap { it.legs }.filterIsInstance<Leg.Ride>().forEach { ride ->
                        rides++
                        val shape = ride.shapeId?.let { id -> shapes.getOrPut(id) { timetable.shape(id) } }
                        if (shape != null && ShapeSection.between(shape, ride.from.location, ride.to.location) != null) ridesOnTheirPath++
                    }
                    plan.journeys.forEach { journey ->
                        assertTrue(journey.departure >= time, "leaves before the search time: ${journey.describe()}")
                        journey.legs.zipWithNext { a, b -> assertTrue(b.departure >= a.arrival, "legs overlap: ${journey.describe()}") }
                    }
                }
            }
        println("$found of $queries random queries found a journey; ${searchTime / queries} per query")
        println("$ridesOnTheirPath of $rides rides can be drawn along the path of their trip")

        // then
        assertTrue(found > queries * 0.9, "only $found of $queries queries found a journey")
        assertTrue(ridesOnTheirPath > rides * 0.95, "only $ridesOnTheirPath of $rides rides match the path of their trip")
    }

    private fun Stop.asAddress() = Endpoint(GeoPoint(location.lat, location.lon))
}
