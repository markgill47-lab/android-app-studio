package studio.apps.eightball

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

// The die floating in the ball's liquid. Depth is the distance from the eye
// to the die's center. It is buoyant: when the screen faces up it rises
// until it presses against the window, and when the screen faces down it
// sinks away into the fog.
class Die(private val faces: List<Face>) {
    // Declared first: the starting orientation below already needs it.
    private val random = Random.Default

    var orientation: Quat = randomOrientation()
        private set
    var depth = DEPTH_MAX
        private set
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set

    private var omega = Vec3.ZERO
    private var depthVel = 0f
    private var velX = 0f
    private var velY = 0f
    private var shake = 0f
    private var faceDown = false

    val center get() = Vec3(offsetX, offsetY, -depth)

    // up: unit vector pointing away from the ground, in screen axes.
    // linear: the phone's own acceleration with gravity removed, in m/s².
    fun step(dt: Float, up: Vec3, linear: Vec3) {
        var left = dt
        while (left > 0f) {
            val h = min(left, MAX_STEP)
            integrate(h, up, linear)
            left -= h
        }
    }

    // A tap on the screen: a small knock, for when shaking is not handy.
    fun nudge() {
        omega = omega + randomUnit() * 7f
        depthVel += 1.5f
        shake = max(shake, 1f)
    }

    private fun integrate(dt: Float, up: Vec3, linear: Vec3) {
        // Shake energy rises with every hard jolt and decays over a third of
        // a second. While it is up, the die tumbles freely.
        val jolt = ((linear.length() - SHAKE_THRESHOLD) / 6f).coerceIn(0f, 1.5f)
        shake = max(shake * exp(-dt / 0.35f), jolt)
        val calm = (1f - shake).coerceIn(0f, 1f)

        // Turning the screen away sends the die off spinning, so the next
        // answer is a fresh one.
        if (!faceDown && up.z < -0.5f) {
            faceDown = true
            omega = omega + randomUnit() * 5f
        } else if (faceDown && up.z > -0.2f) {
            faceDown = false
        }

        // Depth: buoyancy toward whichever way is up, the liquid's drag, and
        // the die lagging behind when the phone is jerked toward the face.
        var accDepth = -BUOYANCY * up.z
        accDepth += linear.z * DEPTH_INERTIA
        accDepth -= DEPTH_DRAG * depthVel
        depthVel += accDepth * dt
        depth += depthVel * dt
        if (depth < DEPTH_MIN) {
            depth = DEPTH_MIN
            if (depthVel < 0f) depthVel = -depthVel * 0.25f
        }
        if (depth > DEPTH_MAX) {
            depth = DEPTH_MAX
            if (depthVel > 0f) depthVel = 0f
        }

        // Sideways: drifts toward the high side of a tilted screen, sloshes
        // against the phone's motion, and a spring keeps it in the window.
        val ax = up.x * SIDE_BUOYANCY - linear.x * SIDE_INERTIA - SIDE_SPRING * offsetX - SIDE_DRAG * velX
        val ay = up.y * SIDE_BUOYANCY - linear.y * SIDE_INERTIA - SIDE_SPRING * offsetY - SIDE_DRAG * velY
        velX += ax * dt
        velY += ay * dt
        offsetX += velX * dt
        offsetY += velY * dt
        val r = sqrt(offsetX * offsetX + offsetY * offsetY)
        if (r > SIDE_LIMIT) {
            val nx = offsetX / r
            val ny = offsetY / r
            offsetX = nx * SIDE_LIMIT
            offsetY = ny * SIDE_LIMIT
            val outward = velX * nx + velY * ny
            if (outward > 0f) {
                velX -= outward * nx
                velY -= outward * ny
            }
        }

        // Spin: near the window and calm, a spring turns the nearest face
        // flat and upright against the glass. A shake spins it.
        val nearness = ((DEPTH_MIN + SETTLE_RANGE - depth) / SETTLE_RANGE).coerceIn(0f, 1f)
        val k = ALIGN_STIFFNESS * nearness * calm
        val damping = SPIN_DRAG + 1.4f * sqrt(k)
        var alpha = orientation.errorTo(nearestFace().showing) * k - omega * damping
        if (jolt > 0f) {
            alpha = alpha + (linear cross SPIN_LEVER) * SPIN_FROM_SHAKE + randomUnit() * (jolt * 30f)
        }
        omega = omega + alpha * dt
        val speed = omega.length()
        if (speed > MAX_SPIN) omega = omega * (MAX_SPIN / speed)
        if (speed > 1e-5f) {
            orientation = (Quat.fromAxisAngle(omega, omega.length() * dt) * orientation).normalized()
        }
    }

    // The face turned most toward the viewer, the one the die settles on.
    fun nearestFace(): Face = faces.maxBy { orientation.rotate(it.normal).z }

    private fun randomUnit(): Vec3 {
        while (true) {
            val v = Vec3(
                random.nextFloat() * 2f - 1f,
                random.nextFloat() * 2f - 1f,
                random.nextFloat() * 2f - 1f,
            )
            val l = v.length()
            if (l in 0.1f..1f) return v / l
        }
    }

    private fun randomOrientation(): Quat =
        Quat.fromAxisAngle(randomUnit(), random.nextFloat() * 6.2832f)

    companion object {
        const val DEPTH_MIN = 3.4f
        const val DEPTH_MAX = 8f
        private const val MAX_STEP = 1f / 120f
        private const val BUOYANCY = 7f
        private const val DEPTH_DRAG = 2.2f
        private const val DEPTH_INERTIA = 0.35f
        private const val SIDE_BUOYANCY = 1.5f
        private const val SIDE_INERTIA = 0.12f
        private const val SIDE_SPRING = 10f
        private const val SIDE_DRAG = 3f
        private const val SIDE_LIMIT = 0.8f
        private const val SETTLE_RANGE = 1.5f
        private const val ALIGN_STIFFNESS = 30f
        private const val SPIN_DRAG = 1.2f
        private const val SPIN_FROM_SHAKE = 2.5f
        private const val SHAKE_THRESHOLD = 2.5f
        private const val MAX_SPIN = 20f
        private val SPIN_LEVER = Vec3(0.3f, 0.5f, 1f)
    }
}
