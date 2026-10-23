package com.blesniffer.ml

import com.blesniffer.recover.BytePrior
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a damaged frame into the numbers a classifier can learn from.
 *
 * Every feature here is computable from the frame alone plus what the model has
 * already learned - none of them need the original bytes, which by definition do
 * not exist for a real corrupt packet. That constraint is what makes the whole
 * scheme work at inference time and is worth stating, because it is easy to
 * write a feature that quietly needs the answer.
 *
 * The damage pattern is estimated by diffing against the best-matching device's
 * modal frame (see [DeviceProfile.modalFrame]). Positions the device is known to
 * vary are excluded from the diff: a counter that advanced is not damage, and
 * counting it as damage is precisely how a device with a sequence number gets
 * classified as noise.
 */
object Features {

    /** Length of the vector [extract] produces. */
    const val COUNT = 14

    /** Names in vector order, for debugging and for the write-up. */
    val NAMES = listOf(
        "bias",
        "damaged_fraction",
        "contiguity",
        "run_density",
        "longest_run",
        "splice_gain",
        "splice_split_device",
        "tiling_residual",
        "length_mismatch",
        "device_fit",
        "prior_fit",
        "stable_violation",
        "bit_density",
        "damage_reaches_end",
    )

    fun extract(frame: Frame, devices: DeviceIndex, prior: BytePrior): DoubleArray {
        val f = DoubleArray(COUNT)
        f[0] = 1.0

        // The body, not the raw frame: the device profiles were built from
        // bodies, and three CRC bytes on the end would read as three damaged
        // positions against every profile there is.
        val bytes = frame.body
        if (bytes.isEmpty()) return f

        val n = bytes.size
        val best = devices.identify(bytes, top = 1).firstOrNull()
        val profile = best?.let { devices.profile(it.address) }

        // --- damage pattern, against what this device usually sends ----------
        val damaged = BooleanArray(n)
        var damagedCount = 0
        var stableViolations = 0
        var damagedBits = 0
        if (profile != null) {
            val modal = profile.modalFrame(n)
            for (i in 0 until n) {
                val expected = modal[i] ?: continue          // varying: unknowable
                val actual = bytes[i].toInt() and 0xFF
                if (actual != expected) {
                    damaged[i] = true
                    damagedCount++
                    damagedBits += Integer.bitCount(actual xor expected)
                    if (profile.isStable(i)) stableViolations++
                }
            }
        }

        val first = damaged.indexOfFirst { it }
        val last = damaged.indexOfLast { it }
        val span = if (first < 0) 0 else last - first + 1

        var runs = 0
        var longestRun = 0
        var current = 0
        for (d in damaged) {
            if (d) {
                if (current == 0) runs++
                current++
                longestRun = max(longestRun, current)
            } else {
                current = 0
            }
        }

        f[1] = damagedCount.toDouble() / n
        // Contiguity separates a burst from scatter better than either count or
        // span alone: a burst fills its span, scattered flips leave it mostly
        // untouched. It is the single most informative number here.
        f[2] = if (span == 0) 0.0 else damagedCount.toDouble() / span
        f[3] = if (damagedCount == 0) 0.0 else runs.toDouble() / damagedCount
        f[4] = longestRun.toDouble() / n

        // --- splice: does the frame want to be two devices? ------------------
        val splice = spliceEvidence(bytes, devices)
        f[5] = splice.gain
        f[6] = if (splice.differentDevices) 1.0 else 0.0

        // --- structure --------------------------------------------------------
        f[7] = tilingResidual(bytes, frame.adOffset)
        f[8] = lengthMismatch(frame)

        // --- how well anything fits at all -----------------------------------
        // Squashed into 0..1 so a frame that matches nothing does not hand the
        // classifier an unbounded input. exp() of a mean log-probability is the
        // per-byte likelihood, which is already the natural scale.
        f[9] = exp(best?.score ?: -6.0)
        val priorScore = if (prior.trained) prior.logProbability(bytes) / n else -6.0
        f[10] = exp(priorScore.coerceIn(-20.0, 0.0))
        f[11] = if (damagedCount == 0) 0.0 else stableViolations.toDouble() / damagedCount

        // How hard each damaged byte was hit. This is what finally separates a
        // short burst from a couple of scattered flips, which contiguity cannot:
        // one flip in one byte has a span of one and looks perfectly contiguous.
        // A burst rolls through the bits of the bytes it covers and changes
        // about half of them; scatter changes exactly one per byte it touches;
        // a spliced or noisy region carries independent bytes and sits at half
        // again. One number, and it does more work than the four above it.
        f[12] = if (damagedCount == 0) 0.0 else (damagedBits.toDouble() / damagedCount) / 8.0

        // A splice runs to the end of the frame by construction - the tail is
        // the other transmission - where a burst usually stops somewhere in the
        // middle.
        f[13] = if (last == n - 1) 1.0 else 0.0

        return f
    }

    private class Splice(val gain: Double, val differentDevices: Boolean)

    /**
     * Looks for a cut point where the two halves of the frame fit two different
     * devices better than the whole frame fits any one of them.
     *
     * This is what distinguishes a collision from mere damage, and no amount of
     * bit-pattern analysis gets there: a spliced frame is not corrupt at all in
     * the ordinary sense - both halves are perfectly good bytes - which is why a
     * pipeline without this test will happily "repair" one of them into the
     * other and record it as a success.
     */
    private fun spliceEvidence(bytes: ByteArray, devices: DeviceIndex): Splice {
        val trained = devices.candidates()
        if (trained.size < 2 || bytes.size < 8) return Splice(0.0, false)

        // Scored untrimmed, like the halves below it. The trimmed score used
        // for identification discards the worst quarter of positions, so
        // comparing a trimmed whole against untrimmed halves made every frame
        // look spliced by a constant margin and the feature carried no signal.
        val whole = trained.maxOf { it.score(bytes, 0, bytes.size, trim = 0) }
        var bestGain = 0.0
        var bestDifferent = false

        // Cut points are kept away from the ends: a "split" two bytes from the
        // edge is one that any short run of damage can manufacture.
        val margin = max(4, bytes.size / 6)
        for (cut in margin..(bytes.size - margin)) {
            var leftBest = Double.NEGATIVE_INFINITY
            var leftWho = ""
            var rightBest = Double.NEGATIVE_INFINITY
            var rightWho = ""
            for (profile in trained) {
                val l = profile.score(bytes, 0, cut, trim = 0)
                if (l > leftBest) { leftBest = l; leftWho = profile.address }
                val r = profile.score(bytes, cut, bytes.size, trim = 0)
                if (r > rightBest) { rightBest = r; rightWho = profile.address }
            }

            // Weighted by how much of the frame each half accounts for, so the
            // comparison against the whole-frame score is like for like.
            val combined =
                (leftBest * cut + rightBest * (bytes.size - cut)) / bytes.size
            val gain = combined - whole
            if (gain > bestGain) {
                bestGain = gain
                bestDifferent = leftWho != rightWho
            }
        }
        return Splice(min(bestGain, 6.0) / 6.0, bestDifferent)
    }

    /**
     * How far the advertising-data chain is from tiling its region exactly,
     * as a fraction of the frame. Zero means a valid chain.
     */
    fun tilingResidual(bytes: ByteArray, adOffset: Int): Double {
        if (adOffset < 0 || adOffset >= bytes.size) return 0.0

        var i = adOffset
        while (i < bytes.size) {
            val length = bytes[i].toInt() and 0xFF
            if (length == 0) {
                val allZero = (i until bytes.size).all { bytes[it].toInt() == 0 }
                return if (allZero) 0.0 else (bytes.size - i).toDouble() / bytes.size
            }
            i += length + 1
        }
        return abs(i - bytes.size).toDouble() / bytes.size
    }

    /**
     * Disagreement between the PDU header's length field and the bytes that
     * actually arrived.
     *
     * Only meaningful for board captures: Android hands over the advertising
     * data with the header already removed, so there is no length field to
     * check and the feature is zero rather than noise.
     */
    fun lengthMismatch(frame: Frame): Double {
        val bytes = frame.bytes
        if (!frame.hasHeader || bytes.size < 2) return 0.0
        val declared = bytes[1].toInt() and 0x3F
        val actual = bytes.size - 2 - if (frame.hasCrc) 3 else 0
        return min(abs(declared - actual).toDouble() / max(actual, 1), 1.0)
    }
}

/**
 * A frame and the little that has to be known about its shape to read it.
 *
 * The two sources hand over different things - the board a whole link-layer PDU,
 * the phone the advertising data with the header already stripped - and nearly
 * every structural test below depends on knowing which. Carrying it alongside
 * the bytes keeps that from being re-guessed at each call site, which is where
 * it would eventually be guessed differently.
 */
data class Frame(
    val bytes: ByteArray,
    /** First advertising-data byte, or -1 when the PDU carries no AD structures. */
    val adOffset: Int,
    /** True when byte 1 is the PDU length field: board captures only. */
    val hasHeader: Boolean,
    /** True when the last three bytes are the received CRC (raw capture mode). */
    val hasCrc: Boolean = false,
) {
    /** The advertising body, without the CRC the structural tests must not see. */
    val body: ByteArray
        get() = if (hasCrc && bytes.size > 3) bytes.copyOfRange(0, bytes.size - 3) else bytes

    // ByteArray gives these identity semantics, which silently breaks any use
    // of this class as a map key or in a set comparison.
    override fun equals(other: Any?): Boolean =
        other is Frame &&
            bytes.contentEquals(other.bytes) &&
            adOffset == other.adOffset &&
            hasHeader == other.hasHeader &&
            hasCrc == other.hasCrc

    override fun hashCode(): Int =
        (((bytes.contentHashCode() * 31) + adOffset) * 31 + hasHeader.hashCode()) * 31 +
            hasCrc.hashCode()
}
