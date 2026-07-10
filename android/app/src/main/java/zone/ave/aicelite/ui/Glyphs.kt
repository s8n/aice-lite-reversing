package zone.ave.aicelite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-drawn icons. The mode and option icons don't exist in the core Material
 * set, and pulling in `material-icons-extended` for six shapes isn't worth it.
 */
enum class Glyph {
    Cooling, Heating, Wind, Ai,
    Silent, HotPack, CoolingFirst, LowPower,
    Plus, Minus, Gear, ChevronRight, Back,
    Pause, Play,
}

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.085f, cap = StrokeCap.Round)
        when (glyph) {
            Glyph.Cooling -> snowflake(tint, s, stroke)
            Glyph.Heating -> sun(tint, s, stroke)
            Glyph.Wind -> windLines(tint, s, stroke)
            Glyph.Ai -> label(measurer, "PID", tint, s)
            Glyph.Silent -> mutedSpeaker(tint, s, stroke)
            Glyph.HotPack -> hotPack(tint, s, stroke)
            Glyph.CoolingFirst -> thermometerDown(tint, s, stroke)
            Glyph.LowPower -> bolt(tint, s)
            Glyph.Plus -> plus(tint, s, stroke)
            Glyph.Minus -> minus(tint, s, stroke)
            Glyph.Gear -> gear(tint, s, stroke)
            Glyph.ChevronRight -> chevron(tint, s, stroke, pointsRight = true)
            Glyph.Back -> chevron(tint, s, stroke, pointsRight = false)
            Glyph.Pause -> pauseBars(tint, s)
            Glyph.Play -> playTriangle(tint, s)
        }
    }
}

private fun DrawScope.pauseBars(tint: Color, s: Float) {
    val w = s * 0.13f
    val top = s * 0.24f
    val h = s * 0.52f
    drawRoundRect(tint, topLeft = Offset(s * 0.32f - w / 2, top), size = Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2))
    drawRoundRect(tint, topLeft = Offset(s * 0.68f - w / 2, top), size = Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2))
}

private fun DrawScope.playTriangle(tint: Color, s: Float) {
    val path = Path().apply {
        moveTo(s * 0.34f, s * 0.24f)
        lineTo(s * 0.76f, s * 0.5f)
        lineTo(s * 0.34f, s * 0.76f)
        close()
    }
    drawPath(path, tint)
}

private fun DrawScope.plus(tint: Color, s: Float, stroke: Stroke) {
    drawLine(tint, Offset(s * 0.5f, s * 0.18f), Offset(s * 0.5f, s * 0.82f), stroke.width, stroke.cap)
    minus(tint, s, stroke)
}

private fun DrawScope.minus(tint: Color, s: Float, stroke: Stroke) {
    drawLine(tint, Offset(s * 0.18f, s * 0.5f), Offset(s * 0.82f, s * 0.5f), stroke.width, stroke.cap)
}

private fun DrawScope.gear(tint: Color, s: Float, stroke: Stroke) {
    val c = Offset(s / 2, s / 2)
    drawCircle(tint, radius = s * 0.17f, center = c, style = stroke)
    drawCircle(tint, radius = s * 0.34f, center = c, style = Stroke(width = stroke.width * 0.9f))
    repeat(8) { i ->
        val a = Math.toRadians(i * 45.0)
        val from = Offset(c.x + cos(a).toFloat() * s * 0.34f, c.y + sin(a).toFloat() * s * 0.34f)
        val to = Offset(c.x + cos(a).toFloat() * s * 0.46f, c.y + sin(a).toFloat() * s * 0.46f)
        drawLine(tint, from, to, stroke.width * 1.5f, stroke.cap)
    }
}

private fun DrawScope.chevron(tint: Color, s: Float, stroke: Stroke, pointsRight: Boolean) {
    val near = if (pointsRight) s * 0.38f else s * 0.62f
    val far = if (pointsRight) s * 0.62f else s * 0.38f
    drawLine(tint, Offset(near, s * 0.24f), Offset(far, s * 0.5f), stroke.width, stroke.cap)
    drawLine(tint, Offset(far, s * 0.5f), Offset(near, s * 0.76f), stroke.width, stroke.cap)
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, tint: Color, s: Float) {
    // Fit the text to ~82% of the glyph width so multi-letter labels (e.g. "PID") don't clip.
    val base = measurer.measure(text, TextStyle(fontSize = (s * 0.5f).toSp(), fontWeight = FontWeight.SemiBold))
    val scale = minOf(1f, (s * 0.82f) / base.size.width)
    val style = TextStyle(color = tint, fontSize = (s * 0.5f * scale).toSp(), fontWeight = FontWeight.SemiBold)
    val result = measurer.measure(text, style)
    drawText(result, topLeft = Offset((s - result.size.width) / 2f, (s - result.size.height) / 2f))
}

private fun DrawScope.snowflake(tint: Color, s: Float, stroke: Stroke) {
    val c = Offset(s / 2, s / 2)
    val r = s * 0.42f
    val barb = r * 0.3f
    repeat(3) { i ->
        rotate(degrees = i * 60f, pivot = c) {
            drawLine(tint, Offset(c.x, c.y - r), Offset(c.x, c.y + r), stroke.width, stroke.cap)
            for (dir in listOf(-1f, 1f)) {
                val tip = Offset(c.x, c.y + dir * r)
                drawLine(tint, tip, Offset(tip.x - barb * 0.7f, tip.y - dir * barb), stroke.width, stroke.cap)
                drawLine(tint, tip, Offset(tip.x + barb * 0.7f, tip.y - dir * barb), stroke.width, stroke.cap)
            }
        }
    }
}

private fun DrawScope.sun(tint: Color, s: Float, stroke: Stroke) {
    val c = Offset(s / 2, s / 2)
    drawCircle(tint, radius = s * 0.19f, center = c, style = stroke)
    repeat(8) { i ->
        val a = Math.toRadians(i * 45.0)
        val from = Offset(c.x + cos(a).toFloat() * s * 0.30f, c.y + sin(a).toFloat() * s * 0.30f)
        val to = Offset(c.x + cos(a).toFloat() * s * 0.44f, c.y + sin(a).toFloat() * s * 0.44f)
        drawLine(tint, from, to, stroke.width, stroke.cap)
    }
}

private fun DrawScope.windLines(tint: Color, s: Float, stroke: Stroke) {
    listOf(0.62f, 0.78f, 0.55f).forEachIndexed { i, w ->
        val y = s * (0.32f + i * 0.18f)
        val x0 = s * 0.14f
        val x1 = x0 + s * w
        val path = Path().apply {
            moveTo(x0, y)
            cubicTo(x0 + s * 0.2f, y - s * 0.09f, x1 - s * 0.22f, y + s * 0.09f, x1, y)
            cubicTo(x1 + s * 0.10f, y - s * 0.06f, x1 - s * 0.02f, y - s * 0.14f, x1 - s * 0.08f, y - s * 0.06f)
        }
        drawPath(path, tint, style = stroke)
    }
}

private fun DrawScope.mutedSpeaker(tint: Color, s: Float, stroke: Stroke) {
    val path = Path().apply {
        moveTo(s * 0.16f, s * 0.38f)
        lineTo(s * 0.30f, s * 0.38f)
        lineTo(s * 0.48f, s * 0.20f)
        lineTo(s * 0.48f, s * 0.80f)
        lineTo(s * 0.30f, s * 0.62f)
        lineTo(s * 0.16f, s * 0.62f)
        close()
    }
    drawPath(path, tint)
    drawLine(tint, Offset(s * 0.60f, s * 0.38f), Offset(s * 0.86f, s * 0.64f), stroke.width, stroke.cap)
    drawLine(tint, Offset(s * 0.86f, s * 0.38f), Offset(s * 0.60f, s * 0.64f), stroke.width, stroke.cap)
}

private fun DrawScope.hotPack(tint: Color, s: Float, stroke: Stroke) {
    repeat(3) { i ->
        val x = s * (0.26f + i * 0.24f)
        val path = Path().apply {
            moveTo(x, s * 0.62f)
            cubicTo(x - s * 0.12f, s * 0.46f, x + s * 0.12f, s * 0.36f, x, s * 0.16f)
        }
        drawPath(path, tint, style = stroke)
    }
    drawLine(tint, Offset(s * 0.16f, s * 0.82f), Offset(s * 0.84f, s * 0.82f), stroke.width, stroke.cap)
}

private fun DrawScope.thermometerDown(tint: Color, s: Float, stroke: Stroke) {
    val x = s * 0.34f
    drawLine(tint, Offset(x, s * 0.18f), Offset(x, s * 0.58f), stroke.width, stroke.cap)
    drawCircle(tint, radius = s * 0.13f, center = Offset(x, s * 0.72f))
    val ax = s * 0.72f
    drawLine(tint, Offset(ax, s * 0.22f), Offset(ax, s * 0.74f), stroke.width, stroke.cap)
    drawLine(tint, Offset(ax - s * 0.13f, s * 0.60f), Offset(ax, s * 0.76f), stroke.width, stroke.cap)
    drawLine(tint, Offset(ax + s * 0.13f, s * 0.60f), Offset(ax, s * 0.76f), stroke.width, stroke.cap)
}

private fun DrawScope.bolt(tint: Color, s: Float) {
    val path = Path().apply {
        moveTo(s * 0.58f, s * 0.08f)
        lineTo(s * 0.20f, s * 0.56f)
        lineTo(s * 0.45f, s * 0.56f)
        lineTo(s * 0.40f, s * 0.92f)
        lineTo(s * 0.80f, s * 0.42f)
        lineTo(s * 0.54f, s * 0.42f)
        close()
    }
    drawPath(path, tint)
}

/** Drawn rather than imported so its stroke weight matches the glyphs above. */
fun DrawScope.powerSymbol(tint: Color, strokeWidth: Float) {
    val s = size.minDimension
    val c = Offset(size.width / 2, size.height / 2)
    val r = s * 0.30f
    drawArc(
        color = tint,
        startAngle = -60f,
        sweepAngle = 300f,
        useCenter = false,
        topLeft = Offset(c.x - r, c.y - r),
        size = Size(r * 2, r * 2),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
    drawLine(tint, Offset(c.x, c.y - r * 1.15f), Offset(c.x, c.y - r * 0.05f), strokeWidth, StrokeCap.Round)
}
