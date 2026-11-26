package app.dojazd.realtime

/** Builds protocol buffer messages for tests, field by field. */
class ProtoWriter {
    private val out = ArrayList<Byte>()

    fun varint(
        field: Int,
        value: Long,
    ) = apply {
        key(field, 0)
        raw(value)
    }

    fun int(
        field: Int,
        value: Int,
    ) = varint(field, value.toLong())

    fun float(
        field: Int,
        value: Float,
    ) = apply {
        key(field, 5)
        val bits = value.toRawBits()
        for (i in 0..3) out += (bits shr (8 * i)).toByte()
    }

    fun string(
        field: Int,
        value: String,
    ) = bytes(field, value.encodeToByteArray())

    fun message(
        field: Int,
        build: ProtoWriter.() -> Unit,
    ) = bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun bytes(
        field: Int,
        value: ByteArray,
    ) = apply {
        key(field, 2)
        raw(value.size.toLong())
        value.forEach { out += it }
    }

    private fun key(
        field: Int,
        wireType: Int,
    ) = raw((field shl 3 or wireType).toLong())

    private fun raw(value: Long) {
        var rest = value
        while (true) {
            if (rest and 0x7FL.inv() == 0L) {
                out += rest.toByte()
                return
            }
            out += ((rest and 0x7F) or 0x80).toByte()
            rest = rest ushr 7
        }
    }
}

fun feed(
    timestamp: Long = 1_000,
    entities: ProtoWriter.() -> Unit,
): ByteArray =
    ProtoWriter()
        .apply {
            message(1) {
                string(1, "2.0")
                varint(3, timestamp)
            }
            entities()
        }.toByteArray()

fun ProtoWriter.tripDescriptor(
    field: Int,
    tripId: String,
    startDate: String? = null,
    relationship: Int? = null,
) = message(field) {
    string(1, tripId)
    startDate?.let { string(3, it) }
    relationship?.let { int(4, it) }
}

fun ProtoWriter.stopTimeUpdate(
    sequence: Int,
    delay: Int? = null,
    time: Long? = null,
    relationship: Int? = null,
) = message(2) {
    int(1, sequence)
    if (delay != null || time != null) {
        message(3) {
            delay?.let { int(1, it) }
            time?.let { varint(2, it) }
        }
    }
    relationship?.let { int(5, it) }
}
