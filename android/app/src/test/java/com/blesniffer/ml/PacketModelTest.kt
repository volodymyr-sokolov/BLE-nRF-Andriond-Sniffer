package com.blesniffer.ml

import com.blesniffer.model.BlePacket
import com.blesniffer.model.SourceKind
import com.blesniffer.model.hexToBytes
import com.blesniffer.model.RecoveryInfo
import com.blesniffer.model.RecoveryMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Measures the model on the same corpus the recovery pipeline is measured on:
 * 2,854 real PDUs from 18 advertisers, captured with the nRF52840 in this
 * environment.
 *
 * Real payload structure matters more here than anywhere else in the project.
 * Device identification is *entirely* a claim about payload structure, and
 * synthetic payloads would hand it a signature far cleaner than any real
 * advertiser produces.
 *
 * Every corrupt frame is manufactured, because a real one has no ground truth to
 * measure against. Manufacturing it is what makes the label exist; it is also
 * the ceiling on what these numbers mean, and §6 of ML_MODEL.md says so.
 */
class PacketModelTest {

    private data class Corpus(val address: String, val adOffset: Int, val packets: List<ByteArray>)

    private fun loadCorpus(): List<Corpus> {
        val text = javaClass.classLoader!!.getResourceAsStream("clean-packets.csv")!!
            .bufferedReader().readText()

        val grouped = LinkedHashMap<String, MutableList<Pair<Int, ByteArray>>>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val (address, adOffset, hex) = line.split(',', limit = 3)
            grouped.getOrPut(address) { mutableListOf() }
                .add(adOffset.toInt() to hex.hexToBytes())
        }
        return grouped.map { (address, packets) ->
            Corpus(address, packets.first().first, packets.map { it.second })
        }
    }

    private fun frames(): List<Triple<String, Frame, Int>> =
        loadCorpus().flatMapIndexed { device, corpus ->
            corpus.packets.map { bytes ->
                Triple(
                    corpus.address,
                    Frame(bytes = bytes, adOffset = corpus.adOffset, hasHeader = true),
                    device,
                )
            }
        }

    /** Two thirds trains, one third is held out and damaged. */
    private fun split(): Pair<List<Pair<String, Frame>>, List<Pair<String, Frame>>> {
        val byDevice = frames().groupBy { it.first }
        val train = mutableListOf<Pair<String, Frame>>()
        val test = mutableListOf<Pair<String, Frame>>()
        for ((address, items) in byDevice) {
            val cut = items.size * 2 / 3
            items.forEachIndexed { i, (_, frame, _) ->
                if (i < cut) train += address to frame else test += address to frame
            }
        }
        return train to test
    }

    // --- the rule that governs everything -------------------------------------

    @Test
    fun `refuses to train on a corrupt packet`() {
        val model = PacketModel()
        val corrupt = packet(crcOk = false, recovery = null)

        assertFalse(model.observe(corrupt))
        assertEquals(0L, model.observedCount)
        assertEquals(1L, model.refusedCount)
    }

    @Test
    fun `refuses to train on a repaired packet`() {
        val model = PacketModel()
        // The case the requirement is actually about: the pipeline believes it
        // put this packet back together, and it may well have. It is still a
        // hypothesis, and training on it would make the model's own output its
        // evidence.
        val repaired = packet(
            crcOk = false,
            recovery = RecoveryInfo(
                method = RecoveryMethod.CROSS_TIME,
                correctedHex = "02011A0AFF",
                bitsCorrected = 2,
                confidence = 0.8f,
                candidatesEvaluated = 4,
                originalCrcOk = false,
            ),
        )

        assertFalse(model.observe(repaired))
        assertEquals(0L, model.observedCount)
    }

    @Test
    fun `refuses a repaired packet even if it is marked CRC-OK`() {
        val model = PacketModel()
        // Defence against a future change that starts marking accepted repairs
        // as CRC-OK. The fact and the rule are checked separately for exactly
        // this reason.
        val laundered = packet(
            crcOk = true,
            recovery = RecoveryInfo(
                method = RecoveryMethod.CRC_SYNDROME,
                correctedHex = "02011A0AFF",
                bitsCorrected = 1,
                confidence = 0.99f,
                candidatesEvaluated = 1,
                originalCrcOk = false,
            ),
        )

        assertFalse(model.observe(laundered))
        assertEquals(0L, model.observedCount)
    }

    @Test
    fun `learns from an intact packet`() {
        val model = PacketModel()
        assertTrue(model.observe(packet(crcOk = true, recovery = null)))
        assertEquals(1L, model.observedCount)
        assertEquals(0L, model.refusedCount)
    }

    // --- initial and incremental learning -------------------------------------

    @Test
    fun `bootstraps from a stored capture and keeps learning`() {
        val (train, test) = split()
        val model = PacketModel(Random(1))

        model.bootstrap(train, rounds = 2)
        assertTrue("bootstrap should train the model", model.trained)
        val afterBootstrap = model.classifierUpdates

        // Incremental: every further intact packet is another gradient step,
        // with no retraining and no pass over history.
        for ((address, frame) in test.take(200)) {
            model.observe(address, frame)
        }
        assertTrue(model.classifierUpdates > afterBootstrap)
        assertEquals(18, model.deviceCount)
    }

    @Test
    fun `an incrementally trained model reaches the same accuracy as a bootstrapped one`() {
        val (train, test) = split()

        val bootstrapped = PacketModel(Random(2)).apply { bootstrap(train, rounds = 3) }
        // The same data, one packet at a time, never handed back - the
        // situation during a live capture. Interleaved across advertisers
        // because that is how a capture arrives; the corpus on disk is grouped
        // by device, and feeding it in that order means the model spends its
        // first thousand packets in a world containing exactly one device,
        // where a collision cannot be constructed and nothing it learns about
        // telling devices apart can be right.
        val incremental = PacketModel(Random(2)).apply {
            for ((address, frame) in interleave(train)) observe(address, frame)
        }

        val a = classificationAccuracy(bootstrapped, test, Random(7))
        val b = classificationAccuracy(incremental, test, Random(7))
        println("accuracy: bootstrapped %.1f%%, incremental %.1f%%".format(a * 100, b * 100))

        assertTrue("bootstrapped accuracy $a too low", a >= 0.70)
        assertTrue("incremental accuracy $b too low", b >= 0.70)
    }

    // --- error classification --------------------------------------------------

    @Test
    fun `classifies manufactured damage`() {
        val (train, test) = split()
        val model = PacketModel(Random(3)).apply { bootstrap(train, rounds = 3) }

        val confusion = HashMap<Pair<ErrorClass, ErrorClass>, Int>()
        val perClass = HashMap<ErrorClass, IntArray>()
        val random = Random(11)

        for ((address, frame) in test) {
            val kind = ErrorClass.entries[random.nextInt(ErrorClass.entries.size)]
            val other = test.firstOrNull { it.first != address }?.second?.bytes
            val damaged = SyntheticDamage.apply(frame, kind, other, random) ?: continue

            val got = model.analyse(damaged).errorClass
            confusion[kind to got] = (confusion[kind to got] ?: 0) + 1
            val counts = perClass.getOrPut(kind) { IntArray(2) }
            counts[0]++
            if (got == kind) counts[1]++
        }

        val total = perClass.values.sumOf { it[0] }
        val right = perClass.values.sumOf { it[1] }
        val accuracy = right.toDouble() / total

        println("error classification: %d examples, accuracy %.1f%%".format(total, accuracy * 100))
        for (kind in ErrorClass.entries) {
            val c = perClass[kind] ?: continue
            println("  %-10s %5d examples, recall %.1f%%".format(kind.id, c[0], 100.0 * c[1] / c[0]))
        }

        assertTrue("overall accuracy $accuracy", accuracy >= 0.70)
        // Splice is the class that changes what the pipeline should do - it is
        // the one case where repair is not merely hard but wrong - so it is
        // asserted separately rather than being allowed to hide inside an
        // average carried by the easy classes.
        val splice = perClass[ErrorClass.SPLICE]!!
        assertTrue(
            "splice recall ${100.0 * splice[1] / splice[0]}%",
            splice[1].toDouble() / splice[0] >= 0.55,
        )
    }

    // --- device identification -------------------------------------------------

    @Test
    fun `names the sender of a packet it cannot repair`() {
        val (train, test) = split()
        val model = PacketModel(Random(4)).apply { bootstrap(train, rounds = 2) }

        val random = Random(13)
        var attributed = 0
        var correct = 0

        for ((address, frame) in test) {
            // Heavy burst damage: far beyond what any layer of the pipeline can
            // put back together, which is exactly the packet the requirement is
            // about. The sender should still be named.
            val damaged = SyntheticDamage.apply(frame, ErrorClass.BURST, null, random) ?: continue
            val sender = model.identify(damaged).firstOrNull() ?: continue
            attributed++
            if (sender.address == address) correct++
        }

        val rate = correct.toDouble() / attributed
        println("device identification under burst damage: %.1f%% of %d".format(rate * 100, attributed))
        assertTrue("identification rate $rate", rate >= 0.80)
    }

    @Test
    fun `names the sender when the address bytes themselves are damaged`() {
        val (train, test) = split()
        val model = PacketModel(Random(5)).apply { bootstrap(train, rounds = 2) }

        val random = Random(17)
        var attributed = 0
        var correct = 0

        for ((address, frame) in test) {
            if (frame.bytes.size < 8) continue
            // The advertiser address lives at bytes 2..7 of the PDU. Corrupting
            // it is what mints the phantom advertisers a noisy capture fills up
            // with, and it is the case where reading the address field gives a
            // confident wrong answer. Identification must not be reading it.
            val bytes = frame.bytes.copyOf()
            for (i in 2 until 8) bytes[i] = random.nextInt(256).toByte()

            val sender = model.identify(frame.copy(bytes = bytes)).firstOrNull() ?: continue
            attributed++
            if (sender.address == address) correct++
        }

        val rate = correct.toDouble() / attributed
        println("identification with the address destroyed: %.1f%% of %d".format(rate * 100, attributed))
        assertTrue("identification rate $rate", rate >= 0.70)
    }

    // --- persistence ------------------------------------------------------------

    @Test
    fun `a restored snapshot classifies identically`() {
        val (train, test) = split()
        val model = PacketModel(Random(6)).apply { bootstrap(train, rounds = 2) }

        val restored = PacketModel(Random(6))
        // Profiles are rebuilt by replaying the capture, as the app does on
        // startup; only the classifier is carried across, because only it was
        // fitted on examples that no longer exist.
        for ((address, frame) in train) {
            restored.observeWithoutTraining(address, frame)
        }
        assertTrue(restored.restore(model.snapshot()))

        val random = Random(19)
        var same = 0
        var n = 0
        for ((address, frame) in test.take(300)) {
            val kind = ErrorClass.entries[random.nextInt(ErrorClass.entries.size)]
            val other = test.firstOrNull { it.first != address }?.second?.bytes
            val damaged = SyntheticDamage.apply(frame, kind, other, random) ?: continue
            n++
            if (model.analyse(damaged).errorClass == restored.analyse(damaged).errorClass) same++
        }

        assertEquals("restored model disagreed on ${n - same} of $n", n, same)
    }

    @Test
    fun `refuses a snapshot from a different feature set`() {
        val model = PacketModel()
        assertFalse(model.restore("5 3 100 0.1 0.2 0.3"))
        assertFalse(model.restore("nonsense"))
    }

    // --- helpers ----------------------------------------------------------------

    /** Round-robins the corpus across advertisers, as arrival order does. */
    private fun interleave(items: List<Pair<String, Frame>>): List<Pair<String, Frame>> {
        val queues = items.groupBy { it.first }.values.map { ArrayDeque(it) }
        val out = ArrayList<Pair<String, Frame>>(items.size)
        while (out.size < items.size) {
            for (queue in queues) {
                if (queue.isNotEmpty()) out += queue.removeFirst()
            }
        }
        return out
    }

    private fun classificationAccuracy(
        model: PacketModel,
        test: List<Pair<String, Frame>>,
        random: Random,
    ): Double {
        var right = 0
        var total = 0
        for ((address, frame) in test) {
            val kind = ErrorClass.entries[random.nextInt(ErrorClass.entries.size)]
            val other = test.firstOrNull { it.first != address }?.second?.bytes
            val damaged = SyntheticDamage.apply(frame, kind, other, random) ?: continue
            total++
            if (model.analyse(damaged).errorClass == kind) right++
        }
        return if (total == 0) 0.0 else right.toDouble() / total
    }

    private fun packet(crcOk: Boolean, recovery: RecoveryInfo?) = BlePacket(
        id = 1,
        timestampMs = 1_000,
        deviceTimeUs = 1_000_000,
        channel = 37,
        rssi = -70,
        crcOk = crcOk,
        address = "AA:BB:CC:DD:EE:FF",
        addressType = "public",
        pduType = "ADV_IND",
        rawHex = "0219AABBCCDDEEFF02011A0AFF0102030405",
        adOffset = 8,
        source = SourceKind.USB,
        recovery = recovery,
    )
}
