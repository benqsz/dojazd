package app.dojazd.gtfs

/** Read access to the text files of one GTFS feed, wherever they are stored. */
interface GtfsFeed {
    /**
     * Runs [block] over the lines of [fileName] (for example `stops.txt`).
     * Returns null without calling [block] when the feed has no such file.
     */
    fun <T : Any> useLines(
        fileName: String,
        block: (Sequence<String>) -> T,
    ): T?
}

/** The feed violates the GTFS specification in a way the importer cannot work around. */
class GtfsFormatException(
    message: String,
) : Exception(message)
