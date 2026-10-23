package com.blesniffer.recover

import com.blesniffer.model.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Layer 1's algebra, and its agreement with an actual radio.
 *
 * Most of these verify the decoder is internally exact - every correctable
 * error really is corrected, and the CRC really is linear, which is the
 * property the whole syndrome approach rests on. Every one of them passed
 * while the CRC was seeded wrongly, because a test that builds its frames with
 * [Crc24.compute] and then checks them with [Crc24.compute] agrees with itself
 * whatever the seed is. Internal consistency was never the question.
 *
 * `agrees with a real radio` is the one that was missing, and it is the one
 * that found the defect. See [RealCrcTest].
 */
class Crc24Test {

    private fun frame(body: ByteArray) = body + Crc24.toBytes(Crc24.compute(body))

    @Test
    fun `a freshly built frame verifies`() {
        val body = "0011FFEEDDCCBBAA020106".hexToBytes()
        assertTrue(Crc24.verify(frame(body)))
    }

    @Test
    fun `the seed is the specification's, in this register's bit order`() {
        // 0x555555 as the specification states it, bit-reversed because this
        // register shifts right. Asserting the relationship rather than the
        // literal, so the next person to "correct" ADV_INIT back to 0x555555
        // has to explain themselves to this test.
        assertEquals(0xAAAAAA, Crc24.ADV_INIT)
        assertEquals(Crc24.ADV_INIT, reverse24(Crc24.SPEC_ADV_INIT))
        // An empty body leaves the register at its seed.
        assertEquals(Crc24.ADV_INIT, Crc24.compute(ByteArray(0)))
    }

    private fun reverse24(value: Int): Int {
        var out = 0
        for (bit in 0 until 24) {
            if (value and (1 shl bit) != 0) out = out or (1 shl (23 - bit))
        }
        return out
    }

    @Test
    fun `the crc is linear, which is what makes syndromes work`() {
        // CRC(a ⊕ b) with a zero seed must equal CRC₀(a) ⊕ CRC₀(b).
        val random = Random(7)
        repeat(200) {
            val n = 4 + random.nextInt(30)
            val a = ByteArray(n) { random.nextInt(256).toByte() }
            val b = ByteArray(n) { random.nextInt(256).toByte() }
            val x = ByteArray(n) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }

            assertEquals(
                Crc24.compute(a, init = 0) xor Crc24.compute(b, init = 0),
                Crc24.compute(x, init = 0),
            )
        }
    }

    @Test
    fun `every single bit error is corrected exactly`() {
        val body = "0011FFEEDDCCBBAA0201060AFF4C0012".hexToBytes()
        val good = frame(body)

        for (bit in 0 until good.size * 8) {
            val broken = good.copyOf()
            broken[bit / 8] = (broken[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()

            val fixed = Crc24.correct(broken, maxBits = 1)
            assertNotNull("bit $bit should be correctable", fixed)
            assertEquals("bit $bit", 1, fixed!!.bitsFlipped)
            assertTrue("bit $bit restored", fixed.corrected.contentEquals(good))
        }
    }

    @Test
    fun `two bit errors are corrected`() {
        val body = "0011FFEEDDCCBBAA0201060AFF4C00".hexToBytes()
        val good = frame(body)
        val random = Random(11)

        var corrected = 0
        repeat(200) {
            val p = random.nextInt(good.size * 8)
            var q = random.nextInt(good.size * 8)
            if (q == p) q = (q + 1) % (good.size * 8)

            val broken = good.copyOf()
            for (bit in intArrayOf(p, q)) {
                broken[bit / 8] = (broken[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
            }

            val fixed = Crc24.correct(broken, maxBits = 2)
            if (fixed != null && fixed.corrected.contentEquals(good)) corrected++
        }
        // The pair search is exhaustive over the syndrome structure, so this
        // should be essentially total.
        assertTrue("only $corrected/200 two-bit errors corrected", corrected >= 195)
    }

    @Test
    fun `an intact frame is reported as needing no correction`() {
        val good = frame("0011FFEEDDCCBBAA020106".hexToBytes())
        assertEquals(0, Crc24.correct(good)!!.bitsFlipped)
    }

    @Test
    fun `heavy corruption is refused rather than mangled`() {
        val good = frame("0011FFEEDDCCBBAA0201060AFF4C00".hexToBytes())
        val random = Random(13)

        var falselyAccepted = 0
        repeat(300) {
            val broken = good.copyOf()
            // Eight random bit flips is far beyond what two-bit decoding covers.
            repeat(8) {
                val bit = random.nextInt(broken.size * 8)
                broken[bit / 8] = (broken[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
            }
            val fixed = Crc24.correct(broken, maxBits = 2)
            if (fixed != null && !fixed.corrected.contentEquals(good)) falselyAccepted++
        }
        // Anything accepted here would be a wrong answer wearing a valid CRC.
        // The specification puts the pair-search false acceptance near 0.33%.
        assertTrue("$falselyAccepted/300 wrongly accepted", falselyAccepted <= 3)
    }

    @Test
    fun `the syndrome table covers body and crc bits`() {
        val table = Crc24.syndromeTable(bodyLength = 10)
        assertEquals((10 + 3) * 8, table.size)
        assertNull("a zero syndrome means no error", table[0])
    }
}
