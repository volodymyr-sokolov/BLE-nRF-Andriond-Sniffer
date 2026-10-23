package com.blesniffer.recover

import com.blesniffer.model.BlePacket
import com.blesniffer.model.SourceKind
import com.blesniffer.model.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recovery measured against ground truth from a real radio channel.
 *
 * Every other figure in this project comes from taking a clean packet,
 * corrupting it under a model we chose, and seeing whether the pipeline puts it
 * back. That measures the pipeline against our own assumptions about the
 * channel. §6 of the specification asks for the other kind of evidence, and this
 * is it: a second nRF52840 running `firmware/src/beacon/` transmitted a fixed
 * 39 byte PDU 100 times on each of three channels at each of fourteen power
 * levels, from +8 dBm down to −40 dBm, while the sniffer captured in raw mode.
 *
 * `reference-beacon.csv` is what came back - 1,224 receptions, of which 17
 * differ from what was sent. The correct answer for every one of them is known,
 * because the transmitter said what it transmitted.
 *
 * What the run established about the channel, which no injected damage could:
 *
 *  - **Errors appear only at the bottom.** Nothing was damaged at any level from
 *    +8 down to −20 dBm. All 17 arrived at −40 dBm, mean RSSI −86.6, at the edge
 *    of the receiver's sensitivity.
 *  - **Neither error model is right.** A binary symmetric channel predicts
 *    isolated single-bit flips; only 4 of the 15 ordinary frames looked like
 *    that. Nine had their damage in *adjacent pairs*. The burst model injects
 *    12–96 contiguous bits, which never once happened.
 *  - **A third failure mode exists that neither model contains.** Two frames were
 *    byte-perfect for 9 and 15 bytes and then diverged completely, with zero of
 *    the remaining bytes matching by chance. That is the demodulator losing lock
 *    partway through a packet, not a bit-error process at all.
 */
class ReferenceBeaconTest {

    private class Bench(val sent: ByteArray, val received: List<BlePacket>)

    private fun load(): Bench {
        val lines = javaClass.classLoader!!.getResourceAsStream("reference-beacon.csv")!!
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }

        val sent = lines.first().removePrefix("sent,").hexToBytes()

        var timestamp = 1_000L
        val received = lines.drop(1).mapIndexed { index, line ->
            val (channel, rssi, crcOk, adOffset, hex) = line.split(',', limit = 5)
            timestamp += 10
            BlePacket(
                id = index.toLong(),
                timestampMs = timestamp,
                deviceTimeUs = timestamp * 1000,
                channel = channel.toInt(),
                rssi = rssi.toInt(),
                crcOk = crcOk == "1",
                address = "C0:FF:EE:BE:AC:01",
                addressType = "public",
                pduType = "ADV_NONCONN_IND",
                rawHex = hex,
                adOffset = adOffset.toInt(),
                source = SourceKind.USB,
                hasCrcField = true,
            )
        }
        return Bench(sent, received)
    }

    private operator fun <T> List<T>.component4() = this[3]
    private operator fun <T> List<T>.component5() = this[4]

    /** The advertising body, without the three received CRC bytes. */
    private fun body(hex: String, sentSize: Int): ByteArray {
        val bytes = hex.hexToBytes()
        return if (bytes.size == sentSize + 3) bytes.copyOfRange(0, sentSize) else bytes
    }

    @Test
    fun `the corpus is what the beacon actually sent`() {
        val bench = load()
        assertEquals("39 byte ADV_NONCONN_IND", 39, bench.sent.size)
        assertTrue("no receptions loaded", bench.received.size > 1_000)

        val damaged = bench.received.count { !body(it.rawHex, bench.sent.size).contentEquals(bench.sent) }
        println("beacon receptions: ${bench.received.size}, damaged: $damaged")
        assertEquals("the bench run had 17 damaged frames", 17, damaged)
    }

    @Test
    fun `repairs of real damage are correct, and none is invented`() {
        val bench = load()
        val pipeline = RecoveryPipeline()

        var correct = 0
        var wrong = 0
        var refused = 0

        for (packet in bench.received) {
            val result = pipeline.process(packet)
            if (body(packet.rawHex, bench.sent.size).contentEquals(bench.sent)) {
                continue  // arrived intact
            }

            val recovery = result.recovery
            if (recovery == null || !recovery.succeeded) {
                refused++
                continue
            }
            if (body(recovery.correctedHex, bench.sent.size).contentEquals(bench.sent)) {
                correct++
            } else {
                wrong++
            }
        }

        val total = correct + wrong + refused
        println(
            "against ground truth: $correct correct, $wrong wrong, $refused refused of $total",
        )

        // A false repair is worse than a refusal - the specification is explicit,
        // and this is the only place in the project where "wrong" can be
        // measured rather than modelled. It must be zero.
        assertEquals("a repair was accepted that did not match what was sent", 0, wrong)
        assertTrue("only $correct of $total repaired", correct >= 12)
    }

    @Test
    fun `the frames that lost demodulator sync are refused`() {
        val bench = load()
        val pipeline = RecoveryPipeline()

        var refusedSyncLoss = 0
        for (packet in bench.received) {
            val received = body(packet.rawHex, bench.sent.size)
            val result = pipeline.process(packet)
            if (received.size != bench.sent.size) continue

            // Byte-perfect for a while, then nothing matching at all: the
            // receiver stopped tracking the signal mid-packet. There is no
            // correct repair for these - the bytes were never transmitted - so
            // the only right answer is to refuse.
            val prefix = received.indices.firstOrNull { received[it] != bench.sent[it] } ?: continue
            val tailMatches = (prefix until received.size).count { received[it] == bench.sent[it] }
            if (prefix < 20 && tailMatches == 0) {
                assertTrue(
                    "a frame that lost sync after $prefix bytes was 'repaired'",
                    result.recovery?.succeeded != true,
                )
                refusedSyncLoss++
            }
        }

        println("sync-loss frames correctly refused: $refusedSyncLoss")
        assertTrue("the corpus should contain sync-loss frames", refusedSyncLoss >= 2)
    }
}
