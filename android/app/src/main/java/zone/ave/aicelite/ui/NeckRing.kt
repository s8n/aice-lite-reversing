package zone.ave.aicelite.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import zone.ave.aicelite.ui.theme.Ink

/**
 * The hero: an open C-ring standing in for the neck cooler, lit in the mode's
 * accent — bright while running, dim when it isn't, so the screen tells you the
 * machine's state from across a room.
 */
@Composable
fun NeckRing(accent: Color, running: Boolean, modifier: Modifier = Modifier) {
    val glowColor by animateColorAsState(if (running) accent else Ink.Tertiary, tween(500), label = "glow")
    val intensity = if (running) 1f else 0.28f

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * 0.34f
        val band = size.minDimension * 0.13f
        val box = Size(radius * 2, radius * 2)
        val topLeft = Offset(cx - radius, cy - radius)

        // ambient bloom behind the ring
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glowColor.copy(alpha = 0.20f * intensity), Color.Transparent),
                center = Offset(cx, cy),
                radius = radius * 2.1f,
            ),
            radius = radius * 2.1f,
            center = Offset(cx, cy),
        )

        rotate(degrees = -18f, pivot = Offset(cx, cy)) {
            // soft halo: a few widening strokes fading out stands in for a blur
            for (i in 3 downTo 1) {
                drawArc(
                    color = glowColor.copy(alpha = 0.07f * intensity * i),
                    startAngle = 120f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = box,
                    style = Stroke(width = band + band * 0.55f * i, cap = StrokeCap.Round),
                )
            }

            // body of the ring, brushed metal
            drawArc(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF2C333D), Color(0xFF767F8C), Color(0xFF1A1F26)),
                    start = Offset(cx - radius, cy - radius),
                    end = Offset(cx + radius, cy + radius),
                ),
                startAngle = 120f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = topLeft,
                size = box,
                style = Stroke(width = band, cap = StrokeCap.Round),
            )

            // the vent that actually glows
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(Color.Transparent, glowColor, glowColor.copy(alpha = 0.15f), Color.Transparent),
                    center = Offset(cx, cy),
                ),
                startAngle = 150f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = topLeft,
                size = box,
                style = Stroke(width = band * 0.34f, cap = StrokeCap.Round),
                alpha = intensity,
            )

            // specular highlight along the outer edge
            drawArc(
                color = Color.White.copy(alpha = 0.16f),
                startAngle = 190f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(topLeft.x, topLeft.y),
                size = box,
                style = Stroke(width = band * 0.16f, cap = StrokeCap.Round),
            )
        }
    }
}
