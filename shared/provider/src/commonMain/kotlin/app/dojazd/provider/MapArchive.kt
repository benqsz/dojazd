package app.dojazd.provider

import app.dojazd.model.GeoBounds

/** What the app needs to know about a map file before showing it, read from the file itself. */
data class MapArchive(
    val bounds: GeoBounds,
    val minZoom: Int,
    val maxZoom: Int,
) {
    companion object {
        /** Number of bytes at the start of a PMTiles file that [parse] needs. */
        const val HEADER_SIZE = 127

        /**
         * Reads the fixed-size header of a PMTiles version 3 archive.
         * Layout: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
         *
         * @throws IllegalArgumentException when [header] is not the start of such a file
         */
        fun parse(header: ByteArray): MapArchive {
            require(header.size >= HEADER_SIZE) { "A PMTiles header has $HEADER_SIZE bytes, got ${header.size}" }
            require(header.decodeToString(0, 7) == "PMTiles") { "Not a PMTiles file" }
            require(header[7].toInt() == 3) { "Unsupported PMTiles version ${header[7]}" }

            // Positions are stored as whole numbers of ten-millionths of a degree, least significant byte first.
            fun degrees(offset: Int): Double {
                var value = 0
                for (i in 3 downTo 0) value = (value shl 8) or (header[offset + i].toInt() and 0xFF)
                return value / 1e7
            }
            return MapArchive(
                bounds = GeoBounds(south = degrees(106), west = degrees(102), north = degrees(114), east = degrees(110)),
                minZoom = header[100].toInt(),
                maxZoom = header[101].toInt(),
            )
        }
    }
}
