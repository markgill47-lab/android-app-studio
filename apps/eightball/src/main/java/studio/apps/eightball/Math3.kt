package studio.apps.eightball

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Camera space throughout: x right, y up, z out of the screen toward the
// viewer. The phone's sensor axes are the same axes while the app is portrait.
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(this dot this)
    fun normalized(): Vec3 {
        val l = length()
        return if (l > 1e-6f) this / l else ZERO
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}

// Unit quaternion for the die's orientation, body to world.
data class Quat(val w: Float, val x: Float, val y: Float, val z: Float) {
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conjugate() = Quat(w, -x, -y, -z)

    fun normalized(): Quat {
        val l = sqrt(w * w + x * x + y * y + z * z)
        return Quat(w / l, x / l, y / l, z / l)
    }

    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = (u cross v) * 2f
        return v + t * w + (u cross t)
    }

    // The rotation vector (axis times angle, shortest way) that turns this
    // orientation into the target.
    fun errorTo(target: Quat): Vec3 {
        var e = target * conjugate()
        if (e.w < 0f) e = Quat(-e.w, -e.x, -e.y, -e.z)
        val v = Vec3(e.x, e.y, e.z)
        val s = v.length()
        if (s < 1e-6f) return Vec3.ZERO
        val angle = 2f * atan2(s, e.w)
        return v * (angle / s)
    }

    companion object {
        val IDENTITY = Quat(1f, 0f, 0f, 0f)

        fun fromAxisAngle(axis: Vec3, angle: Float): Quat {
            val a = axis.normalized()
            val s = sin(angle / 2f)
            return Quat(cos(angle / 2f), a.x * s, a.y * s, a.z * s)
        }

        // The rotation whose matrix has these three rows.
        fun fromRows(r0: Vec3, r1: Vec3, r2: Vec3): Quat {
            val m00 = r0.x; val m01 = r0.y; val m02 = r0.z
            val m10 = r1.x; val m11 = r1.y; val m12 = r1.z
            val m20 = r2.x; val m21 = r2.y; val m22 = r2.z
            val trace = m00 + m11 + m22
            val q = when {
                trace > 0f -> {
                    val s = sqrt(trace + 1f) * 2f
                    Quat(0.25f * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
                }
                m00 > m11 && m00 > m22 -> {
                    val s = sqrt(1f + m00 - m11 - m22) * 2f
                    Quat((m21 - m12) / s, 0.25f * s, (m01 + m10) / s, (m02 + m20) / s)
                }
                m11 > m22 -> {
                    val s = sqrt(1f + m11 - m00 - m22) * 2f
                    Quat((m02 - m20) / s, (m01 + m10) / s, 0.25f * s, (m12 + m21) / s)
                }
                else -> {
                    val s = sqrt(1f + m22 - m00 - m11) * 2f
                    Quat((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25f * s)
                }
            }
            return q.normalized()
        }
    }
}

fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
