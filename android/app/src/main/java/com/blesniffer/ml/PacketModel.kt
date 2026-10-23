package com.blesniffer.ml

import com.blesniffer.model.BlePacket
import com.blesniffer.model.SourceKind
import com.blesniffer.model.hexToBytes
import com.blesniffer.recover.BytePrior
import kotlin.random.Random

/**
 * What the model concluded about one corrupt packet.
 *
 * Both halves are reported whatever happens, because they fail independently: a
 * frame can be classified confidently and belong to no device the capture has
 * ever seen, and a frame can be attributed to a sender with no idea what went
 * wrong with it. Collapsing them into one verdict would lose the case the
 * requirement singles out - the packet that cannot be repaired but whose sender
 * is still known.
 */
data class PacketDiagnosis(
    val errorClass: ErrorClass,
    val confidence: Float,
    val probabilities: Map<ErrorClass, Float>,
    /** Likeliest senders, best first; empty when nothing has been learned yet. */
    val devices: List<DeviceMatch>,
    /** True when the model has seen enough to be worth listening to. */
    val trained: Boolean,
) {
    val sender: DeviceMatch? get() = devices.firstOrNull()

    /**
     * Whether repair is worth attempting, and by which route.
     *
     * Advisory, never authority: the pipeline still has to verify whatever it
     * produces. What this saves is the search - enumerating six million
     * three-bit corrections for a frame that was two packets all along.
     */
    val worthRepairing: Boolean
        get() = errorClass == ErrorClass.SCATTERED ||
            errorClass == ErrorClass.BURST ||
            errorClass == ErrorClass.HEADER
}

/**
 * The learned half of the recovery system: what devices look like, what normal
 * bytes look like, and what kind of damage a corrupt frame is carrying.
 *
 * Three models sharing one training signal, because they need the same one and
 * separating them would mean walking the traffic three times:
 *
 * 1. [DeviceIndex] - per-advertiser byte statistics. Identifies the sender of a
 *    frame from its bytes, including one whose address field is itself damaged.
 * 2. [BytePrior] - what advertising traffic looks like in general, across every
 *    device. Ranks repair candidates the CRC has already admitted.
 * 3. [OnlineClassifier] - what kind of error a corrupt frame carries.
 *
 * **Training only ever happens on packets received intact.** The guard is in
 * [observe] and it is the single most important line in this file. A repaired
 * packet is a hypothesis the pipeline emitted, not an observation; feeding it
 * back makes the model's own output its evidence, and the failure mode is not
 * noisy but self-confirming - a wrong repair teaches the device profile that
 * the wrong bytes are what that device sends, which makes the next identical
 * wrong repair look *better* supported than the one before it.
 *
 * Nothing here is wired into the live capture path yet; see ML_MODEL.md §7 for
 * what that costs and why it is a separate decision.
 */
class PacketModel(
    private val random: Random = Random(0x5EED),
) {

    private val devices = DeviceIndex()
    private val prior = BytePrior()
    private val classifier = OnlineClassifier(ErrorClass.entries.size, Features.COUNT)

    /**
     * A small ring of recent clean frames from each device, so a splice example
     * can be built from two real senders. Bounded because this is the only
     * thing here that holds packet bytes at all.
     */
    private val recent = ArrayDeque<Pair<String, Frame>>()

    private var cleanObserved = 0L
    private var refusedForTraining = 0L

    val observedCount: Long get() = cleanObserved
    /** Packets refused as training data because they were corrupt or repaired. */
    val refusedCount: Long get() = refusedForTraining
    val deviceCount: Int get() = devices.size
    val classifierUpdates: Long get() = classifier.updates

    val trained: Boolean get() = classifier.trained && devices.candidates().isNotEmpty()

    // --- learning -------------------------------------------------------------

    /**
     * Learns from a packet, if it is allowed to.
     *
     * Returns false for anything that is not an intact reception. Both
     * conditions are checked even though either would do: `crcOk` is the fact,
     * `recovery == null` is the rule, and a future change that starts marking
     * repaired packets as CRC-OK would silently defeat the first one alone.
     */
    fun observe(packet: BlePacket): Boolean {
        if (!packet.crcOk || packet.recovery != null) {
            refusedForTraining++
            return false
        }
        val bytes = packet.rawHex.hexToBytes()
        if (bytes.isEmpty() || packet.address.isEmpty()) return false

        return observe(
            address = packet.address,
            frame = Frame(
                bytes = bytes,
                adOffset = packet.adOffset,
                hasHeader = packet.source == SourceKind.USB,
                hasCrc = packet.hasCrcField,
            ),
        )
    }

    /**
     * The same, for a frame that did not come through [BlePacket] - the
     * evaluation harness and the bootstrap path.
     */
    fun observe(address: String, frame: Frame): Boolean {
        observeWithoutTraining(address, frame)

        // Incremental supervision. Each intact packet is also one labelled
        // training example for the classifier, manufactured from itself: the
        // model keeps adapting to the traffic actually in the room rather than
        // to whatever was in the room when it was first fitted.
        trainOne(address, frame)

        // Replay. Measured, a purely one-pass classifier reached 55% where the
        // same data bootstrapped reached 78%, for two compounding reasons: the
        // first few hundred packets are scored against device profiles that
        // barely exist yet, so their features describe a world that will never
        // be seen again, and a single pass over a hard five-way problem is not
        // enough gradient steps whatever the features say. Re-drawing a couple
        // of earlier frames per packet fixes both - the old frames are re-scored
        // against the profiles as they are *now* - and costs a few kilobytes.
        repeat(REPLAY_STEPS) {
            val (replayAddress, replayFrame) = recent.randomOrNull(random) ?: return@repeat
            trainOne(replayAddress, replayFrame)
        }
        return true
    }

    private fun <T> Collection<T>.randomOrNull(random: Random): T? =
        if (isEmpty()) null else elementAt(random.nextInt(size))

    /**
     * Rebuilds the counted models from a frame without touching the classifier.
     *
     * This is the restore path. Device profiles and the prior are exact
     * functions of the traffic, so replaying the stored capture reconstructs
     * them precisely; the classifier is not, because its training examples were
     * manufactured and discarded, and it comes back from [restore] instead.
     */
    fun observeWithoutTraining(address: String, frame: Frame) {
        val body = frame.body
        devices.observe(address, body)
        prior.observe(body)
        cleanObserved++

        recent.addLast(address to frame)
        while (recent.size > RECENT_FRAMES) recent.removeFirst()
    }

    /**
     * Initial fit over packets already captured.
     *
     * Two passes with different jobs. The first only builds the device profiles
     * and the prior, because the classifier's features are computed *against*
     * those profiles - fitting it while they are still empty means fitting it on
     * inputs that will never be seen again. The second manufactures labelled
     * damage and does the gradient steps.
     */
    fun bootstrap(corpus: List<Pair<String, Frame>>, rounds: Int = 3) {
        for ((address, frame) in corpus) {
            observeWithoutTraining(address, frame)
        }
        repeat(rounds) {
            for ((address, frame) in corpus.shuffled(random)) {
                trainOne(address, frame)
            }
        }
    }

    /**
     * One labelled example: pick a damage class, apply it, take a gradient step.
     *
     * The class is drawn uniformly rather than from anything like its real
     * frequency. Real corrupt traffic is overwhelmingly scatter and burst, and a
     * classifier trained on that prior learns to answer "burst" and be right
     * most of the time - which is worthless precisely for the rare classes
     * (splice, header damage) that change what the pipeline should do.
     */
    private fun trainOne(address: String, frame: Frame) {
        if (frame.bytes.size < 8) return
        // Nothing to learn from a frame whose own device is still a stranger:
        // every feature is measured against that device's profile, so an
        // example generated now describes a model state that will not exist
        // again a hundred packets later.
        if (devices.profile(address)?.trained != true) return

        val kind = ErrorClass.entries[random.nextInt(ErrorClass.entries.size)]
        val other = if (kind == ErrorClass.SPLICE) otherDeviceFrame(address) else null
        val damaged = SyntheticDamage.apply(frame, kind, other, random) ?: return

        classifier.partialFit(Features.extract(damaged, devices, prior), kind.ordinal)
    }

    /** A recent frame from some *other* advertiser, for building a collision. */
    private fun otherDeviceFrame(address: String): ByteArray? {
        val options = recent.filter { it.first != address }
        if (options.isEmpty()) return null
        return options[random.nextInt(options.size)].second.body
    }

    // --- inference ------------------------------------------------------------

    fun analyse(packet: BlePacket): PacketDiagnosis = analyse(
        Frame(
            bytes = packet.rawHex.hexToBytes(),
            adOffset = packet.adOffset,
            hasHeader = packet.source == SourceKind.USB,
            hasCrc = packet.hasCrcField,
        ),
    )

    /**
     * Classifies the damage in [frame] and names its likeliest sender.
     *
     * Identification runs on the body, not on the parsed address: the frames
     * that most need a sender are exactly the ones whose address bytes were hit,
     * and those parse to an advertiser that does not exist.
     */
    fun analyse(frame: Frame): PacketDiagnosis {
        val body = frame.body
        val matches = devices.identify(body)

        if (!classifier.trained || body.isEmpty()) {
            return PacketDiagnosis(
                errorClass = ErrorClass.NOISE,
                confidence = 0f,
                probabilities = emptyMap(),
                devices = matches,
                trained = false,
            )
        }

        val p = classifier.predict(Features.extract(frame, devices, prior))
        var best = 0
        for (c in p.indices) if (p[c] > p[best]) best = c

        return PacketDiagnosis(
            errorClass = ErrorClass.entries[best],
            confidence = p[best].toFloat(),
            probabilities = ErrorClass.entries.associateWith { p[it.ordinal].toFloat() },
            devices = matches,
            trained = true,
        )
    }

    /** Ranked senders for a frame, without classifying the damage. */
    fun identify(frame: Frame, top: Int = 3): List<DeviceMatch> =
        devices.identify(frame.body, top = top)

    // --- persistence ----------------------------------------------------------

    /**
     * The classifier's weights, for carrying across a restart.
     *
     * Only the classifier. The device profiles and the prior are rebuilt by
     * replaying the stored capture, which the app already reloads on startup -
     * and rebuilding them is exact, where a serialised histogram would be one
     * more format to keep in step with the schema. The classifier is the part
     * that cannot be rebuilt cheaply, because its training examples were
     * manufactured and thrown away.
     */
    fun snapshot(): String = classifier.snapshot()

    fun restore(text: String): Boolean = classifier.restore(text)

    fun reset() {
        devices.reset()
        prior.reset()
        classifier.reset()
        recent.clear()
        cleanObserved = 0
        refusedForTraining = 0
    }

    private companion object {
        /**
         * Frames kept for replay and for building collision examples.
         *
         * A few kilobytes. Small enough not to matter, large enough that
         * consecutive draws are not all the same beacon repeating itself -
         * which a 64-frame window on a busy advertiser very nearly is.
         */
        const val RECENT_FRAMES = 256

        /** Earlier frames re-drawn per new packet. */
        const val REPLAY_STEPS = 2
    }
}
