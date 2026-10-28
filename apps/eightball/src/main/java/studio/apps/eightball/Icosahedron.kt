package studio.apps.eightball

import kotlin.math.abs
import kotlin.math.sqrt

// One triangular face of the die. a is the apex the answer reads up toward,
// and a, b, c run counterclockwise seen from outside.
class Face(
    val a: Vec3,
    val b: Vec3,
    val c: Vec3,
    val answer: Int,
) {
    val centroid = (a + b + c) / 3f
    val normal = ((b - a) cross (c - a)).normalized()
    val up = (a - centroid).normalized()

    // The die orientation that presses this face flat against the window
    // with its text upright.
    val showing: Quat = Quat.fromRows(up cross normal, up, normal)
}

// A regular icosahedron with circumradius 1: 12 vertices, 20 faces, one
// answer per face.
object Icosahedron {
    val faces: List<Face> = build()

    private fun build(): List<Face> {
        val phi = (1f + sqrt(5f)) / 2f
        val raw = mutableListOf<Vec3>()
        for (s in listOf(-1f, 1f)) {
            for (t in listOf(-phi, phi)) {
                raw += Vec3(0f, s, t)
                raw += Vec3(s, t, 0f)
                raw += Vec3(t, 0f, s)
            }
        }
        // Neighbouring vertices of this icosahedron are exactly 2 apart.
        fun adjacent(p: Vec3, q: Vec3): Boolean {
            val d = p - q
            return abs((d dot d) - 4f) < 0.01f
        }
        val radius = raw[0].length()
        val verts = raw.map { it / radius }
        val faces = mutableListOf<Face>()
        for (i in raw.indices) {
            for (j in i + 1 until raw.size) {
                if (!adjacent(raw[i], raw[j])) continue
                for (k in j + 1 until raw.size) {
                    if (!adjacent(raw[i], raw[k]) || !adjacent(raw[j], raw[k])) continue
                    val a = verts[i]
                    var b = verts[j]
                    var c = verts[k]
                    if (((b - a) cross (c - a)) dot (a + b + c) < 0f) {
                        val swap = b
                        b = c
                        c = swap
                    }
                    // Stepping by 7 deals the answers around the die, so the
                    // yes, maybe and no faces are mixed instead of clumped.
                    faces += Face(a, b, c, (faces.size * 7) % ANSWERS.size)
                }
            }
        }
        check(faces.size == 20) { "Expected 20 faces, built ${faces.size}" }
        return faces
    }
}
