package com.blesniffer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.blesniffer.model.TimelinePoint
import com.blesniffer.model.TimelineScale

/** Series colours, shared by the on-screen chart and the exported image. */
object TimelineColors {
    val CHANNEL_37 = Color(0xFF2E86C1)
    val CHANNEL_38 = Color(0xFF27AE60)
    val CHANNEL_39 = Color(0xFFE67E22)
    val UNKNOWN = Color(0xFF8A9199)

    /** Light maroon, reserved for packets that failed CRC. */
    val ERROR = Color(0xFFB56576)
}

@Composable
fun rememberTimelineStyle(scale: Float): TimelineStyle {
    val background = MaterialTheme.colorScheme.surfaceVariant
    val axis = MaterialTheme.colorScheme.outline
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    return TimelineStyle(
        background = background.toArgb(),
        axis = axis.toArgb(),
        label = label.toArgb(),
        channel37 = TimelineColors.CHANNEL_37.toArgb(),
        channel38 = TimelineColors.CHANNEL_38.toArgb(),
        channel39 = TimelineColors.CHANNEL_39.toArgb(),
        unknownChannel = TimelineColors.UNKNOWN.toArgb(),
        error = TimelineColors.ERROR.toArgb(),
        scale = scale,
    )
}

/** Fixed light palette for exported images, which are viewed outside the app. */
fun exportStyle(): TimelineStyle = TimelineStyle(
    background = Color.White.toArgb(),
    axis = Color(0xFF9AA0A6).toArgb(),
    label = Color(0xFF202124).toArgb(),
    channel37 = TimelineColors.CHANNEL_37.toArgb(),
    channel38 = TimelineColors.CHANNEL_38.toArgb(),
    channel39 = TimelineColors.CHANNEL_39.toArgb(),
    unknownChannel = TimelineColors.UNKNOWN.toArgb(),
    error = TimelineColors.ERROR.toArgb(),
    scale = 3f,
)

/**
 * Packet activity over time for the device being inspected.
 *
 * The chart re-reads the clock every second so the window keeps sliding while a
 * capture runs, rather than only moving when a packet happens to arrive.
 */
@Composable
fun TimelineChart(
    points: List<TimelinePoint>,
    scale: TimelineScale,
    atLastData: Boolean,
    onScale: (TimelineScale) -> Unit,
    onAtLastData: (Boolean) -> Unit,
    onSavePng: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Redraw once per column rather than once per second. At the ten minute
    // scale a column is ten seconds wide, and re-rendering every second slid the
    // window by a fraction of a column each time, shuffling packets between
    // neighbouring columns so the bars jittered in height instead of simply
    // marching left.
    val tick by rememberTicker(scale.binMs.coerceIn(250L, 10_000L))

    val newest = points.maxOfOrNull { it.timestampMs }

    // Snapping the right-hand edge to a column boundary is what actually fixes
    // it: within one column the binning is identical, so an extra redraw cannot
    // change any bar. Heights hold steady and the chart shifts a whole column at
    // a time.
    val anchorMs = if (atLastData && newest != null) newest else tick
    val endMs = ((anchorMs / scale.binMs) + 1) * scale.binMs

    val style = rememberTimelineStyle(scale = 1f)
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
        ) {
            drawContext.canvas.nativeCanvas.let { canvas ->
                TimelineRenderer.draw(
                    canvas = canvas,
                    width = size.width,
                    height = size.height,
                    points = points,
                    scale = scale,
                    endMs = endMs,
                    style = style.copy(scale = density),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (option in TimelineScale.entries) {
                FilterChip(
                    selected = option == scale,
                    onClick = { onScale(option) },
                    label = { Text(option.label, style = MaterialTheme.typography.labelSmall) },
                )
            }

            // Pins the right-hand edge to the newest packet instead of to the
            // clock. Off to begin with, because a live capture wants the window
            // to keep sliding whether or not anything is arriving - a chart that
            // stops moving is how you can tell a device has gone quiet. It earns
            // its place afterwards: once advertising stops, or the capture does,
            // the sliding window walks off the end of the data and leaves an
            // empty chart where the packets used to be.
            FilterChip(
                selected = atLastData,
                enabled = newest != null,
                onClick = { onAtLastData(!atLastData) },
                label = { Text("Last data", style = MaterialTheme.typography.labelSmall) },
            )

            // The same save icon the main screen uses for exporting a session,
            // so "save this" looks the same everywhere in the app. Hiding the
            // chart lives in the group header, next to where it is turned on.
            IconButton(onClick = onSavePng) {
                Icon(
                    imageVector = Icons.Filled.FileDownload,
                    contentDescription = "Save chart as PNG",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
