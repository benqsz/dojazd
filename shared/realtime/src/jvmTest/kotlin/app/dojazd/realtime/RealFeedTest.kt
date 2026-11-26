package app.dojazd.realtime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in check against feeds downloaded from a real producer:
 *   ./gradlew :shared:realtime:jvmTest -Pdojazd.gtfsRtDir=/path/to/folder/with/pb/files
 */
class RealFeedTest {
    @Test
    fun parsesEveryDownloadedFeed() {
        val folder = System.getProperty("dojazd.gtfsRtDir")?.let(::File) ?: return
        val files = folder.listFiles { file -> file.extension == "pb" }.orEmpty()
        assertTrue(files.isNotEmpty(), "No .pb files in $folder")
        for (file in files) {
            val feed = GtfsRealtime.parse(file.readBytes())
            val entities = feed.tripUpdates.size + feed.vehicles.size + feed.alerts.size
            println("${file.name}: ${feed.tripUpdates.size} trip updates, ${feed.vehicles.size} vehicles, ${feed.alerts.size} alerts")
            assertTrue(entities > 0, "${file.name} has no entities")
        }
    }
}
