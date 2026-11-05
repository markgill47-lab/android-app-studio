package studio.apps.irontide

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

// Short-lived animation on one grid: shells coming down, splashes, explosions
// and smoke. Positions are in cell units (column, row), so a layer does not
// care how big the grid is drawn.

enum class Kind { SMOKE, DROP, FIRE, SPARK, RING, FLASH }

class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val drag: Float,
    val life: Float,
    val size: Float,
    val grow: Float,
    val color: Color,
    val color2: Color,
    val kind: Kind,
) {
    var age = 0f
    val p: Float get() = (age / life).coerceIn(0f, 1f)
}

class Shell(val cell: Cell, val duration: Float) {
    var age = 0f
}

class EffectLayer {
    private val particles = ArrayList<Particle>()
    private val shells = ArrayList<Shell>()
    private val pending = ArrayList<Pair<Float, () -> Unit>>()
    private val rng = Random.Default
    var shake = 0f
        private set
    var time = 0f
        private set
    private var smokeClock = 0f

    fun clear() {
        particles.clear()
        shells.clear()
        pending.clear()
        shake = 0f
    }

    fun later(delay: Float, action: () -> Unit) {
        pending += (time + delay) to action
    }

    fun step(dt: Float, burning: List<Cell>) {
        time += dt
        if (pending.isNotEmpty()) {
            val due = pending.filter { it.first <= time }
            pending.removeAll(due.toSet())
            due.forEach { it.second() }
        }
        for (s in shells) s.age += dt
        shells.removeAll { it.age >= it.duration }

        val iter = particles.iterator()
        while (iter.hasNext()) {
            val q = iter.next()
            q.age += dt
            if (q.age >= q.life) {
                iter.remove()
                continue
            }
            val k = exp(-q.drag * dt)
            q.vx *= k
            q.vy *= k
            if (q.kind == Kind.SMOKE) q.vy -= 0.25f * dt
            q.x += q.vx * dt
            q.y += q.vy * dt
        }
        shake *= exp(-7f * dt)

        // Burning cells give off a steady trickle of smoke.
        smokeClock += dt
        while (smokeClock > 0.12f) {
            smokeClock -= 0.12f
            for (c in burning) if (rng.nextFloat() < 0.22f) smokePuff(c.col + 0.5f, c.row + 0.5f, small = true)
        }
    }

    fun incoming(cell: Cell, duration: Float) {
        shells += Shell(cell, duration)
    }

    fun splash(cell: Cell) {
        val cx = cell.col + 0.5f
        val cy = cell.row + 0.5f
        add(cx, cy, 0f, 0f, 0f, 0.28f, 0.35f, 0.8f, Color(0xFFE9FBFF), Color(0x00A6E3FF), Kind.FLASH)
        add(cx, cy, 0f, 0f, 0f, 0.9f, 0.12f, 0.95f, Color(0xFFDDF6FF), Color(0xFF6CC6F0), Kind.RING)
        later(0.16f) { add(cx, cy, 0f, 0f, 0f, 1.25f, 0.1f, 1.3f, Color(0xFFBDEBFF), Color(0xFF3F97C8), Kind.RING) }
        repeat(22) {
            val a = rng.nextFloat() * 2 * PI.toFloat()
            val v = 1.2f + rng.nextFloat() * 2.6f
            add(
                cx, cy, cos(a) * v, sin(a) * v, 3.5f, 0.45f + rng.nextFloat() * 0.45f,
                0.035f + rng.nextFloat() * 0.06f, -0.3f, Color(0xFFFFFFFF), Color(0xFF7FD3F7), Kind.DROP,
            )
        }
        shake += 0.03f
    }

    fun explode(cell: Cell, big: Boolean = false) {
        val cx = cell.col + 0.5f
        val cy = cell.row + 0.5f
        val scale = if (big) 1.5f else 1f
        add(cx, cy, 0f, 0f, 0f, 0.24f, 0.55f * scale, 0.9f, Color(0xFFFFFBE6), Color(0x00FF9A3C), Kind.FLASH)
        add(cx, cy, 0f, 0f, 0f, 0.45f, 0.18f, 1.1f * scale, Color(0xFFFFE2A8), Color(0xFFFF6A2B), Kind.RING)
        repeat(if (big) 26 else 16) {
            val a = rng.nextFloat() * 2 * PI.toFloat()
            val v = (0.6f + rng.nextFloat() * 2.2f) * scale
            add(
                cx, cy, cos(a) * v, sin(a) * v, 4f, 0.35f + rng.nextFloat() * 0.5f,
                0.1f + rng.nextFloat() * 0.16f, 0.7f, Color(0xFFFFE89A), Color(0xFFE2361A), Kind.FIRE,
            )
        }
        repeat(if (big) 20 else 12) {
            val a = rng.nextFloat() * 2 * PI.toFloat()
            val v = (3f + rng.nextFloat() * 3.5f) * scale
            add(
                cx, cy, cos(a) * v, sin(a) * v, 2.2f, 0.25f + rng.nextFloat() * 0.4f,
                0.035f, 0f, Color(0xFFFFF6C2), Color(0xFFFF8A2A), Kind.SPARK,
            )
        }
        repeat(if (big) 8 else 5) { smokePuff(cx, cy, small = false) }
        shake += if (big) 0.12f else 0.08f
    }

    fun sink(ship: Ship) {
        val mid = ship.cells[ship.cells.size / 2]
        add(
            mid.col + 0.5f, mid.row + 0.5f, 0f, 0f, 0f, 0.8f, 0.3f, 3.2f,
            Color(0xFFFFF0C8), Color(0xFFFF5A2A), Kind.RING,
        )
        ship.cells.forEachIndexed { i, c -> later(0.05f + i * 0.13f) { explode(c, big = true) } }
        shake += 0.3f
    }

    private fun smokePuff(cx: Float, cy: Float, small: Boolean) {
        val a = rng.nextFloat() * 2 * PI.toFloat()
        val v = if (small) 0.1f + rng.nextFloat() * 0.15f else 0.25f + rng.nextFloat() * 0.6f
        val shade = 0.18f + rng.nextFloat() * 0.18f
        add(
            cx + (rng.nextFloat() - 0.5f) * 0.3f, cy + (rng.nextFloat() - 0.5f) * 0.3f,
            cos(a) * v, sin(a) * v, 0.9f, if (small) 1.4f + rng.nextFloat() else 1.4f + rng.nextFloat() * 0.8f,
            if (small) 0.09f else 0.14f, if (small) 2.2f else 1.8f,
            Color(shade, shade, shade + 0.02f, 1f), Color(0.09f, 0.1f, 0.12f, 1f), Kind.SMOKE,
        )
    }

    private fun add(
        x: Float, y: Float, vx: Float, vy: Float, drag: Float, life: Float,
        size: Float, grow: Float, color: Color, color2: Color, kind: Kind,
    ) {
        if (particles.size < 900) particles += Particle(x, y, vx, vy, drag, life, size, grow, color, color2, kind)
    }

    // The grid's shake right now, as an offset in cells.
    fun jitter(): Offset =
        if (shake < 0.002f) Offset.Zero
        else Offset((rng.nextFloat() - 0.5f) * 2 * shake, (rng.nextFloat() - 0.5f) * 2 * shake)

    fun draw(scope: DrawScope, geo: Geo) = with(scope) {
        val c = geo.cell
        for (kind in Kind.entries) {
            for (q in particles) {
                if (q.kind != kind) continue
                val p = q.p
                val at = geo.point(q.x, q.y)
                val r = max(0.5f, q.size * (1f + q.grow * p) * c)
                val col = lerp(q.color, q.color2, p)
                when (kind) {
                    Kind.SMOKE -> drawCircle(col, r, at, alpha = 0.5f * (1f - p) * min(1f, p * 6f))
                    Kind.DROP -> drawCircle(col, r, at, alpha = (1f - p).pow(0.7f))
                    Kind.FIRE -> drawCircle(
                        Brush.radialGradient(listOf(col, col.copy(alpha = 0f)), at, r),
                        r, at, alpha = (1f - p).pow(1.2f), blendMode = BlendMode.Plus,
                    )
                    Kind.SPARK -> drawLine(
                        col, at, geo.point(q.x - q.vx * 0.05f, q.y - q.vy * 0.05f),
                        strokeWidth = q.size * c, cap = StrokeCap.Round, alpha = 1f - p, blendMode = BlendMode.Plus,
                    )
                    Kind.RING -> drawCircle(
                        col, r, at, alpha = (1f - p).pow(1.4f),
                        style = Stroke(width = max(1f, c * 0.09f * (1f - p))),
                    )
                    Kind.FLASH -> drawCircle(
                        Brush.radialGradient(listOf(q.color, q.color2), at, r),
                        r, at, alpha = (1f - p).pow(2f), blendMode = BlendMode.Plus,
                    )
                }
            }
        }

        // A shell on its way down: a target ring closing on the cell and the
        // shell's shadow growing as it nears the water.
        for (s in shells) {
            val p = (s.age / s.duration).coerceIn(0f, 1f)
            val at = geo.center(s.cell)
            val ring = c * (0.95f - 0.6f * p)
            val amber = Color(0xFFFFC266)
            drawCircle(Color.Black, c * 0.08f + c * 0.22f * p, at, alpha = 0.25f + 0.3f * p)
            drawCircle(amber, ring, at, alpha = 0.4f + 0.6f * p, style = Stroke(width = c * 0.05f))
            for (k in 0 until 4) {
                val a = k * PI.toFloat() / 2 + p * 1.2f
                val inner = ring * 0.55f
                val outer = ring * 1.25f
                drawLine(
                    amber, Offset(at.x + cos(a) * inner, at.y + sin(a) * inner),
                    Offset(at.x + cos(a) * outer, at.y + sin(a) * outer),
                    strokeWidth = c * 0.045f, alpha = 0.4f + 0.6f * p,
                )
            }
        }
    }
}
