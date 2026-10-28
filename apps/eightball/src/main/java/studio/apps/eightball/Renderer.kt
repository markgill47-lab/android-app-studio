package studio.apps.eightball

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val FACE_BLUE = 0xFF2B3FC2.toInt()
private const val EDGE_BLUE = 0xFF18247A.toInt()
private const val TEXT_WHITE = 0xFFE8ECFF.toInt()

// Draws the die into a black void with black fog. Each face is a texture of
// a blue triangle with its answer, mapped onto the projected triangle, then
// darkened by light and by fog so the die fades into black with depth.
class Renderer(private val faces: List<Face>) {
    private val art: List<Bitmap> = ANSWERS.map { faceBitmap(it) }

    private val path = Path()
    private val matrix = Matrix()
    private val src = floatArrayOf(
        TEX_PAD + TEX_SIDE / 2f, TEX_PAD,
        TEX_PAD, TEX_PAD + TEX_HEIGHT,
        TEX_PAD + TEX_SIDE, TEX_PAD + TEX_HEIGHT,
    )
    private val dst = FloatArray(6)
    private val depthAt = FloatArray(3)
    private val dark = FloatArray(3)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FACE_BLUE }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val light = Vec3(0.25f, 0.45f, 1f).normalized()

    fun draw(canvas: Canvas, width: Float, height: Float, die: Die) {
        canvas.drawColor(Color.BLACK)
        val focal = min(width, height) * 1.25f
        val cx = width / 2f
        val cy = height / 2f
        val q = die.orientation
        val center = die.center

        for (face in faces) {
            val normal = q.rotate(face.normal)
            val mid = center + q.rotate(face.centroid)
            // Back faces turn away from the eye and are never seen.
            if ((normal dot -mid) <= 0f) continue

            val corners = arrayOf(face.a, face.b, face.c)
            for (i in 0 until 3) {
                val p = center + q.rotate(corners[i])
                val d = -p.z
                dst[i * 2] = cx + focal * p.x / d
                dst[i * 2 + 1] = cy - focal * p.y / d
                depthAt[i] = d
            }

            val lit = AMBIENT + DIFFUSE * max(0f, normal dot light)
            var allDark = true
            for (i in 0 until 3) {
                val fog = smoothstep(FOG_START, FOG_END, depthAt[i])
                dark[i] = (1f - lit * (1f - fog)).coerceIn(0f, 1f)
                if (dark[i] < 0.995f) allDark = false
            }
            if (allDark) continue

            path.reset()
            path.moveTo(dst[0], dst[1])
            path.lineTo(dst[2], dst[3])
            path.lineTo(dst[4], dst[5])
            path.close()

            canvas.drawPath(path, basePaint)
            matrix.setPolyToPoly(src, 0, dst, 0, 3)
            canvas.drawBitmap(art[face.answer], matrix, bitmapPaint)
            shade(canvas)
        }
    }

    // Darkness varies linearly across the triangle, from the value at each
    // corner, so a face can be clear at the front and lost in fog at the back.
    // A linear gradient from black-clear to black-solid along the direction
    // the darkness rises reproduces that exactly.
    private fun shade(canvas: Canvas) {
        val x0 = dst[0]; val y0 = dst[1]
        val x1 = dst[2]; val y1 = dst[3]
        val x2 = dst[4]; val y2 = dst[5]
        val d0 = dark[0]; val d1 = dark[1]; val d2 = dark[2]
        val det = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        val gx = if (abs(det) > 1e-3f) ((d1 - d0) * (y2 - y0) - (d2 - d0) * (y1 - y0)) / det else 0f
        val gy = if (abs(det) > 1e-3f) ((x1 - x0) * (d2 - d0) - (x2 - x0) * (d1 - d0)) / det else 0f
        val g2 = gx * gx + gy * gy
        if (g2 < 1e-12f) {
            val a = ((d0 + d1 + d2) / 3f * 255f).toInt().coerceIn(0, 255)
            if (a == 0) return
            shadePaint.shader = null
            shadePaint.color = Color.argb(a, 0, 0, 0)
        } else {
            val startX = x0 + gx * (-d0 / g2)
            val startY = y0 + gy * (-d0 / g2)
            val endX = x0 + gx * ((1f - d0) / g2)
            val endY = y0 + gy * ((1f - d0) / g2)
            shadePaint.color = Color.BLACK
            shadePaint.shader = LinearGradient(
                startX, startY, endX, endY,
                Color.argb(0, 0, 0, 0), Color.BLACK,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(path, shadePaint)
    }

    companion object {
        private const val AMBIENT = 0.3f
        private const val DIFFUSE = 0.7f
        // Tight fog: the face pressed to the window is clear, the faces
        // around it dim fast, and a die a little further back is gone.
        private const val FOG_START = Die.DEPTH_MIN - 0.55f
        private const val FOG_END = Die.DEPTH_MIN + 0.45f

        private const val TEX_PAD = 8f
        private const val TEX_SIDE = 512f
        private val TEX_HEIGHT = TEX_SIDE * sqrt(3f) / 2f

        // One face: a blue triangle with a darker rim and the answer in
        // white, laid out to fit inside the triangle's width at each line.
        private fun faceBitmap(answer: String): Bitmap {
            val w = ceil(TEX_SIDE + 2 * TEX_PAD).toInt()
            val h = ceil(TEX_HEIGHT + 2 * TEX_PAD).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)

            val tri = Path().apply {
                moveTo(TEX_PAD + TEX_SIDE / 2f, TEX_PAD)
                lineTo(TEX_PAD, TEX_PAD + TEX_HEIGHT)
                lineTo(TEX_PAD + TEX_SIDE, TEX_PAD + TEX_HEIGHT)
                close()
            }
            c.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FACE_BLUE })
            c.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = EDGE_BLUE
                style = Paint.Style.STROKE
                strokeWidth = 14f
                strokeJoin = Paint.Join.ROUND
            })

            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TEXT_WHITE
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                letterSpacing = 0.04f
            }
            val lines = answer.split("\n")
            val centerY = TEX_PAD + TEX_HEIGHT * 0.64f
            val margin = TEX_SIDE * 0.14f

            // Width of the triangle at a given distance below the apex.
            fun widthAt(y: Float) = TEX_SIDE * ((y - TEX_PAD) / TEX_HEIGHT) - margin

            var size = 96f
            while (size > 18f) {
                text.textSize = size
                val lineHeight = size * 1.12f
                val top = centerY - lineHeight * lines.size / 2f
                val fits = lines.withIndex().all { (i, line) ->
                    val glyphTop = top + lineHeight * i
                    text.measureText(line) <= widthAt(glyphTop)
                }
                if (fits && top > TEX_PAD + TEX_HEIGHT * 0.18f) break
                size -= 2f
            }
            val lineHeight = size * 1.12f
            val top = centerY - lineHeight * lines.size / 2f
            val fm = text.fontMetrics
            lines.forEachIndexed { i, line ->
                // Baseline that centers the cap height inside the line slot.
                val slotMid = top + lineHeight * (i + 0.5f)
                val baseline = slotMid - (fm.ascent + fm.descent) / 2f
                c.drawText(line, TEX_PAD + TEX_SIDE / 2f, baseline, text)
            }
            return bmp
        }
    }
}
