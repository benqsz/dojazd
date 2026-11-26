package app.dojazd.gtfs

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.dojazd.gtfs.db.GtfsDatabase

fun newInMemoryDatabase(): GtfsDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    GtfsDatabase.Schema.create(driver)
    return GtfsDatabase(driver)
}

/** A feed held in memory: file name to file content. */
class MapFeed(
    private val files: Map<String, String>,
) : GtfsFeed {
    override fun <T : Any> useLines(
        fileName: String,
        block: (Sequence<String>) -> T,
    ): T? = files[fileName]?.let { block(it.lineSequence()) }

    fun with(
        fileName: String,
        content: String,
    ) = MapFeed(files + (fileName to content))

    fun without(fileName: String) = MapFeed(files - fileName)
}

/**
 * A tiny city: bus line 75 between Alfa and Delta, and tram line 1.
 *
 * Line 75, direction 0 (weekdays unless noted):
 * - t1 08:00 Alfa, Beta, Gamma (no boarding), Delta
 * - t2 09:00 the same stops; Beta is on request
 * - t3 10:00 short run Alfa to Beta, signed "Beta"
 * - t5 24:30 night run Alfa, Beta, Delta (after midnight of the service day)
 * - t6 12:00 Alfa, Beta, Delta on the holiday service only
 * Line 75, direction 1: t4 15:00 Delta, Gamma, Alfa.
 * Line 1: t7 on Saturdays.
 *
 * 2 March 2026 is a Monday; Friday 1 May 2026 swaps the weekday service for the holiday one.
 */
val testFeed =
    MapFeed(
        mapOf(
            "routes.txt" to
                """
                "route_id","route_short_name","route_long_name","route_desc","route_type","route_color","route_text_color"
                "T1","1","Alfa - Delta","Linia zwykła","0","",""
                "B75","75","Alfa - Delta","Linia zwykła","3","B3040F","FFFFFF"
                """.trimIndent(),
            "stops.txt" to
                """
                stop_id,stop_code,stop_name,stop_lat,stop_lon,location_type,platform_code
                100,s1,Alfa,53.40,14.50,,11
                200,s2,Beta,53.41,14.51,0,11
                300,s3,Gamma,53.42,14.52,,11
                400,s4,Delta,53.43,14.53,,11
                900,hub,Dworzec,53.44,14.54,1,
                """.trimIndent(),
            "calendar.txt" to
                """
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                WD,1,1,1,1,1,0,0,20260101,20261231
                SAT,0,0,0,0,0,1,0,20260101,20261231
                """.trimIndent(),
            "calendar_dates.txt" to
                """
                service_id,date,exception_type
                WD,20260501,2
                HOL,20260501,1
                """.trimIndent(),
            "trips.txt" to
                """
                route_id,service_id,trip_id,trip_headsign,direction_id,wheelchair_accessible
                B75,WD,t1,Delta,0,1
                B75,WD,t2,Delta,0,2
                B75,WD,t3,Beta,0,
                B75,WD,t4,Alfa,1,1
                B75,WD,t5,Delta,0,1
                B75,HOL,t6,,0,1
                T1,SAT,t7,Delta,0,1
                """.trimIndent(),
            "stop_times.txt" to
                """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type
                t1,08:00:00,08:00:00,100,1,0
                t1,08:05:00,08:05:00,200,2,0
                t1,08:10:00,08:10:00,300,3,1
                t1,08:20:00,08:20:00,400,4,0
                t2,09:00:00,09:00:00,100,1,0
                t2,09:05:00,09:05:00,200,2,3
                t2,09:10:00,09:10:00,300,3,0
                t2,09:20:00,09:20:00,400,4,0
                t3,10:00:00,10:00:00,100,1,0
                t3,10:05:00,10:05:00,200,2,0
                t4,15:00:00,15:00:00,400,1,0
                t4,15:10:00,15:10:00,300,2,0
                t4,15:25:00,15:25:00,100,3,0
                t5,24:30:00,24:30:00,100,1,0
                t5,24:40:00,24:40:00,200,2,0
                t5,25:00:00,25:00:00,400,3,0
                t6,12:00:00,12:00:00,100,1,0
                t6,12:05:00,12:05:00,200,2,0
                t6,12:20:00,12:20:00,400,3,0
                t7,07:00:00,07:00:00,100,1,0
                t7,07:30:00,07:30:00,400,2,0
                """.trimIndent(),
        ),
    )
