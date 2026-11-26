package app.dojazd.geocode

import app.dojazd.model.GeoPoint
import app.dojazd.model.SearchText

/** Something [AddressIndex.search] found. */
data class PlaceMatch(
    /** What to call the place: "Aleja Wojska Polskiego 12", "Pogodno", "Filharmonia". */
    val name: String,
    /** The town for a street or an address, the address for a named place; may be empty. */
    val detail: String,
    val location: GeoPoint,
    val kind: Kind,
) {
    enum class Kind { ADDRESS, STREET, PLACE }
}

/**
 * The streets, house numbers and named places of one city, held in memory and
 * searched on the device. Filled from the address file built by `tools/citypack`;
 * see [parse] for the format.
 */
class AddressIndex private constructor(
    private val streets: List<Street>,
    private val places: List<Place>,
    /** Free text from the file header saying where the data comes from. */
    val source: String,
) {
    private class Street(
        val name: String,
        val locality: String,
        val location: GeoPoint,
        val numbers: List<String>,
        val numberLats: DoubleArray,
        val numberLons: DoubleArray,
    ) {
        val words: List<String> = wordsOf(name)
        val localityWords: List<String> = wordsOf(locality)

        // Most numbers are plain digits and fold to themselves: those share the one text.
        val foldedNumbers: List<String> = numbers.map { number -> SearchText.fold(number).let { if (it == number) number else it } }

        /** The street as a whole, without a house number. */
        fun asMatch() = PlaceMatch(name, locality, location, PlaceMatch.Kind.STREET)
    }

    private class Place(
        val name: String,
        val detail: String,
        val location: GeoPoint,
    ) {
        val words: List<String> = wordsOf(name)
    }

    val streetCount: Int get() = streets.size
    val addressCount: Int get() = streets.sumOf { it.numbers.size }
    val placeCount: Int get() = places.size

    /**
     * Finds streets, addresses and named places for what the user typed, best
     * first. Every word typed must start a word of the name ("woj pol" finds
     * "Aleja Wojska Polskiego"); a word that starts with a digit is taken as the
     * house number, and a town name may be added to tell two streets apart.
     */
    fun search(
        query: String,
        limit: Int = 20,
    ): List<PlaceMatch> {
        val typed = wordsOf(query).map { ABBREVIATIONS[it] ?: it }.filter { it !in IGNORED_WORDS }
        if (typed.isEmpty()) return emptyList()
        val number = typed.lastOrNull { it.first().isDigit() }?.takeIf { typed.size > 1 }
        val words = if (number == null) typed else typed - number

        class Scored(
            val match: PlaceMatch,
            val rank: Int,
            val weight: Int,
        )
        val found = ArrayList<Scored>()

        for (street in streets) {
            val rank = rank(words, street.words, street.localityWords, fromTheEnd = true) ?: continue
            if (number == null) {
                found += Scored(street.asMatch(), rank, street.numbers.size)
                continue
            }
            val exact = street.foldedNumbers.indexOf(number)
            val indexes =
                if (exact >= 0) {
                    listOf(exact)
                } else {
                    // "12" also offers 12a and 120, but the street itself comes first: the number may simply not be mapped.
                    found += Scored(street.asMatch(), rank + 1, street.numbers.size)
                    street.foldedNumbers.indices
                        .filter { street.foldedNumbers[it].startsWith(number) }
                        .take(SIMILAR_NUMBERS)
                }
            indexes.forEach { index ->
                found +=
                    Scored(
                        PlaceMatch(
                            name = "${street.name} ${street.numbers[index]}",
                            detail = street.locality,
                            location = GeoPoint(street.numberLats[index], street.numberLons[index]),
                            kind = PlaceMatch.Kind.ADDRESS,
                        ),
                        rank = if (exact >= 0) rank else rank + 2,
                        weight = street.numbers.size,
                    )
            }
        }
        // A number in a place's name ("School no. 12") is just another word of it.
        for (place in places) {
            val rank = rank(typed, place.words, emptyList(), fromTheEnd = false) ?: continue
            // At equal quality of match, streets come before the many shops and offices named after them.
            found += Scored(PlaceMatch(place.name, place.detail, place.location, PlaceMatch.Kind.PLACE), rank, -1)
        }

        return found
            .sortedWith(compareBy<Scored>({ it.rank }, { -it.weight }, { it.match.name.length }, { it.match.name }))
            .take(limit)
            .map { it.match }
    }

    /** The address closest to [point], for naming a spot picked on a map; null when none is within [maxMeters]. */
    fun nearest(
        point: GeoPoint,
        maxMeters: Double = 100.0,
    ): PlaceMatch? {
        var best: PlaceMatch? = null
        var bestMeters = maxMeters
        // A degree of latitude is about 111 km, which rules out most addresses without any trigonometry.
        val maxLatDifference = maxMeters / 111_000.0
        for (street in streets) {
            for (index in street.numbers.indices) {
                val lat = street.numberLats[index]
                if (lat - point.lat > maxLatDifference || point.lat - lat > maxLatDifference) continue
                val location = GeoPoint(lat, street.numberLons[index])
                val meters = point.distanceMetersTo(location)
                if (meters <= bestMeters) {
                    bestMeters = meters
                    best = PlaceMatch("${street.name} ${street.numbers[index]}", street.locality, location, PlaceMatch.Kind.ADDRESS)
                }
            }
        }
        return best
    }

    companion object {
        /** Version of the file format this code reads; bump together with `tools/citypack`. */
        const val FORMAT = 1

        private const val SIMILAR_NUMBERS = 5

        // Written out in the map data but usually typed short. Extend as cities are added.
        private val ABBREVIATIONS =
            mapOf(
                "al" to "aleja",
                "pl" to "plac",
                "os" to "osiedle",
                "sw" to "swietego",
                "gen" to "generala",
                "ks" to "ksiedza",
            )

        // "ulica" is never part of a street's name in the data, so typing it must not rule the street out.
        private val IGNORED_WORDS = setOf("ul", "ulica")

        /**
         * Reads an address file: tab-separated lines, where the first field says what the line is.
         *
         * - `#key<TAB>value`: header. `#dojazd-addresses` carries the format version, `#source` the attribution.
         * - `S<TAB>name<TAB>town<TAB>lat<TAB>lon`: a street (or a village without street names) and a point on it.
         * - `N<TAB>number<TAB>lat<TAB>lon`: a house number of the street above.
         * - `P<TAB>name<TAB>detail<TAB>lat<TAB>lon`: a named place such as a school, a shop or a district.
         *
         * @throws IllegalArgumentException when the file is not an address file of a known version
         */
        fun parse(lines: Sequence<String>): AddressIndex {
            class StreetDraft(
                val name: String,
                val locality: String,
                val location: GeoPoint,
            ) {
                val numbers = ArrayList<String>()
                val lats = ArrayList<Double>()
                val lons = ArrayList<Double>()
            }

            // The same few thousand house numbers recur on every street; each is kept once.
            val numbers = HashMap<String, String>()
            val drafts = ArrayList<StreetDraft>()
            val places = ArrayList<Place>()
            var version: Int? = null
            var source = ""

            for (line in lines) {
                if (line.isEmpty()) continue
                val fields = line.split('\t')
                when (fields[0]) {
                    "#dojazd-addresses" -> {
                        version = fields.getOrNull(1)?.toIntOrNull()
                    }

                    "#source" -> {
                        source = fields.getOrNull(1).orEmpty()
                    }

                    "S" -> {
                        point(fields, 3)?.let { drafts += StreetDraft(fields[1], fields[2], it) }
                    }

                    "N" -> {
                        val street = drafts.lastOrNull() ?: continue
                        val location = point(fields, 2) ?: continue
                        street.numbers += numbers.getOrPut(fields[1]) { fields[1] }
                        street.lats += location.lat
                        street.lons += location.lon
                    }

                    "P" -> {
                        point(fields, 3)?.let { places += Place(fields[1], fields[2], it) }
                    }
                }
            }
            require(version == FORMAT) { "Not an address file of format $FORMAT (found: $version)" }

            return AddressIndex(
                streets =
                    drafts.map {
                        Street(it.name, it.locality, it.location, it.numbers, it.lats.toDoubleArray(), it.lons.toDoubleArray())
                    },
                places = places,
                source = source,
            )
        }

        private fun point(
            fields: List<String>,
            latIndex: Int,
        ): GeoPoint? {
            val lat = fields.getOrNull(latIndex)?.toDoubleOrNull() ?: return null
            val lon = fields.getOrNull(latIndex + 1)?.toDoubleOrNull() ?: return null
            return GeoPoint(lat, lon)
        }

        /** Lowercase words without diacritics or punctuation: "al. Wojska Polskiego 12A" gives al, wojska, polskiego, 12a. */
        private fun wordsOf(text: String): List<String> = SearchText.fold(text).split(WORD_BREAK).filter { it.isNotEmpty() }

        private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")

        /**
         * How well the [typed] words match a name, lower being better, or null when they do not:
         * 0 when they are the start of the name, 1 when they start words elsewhere in it.
         * Words that match nothing in the name may name the town instead.
         *
         * Streets are mostly known by the end of their name ("Mickiewicza" for "Adama
         * Mickiewicza", "Wojska Polskiego" for "Aleja Wojska Polskiego"), so with
         * [fromTheEnd] matching the last words is as good as matching the first.
         */
        private fun rank(
            typed: List<String>,
            nameWords: List<String>,
            localityWords: List<String>,
            fromTheEnd: Boolean,
        ): Int? {
            if (typed.isEmpty()) return null
            var inName = 0
            for (word in typed) {
                when {
                    nameWords.any { it.startsWith(word) } -> inName++
                    localityWords.none { it.startsWith(word) } -> return null
                }
            }
            if (inName == 0) return null
            if (typed.size > nameWords.size) return 1
            val leading = typed.indices.all { nameWords[it].startsWith(typed[it]) }
            val offset = nameWords.size - typed.size
            val trailing = fromTheEnd && typed.indices.all { nameWords[offset + it].startsWith(typed[it]) }
            return if (leading || trailing) 0 else 1
        }
    }
}
