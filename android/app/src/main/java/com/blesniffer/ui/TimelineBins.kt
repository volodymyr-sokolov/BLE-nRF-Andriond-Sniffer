package com.blesniffer.ui

import com.blesniffer.model.TimelinePoint
import com.blesniffer.model.TimelineScale

/**
 * Stacking order of the chart's series. Errors sit on top so a burst of
 * corruption reads as its own band instead of being buried in a channel.
 */
enum class TimelineSeries { CHANNEL_37, CHANNEL_38, CHANNEL_39, UNKNOWN, ERROR }

/**
 * Packets counted into equal time slots.
 *
 * Kept apart from the drawing so the arithmetic - which bin a timestamp falls
 * in, what counts as the busiest column - can be tested directly. Getting this
 * subtly wrong shifts the whole chart by one column, which is invisible to
 * anyone reading it.
 */
class TimelineBins(
    val binCount: Int,
    val hasChannels: Boolean,
    private val counts: Map<TimelineSeries, IntArray>,
) {
    fun count(series: TimelineSeries, bin: Int): Int = counts[series]?.getOrNull(bin) ?: 0

    fun total(series: TimelineSeries): Int = counts[series]?.sum() ?: 0

    /** Tallest stacked column, used to scale the vertical axis. */
    val peak: Int = (0 until binCount).maxOfOrNull { bin ->
        TimelineSeries.entries.sumOf { count(it, bin) }
    } ?: 0

    /** Series that can appear, in stacking order, for the data given. */
    val series: List<TimelineSeries> = if (hasChannels) {
        TimelineSeries.entries.toList()
    } else {
        // Nothing reported a channel, so there is nothing to separate: one grey
        // series plus errors.
        listOf(TimelineSeries.UNKNOWN, TimelineSeries.ERROR)
    }
}

/**
 * Buckets [points] into [binCount] slots ending at [endMs].
 *
 * The window is the half-open interval (endMs - span, endMs]: the newest packet
 * lands in the last bin, a packet exactly one span old falls outside, and
 * anything with a timestamp in the future is ignored rather than clamped.
 */
fun binPackets(
    points: List<TimelinePoint>,
    scale: TimelineScale,
    endMs: Long,
    binCount: Int = TimelineRenderer.BIN_COUNT,
): TimelineBins {
    val counts = TimelineSeries.entries.associateWith { IntArray(binCount) }
    val binMs = (scale.spanMs.toDouble() / binCount).coerceAtLeast(1.0)
    val startMs = endMs - scale.spanMs

    val hasChannels = points.any { it.channel in 37..39 }

    for (point in points) {
        if (point.timestampMs <= startMs || point.timestampMs > endMs) continue

        val fromEnd = (endMs - point.timestampMs).toDouble()
        val bin = binCount - 1 - (fromEnd / binMs).toInt()
        if (bin !in 0 until binCount) continue

        val series = when {
            !point.crcOk -> TimelineSeries.ERROR
            // The host's own receiver reports no channel, so its packets are
            // grey even on a chart that is otherwise split by channel. That is
            // the honest rendering: they carry no channel to colour by.
            !hasChannels -> TimelineSeries.UNKNOWN
            point.channel == 37 -> TimelineSeries.CHANNEL_37
            point.channel == 38 -> TimelineSeries.CHANNEL_38
            point.channel == 39 -> TimelineSeries.CHANNEL_39
            else -> TimelineSeries.UNKNOWN
        }
        counts.getValue(series)[bin]++
    }

    return TimelineBins(binCount, hasChannels, counts)
}
