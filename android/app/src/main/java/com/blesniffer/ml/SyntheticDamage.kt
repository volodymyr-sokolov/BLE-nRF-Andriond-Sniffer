package com.blesniffer.ml

import kotlin.random.Random

/**
 * Damage of a known kind, applied to a packet that arrived intact.
 *
 * This is where the training labels come from, and it is the only place they
 * can come from. A real corrupt packet has no ground truth - that is the whole
 * problem - so a supervised classifier over real damage would need someone to
 * label it, and nobody can: looking at a corrupt frame and declaring "this one
 * is a collision" is the very judgement the model is being asked to make.
 *
 * Manufacturing the damage inverts that. The label is known because we caused
 * it, the payloads are real traffic from the same environment, and the features
 * the classifier sees are computed by the same code path that will see real
 * packets. What it cannot capture is any error mode not modelled here; that is
 * a real limitation and it is written down rather than hidden behind an
 * accuracy number.
 */
object SyntheticDamage {

    /**
     * Applies [kind] to [clean], returning null when this frame cannot carry
     * that kind of damage - a splice needs a second device's frame, and a
     * header class needs a header.
     *
     * @param other a frame from a *different* advertiser, for [ErrorClass.SPLICE]
     */
    fun apply(
        frame: Frame,
        kind: ErrorClass,
        other: ByteArray?,
        random: Random,
    ): Frame? {
        val clean = frame.bytes
        if (clean.size < 8) return null

        return when (kind) {
            ErrorClass.SCATTERED -> frame.copy(bytes = scattered(clean, random))
            ErrorClass.BURST -> frame.copy(bytes = burst(clean, random))
            ErrorClass.SPLICE -> other
                ?.takeIf { it.size >= 8 }
                ?.let { frame.copy(bytes = splice(clean, it, random)) }
            ErrorClass.HEADER -> if (!frame.hasHeader) null else {
                frame.copy(bytes = header(clean, random))
            }
            ErrorClass.NOISE -> frame.copy(bytes = noise(clean.size, random))
        }
    }

    /**
     * A handful of isolated bit flips, spread out.
     *
     * Spread deliberately: independent flips at a realistic error rate land far
     * apart on a 30 byte frame, and letting two of them fall in neighbouring
     * bytes by chance would hand the classifier examples of scatter that look
     * exactly like short bursts.
     */
    private fun scattered(clean: ByteArray, random: Random): ByteArray {
        val out = clean.copyOf()
        val flips = random.nextInt(1, 5)
        val used = mutableSetOf<Int>()
        var attempts = 0
        while (used.size < flips && attempts < 40) {
            attempts++
            val at = random.nextInt(out.size)
            if (used.any { kotlin.math.abs(it - at) < 2 }) continue
            used += at
            out[at] = (out[at].toInt() xor (1 shl random.nextInt(8))).toByte()
        }
        return out
    }

    /** One contiguous run of corrupted bits, as fading and interference produce. */
    private fun burst(clean: ByteArray, random: Random): ByteArray {
        val out = clean.copyOf()
        val totalBits = out.size * 8
        val length = random.nextInt(12, minOf(totalBits - 8, 96))
        val start = random.nextInt(totalBits - length)
        for (bit in start until start + length) {
            out[bit / 8] = (out[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
        }
        return out
    }

    /**
     * The head of one transmission and the tail of another, at full strength.
     *
     * No bits are flipped here at all, which is exactly the point: a collision
     * produces a frame of entirely valid bytes that simply came from two
     * senders. Anything looking for corrupted bits will call this clean.
     */
    private fun splice(clean: ByteArray, other: ByteArray, random: Random): ByteArray {
        val out = clean.copyOf()
        val margin = maxOf(4, out.size / 5)
        val cut = random.nextInt(margin, out.size - margin + 1)
        for (i in cut until out.size) {
            out[i] = other[i % other.size]
        }
        return out
    }

    /**
     * Damage in the first two bytes, where the PDU type and length live.
     *
     * Singled out from ordinary scatter because the consequence is different in
     * kind: every other error corrupts data, this one moves the boundary of the
     * frame, so the CRC is computed over the wrong bytes and nothing downstream
     * is where it should be.
     */
    private fun header(clean: ByteArray, random: Random): ByteArray {
        val out = clean.copyOf()
        val at = random.nextInt(2)
        out[at] = (out[at].toInt() xor (1 shl random.nextInt(6))).toByte()
        return out
    }

    /** Uniform bytes: what a false access-address lock delivers. */
    private fun noise(size: Int, random: Random): ByteArray =
        ByteArray(size) { random.nextInt(256).toByte() }
}
