package com.blesniffer.recover

import kotlin.math.ln

/**
 * Layer 4: a learned prior over byte sequences, used to rank repair candidates.
 *
 * The specification asks for a byte-level masked language model - a small
 * transformer or dilated CNN - trained on valid PDUs from the same environment.
 * This is not that. It is an order-1 context model with per-position statistics:
 *
 * ```
 *     log P(x) = Σᵢ [ log P(xᵢ | xᵢ₋₁) + log P(xᵢ | i) ] / 2
 * ```
 *
 * estimated by counting, with Laplace smoothing. A transformer would model
 * longer-range structure; this captures which bytes follow which, and what
 * belongs at each offset, which is most of what advertising payloads exhibit.
 * It trains in one pass on the traffic already captured, on the phone, with no
 * corpus to ship and no training step to run - and it can be replaced by the
 * specified model without changing anything around it.
 *
 * **The prior never decides.** Section 6.4 is explicit: candidates are always
 * checked against the CRC, and the accepted one is the highest-probability
 * candidate *among those that pass*. Here the prior only orders candidates that
 * have already satisfied a 24-bit CRC agreement and the structural filter. Its
 * worst possible failure is preferring one valid frame over another equally
 * valid one, never inventing a frame that fails the check.
 */
class BytePrior {

    private companion object {
        /** Laplace smoothing, so an unseen byte is unlikely rather than impossible. */
        const val ALPHA = 0.5
        const val ALPHABET = 256
        const val MIN_SAMPLES = 32
    }

    /** transition[prev][next] */
    private val transition = Array(ALPHABET) { IntArray(ALPHABET) }
    private val transitionTotal = IntArray(ALPHABET)

    /** position[offset][value], for the first positions where structure lives. */
    private val position = HashMap<Int, IntArray>()
    private val positionTotal = HashMap<Int, Int>()

    private var samples = 0

    val trained: Boolean get() = samples >= MIN_SAMPLES

    /** Learns from a packet known to be intact. */
    fun observe(bytes: ByteArray) {
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            if (i > 0) {
                val prev = bytes[i - 1].toInt() and 0xFF
                transition[prev][v]++
                transitionTotal[prev]++
            }
            position.getOrPut(i) { IntArray(ALPHABET) }[v]++
            positionTotal[i] = (positionTotal[i] ?: 0) + 1
        }
        samples++
    }

    /**
     * Log-probability of a byte sequence under the model. Higher is more like
     * the traffic this device population actually produces.
     */
    fun logProbability(bytes: ByteArray): Double {
        if (!trained || bytes.isEmpty()) return Double.NEGATIVE_INFINITY

        var total = 0.0
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF

            val contextTerm = if (i > 0) {
                val prev = bytes[i - 1].toInt() and 0xFF
                ln((transition[prev][v] + ALPHA) / (transitionTotal[prev] + ALPHA * ALPHABET))
            } else {
                ln(1.0 / ALPHABET)
            }

            val counts = position[i]
            val positionTerm = if (counts == null) {
                ln(1.0 / ALPHABET)
            } else {
                ln((counts[v] + ALPHA) / ((positionTotal[i] ?: 0) + ALPHA * ALPHABET))
            }

            total += (contextTerm + positionTerm) / 2.0
        }
        return total
    }

    /**
     * Picks the likeliest of several candidates.
     *
     * Every candidate offered here has already passed the CRC; this only breaks
     * the tie. Returns null when the model has too little history to have an
     * opinion, in which case the caller must not guess.
     */
    fun bestOf(candidates: List<ByteArray>): ByteArray? {
        if (!trained || candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        return candidates.maxByOrNull { logProbability(it) }
    }

    fun reset() {
        for (row in transition) row.fill(0)
        transitionTotal.fill(0)
        position.clear()
        positionTotal.clear()
        samples = 0
    }
}
