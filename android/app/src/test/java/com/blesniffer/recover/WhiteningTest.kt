package com.blesniffer.recover

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Properties of the whitening sequence.
 *
 * These pin the structure - period, seeding, involution - which is what catches
 * a wrong tap or a reversed seed. What they cannot establish is that this
 * sequence is the one a real radio uses; that needs raw captures from a board
 * running the firmware's raw mode, which has not been possible yet.
 */
class WhiteningTest {

    @Test
    fun `whitening is its own inverse`() {
        val random = Random(3)
        repeat(50) {
            val data = ByteArray(42) { random.nextInt(256).toByte() }
            for (channel in listOf(37, 38, 39)) {
                assertArrayEquals(
                    data,
                    Whitening.apply(Whitening.apply(data, channel), channel),
                )
            }
        }
    }

    @Test
    fun `the sequence has the full period of a 7 bit register`() {
        // A maximal-length 7-bit LFSR repeats after 127 bits. Anything shorter
        // means the taps are wrong, and every packet would be corrupted into
        // bytes that still look plausible.
        for (channel in 0..39) {
            val stream = Whitening.keystream(channel, 127 * 3)
            for (i in 0 until 127 * 2) {
                assertEquals("channel $channel bit $i", stream[i], stream[i + 127])
            }

            // And it must not repeat sooner.
            for (period in 1 until 127) {
                var identical = true
                for (i in 0 until 127) {
                    if (stream[i] != stream[i + period]) {
                        identical = false
                        break
                    }
                }
                assertTrue("channel $channel repeats after $period bits", !identical)
            }
        }
    }

    @Test
    fun `each channel produces a different sequence`() {
        val seen = mutableMapOf<List<Int>, Int>()
        for (channel in 37..39) {
            val stream = Whitening.keystream(channel, 64).toList()
            val previous = seen.put(stream, channel)
            assertEquals("channels $previous and $channel share a sequence", null, previous)
        }
    }

    @Test
    fun `the seed is the channel index, not something else`() {
        // Channel 37 and 38 differ in several bits, so their sequences must
        // diverge almost immediately rather than after a long common prefix.
        val a = Whitening.keystream(37, 16)
        val b = Whitening.keystream(38, 16)
        assertNotEquals(a.take(8), b.take(8))
    }

    @Test
    fun `whitening actually changes the data`() {
        val zeros = ByteArray(20)
        val whitened = Whitening.apply(zeros, 37)
        assertTrue("a whitened run of zeros must not stay zero", whitened.any { it.toInt() != 0 })
    }
}
