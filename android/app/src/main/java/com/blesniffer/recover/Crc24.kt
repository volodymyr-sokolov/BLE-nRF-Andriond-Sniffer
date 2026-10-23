package com.blesniffer.recover

/**
 * BLE's CRC-24 and the syndrome decoding that layer 1 of the specification is
 * built on.
 *
 * The polynomial is x²⁴+x¹⁰+x⁹+x⁶+x⁴+x³+x+1, seeded with 0x555555 on the
 * advertising channels. BLE clocks the register least-significant-bit first,
 * which is why the mask below is the bit-reversal of 0x65B rather than 0x65B
 * itself - getting that backwards produces a CRC that is self-consistent and
 * wrong, which is the worst possible failure for this code.
 *
 * The property everything here rests on is **linearity**:
 *
 * ```
 *     CRC(x ⊕ e) = CRC(x) ⊕ CRC₀(e)
 * ```
 *
 * where CRC₀ uses a zero seed. So the syndrome
 *
 * ```
 *     S = CRC(received) ⊕ received_CRC_field
 * ```
 *
 * depends only on the error vector and not at all on the data. Every possible
 * single-bit error therefore has a fixed, precomputable syndrome, and finding
 * which bit flipped is a hash lookup rather than a search.
 */
object Crc24 {

    /**
     * The seed the specification states, in the specification's bit order.
     *
     * Kept as a named constant purely so [ADV_INIT] can be derived from it and
     * be seen to be derived from it. Nothing should seed the register with this.
     */
    const val SPEC_ADV_INIT = 0x555555

    /**
     * Seed for advertising channel PDUs, in *this* register's bit order.
     *
     * The specification says 0x555555, and the register below shifts right, so
     * the seed has to be bit-reversed to match: rev24(0x555555) = 0xAAAAAA. The
     * two differ, which is the only reason this is worth a comment - 0x555555
     * bit-reverses to something that looks equally like a plausible seed, so the
     * mistake produces no visible symptom in any test that generates its own
     * CRCs. Every such test agreed with itself perfectly while disagreeing with
     * every real BLE radio, and the error survived until frames captured off the
     * air were finally checked against it: 0 of 501 verified. With this value,
     * 481 of 501. See `RECOVERY.md` §5.5.
     */
    const val ADV_INIT = 0xAAAAAA

    /** Bit-reversed 0x65B, for a register that shifts right. */
    private const val LFSR_MASK = 0x5A6000

    /** CRC over [data], least-significant-bit first as BLE transmits. */
    fun compute(data: ByteArray, from: Int = 0, to: Int = data.size, init: Int = ADV_INIT): Int {
        var state = init
        for (i in from until to) {
            var cur = data[i].toInt() and 0xFF
            repeat(8) {
                val feedback = (state xor cur) and 1
                cur = cur shr 1
                state = state shr 1
                if (feedback != 0) {
                    // The x²⁴ term re-enters at the top of the register, then
                    // the tap mask is applied. Omitting the top bit, or ORing
                    // the mask instead of XORing it, yields a CRC that is
                    // perfectly self-consistent and not BLE's - which the
                    // linearity test catches and nothing else would.
                    state = state or (1 shl 23)
                    state = state xor LFSR_MASK
                }
            }
        }
        return state and 0xFFFFFF
    }

    /** The three CRC bytes as transmitted, least significant first. */
    fun toBytes(crc: Int) = byteArrayOf(
        (crc and 0xFF).toByte(),
        ((crc shr 8) and 0xFF).toByte(),
        ((crc shr 16) and 0xFF).toByte(),
    )

    fun fromBytes(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)

    /**
     * Syndrome of a single-bit error, per bit position, for a body of
     * [bodyLength] bytes followed by the 3 CRC bytes.
     *
     * Cached per length: the table depends on how many bytes follow the error,
     * and advertising PDUs only take a few dozen distinct lengths.
     */
    private val tables = HashMap<Int, Map<Int, Int>>()

    @Synchronized
    fun syndromeTable(bodyLength: Int): Map<Int, Int> = tables.getOrPut(bodyLength) {
        val table = HashMap<Int, Int>((bodyLength + 3) * 8 * 2)
        val zero = ByteArray(bodyLength)

        // Errors in the body: flip one bit of an all-zero body and see what the
        // zero-seeded CRC becomes. Linearity makes that the syndrome.
        for (bit in 0 until bodyLength * 8) {
            val e = zero.copyOf()
            e[bit / 8] = (1 shl (bit % 8)).toByte()
            table[compute(e, init = 0)] = bit
        }

        // Errors in the CRC field itself pass through unchanged: the syndrome is
        // the error pattern.
        for (bit in 0 until 24) {
            table.putIfAbsent(1 shl bit, bodyLength * 8 + bit)
        }
        table
    }

    /** What a single-bit correction produced. */
    data class Correction(val corrected: ByteArray, val bitsFlipped: Int) {
        override fun equals(other: Any?) =
            other is Correction && corrected.contentEquals(other.corrected) &&
                bitsFlipped == other.bitsFlipped
        override fun hashCode() = corrected.contentHashCode() * 31 + bitsFlipped
    }

    /**
     * Attempts to repair [frame], which is `body ‖ crc`, by flipping one or two
     * bits.
     *
     * Returns null when the syndrome matches no correctable pattern. A returned
     * correction is *algebraically certain* in a way nothing else in this
     * pipeline is: the repaired frame's CRC checks out, which is a 24-bit
     * agreement rather than an inference from what the device usually says.
     *
     * Two-bit search costs a scan over bit positions; the specification puts the
     * false-acceptance probability for that at roughly 56000/2²⁴ ≈ 0.33%, and it
     * is further filtered by the structural check upstream.
     */
    fun correct(frame: ByteArray, maxBits: Int = 2): Correction? {
        if (frame.size < 4) return null
        val bodyLength = frame.size - 3

        val syndrome = compute(frame, 0, bodyLength) xor fromBytes(frame, bodyLength)
        if (syndrome == 0) return Correction(frame.copyOf(), 0)   // already valid

        val table = syndromeTable(bodyLength)

        table[syndrome]?.let { bit ->
            return Correction(flip(frame, bit), 1)
        }
        if (maxBits < 2) return null

        // Two bits: S = syn(p) ⊕ syn(q), so for each p check whether the residue
        // is itself a known single-bit syndrome.
        for ((syn, p) in table) {
            val residue = syndrome xor syn
            val q = table[residue] ?: continue
            if (q <= p) continue
            return Correction(flip(flip(frame, p), q), 2)
        }
        return null
    }

    private fun flip(frame: ByteArray, bit: Int): ByteArray {
        val out = frame.copyOf()
        out[bit / 8] = (out[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
        return out
    }

    /** True when `body ‖ crc` carries a CRC that checks out. */
    fun verify(frame: ByteArray): Boolean {
        if (frame.size < 4) return false
        val bodyLength = frame.size - 3
        return compute(frame, 0, bodyLength) == fromBytes(frame, bodyLength)
    }
}
