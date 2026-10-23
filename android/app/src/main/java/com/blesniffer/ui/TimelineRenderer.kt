package com.blesniffer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.blesniffer.model.TimelinePoint
import com.blesniffer.model.TimelineScale

/**
 * Colours and metrics for one rendering. Kept separate from the drawing so the
 * screen can pass theme colours and the PNG export can pass a fixed light
 * palette and a much larger scale factor.
 */
data class TimelineStyle(
    val background: Int,
    val axis: Int,
    val label: Int,
    val channel37: Int,
    val channel38: Int,
    val channel39: Int,
    val unknownChannel: Int,
    val error: Int,
    /** Multiplies every line width and text size; 1f is a phone-sized chart. */
    val scale: Float,
) {
    fun colourOf(series: TimelineSeries): Int = when (series) {
        TimelineSeries.CHANNEL_37 -> channel37
        TimelineSeries.CHANNEL_38 -> channel38
        TimelineSeries.CHANNEL_39 -> channel39
        TimelineSeries.UNKNOWN -> unknownChannel
        TimelineSeries.ERROR -> error
    }
}

/**
 * Draws the packet timeline.
 *
 * Both the on-screen chart and the exported PNG go through this one function
 * against a plain android.graphics.Canvas, so the file is necessarily the same
 * picture the user was looking at rather than a second implementation that has
 * to be kept in step. The counting itself lives in [binPackets].
 */
object TimelineRenderer {

    /** Columns across the axis. 60 keeps each bin a round fraction of the span. */
    const val BIN_COUNT = TimelineScale.BIN_COUNT

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        points: List<TimelinePoint>,
        scale: TimelineScale,
        endMs: Long,
        style: TimelineStyle,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = style.background
        canvas.drawRect(0f, 0f, width, height, paint)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.label
            textSize = 10f * style.scale
        }

        // Room for the count labels on the left and the time labels underneath.
        val plotLeft = 30f * style.scale
        val plotRight = width - 4f * style.scale
        val plotTop = 4f * style.scale
        val plotBottom = height - 14f * style.scale
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

        val bins = binPackets(points, scale, endMs, BIN_COUNT)

        paint.color = style.axis
        paint.strokeWidth = 1f * style.scale
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, paint)

        if (bins.peak == 0) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "no packets in the last ${scale.label}",
                (plotLeft + plotRight) / 2f,
                plotTop + plotHeight / 2f,
                text,
            )
            drawTimeLabels(canvas, plotLeft, plotRight, height, scale, text, style)
            return
        }

        val binWidth = plotWidth / bins.binCount
        val barWidth = (binWidth * 0.8f).coerceAtLeast(1f)
        val gap = (binWidth - barWidth) / 2f

        for (bin in 0 until bins.binCount) {
            var y = plotBottom
            val left = plotLeft + bin * binWidth + gap
            for (series in bins.series) {
                val count = bins.count(series, bin)
                if (count == 0) continue
                val barHeight = plotHeight * count / bins.peak
                paint.color = style.colourOf(series)
                canvas.drawRect(left, y - barHeight, left + barWidth, y, paint)
                y -= barHeight
            }
        }

        // Peak label, so the vertical axis means something.
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(bins.peak.toString(), plotLeft - 4f * style.scale, plotTop + text.textSize, text)
        canvas.drawText("0", plotLeft - 4f * style.scale, plotBottom, text)

        drawTimeLabels(canvas, plotLeft, plotRight, height, scale, text, style)
    }

    private fun drawTimeLabels(
        canvas: Canvas,
        plotLeft: Float,
        plotRight: Float,
        height: Float,
        scale: TimelineScale,
        text: Paint,
        style: TimelineStyle,
    ) {
        val baseline = height - 3f * style.scale
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("-${scale.label}", plotLeft, baseline, text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("now", plotRight, baseline, text)
    }

    /**
     * Renders the same chart into a bitmap for export, with a title and a key
     * so the image still makes sense away from the app.
     */
    fun toBitmap(
        points: List<TimelinePoint>,
        scale: TimelineScale,
        endMs: Long,
        style: TimelineStyle,
        widthPx: Int = 2400,
        heightPx: Int = 900,
        title: String? = null,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(style.background)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.label
            textSize = 34f
        }

        var top = 0f
        if (title != null) {
            canvas.drawText("$title  ·  ${scale.label} scale", 24f, 24f + titlePaint.textSize, titlePaint)
            top += 24f + titlePaint.textSize * 1.4f
        }

        val hasChannels = points.any { it.channel in 37..39 }
        top += drawLegend(canvas, widthPx, top, style, hasChannels)

        canvas.save()
        canvas.translate(0f, top)
        draw(
            canvas = canvas,
            width = widthPx.toFloat(),
            height = heightPx - top,
            points = points,
            scale = scale,
            endMs = endMs,
            style = style,
        )
        canvas.restore()

        return bitmap
    }

    /** Draws the key across the top and returns the height it consumed. */
    private fun drawLegend(
        canvas: Canvas,
        widthPx: Int,
        top: Float,
        style: TimelineStyle,
        hasChannels: Boolean,
    ): Float {
        val entries = if (hasChannels) {
            listOf(
                "ch 37" to style.channel37,
                "ch 38" to style.channel38,
                "ch 39" to style.channel39,
                "no channel" to style.unknownChannel,
                "CRC error" to style.error,
            )
        } else {
            listOf("packets" to style.unknownChannel, "CRC error" to style.error)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.label
            textSize = 26f
        }
        val bounds = Rect()

        var x = 24f
        val y = top + 8f
        val swatch = 24f
        for ((label, colour) in entries) {
            paint.color = colour
            canvas.drawRect(x, y, x + swatch, y + swatch, paint)
            text.getTextBounds(label, 0, label.length, bounds)
            canvas.drawText(label, x + swatch + 8f, y + swatch - 4f, text)
            x += swatch + 16f + bounds.width() + 28f
            if (x > widthPx - 200f) break
        }
        return swatch + 24f
    }
}
