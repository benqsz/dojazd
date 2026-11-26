package app.dojazd.gtfs

import java.io.File
import java.util.zip.ZipFile

/** A GTFS feed read straight from its zip archive; nothing is extracted to disk. */
class ZipGtfsFeed(
    private val file: File,
) : GtfsFeed {
    override fun <T : Any> useLines(
        fileName: String,
        block: (Sequence<String>) -> T,
    ): T? =
        ZipFile(file).use { zip ->
            // Some feeds put their files in a folder inside the archive, so match on the base name.
            val entry =
                zip
                    .entries()
                    .asSequence()
                    .firstOrNull { !it.isDirectory && it.name.substringAfterLast('/') == fileName }
                    ?: return null
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines(block)
        }
}
