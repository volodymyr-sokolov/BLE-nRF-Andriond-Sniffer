package com.blesniffer.recover

import com.blesniffer.model.AdvertisingDecoder
import com.blesniffer.model.hexToBytes
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CRC, measured against a radio instead of against itself.
 *
 * `real-raw-frames.csv` holds advertising frames captured off the air by the
 * nRF52840 in raw mode (firmware requirements F2/F4): a fixed 42 bytes with
 * whitening and CRC checking both disabled, dewhitened on the host, carrying
 * the CRC field **as the transmitter computed it**. Nothing in this project
 * produced those three bytes, which is the entire point - every other test in
 * the suite builds its frames with [Crc24.compute] and then checks them with
 * [Crc24.compute], and agrees with itself no matter what the seed is.
 *
 * That is not a hypothetical. The seed was wrong, every algebraic test passed,
 * and **0 of 501** captured frames verified. Fixing it took that to 481 of 501,
 * with the remainder being genuine reception errors - which is what a sniffer
 * pointed at a room full of advertisers is supposed to see.
 *
 * That the bodies are right is independently established: they dewhiten into
 * well-formed PDUs whose advertising-data chains tile exactly, and a PDU caught
 * on two different channels dewhitens to identical bytes, which it could only
 * do if the whitening were correct.
 */
class RealCrcTest {

    private data class Frame(val channel: Int, val timesSeen: Int, val bytes: ByteArray)

    private fun load(): List<Frame> =
        javaClass.classLoader!!.getResourceAsStream("real-raw-frames.csv")!!
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val (channel, seen, hex) = line.split(',', limit = 3)
                Frame(channel.toInt(), seen.toInt(), hex.hexToBytes())
            }

    @Test
    fun `frames captured off the air verify`() {
        val frames = load()
        assertTrue("no corpus loaded", frames.size > 50)

        val verified = frames.count { Crc24.verify(it.bytes) }
        val weighted = frames.filter { Crc24.verify(it.bytes) }.sumOf { it.timesSeen }
        val total = frames.sumOf { it.timesSeen }

        println(
            "real frames: %d of %d distinct verify; %d of %d receptions (%.1f%%)".format(
                verified, frames.size, weighted, total, 100.0 * weighted / total,
            ),
        )

        // Distinct frames include every one-off corruption, so the rate over
        // distinct frames is the pessimistic one. Over receptions - which is
        // what an error rate actually means - it should be well above 90%.
        assertTrue("only $weighted of $total receptions verified", weighted >= total * 0.9)
    }

    @Test
    fun `a frame seen many times always verifies`() {
        // A frame received identically dozens of times is one that arrived
        // intact: interference does not reproduce the same bit pattern. These
        // are the closest thing to ground truth this project has, and every one
        // of them must check out - no averaging to hide behind.
        val repeated = load().filter { it.timesSeen >= 5 }
        assertTrue("no repeated frames in the corpus", repeated.size >= 3)

        for (frame in repeated) {
            assertTrue(
                "a frame seen ${frame.timesSeen}x failed CRC: ${frame.bytes.toHexString()}",
                Crc24.verify(frame.bytes),
            )
        }
    }

    @Test
    fun `verified frames are well formed PDUs`() {
        // Guards against the opposite failure: a CRC convention tuned until the
        // numbers agreed, over bodies that were never valid packets.
        val good = load().filter { it.timesSeen >= 5 && Crc24.verify(it.bytes) }

        for (frame in good) {
            val body = frame.bytes.copyOfRange(0, frame.bytes.size - 3)
            val layout = AdvertisingDecoder.locate(body)
            assertTrue(
                "no PDU layout for ${body.toHexString()}",
                layout != null && layout.pduType.isNotEmpty(),
            )
        }
    }

    @Test
    fun `syndrome decoding repairs a real frame`() {
        // Layer 1 on genuine bytes rather than manufactured ones. Until the seed
        // was fixed this could not have worked on a real packet at all: the
        // syndrome of an undamaged frame was non-zero, so every frame looked
        // corrupt and every "correction" was decoding noise.
        val frame = load().first { it.timesSeen >= 5 && Crc24.verify(it.bytes) }.bytes

        for (bit in 0 until frame.size * 8 step 7) {
            val broken = frame.copyOf()
            broken[bit / 8] = (broken[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()

            val fixed = Crc24.correct(broken, maxBits = 1)
            assertTrue(
                "bit $bit of a real frame was not repaired",
                fixed != null && fixed.corrected.contentEquals(frame),
            )
        }
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02X".format(it) }
}
