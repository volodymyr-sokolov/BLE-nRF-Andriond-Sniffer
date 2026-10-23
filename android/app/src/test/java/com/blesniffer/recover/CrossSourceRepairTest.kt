package com.blesniffer.recover

import com.blesniffer.model.BlePacket
import com.blesniffer.model.RecoveryMethod
import com.blesniffer.model.SourceKind
import com.blesniffer.model.hexToBytes
import com.blesniffer.model.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repairing a damaged board reception from the phone's clean copy of the same
 * transmission - the strongest evidence the two-receiver design exists to
 * produce, and which was being discarded entirely.
 *
 * Two independent reasons it could never fire. The deduplicator pairs receptions
 * on advertiser address *plus payload*, and a corrupt copy has a different
 * payload by definition, so the pair it most wants to make is the one pair it
 * cannot. And inside the pipeline the two receivers' frames were compared with
 * `size == size`, which is never true: Android hands up advertising data alone,
 * the board hands up a whole PDU with a two byte header and a six byte address
 * in front of it.
 *
 * So `CROSS_SOURCE` was only ever reported by inferring it from the packet being
 * corroborated - a fact about the packet, not about the evidence used to repair
 * it - and the label went on a repair that had actually come from a repeat by
 * the same radio.
 */
class CrossSourceRepairTest {

    /** Advertising data both receivers see: flags, TX power, a service UUID. */
    private val advertisingData = "020106020A040302F018"

    /** What the board delivers: header, address, then the advertising data. */
    private fun boardFrame(ad: String = advertisingData): String {
        val body = "4010" + "9E56A95EE9D5" + ad
        return body
    }

    private fun boardPacket(hex: String, crcOk: Boolean, timestampMs: Long) = BlePacket(
        id = 0,
        timestampMs = timestampMs,
        deviceTimeUs = timestampMs * 1000,
        channel = 37,
        rssi = -70,
        crcOk = crcOk,
        address = "D5:E9:5E:A9:56:9E",
        addressType = "public",
        pduType = "ADV_IND",
        rawHex = hex,
        adOffset = 8,
        source = SourceKind.USB,
    )

    /** What Android delivers: the advertising data, nothing in front of it. */
    private fun phonePacket(ad: String, timestampMs: Long) = BlePacket(
        id = 0,
        timestampMs = timestampMs,
        deviceTimeUs = timestampMs * 1000,
        channel = BlePacket.CHANNEL_UNKNOWN,
        rssi = -74,
        crcOk = true,
        address = "D5:E9:5E:A9:56:9E",
        addressType = "public",
        pduType = "legacy, connectable",
        rawHex = ad,
        adOffset = 0,
        source = SourceKind.PHONE,
    )

    /** Flips bits in the advertising region of a board frame. */
    private fun damageAdvertisingData(hex: String, byteIndex: Int, mask: Int): String {
        val bytes = hex.hexToBytes()
        bytes[8 + byteIndex] = (bytes[8 + byteIndex].toInt() xor mask).toByte()
        return bytes.toHex()
    }

    @Test
    fun `the phone's clean copy repairs the board's damaged one`() {
        val pipeline = RecoveryPipeline()

        // The phone saw this transmission cleanly.
        pipeline.process(phonePacket(advertisingData, 1_000))

        // The board's copy of the same thing arrived with two damaged bytes.
        val damaged = damageAdvertisingData(boardFrame(), byteIndex = 3, mask = 0x24)
        val result = pipeline.process(boardPacket(damaged, crcOk = false, timestampMs = 1_010))

        val recovery = result.recovery
        assertTrue("no repair was attempted", recovery != null)
        assertEquals(
            "the repair must be attributed to the other receiver",
            RecoveryMethod.CROSS_SOURCE,
            recovery!!.method,
        )
        assertEquals(
            "the original frame should have been restored exactly",
            boardFrame().uppercase(),
            recovery.correctedHex.uppercase(),
        )
    }

    @Test
    fun `a repeat from the same radio is not called cross-source`() {
        val pipeline = RecoveryPipeline()

        // Only the board is running, and it has seen this frame cleanly twice.
        // Ten, not three: the template gate needs eight samples before "this
        // position never varies" means anything, and below that the pipeline
        // correctly refuses to rewrite anything at all.
        repeat(10) { i ->
            pipeline.process(boardPacket(boardFrame(), crcOk = true, timestampMs = 1_000L + i))
        }

        val damaged = damageAdvertisingData(boardFrame(), byteIndex = 2, mask = 0x08)
        val result = pipeline.process(boardPacket(damaged, crcOk = false, timestampMs = 1_020))

        val recovery = result.recovery
        assertTrue("no repair was attempted", recovery != null)
        assertNotEquals(
            "there was no other receiver, so this cannot be cross-source",
            RecoveryMethod.CROSS_SOURCE,
            recovery!!.method,
        )
        assertEquals(RecoveryMethod.CROSS_TIME, recovery.method)
    }

    @Test
    fun `a corroborated packet repaired from its own radio is still cross-time`() {
        val pipeline = RecoveryPipeline()
        // Ten, not three: the template gate needs eight samples before "this
        // position never varies" means anything, and below that the pipeline
        // correctly refuses to rewrite anything at all.
        repeat(10) { i ->
            pipeline.process(boardPacket(boardFrame(), crcOk = true, timestampMs = 1_000L + i))
        }

        // Corroborated - both receivers saw this transmission - but the repair
        // below still comes from the board's own earlier receptions, because the
        // phone contributed no reference. The old code read the label off this
        // flag and would have called it CROSS_SOURCE.
        val damaged = damageAdvertisingData(boardFrame(), byteIndex = 2, mask = 0x08)
        val result = pipeline.process(
            boardPacket(damaged, crcOk = false, timestampMs = 1_020)
                .copy(sources = setOf(SourceKind.USB, SourceKind.PHONE)),
        )

        assertEquals(RecoveryMethod.CROSS_TIME, result.recovery!!.method)
    }

    @Test
    fun `a cross-source repair that carries a CRC is verified rather than believed`() {
        val pipeline = RecoveryPipeline()

        val body = boardFrame().hexToBytes()
        val withCrc = body + Crc24.toBytes(Crc24.compute(body))

        pipeline.process(phonePacket(advertisingData, 1_000))

        // Eight flipped bits across two bytes. Fewer than three and layer 1
        // repairs it by syndrome decoding before this path is ever reached,
        // which is the correct ordering - algebra outranks a twin - and makes a
        // lightly damaged frame useless for testing this layer.
        val bytes = withCrc.copyOf()
        bytes[8 + 2] = (bytes[8 + 2].toInt() xor 0x3C).toByte()
        bytes[8 + 3] = (bytes[8 + 3].toInt() xor 0x3C).toByte()

        val result = pipeline.process(
            boardPacket(bytes.toHex(), crcOk = false, timestampMs = 1_010)
                .copy(hasCrcField = true),
        )

        val recovery = result.recovery!!
        assertEquals(RecoveryMethod.CROSS_SOURCE, recovery.method)
        assertTrue(
            "a CRC-verified repair should not be reported as a guess",
            recovery.confidence >= 0.99f,
        )
        assertTrue(Crc24.verify(recovery.correctedHex.hexToBytes()))
    }

    @Test
    fun `a different advertisement from the phone is not spliced in`() {
        val pipeline = RecoveryPipeline()

        // Same device, same length, entirely different contents. Accepting this
        // would be a false repair wearing a valid structure.
        pipeline.process(phonePacket("0201061AFF4C0002FFEE11", 1_000))

        val damaged = damageAdvertisingData(boardFrame(), byteIndex = 3, mask = 0x24)
        val result = pipeline.process(boardPacket(damaged, crcOk = false, timestampMs = 1_010))

        assertEquals(
            "too far from the damaged frame to be the same transmission",
            RecoveryMethod.UNRECOVERABLE,
            result.recovery!!.method,
        )
    }
}
