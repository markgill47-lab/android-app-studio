package studio.apps.swappuzzle

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class GlyphShape { CIRCLE, SQUARE, TRIANGLE, DIAMOND, PENTAGON, HEXAGON, STAR, PLUS, HEART }

// Each glyph pairs one shape with one color, so no two glyphs share either.
data class Glyph(val shape: GlyphShape, val color: Color) {
    companion object {
        private val colors = listOf(
            Color(0xFFE53935), // red
            Color(0xFFFB8C00), // orange
            Color(0xFFFDD835), // yellow
            Color(0xFF43A047), // green
            Color(0xFF00ACC1), // teal
            Color(0xFF1E88E5), // blue
            Color(0xFF8E24AA), // purple
            Color(0xFFEC407A), // pink
            Color(0xFF6D4C41), // brown
        )

        val all: List<Glyph> = GlyphShape.entries.zip(colors) { shape, color -> Glyph(shape, color) }
    }
}

fun DrawScope.drawGlyph(glyph: Glyph) {
    val r = size.minDimension / 2f
    val c = center
    val color = glyph.color
    when (glyph.shape) {
        GlyphShape.CIRCLE -> drawCircle(color, r * 0.9f, c)
        GlyphShape.SQUARE -> drawRect(
            color,
            topLeft = Offset(c.x - r * 0.8f, c.y - r * 0.8f),
            size = Size(r * 1.6f, r * 1.6f),
        )
        // A triangle inscribed in a circle sits high, so nudge it down.
        GlyphShape.TRIANGLE -> drawPath(polygon(Offset(c.x, c.y + r * 0.2f), r * 1.05f, 3, -90f), color)
        GlyphShape.DIAMOND -> drawPath(polygon(c, r, 4, -90f), color)
        GlyphShape.PENTAGON -> drawPath(polygon(Offset(c.x, c.y + r * 0.05f), r, 5, -90f), color)
        GlyphShape.HEXAGON -> drawPath(polygon(c, r * 0.95f, 6, 0f), color)
        GlyphShape.STAR -> drawPath(star(Offset(c.x, c.y + r * 0.08f), r, r * 0.45f), color)
        GlyphShape.PLUS -> {
            val arm = r * 0.32f
            drawRect(color, Offset(c.x - arm, c.y - r * 0.9f), Size(arm * 2, r * 1.8f))
            drawRect(color, Offset(c.x - r * 0.9f, c.y - arm), Size(r * 1.8f, arm * 2))
        }
        GlyphShape.HEART -> drawPath(heart(c, r), color)
    }
}

private fun pointOn(center: Offset, radius: Float, degrees: Float): Offset {
    val rad = degrees * PI.toFloat() / 180f
    return Offset(center.x + radius * cos(rad), center.y + radius * sin(rad))
}

private fun polygon(center: Offset, radius: Float, sides: Int, startDegrees: Float): Path {
    val path = Path()
    for (i in 0 until sides) {
        val p = pointOn(center, radius, startDegrees + 360f * i / sides)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    return path
}

private fun star(center: Offset, outer: Float, inner: Float): Path {
    val path = Path()
    for (i in 0 until 10) {
        val p = pointOn(center, if (i % 2 == 0) outer else inner, -90f + 36f * i)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    return path
}

private fun heart(center: Offset, r: Float): Path {
    // Drawn in a unit box, then scaled to the tile.
    val left = center.x - r
    val top = center.y - r
    val s = r * 2
    fun x(u: Float) = left + u * s
    fun y(v: Float) = top + v * s
    return Path().apply {
        moveTo(x(0.5f), y(0.92f))
        cubicTo(x(0.12f), y(0.66f), x(0.0f), y(0.42f), x(0.08f), y(0.26f))
        cubicTo(x(0.18f), y(0.06f), x(0.44f), y(0.08f), x(0.5f), y(0.28f))
        cubicTo(x(0.56f), y(0.08f), x(0.82f), y(0.06f), x(0.92f), y(0.26f))
        cubicTo(x(1.0f), y(0.42f), x(0.88f), y(0.66f), x(0.5f), y(0.92f))
        close()
    }
}
