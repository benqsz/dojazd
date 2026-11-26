package app.dojazd.gtfs

/**
 * Splits CSV lines into records following RFC 4180: fields may be quoted,
 * `""` is an escaped quote, and a quoted field may contain line breaks.
 * A leading byte-order mark and blank lines are skipped.
 */
internal fun Sequence<String>.csvRecords(): Sequence<List<String>> =
    sequence {
        val pending = StringBuilder()
        // Whether the quotes gathered in [pending] so far pair up; counted line by line, not over again.
        var quotesPaired = true
        var first = true
        for (rawLine in this@csvRecords) {
            val line = if (first) rawLine.removePrefix("\uFEFF") else rawLine
            first = false
            if (pending.isEmpty()) {
                if (line.isBlank()) continue
                if ('"' !in line) {
                    yield(line.split(','))
                    continue
                }
            }
            val pairedInLine = line.count { it == '"' } % 2 == 0
            if (pending.isEmpty() && pairedInLine) {
                // The usual case of a file that quotes its fields: a whole record on one line, read where it is.
                yield(parseQuotedRecord(line))
                continue
            }
            pending.append(line)
            if (!pairedInLine) quotesPaired = !quotesPaired
            // Quotes left unpaired mean a quoted field continues on the next line.
            if (quotesPaired) {
                yield(parseQuotedRecord(pending))
                pending.clear()
            } else {
                // A stray quote would otherwise swallow the rest of the file as one record.
                if (pending.length > MAX_RECORD_CHARS) throw GtfsFormatException("A quoted field is never closed")
                pending.append('\n')
            }
        }
        if (pending.isNotEmpty()) yield(parseQuotedRecord(pending))
    }

/** No record of a real feed comes near this; one that does is a quote left open. */
private const val MAX_RECORD_CHARS = 1_000_000

private fun parseQuotedRecord(text: CharSequence): List<String> {
    val fields = ArrayList<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val char = text[i]
        when {
            inQuotes && char == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                field.append('"')
                i++
            }

            char == '"' -> {
                inQuotes = !inQuotes
            }

            char == ',' && !inQuotes -> {
                fields += field.toString()
                field.clear()
            }

            else -> {
                field.append(char)
            }
        }
        i++
    }
    fields += field.toString()
    return fields
}

/** Column lookup for one GTFS file, built from its header record. */
internal class CsvColumns(
    private val fileName: String,
    header: List<String>,
) {
    private val indexByName = header.withIndex().associate { (index, name) -> name.trim() to index }

    /** Index of an optional column, or -1 when the file does not have it. */
    fun optional(name: String): Int = indexByName[name] ?: -1

    fun required(name: String): Int = indexByName[name] ?: throw GtfsFormatException("$fileName: missing required column '$name'")
}

/** The trimmed value at [index]; empty when the column is absent or the record is short. */
internal fun List<String>.field(index: Int): String = if (index in indices) this[index].trim() else ""
