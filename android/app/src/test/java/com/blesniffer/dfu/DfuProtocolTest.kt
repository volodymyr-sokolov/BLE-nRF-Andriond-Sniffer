package com.blesniffer.dfu

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-for-byte comparison against `adafruit-nrfutil`.
 *
 * The expected strings were produced by running the reference Python
 * implementation (nordicsemi.dfu.dfu_transport_serial.HciPacket) on the same
 * inputs. This protocol writes flash on a device that is not easy to recover
 * blind, so "looks right" is not good enough - the encoder has to agree with
 * the tool the bootloader was built against, exactly.
 */
class DfuProtocolTest {

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

    @Test
    fun `crc16 matches the reference implementation`() {
        assertEquals(0xFFFF, DfuProtocol.crc16(ByteArray(0)))
        assertEquals(0x29B1, DfuProtocol.crc16("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0x3B37, DfuProtocol.crc16(ByteArray(16) { it.toByte() }))
    }

    @Test
    fun `start packet matches the reference implementation`() {
        // Sequence 1, application-only update of a 55312 byte image.
        val packet = DfuProtocol.packet(1, DfuProtocol.startPacket(55312))
        assertEquals(
            "C0D14E01E00300000004000000000000000000000010D80000F978C0",
            packet.hex(),
        )
    }

    @Test
    fun `data packet escapes reserved bytes like the reference implementation`() {
        // Payload deliberately contains 0xC0 and 0xDB, the two bytes SLIP must
        // escape; getting this wrong corrupts the image silently.
        val packet = DfuProtocol.packet(2, DfuProtocol.dataPacket(byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0x01, 0x02)))
        assertEquals("C0DA8E009804000000DBDCDBDD0102B6FCC0", packet.hex())
    }

    @Test
    fun `stop packet matches the reference implementation`() {
        val packet = DfuProtocol.packet(3, DfuProtocol.stopPacket())
        assertEquals("C0E34E00CF05000000128CC0", packet.hex())
    }

    @Test
    fun `escaping round-trips`() {
        val raw = byteArrayOf(0x00, 0xC0.toByte(), 0xDB.toByte(), 0x7F, 0xDB.toByte(), 0xC0.toByte())
        assertEquals(raw.hex(), DfuProtocol.slipUnescape(DfuProtocol.slipEscape(raw)).hex())
    }

    @Test
    fun `sequence number wraps every eight packets`() {
        // The header's low nibble is the sequence, the next three bits the value
        // the bootloader should acknowledge.
        for (seq in 0..7) {
            val header = DfuProtocol.header(seq, 0)
            assertEquals(seq, header[0].toInt() and 0x07)
            assertEquals((seq + 1) % 8, (header[0].toInt() ushr 3) and 0x07)
        }
    }

    @Test
    fun `header checksum makes the first four bytes sum to zero`() {
        for (length in listOf(0, 4, 16, 512, 0x0FFF)) {
            val header = DfuProtocol.header(3, length)
            val sum = header.sumOf { it.toInt() and 0xFF } and 0xFF
            assertEquals("length $length", 0, sum)
        }
    }

    @Test
    fun `ack frames yield the next expected sequence number`() {
        // Bits 3..5 of the header carry "next expected", so a frame numbered 4
        // acknowledges with 5 - which is what the sender compares against.
        assertEquals(5, DfuProtocol.parseAck(DfuProtocol.packet(4, ByteArray(0))))
        assertEquals(0, DfuProtocol.parseAck(DfuProtocol.packet(7, ByteArray(0))))
    }

    @Test
    fun `an incomplete frame yields no acknowledgement`() {
        assertEquals(null, DfuProtocol.parseAck(byteArrayOf(0xC0.toByte(), 0x11)))
        assertEquals(null, DfuProtocol.parseAck(byteArrayOf(0x11, 0x22)))
    }
}
