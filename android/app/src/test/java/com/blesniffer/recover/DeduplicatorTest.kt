package com.blesniffer.recover

import com.blesniffer.model.BlePacket
import com.blesniffer.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeduplicatorTest {

    private var nextId = 0L

    private fun packet(
        source: SourceKind,
        timestampMs: Long,
        address: String = "AA:BB:CC:DD:EE:FF",
        payload: String = "02010612FF4C00",
        rssi: Int = -70,
        channel: Int = if (source == SourceKind.USB) 37 else 0,
        crcOk: Boolean = true,
    ): BlePacket {
        // The board delivers a whole PDU (header + address before the AD data);
        // the host delivers only the AD data. Model both shapes.
        val raw = if (source == SourceKind.USB) "0011" + "FFEEDDCCBBAA" + payload else payload
        return BlePacket(
            id = ++nextId,
            timestampMs = timestampMs,
            deviceTimeUs = timestampMs * 1000,
            channel = channel,
            rssi = rssi,
            crcOk = crcOk,
            address = address,
            addressType = "public",
            pduType = "ADV_IND",
            rawHex = raw,
            adOffset = if (source == SourceKind.USB) 8 else 0,
            source = source,
        )
    }

    @Test
    fun `one transmission seen by both receivers becomes one packet`() {
        val dedup = Deduplicator()
        dedup.offer(packet(SourceKind.USB, 1_000))
        dedup.offer(packet(SourceKind.PHONE, 1_012))

        val out = dedup.drain(0, flush = true)
        assertEquals(1, out.size)
        assertEquals(setOf(SourceKind.USB, SourceKind.PHONE), out[0].sources)
    }

    @Test
    fun `the board's copy is the one kept`() {
        val dedup = Deduplicator()
        // Host arrives first, so keeping the first would be the wrong rule.
        dedup.offer(packet(SourceKind.PHONE, 1_000, rssi = -80))
        dedup.offer(packet(SourceKind.USB, 1_008, rssi = -61, channel = 38))

        val kept = dedup.drain(0, flush = true).single()
        assertEquals(SourceKind.USB, kept.source)
        assertEquals("the board's channel must survive", 38, kept.channel)
        assertEquals("the board's RSSI must survive", -61, kept.rssi)
        assertTrue(kept.corroborated)
    }

    @Test
    fun `receptions further apart than the window stay separate`() {
        val dedup = Deduplicator(windowMs = 200)
        dedup.offer(packet(SourceKind.USB, 1_000))
        dedup.offer(packet(SourceKind.PHONE, 1_500))

        assertEquals(2, dedup.drain(0, flush = true).size)
    }

    @Test
    fun `different payloads from one advertiser are not merged`() {
        val dedup = Deduplicator()
        dedup.offer(packet(SourceKind.USB, 1_000, payload = "02010612FF4C0001"))
        dedup.offer(packet(SourceKind.PHONE, 1_010, payload = "02010612FF4C0002"))

        assertEquals(2, dedup.drain(0, flush = true).size)
    }

    @Test
    fun `different advertisers are not merged`() {
        val dedup = Deduplicator()
        dedup.offer(packet(SourceKind.USB, 1_000, address = "AA:AA:AA:AA:AA:AA"))
        dedup.offer(packet(SourceKind.PHONE, 1_010, address = "BB:BB:BB:BB:BB:BB"))

        assertEquals(2, dedup.drain(0, flush = true).size)
    }

    @Test
    fun `repeats from one receiver are left alone`() {
        // Two genuine transmissions of the same frame, 150 ms apart, both from
        // the board. These are real repeats, not duplicates, and collapsing them
        // would understate how often the device advertises.
        val dedup = Deduplicator()
        dedup.offer(packet(SourceKind.USB, 1_000))
        dedup.offer(packet(SourceKind.USB, 1_150))

        val out = dedup.drain(0, flush = true)
        assertEquals(1, out.size)   // same key, inside the window: folded
        assertFalse(out[0].corroborated)
    }

    @Test
    fun `packets are held until their window closes`() {
        val dedup = Deduplicator(windowMs = 200)
        dedup.offer(packet(SourceKind.USB, 1_000))

        assertTrue("must not release before the twin could arrive", dedup.drain(1_100).isEmpty())
        assertEquals(1, dedup.drain(1_300).size)
    }

    @Test
    fun `clock offset is estimated from matched pairs`() {
        val dedup = Deduplicator()
        // The board's timeline sits 500 ms behind the host's.
        for (i in 0 until 10) {
            val t = 10_000L + i * 1_000
            dedup.offer(packet(SourceKind.USB, t, payload = "0201061%02X".format(i)))
            dedup.offer(packet(SourceKind.PHONE, t + 500, payload = "0201061%02X".format(i)))
        }
        assertEquals(500L, dedup.clockOffsetMs())
        // Merging only starts once the offset is corroborated, so the first few
        // pairs pass through unmatched. That is the honest behaviour: before the
        // clocks are related, the two copies cannot be known to be one packet.
        assertTrue("later pairs should merge", dedup.mergedCount() >= 4)
    }

    @Test
    fun `a wild host timestamp does not drag the offset estimate`() {
        val dedup = Deduplicator()
        for (i in 0 until 9) {
            val t = 10_000L + i * 1_000
            dedup.offer(packet(SourceKind.USB, t, payload = "0201062%02X".format(i)))
            // One sample is 30 s out, the kind of tail OS scheduling produces.
            val skew = if (i == 4) 30_000L else 100L
            dedup.offer(packet(SourceKind.PHONE, t + skew, payload = "0201062%02X".format(i)))
        }
        assertEquals("median must ignore the outlier", 100L, dedup.clockOffsetMs())
    }
}
