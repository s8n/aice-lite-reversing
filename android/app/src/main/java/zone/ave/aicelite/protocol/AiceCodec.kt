package zone.ave.aicelite.protocol

/**
 * Wire codec for the RANVOO AICE Lite (`LH-Aice3 Lite`).
 *
 * Frame (PROTOCOL.md §3), identical in both directions:
 *
 *     [0]=0x00  [1]=0xCC  [2]=crcLo  [3]=crcHi  [4..]=payload
 *
 * CRC is CRC-16/ARC over `payload ++ LH_KEY1`, little-endian (§4). The keyed
 * suffix is not optional — a plain CRC over the payload alone is rejected.
 */
object AiceCodec {

    const val HEADER: Byte = 0x00
    const val SYNC: Byte = 0xCC.toByte()

    /** Fixed suffix mixed into every CRC. From `global_functions.dart`. */
    val LH_KEY1 = byteArrayOf(
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
        0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(),
        0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
    )

    /** 16-byte state buffer (§5). */
    const val STATE_PAYLOAD_SIZE = 16

    private val CRC_TABLE = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
        c
    }

    /** CRC-16/ARC: poly 0x8005 reflected (0xA001), init 0, refin/refout, no xorout. */
    fun crc16arc(data: ByteArray): Int {
        var c = 0
        for (b in data) {
            c = CRC_TABLE[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        }
        return c and 0xFFFF
    }

    fun checksum(payload: ByteArray): Int = crc16arc(payload + LH_KEY1)

    /** Wrap a payload of any length in the `[00 CC crcLo crcHi]` frame. */
    fun encode(payload: ByteArray): ByteArray {
        val crc = checksum(payload)
        return byteArrayOf(HEADER, SYNC, (crc and 0xFF).toByte(), ((crc ushr 8) and 0xFF).toByte()) + payload
    }

    /**
     * Strip the frame and return the payload, or null if the header/sync bytes
     * are wrong or the frame is too short. The CRC is *checked* but a mismatch
     * is not fatal (the official app never validates it either, §6) — inspect
     * [crcValid] when you care.
     */
    fun decode(frame: ByteArray): ByteArray? {
        if (frame.size < 5) return null
        if (frame[0] != HEADER || frame[1] != SYNC) return null
        return frame.copyOfRange(4, frame.size)
    }

    fun crcValid(frame: ByteArray): Boolean {
        val payload = decode(frame) ?: return false
        val expected = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        return checksum(payload) == expected
    }
}

fun ByteArray.toHex(separator: String = " "): String =
    joinToString(separator) { "%02X".format(it) }

/** Parses "00 CC 29 76" / "00cc2976" / "0x00,0xCC" into bytes; null if malformed. */
fun parseHex(text: String): ByteArray? {
    val cleaned = text.replace("0x", "", ignoreCase = true)
        .filter { !it.isWhitespace() && it != ',' && it != ':' && it != '-' }
    if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
    return runCatching {
        ByteArray(cleaned.length / 2) { cleaned.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }.getOrNull()
}
