package studio.apps.irontide

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.floor
import kotlin.math.sin

// Where the 10x10 grid sits inside a square canvas, leaving a gutter on the
// top and left for the coordinate labels.
class Geo(val left: Float, val top: Float, val cell: Float) {
    fun point(u: Float, v: Float) = Offset(left + u * cell, top + v * cell)
    fun center(c: Cell) = point(c.col + 0.5f, c.row + 0.5f)

    fun cellAt(p: Offset): Cell? {
        val c = Cell(floor((p.y - top) / cell).toInt(), floor((p.x - left) / cell).toInt())
        return if (c.inBounds) c else null
    }

    fun cellAtClamped(p: Offset): Cell = Cell(
        floor((p.y - top) / cell).toInt().coerceIn(0, SIZE - 1),
        floor((p.x - left) / cell).toInt().coerceIn(0, SIZE - 1),
    )

    companion object {
        fun of(width: Float, height: Float): Geo {
            val side = minOf(width, height)
            val gutter = side * 0.065f
            val cell = (side - gutter) / SIZE
            return Geo((width - side) / 2 + gutter, (height - side) / 2 + gutter, cell)
        }
    }
}

object Palette {
    val bg = Color(0xFF041320)
    val panel = Color(0xFF0B2338)
    val panelEdge = Color(0xFF1C3D58)
    val text = Color(0xFFE4F0FA)
    val dim = Color(0xFF7D9AB3)
    val amber = Color(0xFFFFB648)
    val red = Color(0xFFFF5B3D)
    val green = Color(0xFF5EE08A)
    val deep = Color(0xFF05243F)
    val shallow = Color(0xFF0B4772)
}

private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
}

fun DrawScope.drawOcean(geo: Geo, t: Float, glow: Color?) {
    val c = geo.cell
    val w = c * SIZE
    val tl = Offset(geo.left, geo.top)
    drawRect(
        Brush.linearGradient(listOf(Palette.shallow, Palette.deep), tl, Offset(geo.left + w, geo.top + w)),
        tl, Size(w, w),
    )

    // Slow swells drifting across the water.
    val rows = 14
    for (k in 0 until rows) {
        val y0 = geo.top + (k + 0.5f) * w / rows
        val path = Path()
        var x = 0f
        val step = c * 0.25f
        path.moveTo(geo.left, y0 + sin(t * 0.7f + k * 1.7f) * c * 0.06f)
        while (x <= w) {
            x += step
            val y = y0 + sin(x / c * 2.6f + t * 0.9f + k * 1.7f) * c * 0.06f
            path.lineTo(geo.left + minOf(x, w), y)
        }
        drawPath(path, Color.White, alpha = 0.045f + 0.02f * sin(t * 0.5f + k), style = Stroke(width = c * 0.035f))
    }

    // Grid lines.
    for (i in 0..SIZE) {
        val a = if (i == 0 || i == SIZE) 0.45f else 0.18f
        drawLine(Color(0xFFA9D9F5), Offset(geo.left + i * c, geo.top), Offset(geo.left + i * c, geo.top + w), 1.2f, alpha = a)
        drawLine(Color(0xFFA9D9F5), Offset(geo.left, geo.top + i * c), Offset(geo.left + w, geo.top + i * c), 1.2f, alpha = a)
    }

    if (glow != null) {
        val pulse = 0.55f + 0.45f * sin(t * 3.2f)
        drawRect(glow, tl, Size(w, w), alpha = 0.35f + 0.4f * pulse, style = Stroke(width = c * 0.08f))
    }

    // A1..J10 labels.
    drawIntoCanvas { canvas ->
        labelPaint.textSize = c * 0.42f
        labelPaint.color = Palette.dim.toArgb()
        val gutter = c * 0.65f
        for (i in 0 until SIZE) {
            canvas.nativeCanvas.drawText("${i + 1}", geo.left + (i + 0.5f) * c, geo.top - gutter * 0.3f, labelPaint)
            canvas.nativeCanvas.drawText("${'A' + i}", geo.left - gutter * 0.5f, geo.top + (i + 0.5f) * c + c * 0.15f, labelPaint)
        }
    }
}

// A miss: a ring of foam and a buoy-like dot, with a ripple that keeps
// spreading from it.
fun DrawScope.drawMiss(geo: Geo, cell: Cell, t: Float) {
    val c = geo.cell
    val at = geo.center(cell)
    val ph = ((t * 0.45f + (cell.index * 0.137f)) % 1f)
    drawCircle(Color.White, c * (0.2f + 0.26f * ph), at, alpha = 0.3f * (1f - ph), style = Stroke(c * 0.04f))
    drawCircle(Color.White, c * 0.2f, at, alpha = 0.65f, style = Stroke(c * 0.05f))
    drawCircle(Color(0xFFE6F7FF), c * 0.07f, at, alpha = 0.9f)
}

// A hit: a scorch mark with flames dancing on it. Smoldering wrecks burn lower.
fun DrawScope.drawFire(geo: Geo, cell: Cell, t: Float, strength: Float) {
    val c = geo.cell
    val at = geo.center(cell)
    val seed = cell.index * 1.31f
    drawCircle(Color.Black, c * 0.3f, at, alpha = 0.35f)
    val glow = c * 0.48f * (0.9f + 0.1f * sin(t * 9f + seed))
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFF7A22).copy(alpha = 0.7f * strength), Color(0x00FF3A10)), at, glow),
        glow, at, blendMode = BlendMode.Plus,
    )
    for (k in 0 until 3) {
        val fx = at.x + sin(t * 3.1f + seed + k * 2.1f) * c * 0.09f + (k - 1) * c * 0.1f
        val fy = at.y - k * c * 0.04f + sin(t * 4.3f + seed * 0.7f + k) * c * 0.04f
        val r = c * strength * (0.13f + 0.05f * sin(t * 11f + seed + k * 2.3f))
        val p = Offset(fx, fy)
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFFFF3B0), Color(0xFFFF9A2E), Color(0x00E8341A)), p, r * 1.6f),
            r * 1.6f, p, blendMode = BlendMode.Plus,
        )
    }
}

enum class HullStyle { AFLOAT, WRECK, GHOST_OK, GHOST_BAD, REVEALED }

// Draws a ship in its cells. The art is laid out bow-right in a frame one
// cell thick, then turned for a ship that runs down the grid.
fun DrawScope.drawShip(geo: Geo, ship: Ship, style: HullStyle) {
    val c = geo.cell
    val left = geo.left + ship.col * c
    val top = geo.top + ship.row * c
    withTransform({
        if (ship.horizontal) {
            translate(left, top)
        } else {
            translate(left + c, top)
            rotate(90f, pivot = Offset.Zero)
        }
    }) {
        drawHull(ship.type, c, style)
    }
}

// The same hull art at any size, bow to the right, for the ship tray.
fun DrawScope.drawHullIcon(type: ShipType, style: HullStyle) {
    val c = minOf(size.width / type.length, size.height)
    translate((size.width - c * type.length) / 2, (size.height - c) / 2) {
        drawHull(type, c, style)
    }
}

private fun DrawScope.drawHull(type: ShipType, c: Float, style: HullStyle) {
    val w = c * type.length
    val sub = type == ShipType.SUBMARINE
    val m = c * (if (sub) 0.24f else 0.13f)
    val top = m
    val bottom = c - m
    val mid = c / 2
    val x0 = c * 0.1f
    val r = (bottom - top) * (if (sub) 0.5f else 0.32f)

    val hull = Path().apply {
        moveTo(x0 + r, top)
        lineTo(w - c * 0.95f, top)
        quadraticTo(w - c * 0.25f, top, w - c * 0.06f, mid)
        quadraticTo(w - c * 0.25f, bottom, w - c * 0.95f, bottom)
        lineTo(x0 + r, bottom)
        quadraticTo(x0, bottom, x0, bottom - r)
        lineTo(x0, top + r)
        quadraticTo(x0, top, x0 + r, top)
        close()
    }

    when (style) {
        HullStyle.GHOST_OK, HullStyle.GHOST_BAD -> {
            val tint = if (style == HullStyle.GHOST_OK) Palette.green else Palette.red
            drawPath(hull, tint, alpha = 0.45f)
            drawPath(hull, tint, alpha = 0.9f, style = Stroke(c * 0.05f))
            return
        }
        else -> Unit
    }

    val wreck = style == HullStyle.WRECK
    val alpha = when (style) {
        HullStyle.WRECK -> 0.9f
        HullStyle.REVEALED -> 0.6f
        else -> 1f
    }
    val (light, dark) = when {
        wreck -> Color(0xFF4A3F38) to Color(0xFF1C1715)
        sub -> Color(0xFF55636F) to Color(0xFF26313A)
        else -> Color(0xFFA7B6C2) to Color(0xFF5B6976)
    }
    val edge = if (wreck) Color(0xFF0B0908) else Color(0xFF26313A)
    val detail = if (wreck) Color(0xFF2B2420) else Color(0xFF6E7C88)
    val metal = if (wreck) Color(0xFF3A302A) else Color(0xFFC9D4DD)

    // A soft shadow on the water, then the hull.
    translate(c * 0.05f, c * 0.07f) { drawPath(hull, Color.Black, alpha = 0.3f * alpha) }
    drawPath(hull, Brush.verticalGradient(listOf(light, dark), top, bottom), alpha = alpha)
    drawPath(hull, edge, alpha = alpha, style = Stroke(c * 0.035f))

    fun turret(x: Float, aft: Boolean, twin: Boolean = true) {
        val dir = if (aft) -1f else 1f
        val len = c * 0.34f
        val gap = c * 0.06f
        for (k in if (twin) listOf(-1f, 1f) else listOf(0f)) {
            drawLine(
                edge, Offset(x, mid + k * gap), Offset(x + dir * len, mid + k * gap),
                strokeWidth = c * 0.05f, cap = StrokeCap.Round, alpha = alpha,
            )
        }
        drawCircle(detail, c * 0.15f, Offset(x, mid), alpha = alpha)
        drawCircle(edge, c * 0.15f, Offset(x, mid), alpha = alpha, style = Stroke(c * 0.025f))
        drawCircle(metal, c * 0.05f, Offset(x - dir * c * 0.03f, mid - c * 0.03f), alpha = alpha * 0.8f)
    }

    fun block(x1: Float, x2: Float, half: Float) {
        drawRect(detail, Offset(x1, mid - half), Size(x2 - x1, half * 2), alpha = alpha)
        drawRect(metal, Offset(x1 + c * 0.04f, mid - half + c * 0.03f), Size((x2 - x1) * 0.5f, half * 0.6f), alpha = alpha * 0.6f)
        drawRect(edge, Offset(x1, mid - half), Size(x2 - x1, half * 2), alpha = alpha, style = Stroke(c * 0.02f))
    }

    when (type) {
        ShipType.CARRIER -> {
            // A flat flight deck with a dashed runway and the island to one side.
            val deckTop = top + c * 0.05f
            val deckH = bottom - top - c * 0.1f
            drawRect(Color(0xFF3B454E).copy(alpha = if (wreck) 0.5f else 1f), Offset(c * 0.35f, deckTop), Size(w - c * 1.35f, deckH), alpha = alpha)
            var x = c * 0.5f
            while (x < w - c * 1.2f) {
                drawLine(metal, Offset(x, mid), Offset(x + c * 0.22f, mid), strokeWidth = c * 0.04f, alpha = alpha * 0.8f)
                x += c * 0.4f
            }
            drawLine(Palette.amber, Offset(c * 0.4f, mid + c * 0.18f), Offset(w * 0.45f, mid + c * 0.18f), strokeWidth = c * 0.02f, alpha = alpha * 0.6f)
            drawRect(detail, Offset(w * 0.56f, bottom - c * 0.24f), Size(c * 0.62f, c * 0.18f), alpha = alpha)
            drawRect(edge, Offset(w * 0.56f, bottom - c * 0.24f), Size(c * 0.62f, c * 0.18f), alpha = alpha, style = Stroke(c * 0.02f))
        }
        ShipType.DREADNOUGHT -> {
            turret(c * 0.62f, aft = true)
            turret(c * 1.25f, aft = true)
            block(c * 1.7f, c * 2.55f, c * 0.17f)
            drawCircle(edge, c * 0.08f, Offset(c * 2.25f, mid), alpha = alpha)
            turret(c * 3.0f, aft = false)
        }
        ShipType.CRUISER -> {
            turret(c * 0.6f, aft = true)
            block(c * 1.05f, c * 1.75f, c * 0.15f)
            drawCircle(edge, c * 0.07f, Offset(c * 1.5f, mid), alpha = alpha)
            turret(c * 2.15f, aft = false)
        }
        ShipType.SUBMARINE -> {
            drawLine(metal, Offset(c * 0.4f, mid), Offset(w - c * 0.4f, mid), strokeWidth = c * 0.025f, alpha = alpha * 0.5f)
            val sail = Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        c * 1.55f, mid - c * 0.09f, c * 2.1f, mid + c * 0.09f,
                        androidx.compose.ui.geometry.CornerRadius(c * 0.09f),
                    ),
                )
            }
            drawPath(sail, Color(0xFF1B232A), alpha = alpha)
            drawPath(sail, metal, alpha = alpha * 0.4f, style = Stroke(c * 0.02f))
        }
        ShipType.DESTROYER -> {
            block(c * 0.45f, c * 0.95f, c * 0.13f)
            drawCircle(edge, c * 0.06f, Offset(c * 0.8f, mid), alpha = alpha)
            turret(c * 1.35f, aft = false, twin = false)
        }
    }
}
