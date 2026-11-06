package studio.apps.emberonyx

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

object Palette {
    val bgTop = Color(0xFF123228)
    val bgBottom = Color(0xFF06150F)
    val panel = Color(0xFF14261F)
    val panelEdge = Color(0xFF2C4339)
    val text = Color(0xFFEFE7D6)
    val dim = Color(0xFF9AAEA4)
    val brass = Color(0xFFE2B45C)
    val ember = Color(0xFFE8432A)
    val emberLight = Color(0xFFFF9A6B)
    val onyx = Color(0xFF22262B)
    val silver = Color(0xFFC9D2DA)
    val backing = Color(0xFF09170F)
    val woodLight = Color(0xFF7A4B2A)
    val woodDark = Color(0xFF452713)
}

// Where everything sits on the board canvas, in pixels. The board is seven
// cells wide and six tall inside a wooden frame, with a row of space above
// it for the piece being held.
class Geo private constructor(val width: Float, val height: Float) {
    val cell = min(width / 7.7f, height / 8.15f)
    val frame = cell * 0.35f
    val gridLeft = (width - COLS * cell) / 2f
    private val totalHeight = cell * 1.2f + ROWS * cell + 2 * frame + cell * 0.3f
    val gridTop = (height - totalHeight) / 2f + cell * 1.2f + frame
    val frameRect = Rect(gridLeft - frame, gridTop - frame, gridLeft + COLS * cell + frame, gridTop + ROWS * cell + frame)

    // Centre of a column (fractional for gliding pieces) and of a height in
    // cells down from the top row.
    fun x(col: Float) = gridLeft + (col + 0.5f) * cell
    fun y(down: Float) = gridTop + (down + 0.5f) * cell

    fun colAt(px: Float): Int = floor((px - gridLeft) / cell).toInt().coerceIn(0, COLS - 1)

    // The wooden front with a hole for every cell, and its grain lines.
    val front: Path = Path().apply {
        fillType = PathFillType.EvenOdd
        addRoundRect(RoundRect(frameRect, CornerRadius(cell * 0.28f)))
        for (c in 0 until COLS) for (r in 0 until ROWS) {
            val cx = x(c.toFloat())
            val cy = y(r.toFloat())
            val h = cell * HOLE
            addOval(Rect(cx - h, cy - h, cx + h, cy + h))
        }
    }

    val grain: List<Path> = run {
        val lines = ArrayList<Path>()
        val n = 34
        val span = frameRect.height / n
        for (i in 0..n) {
            val p = Path()
            val base = frameRect.top + i * span
            val seed = i * 1.7f
            var x = frameRect.left
            p.moveTo(x, base)
            while (x <= frameRect.right) {
                x += cell * 0.25f
                val wave = sin(x / cell * 0.9f + seed) * span * 0.35f + sin(x / cell * 2.3f + seed * 2f) * span * 0.12f
                p.lineTo(x, base + wave)
            }
            lines.add(p)
        }
        lines
    }

    companion object {
        const val HOLE = 0.43f
        const val PIECE = 0.4f
        private var cached: Geo? = null

        fun of(width: Float, height: Float): Geo {
            val c = cached
            if (c != null && c.width == width && c.height == height) return c
            return Geo(width, height).also { cached = it }
        }
    }
}

fun DrawScope.drawPiece(side: Int, cx: Float, cy: Float, r: Float, alpha: Float = 1f) {
    val center = Offset(cx, cy)
    if (side == EMBER) {
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFFFFA676), Color(0xFFE8432A), Color(0xFFA3170F), Color(0xFF560905)),
                center = Offset(cx - r * 0.3f, cy - r * 0.35f),
                radius = r * 1.45f,
            ),
            r, center, alpha,
        )
        // The ember's glow, deep in the glass.
        drawCircle(
            Brush.radialGradient(listOf(Color(0x66FFC27A), Color(0x00FF7A3D)), center, r * 0.6f),
            r * 0.6f, center, alpha,
        )
        drawCircle(Color(0xFF6E0E08), r * 0.68f, center, alpha * 0.45f, style = Stroke(r * 0.09f))
        drawCircle(Color(0xFFFFB38A), r * 0.95f, center, alpha * 0.25f, style = Stroke(r * 0.05f))
    } else {
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF737A82), Color(0xFF2B2F35), Color(0xFF111316), Color(0xFF040405)),
                center = Offset(cx - r * 0.3f, cy - r * 0.35f),
                radius = r * 1.45f,
            ),
            r, center, alpha,
        )
        drawCircle(Color.White, r * 0.68f, center, alpha * 0.12f, style = Stroke(r * 0.09f))
        drawCircle(Palette.silver, r * 0.95f, center, alpha * 0.45f, style = Stroke(r * 0.06f))
    }
    // The gloss.
    drawOval(
        Color.White,
        Offset(cx - r * 0.62f, cy - r * 0.7f),
        Size(r * 0.62f, r * 0.34f),
        alpha = alpha * (if (side == EMBER) 0.38f else 0.3f),
    )
}

fun DrawScope.drawBoard(m: Match, geo: Geo) {
    val c = geo.cell
    val pr = c * Geo.PIECE
    val fr = geo.frameRect

    // The dark backing seen through the holes.
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF0E2219), Palette.backing), fr.top, fr.bottom),
        Offset(fr.left + geo.frame * 0.5f, fr.top + geo.frame * 0.5f),
        Size(fr.width - geo.frame, fr.height - geo.frame),
        CornerRadius(c * 0.2f),
    )

    // Pieces sit behind the wooden front.
    val f = m.falling
    for (col in 0 until COLS) for (row in 0 until m.board.height(col)) {
        if (f != null && f.col == col && f.row == row) continue
        drawPiece(m.board.at(col, row), geo.x(col.toFloat()), geo.y((ROWS - 1 - row).toFloat()), pr)
    }
    if (f != null) drawPiece(f.side, geo.x(f.col.toFloat()), geo.y(f.y), pr)
    for (t in m.tumblers) drawPiece(t.side, geo.x(t.col.toFloat()), geo.y(t.y), pr)

    // The walnut front, with its grain and a bevel on every hole.
    drawPath(geo.front, Brush.verticalGradient(listOf(Palette.woodLight, Palette.woodDark), fr.top, fr.bottom))
    clipPath(geo.front) {
        for ((i, line) in geo.grain.withIndex()) {
            drawPath(
                line,
                if (i % 3 == 0) Color(0xFFB07A4A) else Color(0xFF2A1408),
                alpha = if (i % 3 == 0) 0.12f else 0.22f,
                style = Stroke(c * (0.02f + 0.015f * (i % 2))),
            )
        }
    }
    for (col in 0 until COLS) for (row in 0 until ROWS) {
        val cx = geo.x(col.toFloat())
        val cy = geo.y(row.toFloat())
        drawCircle(Color.Black, c * (Geo.HOLE + 0.01f), Offset(cx, cy), 0.38f, style = Stroke(c * 0.05f))
        drawCircle(Color(0xFFE0A66C), c * (Geo.HOLE + 0.035f), Offset(cx + c * 0.012f, cy + c * 0.018f), 0.14f, style = Stroke(c * 0.025f))
    }
    drawRoundRect(
        Color(0xFF2A1408),
        Offset(fr.left, fr.top),
        Size(fr.width, fr.height),
        CornerRadius(c * 0.28f),
        alpha = 0.8f,
        style = Stroke(c * 0.05f),
    )
    // Brass studs in the corners, and two brass feet.
    val inset = geo.frame * 0.5f
    for (p in listOf(
        Offset(fr.left + inset, fr.top + inset), Offset(fr.right - inset, fr.top + inset),
        Offset(fr.left + inset, fr.bottom - inset), Offset(fr.right - inset, fr.bottom - inset),
    )) {
        drawCircle(Palette.brass, c * 0.07f, p)
        drawCircle(Color.White, c * 0.025f, p + Offset(-c * 0.02f, -c * 0.02f), 0.5f)
    }
    for (side in listOf(-1, 1)) {
        val fx = fr.center.x + side * fr.width * 0.36f
        drawRoundRect(
            Brush.verticalGradient(listOf(Palette.brass, Color(0xFF8C6526)), fr.bottom, fr.bottom + c * 0.28f),
            Offset(fx - c * 0.5f, fr.bottom - c * 0.02f),
            Size(c, c * 0.28f),
            CornerRadius(c * 0.08f),
        )
    }

    // A last-move mark, so it is clear where the other side just played.
    m.last?.let { s ->
        if (m.falling == null && m.phase != Phase.OVER) {
            drawCircle(Palette.brass, c * 0.07f, Offset(geo.x(s.col.toFloat()), geo.y((ROWS - 1 - s.row).toFloat())), 0.85f)
        }
    }

    // A column that is already full flashes when the player tries it.
    val since = m.time - m.refusedAt
    if (since < 0.45f && m.refusedCol >= 0) {
        drawRect(
            Palette.ember,
            Offset(geo.x(m.refusedCol.toFloat()) - c * 0.5f, geo.gridTop),
            Size(c, ROWS * c),
            alpha = 0.3f * (1f - since / 0.45f),
        )
    }

    // While the player aims, light the column the piece will go down.
    if (m.phase == Phase.PLAYER && m.touching) {
        drawRect(
            Color.White,
            Offset(geo.x(m.holdX) - c * 0.5f, geo.gridTop - geo.frame * 0.6f),
            Size(c, ROWS * c + geo.frame * 1.2f),
            alpha = 0.07f,
        )
    }

    drawWin(m, geo)

    // The held pieces above the board.
    val bob = sin(m.time * 3.2f) * c * 0.04f
    if (m.phase == Phase.PLAYER) {
        drawPiece(EMBER, geo.x(m.holdX), geo.y(HOLD_Y) + bob, pr)
    }
    if (m.phase == Phase.THINKING) {
        drawPiece(ONYX, geo.x(m.onyxX), geo.y(HOLD_Y) + bob, pr)
    }

    for (s in m.sparks) {
        val life = 1f - s.age / s.life
        val color = if (s.warm) {
            if (s.life > 1.2f) Color(0xFFFFD27A) else Color(0xFFFF7A3D)
        } else {
            Palette.silver
        }
        drawCircle(color, c * (0.03f + 0.05f * life), Offset(geo.x(s.x), geo.y(s.y)), life)
    }
}

private fun DrawScope.drawWin(m: Match, geo: Geo) {
    if (m.winLines.isEmpty()) return
    val c = geo.cell
    val t = m.time - m.winTime
    val color = if (m.winner == EMBER) Color(0xFFFFD27A) else Palette.silver
    val grow = (t / 0.45f).coerceIn(0f, 1f)
    val pulse = 0.65f + 0.35f * sin(m.time * 6f)
    for (spot in m.winLines.flatten().distinct()) {
        val p = Offset(geo.x(spot.col.toFloat()), geo.y((ROWS - 1 - spot.row).toFloat()))
        drawCircle(color, c * 0.47f, p, pulse * grow, style = Stroke(c * 0.06f))
        drawCircle(color, c * 0.55f, p, 0.25f * pulse * grow, style = Stroke(c * 0.09f))
    }
    for (line in m.winLines) {
        val a = line.first()
        val b = line.last()
        val start = Offset(geo.x(a.col.toFloat()), geo.y((ROWS - 1 - a.row).toFloat()))
        val end = Offset(geo.x(b.col.toFloat()), geo.y((ROWS - 1 - b.row).toFloat()))
        val tip = start + (end - start) * grow
        drawLine(color, start, tip, c * 0.34f, StrokeCap.Round, alpha = 0.22f)
        drawLine(color, start, tip, c * 0.13f, StrokeCap.Round, alpha = 0.9f)
    }
}
