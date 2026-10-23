package com.blesniffer

import com.blesniffer.model.BlePacket
import com.blesniffer.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Collapsing byte-identical packets within one advertiser. */
class PayloadGroupingTest {

    private var nextId = 0L

    /** Packets are supplied newest-first, as the UI holds them. */
    private fun packet(
        rawHex: String,
        rssi: Int,
        timestampMs: Long,
        channel: Int = 37,
        crcOk: Boolean = true,
    ) = BlePacket(
        id = ++nextId,
        timestampMs = timestampMs,
        deviceTimeUs = timestampMs * 1000,
        channel = channel,
        rssi = rssi,
        crcOk = crcOk,
        address = "AA:BB:CC:DD:EE:FF",
        addressType = "public",
        pduType = "ADV_IND",
        rawHex = rawHex,
        adOffset = 8,
        source = SourceKind.USB,
    )

    @Test
    fun `identical payloads collapse regardless of signal strength`() {
        val groups = groupByPayload(
            listOf(
                packet("0011AA", rssi = -60, timestampMs = 3000),
                packet("0011AA", rssi = -75, timestampMs = 2000),
                packet("0011AA", rssi = -91, timestampMs = 1000),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals(3, groups[0].count)
    }

    @Test
    fun `the newest packet supplies the displayed rssi and time`() {
        val groups = groupByPayload(
            listOf(
                packet("0011AA", rssi = -60, timestampMs = 3000),
                packet("0011AA", rssi = -91, timestampMs = 1000),
            ),
        )

        assertEquals(-60, groups[0].lastRssi)
        assertEquals(3000L, groups[0].lastTimestampMs)
    }

    @Test
    fun `different payloads stay apart and sort by count`() {
        val groups = groupByPayload(
            listOf(
                packet("AAAA", rssi = -60, timestampMs = 5000),
                packet("BBBB", rssi = -60, timestampMs = 4000),
                packet("BBBB", rssi = -61, timestampMs = 3000),
                packet("BBBB", rssi = -62, timestampMs = 2000),
            ),
        )

        assertEquals(2, groups.size)
        assertEquals("BBBB", groups[0].rawHex)
        assertEquals(3, groups[0].count)
        assertEquals("AAAA", groups[1].rawHex)
    }

    @Test
    fun `the same payload seen on several channels records all of them`() {
        val groups = groupByPayload(
            listOf(
                packet("0011AA", rssi = -60, timestampMs = 3000, channel = 39),
                packet("0011AA", rssi = -62, timestampMs = 2000, channel = 38),
                packet("0011AA", rssi = -64, timestampMs = 1000, channel = 37),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals(setOf(37, 38, 39), groups[0].channels)
    }

    @Test
    fun `a corrupt copy does not merge with the clean one`() {
        // Same bytes but one failed CRC: merging them would hide the fact that
        // the payload was only ever received intact some of the time.
        val groups = groupByPayload(
            listOf(
                packet("0011AA", rssi = -60, timestampMs = 2000, crcOk = true),
                packet("0011AA", rssi = -88, timestampMs = 1000, crcOk = false),
            ),
        )

        assertEquals(2, groups.size)
        assertTrue(groups.any { it.crcOk })
        assertTrue(groups.any { !it.crcOk })
    }

    @Test
    fun `channels are not recorded for packets with no channel information`() {
        val groups = groupByPayload(
            listOf(packet("0011AA", rssi = -60, timestampMs = 1000, channel = 0)),
        )
        assertTrue(groups[0].channels.isEmpty())
    }
}
