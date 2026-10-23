package com.blesniffer.model

private const val HEX_DIGITS = "0123456789ABCDEF"

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
    }
    return out.toString()
}

/** Parses an even-length hex string. Returns an empty array on malformed input. */
fun String.hexToBytes(): ByteArray {
    if (length % 2 != 0) return ByteArray(0)
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return ByteArray(0)
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/** Renders bytes as space separated hex, wrapped for a fixed width display. */
fun ByteArray.toHexDump(bytesPerLine: Int = 8): String {
    if (isEmpty()) return ""
    val out = StringBuilder()
    for (i in indices) {
        if (i > 0) out.append(if (i % bytesPerLine == 0) '\n' else ' ')
        val v = this[i].toInt() and 0xFF
        out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
    }
    return out.toString()
}

/**
 * Formats six address bytes, which BLE transmits least significant first, as the
 * conventional big-endian colon separated form.
 */
fun ByteArray.toBleAddress(offset: Int): String {
    if (offset < 0 || offset + 6 > size) return ""
    val out = StringBuilder(17)
    for (i in 5 downTo 0) {
        if (out.isNotEmpty()) out.append(':')
        val v = this[offset + i].toInt() and 0xFF
        out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
    }
    return out.toString()
}
