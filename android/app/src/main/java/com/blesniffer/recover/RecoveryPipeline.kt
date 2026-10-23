package com.blesniffer.recover

import com.blesniffer.model.BlePacket
import com.blesniffer.model.RecoveryInfo
import com.blesniffer.model.RecoveryMethod
import com.blesniffer.model.SourceKind
import com.blesniffer.model.hexToBytes
import com.blesniffer.model.toHex

/**
 * Repairs packets that arrived with a CRC failure.
 *
 * This is the pipeline of section 6 of the specification, run in order, each
 * layer seeing only what the previous one could not fix:
 *
 *  - **Layer 2, majority voting.** A clean reception of the same transmission -
 *    from the other receiver, or from a repeat by the same advertiser - is used
 *    directly. Highest yield for the least machinery, which is why it runs
 *    first here.
 *  - **Layer 3, device template and structural validation.** Byte positions
 *    that never vary for a given advertiser are restored from that history, and
 *    every candidate must have an advertising-data chain that tiles the payload
 *    exactly before it is accepted.
 *  - **Layer 5, flagged unrecoverable.** Anything left over is kept and marked,
 *    contributing only to error statistics.
 *
 * Layers 1 and 4 both need the received CRC bytes, which only the firmware's raw
 * capture mode delivers (requirements F2/F4) - in its normal mode the radio
 * verifies the CRC and strips it. They are implemented and run whenever
 * [BlePacket.hasCrcField] is set, and are skipped entirely when it is not,
 * rather than being allowed to operate without the check they depend on.
 *
 * Layer 4 is a learned prior over byte sequences, not the transformer the
 * specification names; see [BytePrior] for what it is and what that costs.
 *
 * The governing rule throughout is the specification's: a false repair is worse
 * than a refusal. Every candidate must survive a structural check, and anything
 * uncertain is left flagged rather than silently "fixed".
 */
class RecoveryPipeline(
    /** How long a clean reference reception stays usable for repairs. */
    private val historyMs: Long = 30_000,
    /** Most byte differences a reference may have and still be the same frame. */
    private val maxByteDistance: Int = 4,
) {

    private companion object {
        /**
         * Times the winning reference must have been received before it is
         * trusted as the true frame.
         */
        const val MIN_CORROBORATING_REFERENCES = 2

        /** Cap on layer 4's candidate enumeration, to bound worst-case cost. */
        const val MAX_ML_CANDIDATES = 16

        /**
         * How far ahead the prior's favourite must be, in nats, before it is
         * allowed to choose between CRC-valid candidates.
         *
         * Tuned by measurement rather than argued. At 8 the prior recovered
         * three more packets at BER 1e-2 and produced one more false repair,
         * which is the wrong side of a trade the specification settles
         * explicitly - a false repair is worse than a refusal. At 25 the same
         * three packets come back and the false count does not move at all
         * (8 either way), so the margin is set where the gain is free.
         */
        const val DECISIVE_MARGIN = 25.0

        /** Bytes that must match exactly before a divergence reads as lost sync. */
        const val MIN_SYNC_PREFIX = 6

        /** Bytes that must follow it, for "the tail agrees with nothing" to mean anything. */
        const val MIN_SYNC_TAIL = 8
    }

    /**
     * A clean reception, kept as reference material for repairs.
     *
     * [source] and [adOffset] travel with the bytes because the two receivers do
     * not frame a packet the same way - the board delivers a whole link-layer
     * PDU, the host only the advertising data - and a reference is useless
     * unless the code using it knows which it is holding.
     */
    private class Reference(
        val timestampMs: Long,
        val bytes: ByteArray,
        val source: SourceKind,
        val adOffset: Int,
    )

    /** Clean history per advertiser, newest last. */
    private val history = HashMap<String, ArrayDeque<Reference>>()

    /** Per-advertiser byte profile: how often each position held each value. */
    private val templates = HashMap<String, ByteTemplate>()

    /** Layer 4's prior, learned across all advertisers seen this session. */
    private val prior = BytePrior()

    /**
     * Offers a packet to the pipeline, returning it with recovery information
     * attached when it needed and received attention.
     *
     * Clean packets are returned untouched, but feed the reference history and
     * the device template that later repairs depend on.
     */
    fun process(packet: BlePacket): BlePacket {
        val key = packet.address.ifEmpty { return packet }
        val bytes = packet.rawHex.hexToBytes()
        if (bytes.isEmpty()) return packet

        if (packet.crcOk) {
            remember(key, packet, bytes)
            return packet
        }

        val recovery = repair(key, packet, bytes)
        return packet.copy(recovery = recovery)
    }

    private fun remember(key: String, packet: BlePacket, bytes: ByteArray) {
        val list = history.getOrPut(key) { ArrayDeque() }
        list.addLast(Reference(packet.timestampMs, bytes, packet.source, packet.adOffset))
        while (list.size > 32) list.removeFirst()
        while (list.isNotEmpty() && packet.timestampMs - list.first().timestampMs > historyMs) {
            list.removeFirst()
        }
        // The template and the prior are only meaningful over one framing. A
        // host reference is advertising data with the PDU header and address
        // already stripped, so mixing it in would teach the template that byte 0
        // is sometimes a PDU type and sometimes a flags length.
        if (packet.source == SourceKind.USB) {
            templates.getOrPut(key) { ByteTemplate() }.observe(bytes)
            prior.observe(bytes)
        }
    }

    private fun repair(key: String, packet: BlePacket, damaged: ByteArray): RecoveryInfo {
        var candidatesEvaluated = 0

        // --- Layer 1: CRC-24 syndrome decoding --------------------------------
        //
        // Only reachable when the firmware sends the CRC bytes (raw capture
        // mode, requirements F2/F4). When it does, this runs first and outranks
        // everything below: a corrected frame whose CRC checks out is a 24-bit
        // agreement, not an inference about what the device usually says.
        if (packet.hasCrcField) {
            val correction = Crc24.correct(damaged, maxBits = 2)
            candidatesEvaluated++
            if (correction != null && correction.bitsFlipped > 0) {
                val body = correction.corrected.copyOfRange(0, correction.corrected.size - 3)
                if (structurallyValid(body, packet.adOffset)) {
                    return RecoveryInfo(
                        method = RecoveryMethod.CRC_SYNDROME,
                        correctedHex = correction.corrected.toHex(),
                        bitsCorrected = correction.bitsFlipped,
                        // One bit is exact. Two bits carry the ~0.33% false
                        // acceptance the specification quotes for the pair
                        // search, so it is not claimed as certainty.
                        confidence = if (correction.bitsFlipped == 1) 0.99f else 0.90f,
                        candidatesEvaluated = candidatesEvaluated,
                        originalCrcOk = false,
                    )
                }
            }
        }

        // --- Layer 2: a clean reception of the same thing ---------------------
        val references = history[key].orEmpty()
            .filter { packet.timestampMs - it.timestampMs <= historyMs }

        val template = templates[key]

        // --- Layer 2a: the *other* receiver's clean copy -----------------------
        //
        // The strongest evidence available short of the CRC, and until now it was
        // being thrown away twice over. The deduplicator can never pair a corrupt
        // board copy with a clean host copy - their payloads differ, which is the
        // entire point, so they hash to different keys and never meet. And even
        // reaching the pipeline together they could not be compared, because the
        // two receivers frame a packet differently: the host hands over the
        // advertising data alone, the board a whole PDU with a header and address
        // in front of it, so `size == size` was never true and the host's copy
        // was silently never a candidate.
        //
        // Aligning on the advertising-data region is what makes the comparison
        // possible at all. Only that region is repaired - it is the only part the
        // host ever saw - and the header and address are left exactly as received.
        val crossSource = references.asSequence()
            .filter { it.source != packet.source }
            .mapNotNull { reference -> spliceAdvertisingData(damaged, packet, reference) }
            .firstOrNull()
        candidatesEvaluated += if (crossSource == null) 0 else 1

        if (crossSource != null) {
            // With the CRC present this is not an inference at all: the twin's
            // bytes dropped into the damaged frame make a 24-bit checksum agree.
            val verified = packet.hasCrcField && Crc24.verify(crossSource)
            if (verified || structurallyValid(bodyOf(crossSource, packet), packet.adOffset)) {
                return RecoveryInfo(
                    method = RecoveryMethod.CROSS_SOURCE,
                    correctedHex = crossSource.toHex(),
                    bitsCorrected = bitDistance(crossSource, damaged),
                    confidence = if (verified) {
                        0.99f
                    } else {
                        confidenceFor(bitDistance(crossSource, damaged), references.size)
                    },
                    candidatesEvaluated = candidatesEvaluated,
                    originalCrcOk = false,
                )
            }
        }

        val scored = references
            .filter { it.bytes.size == damaged.size && it.source == packet.source }
            .map { it to byteDistance(it.bytes, damaged) }
            .filter { it.second in 1..maxByteDistance }
            .sortedBy { it.second }
        candidatesEvaluated += scored.size

        val closest = scored.firstOrNull()?.second
        val tied = scored.filter { it.second == closest }
            .map { it.first.bytes }
            .distinctBy { it.toHex() }

        // How many times the winning payload was actually received. A frame seen
        // once could itself have been a one-off; a frame seen repeatedly is the
        // device's steady state and is worth trusting.
        val timesSeen = tied.singleOrNull()
            ?.let { winner -> references.count { it.bytes.contentEquals(winner) } }
            ?: 0

        // Two different references equally close means the evidence does not
        // single out an answer. Picking one would be a coin toss recorded as a
        // repair, so nothing is picked.
        val best = tied.singleOrNull()?.takeIf { timesSeen >= MIN_CORROBORATING_REFERENCES }?.takeIf {
            // Section 6.2 is explicit that voting must not touch fields that
            // change from packet to packet. Without this gate a counter byte
            // that legitimately advanced looks exactly like a corrupted one,
            // and the "repair" silently rewrites it to the previous value -
            // which measurement showed happening on roughly 1% of packets.
            differencesAreStable(template, it, damaged)
        }

        if (best != null && structurallyValid(bodyOf(best, packet), packet.adOffset)) {
            val bits = bitDistance(best, damaged)
            return RecoveryInfo(
                // A repeat from the same receiver. This used to claim
                // CROSS_SOURCE whenever the packet happened to be corroborated,
                // which described the packet rather than the evidence: the
                // reference used here always came from this same radio, and a
                // genuine cross-source repair now returns above with the label
                // it has actually earned.
                method = RecoveryMethod.CROSS_TIME,
                correctedHex = best.toHex(),
                bitsCorrected = bits,
                confidence = confidenceFor(bits, references.size),
                candidatesEvaluated = candidatesEvaluated,
                originalCrcOk = false,
            )
        }

        // --- Layer 3 is a gate, not a repair ----------------------------------
        //
        // Restoring only the positions a device never varies cannot reconstruct
        // a packet whose errors also landed on a byte that does change: the
        // result differs from the truth while looking perfectly well-formed.
        // Measurement bore this out - as a standalone repair it produced wrong
        // answers on 14% of heavily corrupted packets, worse than refusing. It
        // earns its keep above, deciding which differences Layer 2 may touch.

        // --- Layer 4: learned prior, verified by CRC --------------------------
        //
        // Only with a CRC to check against. The prior proposes an ordering over
        // candidates; the CRC decides which are admissible at all. Without the
        // CRC field there is nothing to verify against and this is skipped
        // entirely rather than allowed to guess.
        if (packet.hasCrcField && prior.trained && !lostSync(damaged, references)) {
            val admissible = crcAdmissibleCandidates(damaged, packet.adOffset)
            candidatesEvaluated += admissible.size

            // A unique survivor means the CRC really did determine the answer,
            // and is accepted on that basis alone.
            //
            // With more than one, the CRC has stopped deciding - enumerating
            // three-bit corrections produces candidates that all satisfy it by
            // construction, because the search generates them rather than
            // filtering them. Letting the prior simply take the best of those
            // was measured at 2% wrong, twenty times the specification's limit,
            // and refusing outright meant the prior never decided anything at
            // all and was decorative.
            //
            // The middle course is to let it decide only when it is not close:
            // the leader must beat the runner-up by [DECISIVE_MARGIN] nats. A
            // near-tie is exactly the case where a model trained on a few
            // thousand local packets has no business adjudicating.
            val chosen = when {
                admissible.size == 1 -> admissible.single()
                admissible.size > 1 && prior.trained -> decisive(admissible)
                else -> null
            }
            if (chosen != null) {
                return RecoveryInfo(
                    method = RecoveryMethod.ML_PRIOR,
                    correctedHex = chosen.toHex(),
                    bitsCorrected = bitDistance(chosen, damaged),
                    confidence = 0.80f,
                    candidatesEvaluated = candidatesEvaluated,
                    originalCrcOk = false,
                )
            }
        }

        // --- Layer 5: kept, flagged, counted ----------------------------------
        return RecoveryInfo(
            method = RecoveryMethod.UNRECOVERABLE,
            correctedHex = "",
            bitsCorrected = 0,
            confidence = 0f,
            candidatesEvaluated = candidatesEvaluated,
            originalCrcOk = false,
        )
    }

    /**
     * True when the frame stopped being this device's packet partway through.
     *
     * The receiver locks onto a transmission, demodulates it correctly for a
     * while, then loses the signal and reports noise for the remainder. The
     * signature is unmistakable once looked for: a byte-exact prefix, then a
     * tail agreeing with the reference no more often than two random byte
     * streams would - about one time in 256.
     *
     * These frames must not reach layer 4, and the reference-beacon bench is
     * what proved it. A frame that lost sync after 23 bytes was "repaired" by
     * flipping three bits, one of which changed an advertising-data length byte
     * from 0x0B to 0x1B - after which the *noise* tiled the region exactly, the
     * structural filter was satisfied, and the CRC agreed because the candidate
     * had been generated to make it agree. The result was a confident, valid,
     * completely wrong packet, and it was the only false repair in the run.
     *
     * There is nothing to recover here in any case: past the divergence point
     * the transmitted bytes are not present in the frame at all, so no amount of
     * correction can reach them. Refusing is not a loss of recall.
     */
    private fun lostSync(damaged: ByteArray, references: List<Reference>): Boolean {
        val sameShape = references.filter { it.bytes.size == damaged.size }
        if (sameShape.isEmpty()) return false

        var bestPrefix = -1
        var best: Reference? = null
        for (reference in sameShape) {
            var i = 0
            while (i < damaged.size && reference.bytes[i] == damaged[i]) i++
            if (i > bestPrefix) {
                bestPrefix = i
                best = reference
            }
        }

        val reference = best ?: return false
        // Too short a prefix means the receiver never really had it; too long a
        // one leaves no tail to judge. Neither is the case this describes.
        if (bestPrefix < MIN_SYNC_PREFIX) return false
        val tailLength = damaged.size - bestPrefix
        if (tailLength < MIN_SYNC_TAIL) return false

        val agreeing = (bestPrefix until damaged.size)
            .count { reference.bytes[it] == damaged[it] }
        return agreeing <= tailLength / 16
    }

    /**
     * The prior's favourite among CRC-valid candidates, but only when it wins
     * clearly. Returns null on anything close, which the caller must treat as a
     * refusal rather than as a licence to guess.
     */
    private fun decisive(candidates: List<ByteArray>): ByteArray? {
        val ranked = candidates
            .map { it to prior.logProbability(it) }
            .sortedByDescending { it.second }

        val (best, bestScore) = ranked[0]
        val (_, runnerUp) = ranked[1]
        if (!bestScore.isFinite() || !runnerUp.isFinite()) return null
        return if (bestScore - runnerUp >= DECISIVE_MARGIN) best else null
    }

    /**
     * Puts a reference's advertising data into the damaged frame, when the two
     * came from different receivers and therefore have different framing.
     *
     * Returns null unless the two advertising regions are the same length and
     * differ by a plausible amount of damage. Equal length is the alignment
     * check doing real work: a host reception of a *different* advertisement
     * from the same device is usually a different size, and one that is not
     * still has to survive the structural and CRC checks upstream.
     *
     * Only the advertising region is written. The PDU header and address are
     * left as received even when they are the damaged part, because the host
     * never saw those bytes and has nothing to say about them - inventing them
     * from a parsed address string would be manufacturing evidence.
     */
    private fun spliceAdvertisingData(
        damaged: ByteArray,
        packet: BlePacket,
        reference: Reference,
    ): ByteArray? {
        if (packet.adOffset < 0 || reference.adOffset < 0) return null

        val bodyEnd = if (packet.hasCrcField && damaged.size > 3) damaged.size - 3 else damaged.size
        if (packet.adOffset >= bodyEnd) return null

        val length = bodyEnd - packet.adOffset
        if (length <= 0 || reference.adOffset + length != reference.bytes.size) return null

        val candidate = damaged.copyOf()
        var differences = 0
        for (i in 0 until length) {
            val value = reference.bytes[reference.adOffset + i]
            if (candidate[packet.adOffset + i] != value) differences++
            candidate[packet.adOffset + i] = value
        }

        // Nothing to repair, or too different to be the same transmission.
        if (differences == 0 || differences > maxByteDistance) return null
        return candidate
    }

    private fun confidenceFor(bitsCorrected: Int, referenceCount: Int): Float {
        // More corroborating receptions and fewer changed bits both mean a
        // safer repair. Deliberately never reaches 1.0: nothing here is proof.
        val fromRepeats = (referenceCount.coerceAtMost(8) / 8f) * 0.35f
        val fromDistance = (1f - bitsCorrected.coerceAtMost(16) / 16f) * 0.6f
        return (0.05f + fromRepeats + fromDistance).coerceIn(0f, 0.95f)
    }

    /**
     * The structural filter of section 6.3: an advertising-data chain must tile
     * its region exactly. This is what keeps the false-repair rate down, so a
     * candidate that fails it is discarded however plausible it otherwise looks.
     */
    fun structurallyValid(candidate: ByteArray, adOffset: Int): Boolean {
        if (adOffset < 0) return true          // nothing to validate against
        if (adOffset >= candidate.size) return false

        var i = adOffset
        while (i < candidate.size) {
            val length = candidate[i].toInt() and 0xFF
            if (length == 0) {
                // Zero length is end-of-data padding; the rest must be zeros.
                return (i until candidate.size).all { candidate[it].toInt() == 0 }
            }
            if (i + length >= candidate.size + 1) return false
            i += length + 1
        }
        return i == candidate.size
    }

    /**
     * True when every byte the candidate would change is one the device has
     * never been seen to vary.
     *
     * This is the guard that separates "this byte was corrupted" from "this byte
     * legitimately advanced". Without a trained template there is no basis for
     * that distinction, so the answer is no - refusing costs recall, accepting
     * costs correctness, and the specification is unambiguous about which way
     * that trade runs.
     */
    private fun differencesAreStable(
        template: ByteTemplate?,
        candidate: ByteArray,
        damaged: ByteArray,
    ): Boolean {
        if (template == null || !template.trained) return false
        for (i in candidate.indices) {
            if (candidate[i] != damaged[i] && !template.isStable(i)) return false
        }
        return true
    }

    /**
     * Every three-bit correction whose CRC checks out and whose advertising
     * chain tiles. Ranking these is layer 4's only job.
     *
     * Three bits is where the deterministic layers stop: the specification puts
     * the search at ~6.3M combinations and keeps it behind a flag for that
     * reason. It is bounded here by only enumerating on top of the two-bit
     * syndrome structure, and capped so a pathological packet cannot stall the
     * capture.
     */
    private fun crcAdmissibleCandidates(damaged: ByteArray, adOffset: Int): List<ByteArray> {
        if (damaged.size < 4) return emptyList()
        val bodyLength = damaged.size - 3
        val table = Crc24.syndromeTable(bodyLength)
        val syndrome = Crc24.compute(damaged, 0, bodyLength) xor
            Crc24.fromBytes(damaged, bodyLength)

        val out = ArrayList<ByteArray>(8)
        outer@ for ((synA, p) in table) {
            for ((synB, q) in table) {
                if (q <= p) continue
                val residue = syndrome xor synA xor synB
                val r = table[residue] ?: continue
                if (r <= q) continue

                var candidate = damaged.copyOf()
                for (bit in intArrayOf(p, q, r)) {
                    candidate[bit / 8] = (candidate[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
                }
                if (!Crc24.verify(candidate)) continue
                if (!structurallyValid(candidate.copyOfRange(0, bodyLength), adOffset)) continue

                out += candidate
                if (out.size >= MAX_ML_CANDIDATES) break@outer
            }
        }
        return out
    }

    /**
     * The advertising payload without the trailing CRC.
     *
     * The structural filter validates an AD chain, and three CRC bytes on the
     * end are not part of one - checking the whole frame made every candidate
     * fail once raw capture mode started supplying them, which showed up as
     * burst recall collapsing from 71% to 7%.
     */
    private fun bodyOf(frame: ByteArray, packet: BlePacket): ByteArray =
        if (packet.hasCrcField && frame.size > 3) frame.copyOfRange(0, frame.size - 3) else frame

    private fun byteDistance(a: ByteArray, b: ByteArray): Int {
        var n = 0
        for (i in a.indices) if (a[i] != b[i]) n++
        return n
    }

    private fun bitDistance(a: ByteArray, b: ByteArray): Int {
        var n = 0
        for (i in a.indices) {
            n += Integer.bitCount((a[i].toInt() xor b[i].toInt()) and 0xFF)
        }
        return n
    }

    fun reset() {
        history.clear()
        templates.clear()
    }
}

/**
 * Per-advertiser byte profile, the "device template" of section 6.3.
 *
 * Learned from the packets actually observed: positions that never change are
 * treated as structural and can be restored, positions that vary are left alone.
 * Counter and random fields are exactly the ones that vary, so this refuses to
 * touch them by construction rather than by rule.
 */
class ByteTemplate {

    private var samples = 0
    private var values: Array<MutableSet<Int>> = emptyArray()

    fun observe(bytes: ByteArray) {
        if (values.size != bytes.size) {
            if (samples > 0 && values.isNotEmpty()) return   // different frame shape
            values = Array(bytes.size) { mutableSetOf<Int>() }
        }
        for (i in bytes.indices) {
            val seen = values[i]
            if (seen.size <= 4) seen.add(bytes[i].toInt() and 0xFF)
        }
        samples++
    }

    /** True once there is enough history for "never varies" to mean anything. */
    val trained: Boolean get() = samples >= MIN_SAMPLES

    /** True when position [index] has only ever carried one value. */
    fun isStable(index: Int): Boolean =
        trained && index in values.indices && values[index].size == 1

    /**
     * Rewrites positions that have only ever held one value. Returns null when
     * the template cannot say anything useful about this packet.
     */
    fun restoreConstantPositions(damaged: ByteArray): ByteArray? {
        if (!trained || values.size != damaged.size) return null

        val out = damaged.copyOf()
        var changed = 0
        for (i in out.indices) {
            val seen = values[i]
            if (seen.size == 1) {
                val only = seen.first().toByte()
                if (out[i] != only) {
                    out[i] = only
                    changed++
                }
            }
        }
        return if (changed == 0) null else out
    }

    private companion object {
        /** Below this, "constant" is indistinguishable from "not seen vary yet". */
        const val MIN_SAMPLES = 8
    }
}
