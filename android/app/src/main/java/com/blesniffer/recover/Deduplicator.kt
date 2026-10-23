package com.blesniffer.recover

import com.blesniffer.model.BlePacket
import com.blesniffer.model.SourceKind
import kotlin.math.abs

/**
 * Folds together the two receivers' views of the same transmission.
 *
 * The two radios run independently, so one advertisement arrives twice, some
 * milliseconds apart, with different timestamps and different RSSI. Section 4 of
 * the specification matches such pairs on advertiser address plus payload within
 * a time window; section 6 keeps the nRF52840's copy, because that is the one
 * carrying the channel, the hardware timestamp and the true CRC status, while
 * the host's copy is a parsed ScanResult with all of that already discarded.
 *
 * The host's copy is not thrown away: the surviving packet records that both
 * receivers saw it, which is what later makes cross-source recovery and
 * cross-validation possible.
 *
 * Packets are held for the length of the matching window before being released,
 * because a duplicate can only be recognised once its twin has had time to
 * arrive. That delay is the price of deduplication and is paid only when both
 * receivers are running.
 */
class Deduplicator(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    companion object {
        /**
         * The specification pairs receptions within 200 ms when estimating the
         * clock relationship. The same figure comfortably covers the difference
         * between a hardware capture and an Android scan callback.
         */
        const val DEFAULT_WINDOW_MS = 200L

        /**
         * Search window used only until the clock relationship is known. Wide
         * enough to cover a board whose timeline starts seconds away from the
         * host's, which is normal - it counts from whenever capture began.
         */
        const val BOOTSTRAP_WINDOW_MS = 5_000L

        /**
         * Matched pairs needed before the offset is believed.
         *
         * A beacon repeating the same payload makes a single pair ambiguous:
         * the host's copy of *this* transmission and its copy of the *next* one
         * are indistinguishable without a clock to tell them apart, which is the
         * very thing being measured. One coincidence must not be allowed to
         * shift the whole timeline, so the estimate is not applied until several
         * independent pairs agree.
         */
        const val MIN_OFFSET_SAMPLES = 5

        /**
         * Shortest shared payload prefix that counts as the same transmission,
         * in hex characters - so 16 of them is 8 bytes.
         *
         * Below this the "match" is structure every advertiser has in common.
         * A Flags structure is three bytes and opens most advertisements in
         * existence.
         */
        const val MIN_SHARED_PAYLOAD_HEX = 16
    }

    private class Pending(
        val address: String,
        val payload: String,
        val releaseAt: Long,
        var packet: BlePacket,
    )

    private val pending = ArrayDeque<Pending>()

    /** Recent packets from both sources, for measuring the clock offset only. */
    private val crossSource = ArrayDeque<Pending>()

    /** Median of the samples so far; only applied once corroborated. */
    private var measuredOffsetMs: Long = 0
    private val offsetSamples = ArrayDeque<Long>()

    /** The offset actually used for alignment - zero until it is trusted. */
    private val offsetMs: Long
        get() = if (offsetSamples.size >= MIN_OFFSET_SAMPLES) measuredOffsetMs else 0L

    private var merged: Long = 0
    private var released: Long = 0

    /**
     * Identity of a transmission: who sent it and exactly what they said.
     *
     * RSSI and channel are deliberately excluded. The receivers measure
     * different signal strengths for one transmission and the host reports no
     * channel at all, so including either would stop any pair from matching.
     */
    private fun addressOf(packet: BlePacket): String = packet.address.ifEmpty { "?" }

    /**
     * The advertising data, and only that: the part both receivers can see.
     *
     * The host sees only the advertising data; the board delivers a whole PDU,
     * and in raw capture mode three CRC bytes on the end of it as well. Those
     * CRC bytes are the reason this is a function rather than a substring at the
     * call site - they are bytes the host has never had and never will, so
     * leaving them in guaranteed that no pair ever matched in raw mode.
     */
    private fun payloadOf(packet: BlePacket): String {
        val hex = packet.rawHex
        val end = if (packet.hasCrcField && hex.length >= 6) hex.length - 6 else hex.length
        val start = if (packet.adOffset > 0 && hex.length >= packet.adOffset * 2) {
            packet.adOffset * 2
        } else {
            0
        }
        return if (start < end) hex.substring(start, end) else hex
    }

    /**
     * Whether two advertising payloads are the same transmission.
     *
     * Equality is the easy half. The hard half is that **Android does not hand
     * up a PDU at all**: it merges an advertisement with the scan response that
     * followed it into a single ScanRecord, so for any scannable advertiser the
     * host's payload is the board's PDU *plus* the scan response's structures on
     * the end. Measured against a real 630 second two-receiver capture, exact
     * equality matched zero pairs out of every packet in it, and this is why.
     *
     * Prefix containment is the honest relationship between the two. The
     * minimum length keeps it honest: a couple of shared bytes is what every
     * advertisement in the room starts with - `020106` is a Flags structure -
     * and matching on that would fold unrelated frames together.
     */
    private fun samePayload(a: String, b: String): Boolean {
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        return shorter.length >= MIN_SHARED_PAYLOAD_HEX && longer.startsWith(shorter)
    }

    /** A packet's time brought onto the host's scale. */
    private fun aligned(packet: BlePacket): Long =
        if (packet.source == SourceKind.USB) packet.timestampMs + offsetMs else packet.timestampMs

    /** Takes a packet into the window, merging it with its twin if it has one. */
    fun offer(packet: BlePacket) {
        estimateOffsetFrom(packet)

        val at = aligned(packet)
        val address = addressOf(packet)
        val payload = payloadOf(packet)

        val twin = pending.firstOrNull {
            it.address == address &&
                samePayload(it.payload, payload) &&
                abs(aligned(it.packet) - at) <= windowMs
        }

        if (twin == null) {
            pending.addLast(Pending(address, payload, at + windowMs, packet))
            return
        }

        if (twin.packet.source != packet.source) merged++

        val keep = preferred(twin.packet, packet)
        twin.packet = keep.copy(sources = twin.packet.sources + packet.sources)
    }

    /**
     * Learns the offset between the two clocks, over a much wider window than
     * merging uses.
     *
     * This has to be separate, and it has to come first. Merging compares
     * *aligned* times, so until an offset is known the two receivers' views of
     * the same transmission can look arbitrarily far apart - if the board's
     * timeline happens to start half a second away from the host's, no pair ever
     * falls inside the matching window and the offset can never be learned. The
     * specification bootstraps this from periodic sync frames (requirement F5),
     * which the firmware does not send; searching a wide window for pairs is the
     * equivalent that works with what the board does send.
     *
     * Only cross-source pairs are considered, and only for measurement - nothing
     * here merges anything, so a wrong guess at this stage costs an outlier in a
     * median rather than a lost packet.
     */
    private fun estimateOffsetFrom(packet: BlePacket) {
        val address = addressOf(packet)
        val payload = payloadOf(packet)
        val window = if (offsetSamples.isEmpty()) BOOTSTRAP_WINDOW_MS else windowMs * 4

        val counterpart = crossSource.lastOrNull {
            it.address == address &&
                samePayload(it.payload, payload) &&
                it.packet.source != packet.source &&
                abs(it.packet.timestampMs - packet.timestampMs) <= window
        }

        if (counterpart != null) {
            val host = if (packet.source == SourceKind.PHONE) packet else counterpart.packet
            val nrf = if (packet.source == SourceKind.USB) packet else counterpart.packet
            recordOffset(host.timestampMs - nrf.timestampMs)
            crossSource.remove(counterpart)
        }

        crossSource.addLast(Pending(address, payload, packet.timestampMs, packet))
        while (crossSource.size > 512) crossSource.removeFirst()
        while (crossSource.isNotEmpty() &&
            packet.timestampMs - crossSource.first().packet.timestampMs > BOOTSTRAP_WINDOW_MS * 2
        ) {
            crossSource.removeFirst()
        }
    }

    /**
     * Returns the packets whose matching window has closed, oldest first.
     * Passing [flush] releases everything regardless, for the end of a capture.
     */
    fun drain(nowMs: Long, flush: Boolean = false): List<BlePacket> {
        val out = mutableListOf<BlePacket>()
        while (pending.isNotEmpty() && (flush || pending.first().releaseAt <= nowMs)) {
            out += pending.removeFirst().packet
        }
        released += out.size
        return out
    }

    /**
     * Which of two receptions to keep.
     *
     * The nRF52840 wins on principle: it reports the channel, a microsecond
     * hardware timestamp and the real CRC status, none of which a parsed
     * ScanResult can supply. Between two receptions from the same source the
     * first is kept, so timing stays honest.
     */
    private fun preferred(existing: BlePacket, incoming: BlePacket): BlePacket = when {
        existing.source == incoming.source -> existing
        existing.source == SourceKind.USB -> existing
        else -> incoming
    }

    private fun recordOffset(sample: Long) {
        offsetSamples.addLast(sample)
        while (offsetSamples.size > 64) offsetSamples.removeFirst()
        // Median, not mean: OS scheduling gives host timestamps a heavy tail and
        // the specification asks for a robust estimator.
        measuredOffsetMs = offsetSamples.sorted()[offsetSamples.size / 2]
    }

    fun clockOffsetMs(): Long = offsetMs

    /** Number of cross-source pairs folded together so far. */
    fun mergedCount(): Long = merged

    fun reset() {
        pending.clear()
        crossSource.clear()
        offsetSamples.clear()
        measuredOffsetMs = 0
        merged = 0
        released = 0
    }
}
