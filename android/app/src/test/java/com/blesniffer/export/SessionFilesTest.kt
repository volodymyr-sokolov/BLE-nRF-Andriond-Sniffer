package com.blesniffer.export

import com.blesniffer.model.BlePacket
import com.blesniffer.model.ExportFormat
import com.blesniffer.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Round-trips captures through both file formats.
 *
 * The point is that a saved session reopens as the same session: an export that
 * silently drops a field would otherwise only show up when someone came back to
 * old data, which is exactly when it is too late.
 */
class SessionFilesTest {

    private val info = SessionInfo(
        source = "nRF52840 (USB)",
        channel = "All",
        startedAtMs = 1_786_179_274_851L,
        exportedAtMs = 1_786_179_999_000L,
        packetCount = 4,
    )

    private val packets = listOf(
        BlePacket(
            id = 1,
            timestampMs = 1_786_179_274_851L,
            deviceTimeUs = 27_788_271L,
            channel = 39,
            rssi = -83,
            crcOk = true,
            address = "90:E5:B1:20:24:06",
            addressType = "public",
            pduType = "ADV_IND",
            rawHex = "0012062420B1E59002010608FF4D310000000000",
            adOffset = 8,
            source = SourceKind.USB,
        ),
        // A CRC failure has to survive the round-trip: those packets are kept on
        // purpose and must not come back looking clean.
        BlePacket(
            id = 2,
            timestampMs = 1_786_179_274_869L,
            deviceTimeUs = 27_869_642L,
            channel = 38,
            rssi = -84,
            crcOk = false,
            address = "",
            addressType = "",
            pduType = "Reserved 0xC",
            rawHex = "0000",
            adOffset = -1,
            source = SourceKind.USB,
        ),
        // Commas inside a field would break a naive CSV writer.
        BlePacket(
            id = 3,
            timestampMs = 1_786_179_274_900L,
            deviceTimeUs = 0L,
            channel = 0,
            rssi = -62,
            crcOk = true,
            address = "65:F4:17:3B:58:80",
            addressType = "random",
            pduType = "extended, connectable",
            rawHex = "02011A03030A18",
            adOffset = 0,
            source = SourceKind.PHONE,
        ),
        BlePacket(
            id = 4,
            timestampMs = 1_786_179_275_000L,
            deviceTimeUs = 28_000_000L,
            channel = 37,
            rssi = -65,
            crcOk = true,
            address = "64:1C:AE:7A:6F:C1",
            addressType = "public",
            pduType = "ADV_IND",
            rawHex = "0222C16F7AAE1C641BFF7500420401806064",
            adOffset = 8,
            source = SourceKind.USB,
        ),
    )

    private fun roundTrip(format: ExportFormat): SessionFiles.LoadedSession {
        val out = ByteArrayOutputStream()
        SessionFiles.export(out, format, info) { emit -> packets.forEach(emit) }
        return SessionFiles.load(ByteArrayInputStream(out.toByteArray()))
    }

    private fun assertMatchesOriginal(loaded: SessionFiles.LoadedSession) {
        assertEquals(packets.size, loaded.packets.size)

        for ((expected, actual) in packets.zip(loaded.packets)) {
            assertEquals(expected.timestampMs, actual.timestampMs)
            assertEquals(expected.deviceTimeUs, actual.deviceTimeUs)
            assertEquals(expected.channel, actual.channel)
            assertEquals(expected.rssi, actual.rssi)
            assertEquals(expected.crcOk, actual.crcOk)
            assertEquals(expected.address, actual.address)
            assertEquals(expected.addressType, actual.addressType)
            assertEquals(expected.pduType, actual.pduType)
            assertEquals(expected.rawHex, actual.rawHex)
            assertEquals(expected.adOffset, actual.adOffset)
            assertEquals(expected.source, actual.source)
        }
    }

    @Test
    fun `json round-trips every field`() {
        assertMatchesOriginal(roundTrip(ExportFormat.JSON))
    }

    @Test
    fun `csv round-trips every field`() {
        assertMatchesOriginal(roundTrip(ExportFormat.CSV))
    }

    @Test
    fun `csv keeps session metadata in comment lines`() {
        val out = ByteArrayOutputStream()
        SessionFiles.export(out, ExportFormat.CSV, info) { emit -> packets.forEach(emit) }
        val text = out.toString(Charsets.UTF_8.name())

        assertTrue(text.startsWith("# ble-sniffer-session v1"))
        assertTrue(text.contains("# source=nRF52840 (USB)"))
        assertTrue(text.contains("# channel=All"))

        val loaded = SessionFiles.load(ByteArrayInputStream(out.toByteArray()))
        assertEquals("nRF52840 (USB)", loaded.info.source)
        assertEquals("All", loaded.info.channel)
    }

    @Test
    fun `csv quotes fields containing commas`() {
        val out = ByteArrayOutputStream()
        SessionFiles.export(out, ExportFormat.CSV, info) { emit -> packets.forEach(emit) }
        assertTrue(out.toString(Charsets.UTF_8.name()).contains("\"extended, connectable\""))
    }

    @Test
    fun `format is detected from content not file name`() {
        val json = ByteArrayOutputStream()
        SessionFiles.export(json, ExportFormat.JSON, info) { emit -> packets.forEach(emit) }
        val csv = ByteArrayOutputStream()
        SessionFiles.export(csv, ExportFormat.CSV, info) { emit -> packets.forEach(emit) }

        // Neither call is told which format it is reading.
        assertEquals(packets.size, SessionFiles.load(ByteArrayInputStream(json.toByteArray())).packets.size)
        assertEquals(packets.size, SessionFiles.load(ByteArrayInputStream(csv.toByteArray())).packets.size)
    }
}
