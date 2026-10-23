package com.blesniffer.ui

import com.blesniffer.model.TimelinePoint
import com.blesniffer.model.TimelineScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineBinsTest {

    private val now = 1_800_000_000_000L

    private fun point(agoMs: Long, channel: Int = 37, crcOk: Boolean = true) =
        TimelinePoint(now - agoMs, channel, crcOk)

    @Test
    fun `newest packet lands in the last bin`() {
        val bins = binPackets(listOf(point(0)), TimelineScale.MINUTE, now, binCount = 60)
        assertEquals(1, bins.count(TimelineSeries.CHANNEL_37, 59))
        assertEquals(0, bins.count(TimelineSeries.CHANNEL_37, 58))
    }

    @Test
    fun `a packet just inside the window lands in the first bin`() {
        // 60 bins over a minute is one bin per second; 59.5 s ago is bin 0.
        val bins = binPackets(listOf(point(59_500)), TimelineScale.MINUTE, now, binCount = 60)
        assertEquals(1, bins.count(TimelineSeries.CHANNEL_37, 0))
        assertEquals(1, bins.peak)
    }

    @Test
    fun `packets older than the span are excluded`() {
        val points = listOf(point(60_000), point(60_001), point(120_000))
        val bins = binPackets(points, TimelineScale.MINUTE, now, binCount = 60)
        assertEquals(0, bins.peak)
        assertEquals(0, bins.total(TimelineSeries.CHANNEL_37))
    }

    @Test
    fun `packets in the future are ignored rather than clamped`() {
        val bins = binPackets(
            listOf(TimelinePoint(now + 5_000, 37, true)),
            TimelineScale.MINUTE,
            now,
            binCount = 60,
        )
        assertEquals(0, bins.peak)
    }

    @Test
    fun `each channel is counted into its own series`() {
        val points = listOf(point(0, 37), point(0, 38), point(0, 38), point(0, 39))
        val bins = binPackets(points, TimelineScale.TEN_SECONDS, now, binCount = 60)

        assertEquals(1, bins.total(TimelineSeries.CHANNEL_37))
        assertEquals(2, bins.total(TimelineSeries.CHANNEL_38))
        assertEquals(1, bins.total(TimelineSeries.CHANNEL_39))
        assertEquals(4, bins.peak)
    }

    @Test
    fun `crc failures are counted as errors whatever channel they arrived on`() {
        val points = listOf(point(0, 38, crcOk = false), point(0, 38, crcOk = true))
        val bins = binPackets(points, TimelineScale.TEN_SECONDS, now, binCount = 60)

        assertEquals(1, bins.total(TimelineSeries.ERROR))
        assertEquals(1, bins.total(TimelineSeries.CHANNEL_38))
    }

    @Test
    fun `a capture with no channel information collapses to one grey series`() {
        val points = listOf(point(0, channel = 0), point(0, channel = 0), point(0, channel = 0, crcOk = false))
        val bins = binPackets(points, TimelineScale.TEN_SECONDS, now, binCount = 60)

        assertFalse(bins.hasChannels)
        assertEquals(listOf(TimelineSeries.UNKNOWN, TimelineSeries.ERROR), bins.series)
        assertEquals(2, bins.total(TimelineSeries.UNKNOWN))
        assertEquals(1, bins.total(TimelineSeries.ERROR))
    }

    @Test
    fun `a mixed capture keeps channels separate and greys the rest`() {
        // What the chart sees after switching sources mid-session: the phone
        // reports no channel, the board does, and both belong on one chart.
        val points = listOf(point(0, 37), point(0, channel = 0), point(0, 39))
        val bins = binPackets(points, TimelineScale.TEN_SECONDS, now, binCount = 60)

        assertTrue(bins.hasChannels)
        assertEquals(1, bins.total(TimelineSeries.CHANNEL_37))
        assertEquals(1, bins.total(TimelineSeries.CHANNEL_39))
        assertEquals(1, bins.total(TimelineSeries.UNKNOWN))
    }

    @Test
    fun `peak is the tallest stacked column not the busiest series`() {
        val points = listOf(
            point(0, 37), point(0, 38), point(0, 39),   // three in the newest bin
            point(9_000, 37), point(9_000, 37),          // two in an older bin
        )
        val bins = binPackets(points, TimelineScale.TEN_SECONDS, now, binCount = 60)
        assertEquals(3, bins.peak)
    }

    /** Mirrors what TimelineChart computes for the right-hand edge. */
    private fun snap(nowMs: Long, scale: TimelineScale) =
        ((nowMs / scale.binMs) + 1) * scale.binMs

    @Test
    fun `redrawing within one column leaves every bar unchanged`() {
        // The ten minute chart used to be re-rendered every second against a
        // window ending at "now". Each redraw slid the bin edges by a second, so
        // packets hopped between neighbouring columns and the bars visibly
        // changed height. Snapping the edge to a column boundary means any two
        // redraws inside the same column produce identical bins.
        val scale = TimelineScale.TEN_MINUTES
        val points = (0 until 400).map { point((it * 1_500L)) }

        val base = snap(now, scale)
        val counts = { end: Long ->
            val bins = binPackets(points, scale, end, binCount = 60)
            (0 until 60).map { bins.count(TimelineSeries.CHANNEL_37, it) }
        }

        val reference = counts(base)
        // Every instant inside the same column must snap to the same edge.
        for (offsetMs in listOf(1L, 999L, 4_000L, scale.binMs - 1)) {
            assertEquals(base, snap(now + offsetMs, scale))
            assertEquals(reference, counts(snap(now + offsetMs, scale)))
        }
    }

    @Test
    fun `crossing a column boundary shifts the bars by exactly one column`() {
        val scale = TimelineScale.TEN_MINUTES
        val points = (0 until 400).map { point((it * 1_500L)) }

        val before = binPackets(points, scale, snap(now, scale), binCount = 60)
        val after = binPackets(points, scale, snap(now, scale) + scale.binMs, binCount = 60)

        // Column n after the shift holds what column n+1 held before it.
        for (bin in 0 until 59) {
            assertEquals(
                "column $bin should have shifted left by one",
                before.count(TimelineSeries.CHANNEL_37, bin + 1),
                after.count(TimelineSeries.CHANNEL_37, bin),
            )
        }
    }

    @Test
    fun `every scale distributes across the full width`() {
        for (scale in TimelineScale.entries) {
            val oldest = point(scale.spanMs - scale.spanMs / 60)
            val newest = point(0)
            val bins = binPackets(listOf(oldest, newest), scale, now, binCount = 60)
            assertEquals("$scale should fill bin 0", 1, bins.count(TimelineSeries.CHANNEL_37, 0))
            assertEquals("$scale should fill bin 59", 1, bins.count(TimelineSeries.CHANNEL_37, 59))
        }
    }
}
