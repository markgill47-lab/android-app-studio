package studio.apps.tessera

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object Palette {
    val bgTop = Color(0xFF1C2033)
    val bgBottom = Color(0xFF0B0D16)
    val panel = Color(0xFF1A1E2E)
    val panelEdge = Color(0xFF353B55)
    val text = Color(0xFFEDE6D8)
    val dim = Color(0xFF9EA3BA)
    val gold = Color(0xFFD7B062)
    val slab = Color(0xFF151827)
    val tile = Color(0xFF2A3047)
    val tileDark = Color(0xFF1F2437)
    val sun = Color(0xFFF4A93A)
    val sunLight = Color(0xFFFFDC96)
    val sunGlaze = Color(0xFF6E4316)
    val jade = Color(0xFF3CC9A2)
    val jadeLight = Color(0xFFA8F3DA)
    val jadeGlaze = Color(0xFF15514A)
    val stale = Color(0xFF3A3C44)
}

// Where everything sits on the board canvas, in pixels. Nine small boards,
// three cells each way, with a grout gap between boards and a margin round
// the outside.
class Geo private constructor(val width: Float, val height: Float) {
    val cell = min(width, height) / (9f + 2 * GAP + 2 * PAD)
    private val total = cell * (9f + 2 * GAP + 2 * PAD)
    val left = (width - total) / 2f
    val top = (height - total) / 2f
    val slab = Rect(left, top, left + total, top + total)
    private val stride = cell * (3f + GAP)

    fun boardLeft(bx: Float) = left + PAD * cell + bx * stride
    fun boardTop(by: Float) = top + PAD * cell + by * stride
    fun boardRect(b: Int) = Rect(boardLeft((b % 3).toFloat()), boardTop((b / 3).toFloat()), boardLeft((b % 3).toFloat()) + 3 * cell, boardTop((b / 3).toFloat()) + 3 * cell)
    fun boardCenter(b: Int) = boardRect(b).center

    fun cellCenter(b: Int, c: Int) = Offset(
        boardLeft((b % 3).toFloat()) + (c % 3 + 0.5f) * cell,
        boardTop((b / 3).toFloat()) + (c / 3 + 0.5f) * cell,
    )

    // Which board and cell a touch lands in, along one axis. A touch in the
    // grout between two boards goes to the nearer one.
    private fun axis(v: Float, origin: Float): Pair<Int, Int>? {
        val local = v - origin - PAD * cell
        if (local < -PAD * cell || local > 3 * stride - GAP * cell + PAD * cell) return null
        var b = floor(local / stride).toInt().coerceIn(0, 2)
        var within = local - b * stride
        if (within > 3 * cell) {
            if (within > 3 * cell + GAP * cell / 2 && b < 2) {
                b++
                within = 0f
            } else {
                within = 3 * cell - 0.01f
            }
        }
        val c = floor(within / cell).toInt().coerceIn(0, 2)
        return b to c
    }

    fun hit(x: Float, y: Float): Int {
        val (bx, cx) = axis(x, left) ?: return -1
        val (by, cy) = axis(y, top) ?: return -1
        return (by * 3 + bx) * 9 + (cy * 3 + cx)
    }

    // Small chips of stone over the slab, for a mosaic texture, gathered
    // into one path per colour so they draw in three calls.
    class Chips(val color: Color, val alpha: Float, val path: Path)

    val chips: List<Chips> = run {
        val rng = Random(7)
        val gold = Path()
        val light = Path()
        val dark = Path()
        val step = cell * 0.36f
        val corner = CornerRadius(cell * 0.04f)
        var y = slab.top + step * 0.2f
        var row = 0
        while (y < slab.bottom - step * 0.5f) {
            var x = slab.left + step * 0.2f + (if (row % 2 == 0) 0f else step * 0.5f)
            while (x < slab.right - step * 0.5f) {
                val w = step * (0.72f + rng.nextFloat() * 0.18f)
                val h = step * (0.72f + rng.nextFloat() * 0.18f)
                val shade = rng.nextFloat()
                val target = when {
                    shade < 0.08f -> gold
                    shade < 0.55f -> light
                    else -> dark
                }
                target.addRoundRect(RoundRect(Rect(x, y, x + w, y + h), corner))
                x += step
            }
            y += step
            row++
        }
        listOf(
            Chips(Color(0xFF0D0F1A), 0.55f, dark),
            Chips(Color(0xFF2B3150), 0.55f, light),
            Chips(Palette.gold, 0.12f, gold),
        )
    }

    companion object {
        const val GAP = 0.34f
        const val PAD = 0.24f
        private var cached: Geo? = null

        fun of(width: Float, height: Float): Geo {
            val c = cached
            if (c != null && c.width == width && c.height == height) return c
            return Geo(width, height).also { cached = it }
        }
    }
}

private fun ease(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return 1f - (1f - x) * (1f - x) * (1f - x)
}

// A cross drawn as two brush strokes, the second after the first.
fun DrawScope.drawCross(center: Offset, r: Float, progress: Float, alpha: Float, width: Float, glow: Boolean = true) {
    if (progress <= 0f || alpha <= 0f) return
    val a = Offset(center.x - r, center.y - r)
    val b = Offset(center.x + r, center.y + r)
    val c = Offset(center.x + r, center.y - r)
    val d = Offset(center.x - r, center.y + r)
    val p1 = ease(progress * 2f)
    val p2 = ease(progress * 2f - 1f)
    fun stroke(from: Offset, to: Offset, p: Float) {
        if (p <= 0f) return
        val tip = from + (to - from) * p
        if (glow) drawLine(Palette.sun, from, tip, width * 2.6f, StrokeCap.Round, alpha = 0.16f * alpha)
        drawLine(Palette.sun, from, tip, width, StrokeCap.Round, alpha = alpha)
        drawLine(Palette.sunLight, from, tip, width * 0.38f, StrokeCap.Round, alpha = 0.75f * alpha)
    }
    stroke(a, b, p1)
    stroke(c, d, p2)
}

// A ring drawn as one sweep from the top.
fun DrawScope.drawRing(center: Offset, r: Float, progress: Float, alpha: Float, width: Float, glow: Boolean = true) {
    if (progress <= 0f || alpha <= 0f) return
    val sweep = 360f * ease(progress)
    val tl = Offset(center.x - r, center.y - r)
    val size = Size(2 * r, 2 * r)
    if (glow) drawArc(Palette.jade, -90f, sweep, false, tl, size, alpha = 0.16f * alpha, style = Stroke(width * 2.6f, cap = StrokeCap.Round))
    drawArc(Palette.jade, -90f, sweep, false, tl, size, alpha = alpha, style = Stroke(width, cap = StrokeCap.Round))
    drawArc(Palette.jadeLight, -90f, sweep, false, tl, size, alpha = 0.7f * alpha, style = Stroke(width * 0.38f, cap = StrokeCap.Round))
}

fun DrawScope.drawMark(side: Int, center: Offset, r: Float, progress: Float, alpha: Float, width: Float, glow: Boolean = true) {
    if (side == SUN) drawCross(center, r * 0.86f, progress, alpha, width, glow) else drawRing(center, r, progress, alpha, width, glow)
}

fun DrawScope.drawBoard(m: Match, geo: Geo) {
    val s = geo.cell
    val g = m.game
    val t = m.time

    // The slab, with its chips of stone.
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF1B1F31), Palette.slab), geo.slab.top, geo.slab.bottom),
        geo.slab.topLeft, geo.slab.size, CornerRadius(s * 0.35f),
    )
    for (chips in geo.chips) drawPath(chips.path, chips.color, alpha = chips.alpha)
    drawRoundRect(Palette.gold, geo.slab.topLeft, geo.slab.size, CornerRadius(s * 0.35f), alpha = 0.35f, style = Stroke(s * 0.04f))

    val clearing = m.phase == Phase.CLEARING
    val shown = if (clearing) m.ghost else g
    val clearT = (t - m.clearAt) / Match.CLEAR_SECONDS
    val freeChoice = (m.phase == Phase.PLAYER || m.phase == Phase.THINKING) && g.forced < 0
    val turnColor = if (m.phase == Phase.THINKING) Palette.jade else Palette.sun
    val pulse = 0.5f + 0.5f * sin(t * 4f)
    val refused = (t - m.refusedAt).let { if (it < 0.6f) 1f - it / 0.6f else 0f }

    for (b in 0 until 9) {
        val rect = geo.boardRect(b)
        val owner = shown.owner[b]
        // How far the sweep has got to this board, when clearing.
        val dist = sqrt(((b % 3 - 1f) * (b % 3 - 1f) + (b / 3 - 1f) * (b / 3 - 1f)).toDouble()).toFloat()
        val sweep = if (clearing) ease((clearT * 1.6f - dist * 0.25f)) else 0f
        val decidedP = if (clearing) 1f else ease((t - m.decidedAt[b]) / 0.5f)

        // The tile, glazed in the owner's colour once it is claimed.
        val glaze = when (owner) {
            SUN -> Palette.sunGlaze
            JADE -> Palette.jadeGlaze
            TIED -> Palette.stale
            else -> Palette.tile
        }
        val tileTop = lerp(Palette.tile, glaze, if (owner == NONE) 0f else decidedP * (1f - sweep))
        val tileBottom = lerp(Palette.tileDark, glaze.copy(alpha = 1f).let { lerp(it, Color.Black, 0.25f) }, if (owner == NONE) 0f else decidedP * (1f - sweep))
        drawRoundRect(Color.Black, rect.topLeft + Offset(0f, s * 0.06f), rect.size, CornerRadius(s * 0.22f), alpha = 0.45f)
        drawRoundRect(
            Brush.verticalGradient(listOf(tileTop, tileBottom), rect.top, rect.bottom),
            rect.topLeft, rect.size, CornerRadius(s * 0.22f),
        )
        drawRoundRect(Color.White, rect.topLeft, rect.size, CornerRadius(s * 0.22f), alpha = 0.06f, style = Stroke(s * 0.03f))

        // Open boards glow when the side to move may pick any of them.
        if (freeChoice && g.owner[b] == NONE) {
            drawRoundRect(turnColor, rect.topLeft, rect.size, CornerRadius(s * 0.22f), alpha = 0.05f + 0.06f * pulse)
            drawRoundRect(turnColor, rect.topLeft, rect.size, CornerRadius(s * 0.22f), alpha = 0.35f + 0.25f * pulse, style = Stroke(s * 0.05f))
        }
        // A tap in the wrong board flashes the boards you may play.
        if (refused > 0f && g.allowed(b)) {
            drawRoundRect(Palette.sun, rect.topLeft, rect.size, CornerRadius(s * 0.22f), alpha = 0.3f * refused)
        }

        // Gold inlay lines between the cells.
        val inset = s * 0.18f
        val lineAlpha = (if (owner != NONE) 1f - 0.6f * decidedP else 1f) * 0.55f
        for (k in 1..2) {
            val x = rect.left + k * s
            val y = rect.top + k * s
            drawLine(Palette.gold, Offset(x, rect.top + inset), Offset(x, rect.bottom - inset), s * 0.035f, StrokeCap.Round, alpha = lineAlpha)
            drawLine(Palette.gold, Offset(rect.left + inset, y), Offset(rect.right - inset, y), s * 0.035f, StrokeCap.Round, alpha = lineAlpha)
        }

        // The small marks. They fade back once the board is decided.
        val markFade = if (owner == NONE) 1f else 1f - 0.72f * decidedP
        for (c in 0 until 9) {
            val side = shown.cell(b, c)
            if (side == NONE) continue
            val center = geo.cellCenter(b, c)
            if (clearing) {
                val k = (1f - sweep)
                if (k <= 0f) continue
                drawMark(side, center, s * 0.3f * k, 1f, markFade * k, s * 0.1f * k, glow = false)
                continue
            }
            val age = t - m.placedAt[b * 9 + c]
            val p = (age / 0.26f).coerceIn(0f, 1f)
            val pop = 1f + 0.18f * sin(PI.toFloat() * p) * (if (p < 1f) 1f else 0f)
            if (m.last == b * 9 + c && owner == NONE) {
                drawCircle(if (side == SUN) Palette.sun else Palette.jade, s * 0.44f, center, alpha = 0.12f)
            }
            drawMark(side, center, s * 0.3f * pop, p, markFade, s * 0.1f)
        }

        // The big mark on a claimed board, and a ripple as it lands.
        if (owner == SUN || owner == JADE) {
            val since = t - m.decidedAt[b]
            val p = if (clearing) 1f else ((since - 0.12f) / 0.5f).coerceIn(0f, 1f)
            val k = 1f - sweep
            drawMark(owner, rect.center, s * 1.08f * k, p, k, s * 0.26f * k)
            if (!clearing && since in 0f..0.9f) {
                val q = since / 0.9f
                val color = if (owner == SUN) Palette.sunLight else Palette.jadeLight
                drawCircle(color, s * (0.6f + 1.6f * q), rect.center, alpha = 0.5f * (1f - q), style = Stroke(s * 0.08f * (1f - q) + 1f))
            }
        } else if (owner == TIED) {
            // A drawn board gets a cracked look: a dim engraved line.
            val p = if (clearing) 1f else ease((t - m.decidedAt[b] - 0.1f) / 0.4f)
            val k = 1f - sweep
            val y = rect.center.y
            val from = Offset(rect.left + s * 0.7f, y)
            val to = Offset(rect.right - s * 0.7f, y)
            drawLine(Color.Black, from, from + (to - from) * p, s * 0.16f * k + 1f, StrokeCap.Round, alpha = 0.35f * k)
            drawLine(Color(0xFF9A9CA8), from, from + (to - from) * p, s * 0.07f * k + 1f, StrokeCap.Round, alpha = 0.6f * k)
        }
    }

    // The frame round the board the side to move must play in.
    if (m.frameShown > 0.01f) {
        val x = geo.boardLeft(m.frameX)
        val y = geo.boardTop(m.frameY)
        val pad = s * 0.12f
        val tl = Offset(x - pad, y - pad)
        val size = Size(3 * s + 2 * pad, 3 * s + 2 * pad)
        val a = m.frameShown
        drawRoundRect(turnColor, tl, size, CornerRadius(s * 0.3f), alpha = a * (0.12f + 0.08f * pulse), style = Stroke(s * 0.32f))
        drawRoundRect(turnColor, tl, size, CornerRadius(s * 0.3f), alpha = a * (0.75f + 0.25f * pulse), style = Stroke(s * 0.07f))
    }

    drawWin(m, geo)

    for (sp in m.sparks) {
        val life = 1f - sp.age / sp.life
        val color = if (sp.side == SUN) {
            if (sp.life > 1.0f) Palette.sunLight else Palette.sun
        } else {
            if (sp.life > 1.0f) Palette.jadeLight else Palette.jade
        }
        val p = Offset(geo.boardLeft(0f) + sp.x / 3f * (geo.boardLeft(2f) + 3 * s - geo.boardLeft(0f)),
            geo.boardTop(0f) + sp.y / 3f * (geo.boardTop(2f) + 3 * s - geo.boardTop(0f)))
        drawCircle(color, s * (0.03f + 0.06f * life), p, life)
    }
}

private fun DrawScope.drawWin(m: Match, geo: Geo) {
    val line = m.winLine
    if (line == 0) return
    val t = m.time - m.winTime
    if (t <= 0f) return
    val s = geo.cell
    val first = Integer.numberOfTrailingZeros(line)
    val last = 31 - Integer.numberOfLeadingZeros(line)
    val a = geo.boardCenter(first)
    val b = geo.boardCenter(last)
    // Run the line a little past the two end boards.
    val dir = (b - a) / (b - a).getDistance()
    val start = a - dir * (s * 1.2f)
    val end = b + dir * (s * 1.2f)
    val grow = ease(t / 0.55f)
    val tip = start + (end - start) * grow
    val pulse = 0.75f + 0.25f * sin(m.time * 5f)
    val color = if (m.game.result == SUN) Palette.sunLight else Palette.jadeLight
    val core = if (m.game.result == SUN) Palette.sun else Palette.jade
    drawLine(core, start, tip, s * 0.9f, StrokeCap.Round, alpha = 0.18f * pulse)
    drawLine(core, start, tip, s * 0.4f, StrokeCap.Round, alpha = 0.85f)
    drawLine(color, start, tip, s * 0.16f, StrokeCap.Round, alpha = pulse)
}
