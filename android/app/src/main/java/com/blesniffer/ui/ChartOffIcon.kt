package com.blesniffer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The "hide the chart" icon: the show icon, struck through.
 *
 * Drawn rather than authored as a separate vector, because a hand-copied
 * duplicate of [Icons.Filled.BarChart] does not stay identical - the first
 * attempt had visibly different bar widths from the icon it is paired with.
 * Painting the real vector and cutting a diagonal band out of it means the bars
 * are the same bars by construction, whatever the icon set does next.
 */
@Composable
fun ChartOffIcon(
    tint: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val barChart = rememberVectorPainter(Icons.Filled.BarChart)

    Canvas(
        modifier = modifier
            .size(24.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        // The icon is authored on a 24x24 grid; everything below is in those
        // units and scaled to whatever size the canvas actually got.
        val unit = size.minDimension / 24f
        fun p(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * unit, y * unit)

        // Band removed from the bars, leaving a gap around the strike.
        val gap = Path().apply {
            moveTo(p(5.06f, 1.94f).x, p(5.06f, 1.94f).y)
            lineTo(p(22.06f, 18.94f).x, p(22.06f, 18.94f).y)
            lineTo(p(18.94f, 22.06f).x, p(18.94f, 22.06f).y)
            lineTo(p(1.94f, 5.06f).x, p(1.94f, 5.06f).y)
            close()
        }

        // The strike itself, a 2 unit wide bar down the same diagonal.
        val strike = Path().apply {
            moveTo(p(4.21f, 2.79f).x, p(4.21f, 2.79f).y)
            lineTo(p(21.21f, 19.79f).x, p(21.21f, 19.79f).y)
            lineTo(p(19.79f, 21.21f).x, p(19.79f, 21.21f).y)
            lineTo(p(2.79f, 4.21f).x, p(2.79f, 4.21f).y)
            close()
        }

        clipPath(gap, clipOp = ClipOp.Difference) {
            with(barChart) {
                draw(size, colorFilter = ColorFilter.tint(tint))
            }
        }
        drawPath(strike, tint)
    }
}
