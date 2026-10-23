package com.blesniffer.dfu

/**
 * Wire format for Nordic's legacy serial DFU, as spoken by the Adafruit
 * nRF52 bootloader.
 *
 * This is the protocol `adafruit-nrfutil dfu serial` implements. Every packet is
 * a four byte SLIP header, the payload, a CRC-16, then byte stuffing and 0xC0
 * framing. The bootloader replies to each with an acknowledgement carrying the
 * sequence number it expects next.
 *
 * The encoding is exercised in DfuProtocolTest against byte-for-byte vectors
 * captured from the reference Python implementation - there is no room to be
 * approximately right about a format that writes flash.
 */
object DfuProtocol {

    const val PACKET_TYPE_HCI = 14
    const val DATA_INTEGRITY_CHECK_PRESENT = 1
    const val RELIABLE_PACKET = 1

    const val DFU_INIT_PACKET = 1
    const val DFU_START_PACKET = 3
    const val DFU_DATA_PACKET = 4
    const val DFU_STOP_DATA_PACKET = 5

    /** Only application images are supported; SoftDevice and bootloader are not. */
    const val DFU_UPDATE_MODE_APP = 4

    /** Largest firmware chunk the bootloader accepts in one data packet. */
    const val DFU_PACKET_MAX_SIZE = 512

    private const val SLIP_END = 0xC0
    private const val SLIP_ESC = 0xDB
    private const val SLIP_ESC_END = 0xDC
    private const val SLIP_ESC_ESC = 0xDD

    /** CRC-16/CCITT-FALSE, seeded with 0xFFFF. */
    fun crc16(data: ByteArray, seed: Int = 0xFFFF): Int {
        var crc = seed
        for (byte in data) {
            crc = (crc ushr 8 and 0x00FF) or (crc shl 8 and 0xFF00)
            crc = crc xor (byte.toInt() and 0xFF)
            crc = crc xor ((crc and 0x00FF) ushr 4)
            crc = crc xor ((crc shl 8) shl 4)
            crc = crc xor (((crc and 0x00FF) shl 4) shl 1)
        }
        return crc and 0xFFFF
    }

    fun int32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    /**
     * Four byte SLIP header. The second nibble of the sequence field carries the
     * *next* expected number, which is what the bootloader acknowledges against.
     */
    fun header(sequence: Int, payloadLength: Int): ByteArray {
        val bytes = IntArray(4)
        bytes[0] = sequence or (((sequence + 1) % 8) shl 3) or
            (DATA_INTEGRITY_CHECK_PRESENT shl 6) or (RELIABLE_PACKET shl 7)
        bytes[1] = PACKET_TYPE_HCI or ((payloadLength and 0x000F) shl 4)
        bytes[2] = (payloadLength and 0x0FF0) ushr 4
        bytes[3] = (bytes[0] + bytes[1] + bytes[2]).inv().plus(1) and 0xFF
        return ByteArray(4) { bytes[it].toByte() }
    }

    /** Replaces the two reserved bytes with their escape sequences. */
    fun slipEscape(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size + 8)
        for (byte in data) {
            when (byte.toInt() and 0xFF) {
                SLIP_END -> {
                    out.add(SLIP_ESC.toByte()); out.add(SLIP_ESC_END.toByte())
                }
                SLIP_ESC -> {
                    out.add(SLIP_ESC.toByte()); out.add(SLIP_ESC_ESC.toByte())
                }
                else -> out.add(byte)
            }
        }
        return out.toByteArray()
    }

    fun slipUnescape(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size)
        var i = 0
        while (i < data.size) {
            val byte = data[i].toInt() and 0xFF
            if (byte == SLIP_ESC && i + 1 < data.size) {
                when (data[i + 1].toInt() and 0xFF) {
                    SLIP_ESC_END -> out.add(SLIP_END.toByte())
                    SLIP_ESC_ESC -> out.add(SLIP_ESC.toByte())
                    else -> out.add(data[i])
                }
                i += 2
            } else {
                out.add(data[i])
                i++
            }
        }
        return out.toByteArray()
    }

    /** Builds one complete framed packet ready to write to the port. */
    fun packet(sequence: Int, payload: ByteArray): ByteArray {
        val body = header(sequence, payload.size) + payload
        val crc = crc16(body)
        val withCrc = body + byteArrayOf(
            (crc and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
        )
        return byteArrayOf(SLIP_END.toByte()) + slipEscape(withCrc) + SLIP_END.toByte()
    }

    fun startPacket(applicationSize: Int): ByteArray =
        int32(DFU_START_PACKET) + int32(DFU_UPDATE_MODE_APP) +
            int32(0) + int32(0) + int32(applicationSize)

    /** The init packet from the .dat file, plus the padding the bootloader expects. */
    fun initPacket(initData: ByteArray): ByteArray =
        int32(DFU_INIT_PACKET) + initData + byteArrayOf(0, 0)

    fun dataPacket(chunk: ByteArray): ByteArray = int32(DFU_DATA_PACKET) + chunk

    fun stopPacket(): ByteArray = int32(DFU_STOP_DATA_PACKET)

    /**
     * Reads the sequence number a reply frame acknowledges.
     *
     * That value lives in bits 3..5 of the header, which is the "next expected"
     * field - so a device that has taken packet 4 answers with 5. Returns null
     * when the buffer does not hold a complete frame yet.
     */
    fun parseAck(buffer: ByteArray): Int? {
        val first = buffer.indexOfFirst { (it.toInt() and 0xFF) == SLIP_END }
        if (first < 0) return null
        val second = (first + 1 until buffer.size)
            .firstOrNull { (buffer[it].toInt() and 0xFF) == SLIP_END } ?: return null
        if (second <= first + 1) {
            // Two adjacent frame markers: an empty frame, keep looking.
            return parseAck(buffer.copyOfRange(second, buffer.size))
        }
        val decoded = slipUnescape(buffer.copyOfRange(first + 1, second))
        if (decoded.isEmpty()) return null
        return (decoded[0].toInt() ushr 3) and 0x07
    }
}
