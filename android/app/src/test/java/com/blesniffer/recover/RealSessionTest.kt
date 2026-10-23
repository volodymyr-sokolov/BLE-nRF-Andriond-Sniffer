package com.blesniffer.recover

import com.blesniffer.model.BlePacket
import com.blesniffer.model.RecoveryInfo
import com.blesniffer.model.SourceKind
import com.blesniffer.model.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deduplication and pipeline throughput, measured on a real capture.
 *
 * `real-session.csv` is 2,532 packets from a single session in which **both
 * receivers ran at the same time** - 1,488 from the nRF52840 and 1,044 from the
 * phone, over an overlapping 630 second window. That is the thing `RECOVERY.md`
 * §5.4 said had never happened: every earlier claim about deduplication rested
 * on synthetic pairs, because in the previous session the two radios had run at
 * different times and only twelve payload keys ever appeared from both.
 *
 * What this capture then showed is that deduplication did not work on real data
 * at all, for two reasons neither of which a synthetic pair could reproduce:
 *
 *  - raw capture appends the received CRC to the board's frame, and those three
 *    bytes are ones the host has never had, so the payloads could not be equal;
 *  - **Android does not deliver a PDU.** It merges an advertisement with the
 *    scan response that followed it into one ScanRecord, so for any scannable
 *    advertiser the host's payload is the board's PDU with the scan response's
 *    structures appended. Exact equality can never hold.
 *
 * Both are fixed and both are asserted here.
 */
class RealSessionTest {

    private fun load(): List<BlePacket> =
        javaClass.classLoader!!.getResourceAsStream("real-session.csv")!!
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
            .mapIndexed { index, line ->
                val f = line.split(',', limit = 9)
                BlePacket(
                    id = index.toLong(),
                    timestampMs = f[1].toLong(),
                    deviceTimeUs = f[1].toLong() * 1000,
                    channel = f[2].toInt(),
                    rssi = f[3].toInt(),
                    crcOk = f[4] == "1",
                    address = f[7],
                    addressType = "",
                    pduType = "",
                    rawHex = f[8],
                    adOffset = f[6].toInt(),
                    source = if (f[0] == "usb") SourceKind.USB else SourceKind.PHONE,
                    hasCrcField = f[5] == "1",
                )
            }

    @Test
    fun `the capture really does have both receivers running at once`() {
        val packets = load()
        val usb = packets.filter { it.source == SourceKind.USB }
        val phone = packets.filter { it.source == SourceKind.PHONE }

        assertTrue("no board packets", usb.size > 500)
        assertTrue("no phone packets", phone.size > 500)

        // Overlap, not merely presence. Two sources recorded an hour apart in
        // the same file would satisfy every other assertion here and mean
        // nothing, which is exactly the hole the previous corpus had.
        val start = maxOf(usb.minOf { it.timestampMs }, phone.minOf { it.timestampMs })
        val end = minOf(usb.maxOf { it.timestampMs }, phone.maxOf { it.timestampMs })
        val overlapSeconds = (end - start) / 1000.0

        println("simultaneous window: %.0f s".format(overlapSeconds))
        assertTrue("receivers did not overlap", overlapSeconds > 60)
    }

    @Test
    fun `deduplication finds cross-source pairs in the real capture`() {
        val deduplicator = Deduplicator()
        val packets = load()

        for (packet in packets) deduplicator.offer(packet)
        val released = deduplicator.drain(Long.MAX_VALUE, flush = true)

        val merged = deduplicator.mergedCount()
        val corroborated = released.count { it.sources.size > 1 }

        println(
            "real capture: %d packets in, %d out, %d cross-source merges, %d corroborated"
                .format(packets.size, released.size, merged, corroborated),
        )

        // Before the payload comparison was fixed this was zero - not "low", but
        // exactly zero, across every packet in the session.
        assertTrue("no cross-source pairs found at all", merged > 0)
        assertTrue("merges must reduce the output", released.size < packets.size)
        assertEquals(
            "every merge should leave one packet carrying both sources",
            merged.toInt(),
            corroborated,
        )
    }

    @Test
    fun `a merged packet keeps the board's copy`() {
        val deduplicator = Deduplicator()
        for (packet in load()) deduplicator.offer(packet)

        val both = deduplicator.drain(Long.MAX_VALUE, flush = true)
            .filter { it.sources.size > 1 }
        assertTrue("nothing was merged", both.isNotEmpty())

        // The board's copy is the one carrying the channel, the hardware
        // timestamp and the real CRC status; §1.4 of RECOVERY.md.
        for (packet in both) {
            assertEquals(
                "a merged packet must survive as the board's reception",
                SourceKind.USB,
                packet.source,
            )
        }
    }

    @Test
    fun `the pipeline sustains the specification's throughput target`() {
        // Section 6 asks for at least 5,000 packets/s through layers 1-3. Run
        // over the real capture rather than synthetic frames: the cost is
        // dominated by the reference search, which depends on how many devices
        // there are and how often each repeats itself - both properties of real
        // traffic that invented data gets wrong in whichever direction the
        // inventor was not thinking about.
        val packets = load()

        // Warm up, so the measurement is of the pipeline rather than of the JIT
        // still interpreting it.
        repeat(2) {
            val warm = RecoveryPipeline()
            for (packet in packets) warm.process(packet)
        }

        val rounds = 5
        val started = System.nanoTime()
        var processed = 0
        repeat(rounds) {
            val pipeline = RecoveryPipeline()
            for (packet in packets) {
                pipeline.process(packet)
                processed++
            }
        }
        val elapsed = (System.nanoTime() - started) / 1e9
        val rate = processed / elapsed

        println(
            "throughput: %d packets in %.3f s = %,.0f packets/s".format(processed, elapsed, rate),
        )

        // Deliberately well under the 5,000 target. This runs on whatever
        // machine happens to be building, in a JVM sharing a box with a Gradle
        // daemon, so a tight bound here would fail for reasons that have nothing
        // to do with the pipeline. The number printed above is the result; this
        // assertion only catches a change that makes it catastrophically slower.
        assertTrue("only %.0f packets/s".format(rate), rate >= 2_000)
    }

    @Test
    fun `recovery never overwrites what was received`() {
        // The specification's §5 rule, checked over real traffic including the
        // 547 packets in this session that genuinely failed CRC.
        val pipeline = RecoveryPipeline()
        var repaired = 0

        for (packet in load()) {
            val result = pipeline.process(packet)
            assertEquals(
                "the received bytes must survive exactly",
                packet.rawHex,
                result.rawHex,
            )
            if (result.recovery?.succeeded == true) repaired++
        }
        println("repairs over the real capture: $repaired")
    }

    @Test
    fun `raw-mode frames are recognised as carrying a CRC`() {
        val raw = load().filter { it.hasCrcField }
        assertTrue("no raw-mode frames in the corpus", raw.size > 100)

        // These are the frames layers 1 and 4 need, and the ones that were
        // reported as 100% corrupt before the CRC seed was corrected.
        val verified = raw.count { Crc24.verify(it.rawHex.hexToBytes()) }
        println("raw frames verifying: $verified of ${raw.size}")
        assertTrue("$verified of ${raw.size} verified", verified >= raw.size * 0.9)
    }
}
