package app.dojazd.gtfs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Imports a real GTFS archive end to end. Skipped unless a path is supplied:
 * `./gradlew :shared:gtfs:jvmTest -Pdojazd.gtfsZip=/path/to/gtfs.zip`
 */
class RealFeedTest {
    @Test
    fun importsRealFeed() {
        val path = System.getProperty("dojazd.gtfsZip") ?: return println("RealFeedTest skipped: no dojazd.gtfsZip")

        // given
        val db = newInMemoryDatabase()

        // when
        val elapsed = measureTime { GtfsImporter(db).import(ZipGtfsFeed(File(path))) }
        val timetable = Timetable(db)

        // then
        val lines = timetable.lines()
        val stops = timetable.stops()
        val stopTimes = db.gtfsQueries.countStopTimes().executeAsOne()
        println("Imported ${lines.size} lines, ${stops.size} stops, $stopTimes stop times in $elapsed")

        assertTrue(lines.isNotEmpty() && stops.isNotEmpty() && stopTimes > 0)
        lines.forEach { line ->
            val directions = timetable.directions(line.id)
            assertTrue(directions.isNotEmpty(), "line ${line.name} has no directions")
            directions.forEach { assertTrue(it.stops.size >= 2, "line ${line.name} direction ${it.direction} is too short") }
        }
    }
}
