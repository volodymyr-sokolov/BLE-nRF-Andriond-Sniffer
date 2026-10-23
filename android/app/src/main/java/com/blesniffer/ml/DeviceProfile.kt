package com.blesniffer.ml

import kotlin.math.exp
import kotlin.math.ln

/**
 * What one advertiser's frames look like, learned by counting.
 *
 * Every position keeps a small histogram of the values it has held. That is
 * enough to answer both questions the recovery work needs of it: what byte
 * belongs here (so a damaged frame can be scored against the device), and
 * whether this position means anything at all (a counter or a randomised
 * address holds a different value every time and can never be restored).
 *
 * Counting rather than gradient descent is a deliberate choice, not a shortcut.
 * The statistics are sufficient, the update is exact, one pass is enough, and
 * merging or ageing them is arithmetic - all four are what "incremental" has to
 * mean on a phone that may be handed a hundred thousand packets and then asked
 * for an answer about the next one.
 */
class DeviceProfile(val address: String) {

    private companion object {
        /**
         * Distinct values past which a position is declared varying.
         *
         * A flags byte holds two or three values across a session; a counter
         * holds hundreds. Anything above this is the latter, and pretending to
         * have a distribution over it would let identification score a counter
         * byte as evidence.
         */
        const val MAX_DISTINCT = 12

        /**
         * Smoothing mass. Small on purpose: with 256 symbols an α of 0.5 puts
         * 128 counts of imaginary evidence behind every position, which drowns
         * a real device signature under a hundred observations.
         */
        const val ALPHA = 0.05

        val LOG_UNIFORM = ln(1.0 / 256)

        /** Below this a profile has not seen enough to be evidence of anything. */
        const val MIN_SAMPLES = 8
    }

    private class Position {
        val counts = HashMap<Int, Int>()
        var total = 0
        var varying = false

        fun observe(value: Int) {
            total++
            if (varying) return
            counts[value] = (counts[value] ?: 0) + 1
            if (counts.size > MAX_DISTINCT) {
                varying = true
                counts.clear()
            }
        }

        /** Log-probability of this position holding [value]. */
        fun logP(value: Int): Double {
            if (varying || total == 0) return LOG_UNIFORM
            return ln((counts[value] ?: 0) + ALPHA) - ln(total + ALPHA * 256)
        }

        val stable: Boolean get() = !varying && counts.size == 1

        /** The value seen most often, or null where the position varies. */
        fun modal(): Int? =
            if (varying || counts.isEmpty()) null else counts.maxByOrNull { it.value }!!.key
    }

    private val positions = ArrayList<Position>()
    private val lengths = HashMap<Int, Int>()
    private var samples = 0

    val sampleCount: Int get() = samples
    val trained: Boolean get() = samples >= MIN_SAMPLES

    fun observe(bytes: ByteArray) {
        while (positions.size < bytes.size) positions.add(Position())
        for (i in bytes.indices) {
            positions[i].observe(bytes[i].toInt() and 0xFF)
        }
        lengths[bytes.size] = (lengths[bytes.size] ?: 0) + 1
        samples++
    }

    /** True when this position has only ever carried one value. */
    fun isStable(index: Int): Boolean =
        trained && index in positions.indices && positions[index].stable

    /**
     * The frame this device would be expected to send at [length]: the most
     * common byte at each position, and null where there is no such thing.
     *
     * Not a packet the device ever sent, and not offered as one. It is the
     * reference a damaged frame is diffed against, which is what turns "these
     * bytes are wrong" into a *pattern* of wrongness that can be classified.
     */
    fun modalFrame(length: Int): Array<Int?> =
        Array(length) { i -> if (i in positions.indices) positions[i].modal() else null }

    /** How often a frame of this length has been seen, as a fraction. */
    fun lengthSupport(length: Int): Double =
        if (samples == 0) 0.0 else (lengths[length] ?: 0).toDouble() / samples

    /**
     * Mean log-probability of [bytes] under this profile, discarding the worst
     * [trim] positions.
     *
     * The trimming is what makes this usable on a *damaged* frame, which is the
     * only case that matters: the whole point is to identify a sender whose
     * packet could not be repaired. An untrimmed likelihood is dominated by the
     * handful of corrupted bytes - each contributes about -7 while a correct
     * byte contributes about -0.1 - so the right device loses to a profile that
     * happens to be vague everywhere. Dropping the worst quarter of positions
     * scores the frame on the part of it that survived.
     *
     * Returned as a mean so frames of different lengths are comparable.
     */
    fun score(bytes: ByteArray, from: Int = 0, until: Int = bytes.size, trim: Int = -1): Double {
        val n = until - from
        if (n <= 0 || !trained) return LOG_UNIFORM

        val terms = DoubleArray(n) { positions.getOrNull(from + it)?.logP(bytes[from + it].toInt() and 0xFF) ?: LOG_UNIFORM }
        val drop = if (trim >= 0) trim.coerceAtMost(n - 1) else (n / 4).coerceAtMost(n - 1)
        if (drop == 0) return terms.average()

        terms.sort()                       // ascending: the worst positions first
        var sum = 0.0
        for (i in drop until n) sum += terms[i]
        return sum / (n - drop)
    }
}

/** One candidate sender for a frame, with how strongly it is preferred. */
data class DeviceMatch(
    val address: String,
    /** Mean log-probability per byte; higher is a better fit. */
    val score: Double,
    /** Share of the posterior over all candidate devices, in 0..1. */
    val posterior: Float,
)

/**
 * Every advertiser the model has learned, and the identification built on them.
 *
 * Identification works from the *bytes*, never from the parsed address field.
 * That is the whole requirement: a packet whose address bytes were the ones the
 * interference hit parses to an address no device has, and is exactly the packet
 * that most needs its sender named. What survives is the rest of the frame - the
 * vendor prefix, the service UUIDs, the layout - and that is what is matched.
 */
class DeviceIndex {

    private val profiles = LinkedHashMap<String, DeviceProfile>()

    val size: Int get() = profiles.size

    /** Profiles with enough history to be evidence; the only ones ever matched. */
    fun candidates(): List<DeviceProfile> = profiles.values.filter { it.trained }

    fun profile(address: String): DeviceProfile? = profiles[address]

    fun observe(address: String, bytes: ByteArray) {
        if (address.isEmpty() || bytes.isEmpty()) return
        profiles.getOrPut(address) { DeviceProfile(address) }.observe(bytes)
    }

    /**
     * The likeliest senders of [bytes], best first.
     *
     * The posterior is over the trained profiles only and is deliberately not
     * calibrated against "some device I have never seen": with an open world
     * there is no such quantity to be had from counting alone. Read it as *which
     * of the devices in this capture*, and read [DeviceMatch.score] for whether
     * any of them fits at all.
     */
    fun identify(
        bytes: ByteArray,
        from: Int = 0,
        until: Int = bytes.size,
        top: Int = 3,
    ): List<DeviceMatch> {
        val trained = candidates()
        if (trained.isEmpty() || until <= from) return emptyList()

        val scored = trained.map { it to it.score(bytes, from, until) }
        val n = (until - from).toDouble()

        // Softmax over the total (not mean) log-probability, which is what the
        // means stand in for; the max is subtracted so a long frame cannot
        // overflow the exponential.
        val best = scored.maxOf { it.second }
        val weights = scored.map { exp((it.second - best) * n) }
        val sum = weights.sum().takeIf { it > 0.0 } ?: 1.0

        return scored.mapIndexed { i, (profile, score) ->
            DeviceMatch(profile.address, score, (weights[i] / sum).toFloat())
        }.sortedByDescending { it.score }.take(top)
    }

    fun reset() = profiles.clear()
}
