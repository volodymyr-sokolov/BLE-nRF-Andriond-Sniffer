package com.blesniffer.recover

import com.blesniffer.model.BlePacket
import com.blesniffer.model.RecoveryMethod
import com.blesniffer.model.SourceKind
import com.blesniffer.model.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The specification's governing rule for this pipeline is that a false repair is
 * worse than a refusal, because a wrongly "fixed" packet poisons everything
 * downstream. Most of these tests are therefore about refusing.
 */
class RecoveryPipelineTest {

    private var nextId = 0L

    /** A well-formed PDU: 2 byte header, 6 byte address, then AD structures. */
    private val cleanHex = "0011" + "FFEEDDCCBBAA" + "020106" + "03FF4C00"

    private fun packet(
        hex: String,
        crcOk: Boolean,
        timestampMs: Long,
        address: String = "AA:BB:CC:DD:EE:FF",
        sources: Set<SourceKind> = setOf(SourceKind.USB),
    ) = BlePacket(
        id = ++nextId,
        timestampMs = timestampMs,
        deviceTimeUs = timestampMs * 1000,
        channel = 37,
        rssi = -70,
        crcOk = crcOk,
        address = address,
        addressType = "public",
        pduType = "ADV_IND",
        rawHex = hex,
        adOffset = 8,
        source = SourceKind.USB,
        sources = sources,
    )

    /** Flips one bit in the byte at [index]. */
    private fun corrupt(hex: String, index: Int, mask: Int = 0x01): String {
        val bytes = hex.hexToBytes()
        bytes[index] = (bytes[index].toInt() xor mask).toByte()
        return bytes.joinToString("") { "%02X".format(it) }
    }

    @Test
    fun `a clean packet is returned untouched`() {
        val pipeline = RecoveryPipeline()
        val result = pipeline.process(packet(cleanHex, crcOk = true, timestampMs = 1_000))
        assertEquals(null, result.recovery)
    }

    /**
     * Feeds the same clean frame enough times to count as the device's norm -
     * enough sightings to corroborate it, and enough samples for the byte
     * template to have an opinion about which positions are stable.
     */
    private fun RecoveryPipeline.seed(times: Int = 10, from: Long = 1_000) {
        repeat(times) { process(packet(cleanHex, crcOk = true, timestampMs = from + it)) }
    }

    @Test
    fun `a corrupt packet is repaired from an earlier clean reception`() {
        val pipeline = RecoveryPipeline()
        pipeline.seed()

        val damaged = corrupt(cleanHex, index = 10)
        val result = pipeline.process(packet(damaged, crcOk = false, timestampMs = 1_100))

        val recovery = assertNotNull("should have been repaired", result.recovery).let { result.recovery!! }
        assertTrue(recovery.succeeded)
        assertEquals(cleanHex, recovery.correctedHex)
        assertEquals(1, recovery.bitsCorrected)
        // The original must survive exactly as received.
        assertEquals(damaged, result.rawHex)
    }

    @Test
    fun `corroboration alone does not make a repair cross-source`() {
        val pipeline = RecoveryPipeline()
        pipeline.seed()

        // This test used to assert the opposite, and asserting it was what kept
        // the defect in place: that both receivers saw a transmission says
        // nothing about where the bytes used to repair it came from. Every
        // reference here was fed in by the board, so the repair is a repeat over
        // time however many radios happened to witness the packet.
        //
        // A genuine cross-source repair is exercised in CrossSourceRepairTest,
        // which feeds the phone's differently-framed copy and gets it back.
        val damaged = corrupt(cleanHex, index = 10)
        val result = pipeline.process(
            packet(damaged, false, 1_100, sources = setOf(SourceKind.USB, SourceKind.PHONE)),
        )
        assertEquals(RecoveryMethod.CROSS_TIME, result.recovery?.method)
    }

    @Test
    fun `a repeat from the same receiver is credited to voting over time`() {
        val pipeline = RecoveryPipeline()
        pipeline.seed()

        val result = pipeline.process(packet(corrupt(cleanHex, 10), false, 1_100))
        assertEquals(RecoveryMethod.CROSS_TIME, result.recovery?.method)
    }

    @Test
    fun `a frame seen only once is not trusted as a repair source`() {
        // One sighting could itself have been a corrupted or one-off frame.
        // Measurement showed single-sighting references were a large share of
        // the wrong repairs, so the device has to have repeated it.
        val pipeline = RecoveryPipeline()
        pipeline.process(packet(cleanHex, crcOk = true, timestampMs = 1_000))

        val result = pipeline.process(packet(corrupt(cleanHex, 10), false, 1_100))
        assertEquals(RecoveryMethod.UNRECOVERABLE, result.recovery?.method)
    }

    @Test
    fun `a byte the device varies is never rewritten`() {
        // The template must stop Layer 2 from "correcting" a counter that
        // legitimately advanced - the failure mode that dominated the false
        // repair rate before the gate existed.
        val pipeline = RecoveryPipeline()
        // Byte 10 counts; everything else is fixed.
        repeat(12) { i ->
            val varying = cleanHex.hexToBytes().also { it[10] = i.toByte() }
            pipeline.process(
                packet(varying.joinToString("") { b -> "%02X".format(b) }, true, 1_000L + i),
            )
        }

        // A packet whose only difference is that counter must not be "repaired".
        val next = cleanHex.hexToBytes().also { it[10] = 99 }
        val result = pipeline.process(
            packet(next.joinToString("") { b -> "%02X".format(b) }, false, 1_100),
        )
        assertEquals(RecoveryMethod.UNRECOVERABLE, result.recovery?.method)
    }

    @Test
    fun `with no reference at all the packet is flagged rather than guessed`() {
        val pipeline = RecoveryPipeline()
        val result = pipeline.process(packet(corrupt(cleanHex, 10), false, 1_000))

        assertEquals(RecoveryMethod.UNRECOVERABLE, result.recovery?.method)
        assertFalse(result.recovery!!.succeeded)
        assertEquals("", result.recovery!!.correctedHex)
    }

    @Test
    fun `a stale reference is not used`() {
        val pipeline = RecoveryPipeline(historyMs = 5_000)
        pipeline.seed()

        // Sixty seconds later the device may well be saying something else.
        val result = pipeline.process(packet(corrupt(cleanHex, 10), false, 61_000))
        assertEquals(RecoveryMethod.UNRECOVERABLE, result.recovery?.method)
    }

    @Test
    fun `a wildly different payload is not treated as the same frame`() {
        val pipeline = RecoveryPipeline(maxByteDistance = 4)
        pipeline.seed()

        // Same length, same advertiser, but nothing like the reference.
        val unrelated = "0011" + "FFEEDDCCBBAA" + "112233" + "44556677"
        val result = pipeline.process(packet(unrelated, false, 1_100))
        assertEquals(RecoveryMethod.UNRECOVERABLE, result.recovery?.method)
    }

    @Test
    fun `references from a different advertiser are never used`() {
        val pipeline = RecoveryPipeline()
        pipeline.process(packet(cleanHex, true, 1_000, address = "11:11:11:11:11:11"))

        val result = pipeline.process(
            packet(corrupt(cleanHex, 10), false, 1_100, address = "22:22:22:22:22:22"),
        )
        assertEquals(RecoveryMethod.UNRECOVERABLE, result.recovery?.method)
    }

    @Test
    fun `structural filter rejects an advertising chain that does not tile`() {
        val pipeline = RecoveryPipeline()
        // Length byte claims 9 bytes of AD data where only 3 remain.
        val ragged = ("0011" + "FFEEDDCCBBAA" + "09" + "0106").hexToBytes()
        assertFalse(pipeline.structurallyValid(ragged, adOffset = 8))

        val tidy = ("0011" + "FFEEDDCCBBAA" + "020106").hexToBytes()
        assertTrue(pipeline.structurallyValid(tidy, adOffset = 8))
    }

    @Test
    fun `structural filter accepts zero padding after the chain`() {
        val padded = ("0011" + "FFEEDDCCBBAA" + "020106" + "000000").hexToBytes()
        assertTrue(RecoveryPipeline().structurallyValid(padded, adOffset = 8))
    }

    @Test
    fun `a device template only restores positions that never vary`() {
        val template = ByteTemplate()
        // Byte 3 counts up on every packet; the rest is fixed.
        repeat(10) { i ->
            template.observe(byteArrayOf(0x11, 0x22, 0x33, i.toByte()))
        }
        assertTrue(template.trained)

        val damaged = byteArrayOf(0x99.toByte(), 0x22, 0x33, 0x07)
        val restored = template.restoreConstantPositions(damaged)!!

        assertEquals("constant byte restored", 0x11.toByte(), restored[0])
        assertEquals("counter left alone", 0x07.toByte(), restored[3])
    }

    @Test
    fun `an untrained template refuses to restore anything`() {
        val template = ByteTemplate()
        repeat(3) { template.observe(byteArrayOf(0x11, 0x22)) }
        assertFalse(template.trained)
        assertEquals(null, template.restoreConstantPositions(byteArrayOf(0x99.toByte(), 0x22)))
    }

    @Test
    fun `confidence never claims certainty`() {
        val pipeline = RecoveryPipeline()
        repeat(12) { pipeline.process(packet(cleanHex, true, 1_000L + it)) }

        val result = pipeline.process(packet(corrupt(cleanHex, 10), false, 1_100))
        val confidence = result.recovery!!.confidence
        assertTrue("confidence $confidence must stay below 1", confidence < 1f)
        assertTrue("confidence $confidence should be high here", confidence > 0.5f)
    }
}
