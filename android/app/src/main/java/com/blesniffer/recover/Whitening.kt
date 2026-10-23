package com.blesniffer.recover

/**
 * BLE data whitening, undone on the host.
 *
 * Every advertising packet is XORed on air with the output of a 7-bit LFSR
 * (x⁷ + x⁴ + 1) seeded from the channel index, so long runs of identical bits
 * never reach the modulator. The radio normally undoes this in hardware, but
 * requirement F4 has the firmware leave it alone in raw capture mode: keeping
 * the bytes as they arrived means the host can recompute anything it needs,
 * including trying a different channel's sequence when the channel itself is in
 * doubt.
 *
 * The register is seeded with a leading 1 followed by the six channel bits, most
 * significant first, and clocked once per data bit, least significant bit first.
 *
 * Whitening is its own inverse - it is an XOR with a keystream - so [apply]
 * both whitens and dewhitens.
 */
object Whitening {

    /**
     * XORs [data] with the whitening sequence for [channel].
     *
     * Returns a new array; the input is left alone, because the raw bytes as
     * received are the one thing the specification insists must survive.
     */
    fun apply(data: ByteArray, channel: Int): ByteArray {
        val out = data.copyOf()
        applyInPlace(out, channel)
        return out
    }

    fun applyInPlace(data: ByteArray, channel: Int) {
        // Seven stages, index 0 first. Stage 0 is the leading 1; stages 1..6
        // take the channel index from its most significant bit down.
        val lfsr = IntArray(7)
        lfsr[0] = 1
        for (i in 0 until 6) {
            lfsr[i + 1] = (channel shr (5 - i)) and 1
        }

        for (i in data.indices) {
            var value = data[i].toInt() and 0xFF
            var result = 0
            for (bit in 0 until 8) {
                val output = lfsr[6]

                // x⁷ feeds back into stage 0, and taps stage 4 (x⁴) on the way.
                for (stage in 6 downTo 1) {
                    lfsr[stage] = lfsr[stage - 1]
                }
                lfsr[0] = output
                lfsr[4] = lfsr[4] xor output

                result = result or (((value and 1) xor output) shl bit)
                value = value shr 1
            }
            data[i] = result.toByte()
        }
    }

    /**
     * The keystream itself, for testing. The sequence must repeat with period
     * 127 - a maximal-length 7-bit LFSR - and any shorter period means the taps
     * are wrong, which would corrupt every packet in a way that still looks like
     * plausible bytes.
     */
    fun keystream(channel: Int, bits: Int): IntArray {
        val zeros = ByteArray((bits + 7) / 8)
        applyInPlace(zeros, channel)
        return IntArray(bits) { (zeros[it / 8].toInt() shr (it % 8)) and 1 }
    }
}
