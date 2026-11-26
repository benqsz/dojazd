package app.dojazd.realtime

/** Thrown when bytes are not a well-formed protocol buffer message. */
class ProtoFormatException(
    message: String,
) : Exception(message)

/**
 * Reads the protocol buffer wire format, field by field. GTFS-RT needs only a
 * handful of message types, which do not justify a protobuf library and its
 * generated code; unknown fields are skipped, as the format intends.
 */
internal class ProtoReader(
    private val bytes: ByteArray,
    private var position: Int = 0,
    private val end: Int = bytes.size,
) {
    /** Number of the field just read by [next]. */
    var field = 0
        private set

    private var wireType = 0

    /** Moves to the next field; false at the end of the message. */
    fun next(): Boolean {
        if (position >= end) return false
        val key = varint()
        field = (key ushr 3).toInt()
        wireType = (key and 7).toInt()
        if (field == 0) throw ProtoFormatException("Field number 0")
        return true
    }

    fun long(): Long =
        when (wireType) {
            VARINT -> varint()
            FIXED64 -> fixed(8)
            FIXED32 -> fixed(4)
            else -> throw ProtoFormatException("Field $field is not a number")
        }

    /** An `int32`, `uint32` or enum value; negative `int32`s arrive as ten-byte varints and fit back. */
    fun int(): Int = long().toInt()

    fun bool(): Boolean = long() != 0L

    fun float(): Float {
        if (wireType != FIXED32) throw ProtoFormatException("Field $field is not a float")
        return Float.fromBits(fixed(4).toInt())
    }

    fun double(): Double {
        if (wireType != FIXED64) throw ProtoFormatException("Field $field is not a double")
        return Double.fromBits(fixed(8))
    }

    fun string(): String {
        val start = delimited()
        return bytes.decodeToString(start, position)
    }

    /** A reader over the embedded message in the current field. */
    fun message(): ProtoReader {
        val start = delimited()
        return ProtoReader(bytes, start, position)
    }

    fun skip() {
        when (wireType) {
            VARINT -> varint()
            FIXED64 -> advance(8)
            LENGTH_DELIMITED -> delimited()
            FIXED32 -> advance(4)
            else -> throw ProtoFormatException("Unsupported wire type $wireType")
        }
    }

    /** Skips over a length-delimited field and returns where its content starts. */
    private fun delimited(): Int {
        if (wireType != LENGTH_DELIMITED) throw ProtoFormatException("Field $field is not length-delimited")
        val length = varint()
        if (length < 0 || length > end - position) throw ProtoFormatException("Field $field runs past the end")
        val start = position
        position += length.toInt()
        return start
    }

    private fun advance(count: Int) {
        if (count > end - position) throw ProtoFormatException("Field $field runs past the end")
        position += count
    }

    private fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (position >= end) throw ProtoFormatException("Truncated varint")
            val byte = bytes[position++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        throw ProtoFormatException("Varint too long")
    }

    private fun fixed(size: Int): Long {
        advance(size)
        var result = 0L
        for (i in size - 1 downTo 0) result = (result shl 8) or (bytes[position - size + i].toLong() and 0xFF)
        return result
    }

    private companion object {
        const val VARINT = 0
        const val FIXED64 = 1
        const val LENGTH_DELIMITED = 2
        const val FIXED32 = 5
    }
}
