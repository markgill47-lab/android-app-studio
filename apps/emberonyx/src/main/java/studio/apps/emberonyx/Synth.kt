package studio.apps.emberonyx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

// Every sound in the game is built here from sine waves and noise when the
// app first starts, so there are no recorded samples and nothing to license.

enum class Sfx(val seconds: Float) {
    DROP(0.42f),   // a piece landing in the walnut board
    TICK(0.05f),   // the held piece moving to another column
    WIN(2.2f),     // four embers in a row
    LOSE(2.2f),    // four onyx in a row
    DRAW(1.6f),    // a full board with no line
    CLEAR(1.1f),   // the pieces tumbling out at the start of a new game
    NOPE(0.14f),   // a full column
}

object Synth {
    const val RATE = 22050

    fun render(sfx: Sfx): FloatArray {
        val n = (sfx.seconds * RATE).toInt()
        val out = FloatArray(n)
        val rng = Random(sfx.ordinal * 7919 + 31)
        when (sfx) {
            Sfx.DROP -> knock(out, 0, rng, level = 1f, pitch = 1f)
            Sfx.TICK -> {
                for (i in 0 until n) {
                    val t = i.toFloat() / RATE
                    out[i] = (sin(2 * PI * 2300 * t) * exp(-t * 140.0)).toFloat() * 0.8f
                }
            }
            Sfx.WIN -> {
                // A rising run of bells that lands on a bright chord, with
                // a sparkle of high glints over the top.
                val run = floatArrayOf(523.25f, 659.25f, 783.99f, 1046.5f, 1318.5f)
                run.forEachIndexed { k, f -> bell(out, (k * 0.09f * RATE).toInt(), f, 0.9f, 0.45f) }
                val chord = (0.5f * RATE).toInt()
                for (f in floatArrayOf(523.25f, 783.99f, 1046.5f, 1318.5f)) bell(out, chord, f, 1.6f, 0.3f)
                glints(out, (0.45f * RATE).toInt(), rng, count = 22, spread = 1.3f)
            }
            Sfx.LOSE -> {
                // A slow fall in a minor key that settles low and wavers.
                val notes = floatArrayOf(440f, 349.23f, 293.66f)
                notes.forEachIndexed { k, f -> reed(out, (k * 0.3f * RATE).toInt(), f, 0.34f, 0.42f) }
                reed(out, (0.9f * RATE).toInt(), 220f, 1.25f, 0.45f, wobble = true)
                reed(out, (0.9f * RATE).toInt(), 261.63f, 1.25f, 0.22f, wobble = true)
            }
            Sfx.DRAW -> {
                // Two even chimes a fifth apart, neither happy nor sad.
                bell(out, 0, 392f, 1.2f, 0.5f)
                bell(out, (0.32f * RATE).toInt(), 587.33f, 1.25f, 0.45f)
            }
            Sfx.CLEAR -> {
                // A rattle of pieces sliding out of the bottom of the board,
                // fast at first and thinning out.
                repeat(26) { k ->
                    val at = (sqrtish(k / 26f) * 0.85f * RATE).toInt()
                    knock(out, at, rng, level = 0.35f + rng.nextFloat() * 0.3f, pitch = 0.8f + rng.nextFloat() * 0.6f)
                }
            }
            Sfx.NOPE -> {
                for (i in 0 until n) {
                    val t = i.toFloat() / RATE
                    val tri = (2 / PI * kotlin.math.asin(sin(2 * PI * 180 * t))).toFloat()
                    out[i] = 0.5f * tri * min(1f, (sfx.seconds - t) / 0.03f) * min(1f, t / 0.005f)
                }
            }
        }
        normalize(out, if (sfx == Sfx.TICK || sfx == Sfx.NOPE) 0.45f else 0.92f)
        return out
    }

    private fun sqrtish(x: Float): Float = kotlin.math.sqrt(x.toDouble()).toFloat()

    // A hard piece striking wood: a click of noise and a few short, damped
    // resonances, with a low thump from the board itself.
    private fun knock(out: FloatArray, start: Int, rng: Random, level: Float, pitch: Float) {
        val modes = floatArrayOf(690f, 1170f, 1910f, 2870f)
        val gains = floatArrayOf(1f, 0.6f, 0.38f, 0.2f)
        val decays = floatArrayOf(34f, 48f, 70f, 95f)
        val lp = OnePole().apply { cutoff(5200f) }
        for (i in start until out.size) {
            val t = (i - start).toFloat() / RATE
            if (t > 0.4f) break
            var s = 0f
            for (m in modes.indices) {
                s += gains[m] * sin(2 * PI * modes[m] * pitch * t).toFloat() * exp(-t * decays[m])
            }
            val click = lp.next(rng.nextFloat() * 2 - 1) * exp(-t * 520f) * 1.6f
            val thump = sin(2 * PI * 150 * pitch * t).toFloat() * exp(-t * 26f) * 0.9f
            out[i] += level * (0.55f * s + click + thump)
        }
    }

    // A bell: a sine with a few slightly out-of-tune overtones that fade
    // faster than the note itself.
    private fun bell(out: FloatArray, start: Int, freq: Float, length: Float, level: Float) {
        val ratios = floatArrayOf(1f, 2.0f, 2.76f, 4.07f)
        val gains = floatArrayOf(1f, 0.45f, 0.25f, 0.12f)
        val end = min(out.size, start + (length * RATE).toInt())
        for (i in start until end) {
            val t = (i - start).toFloat() / RATE
            val fade = min(1f, (length - t) / 0.1f)
            var s = 0f
            for (k in ratios.indices) {
                s += gains[k] * sin(2 * PI * freq * ratios[k] * t).toFloat() * exp(-t * (2.2f + 2.6f * k))
            }
            out[i] += level * s * min(1f, t / 0.003f) * fade
        }
    }

    // Short high pings scattered in time, like light catching glass.
    private fun glints(out: FloatArray, start: Int, rng: Random, count: Int, spread: Float) {
        repeat(count) {
            val at = start + (rng.nextFloat() * spread * RATE).toInt()
            val f = 2400f + rng.nextFloat() * 2600f
            val level = 0.05f + rng.nextFloat() * 0.07f
            val end = min(out.size, at + (0.12f * RATE).toInt())
            for (i in at until end) {
                val t = (i - at).toFloat() / RATE
                out[i] += level * sin(2 * PI * f * t).toFloat() * exp(-t * 38f)
            }
        }
    }

    // A soft reed-like note from odd harmonics, for the losing tune.
    private fun reed(out: FloatArray, start: Int, freq: Float, length: Float, level: Float, wobble: Boolean = false) {
        val end = min(out.size, start + (length * RATE).toInt())
        for (i in start until end) {
            val t = (i - start).toFloat() / RATE
            val env = min(t / 0.04f, 1f) * min(1f, (length - t) / 0.15f)
            val vib = if (wobble) 1f + 0.014f * sin(2 * PI * 5f * t).toFloat() else 1f
            var s = 0f
            for ((h, a) in floatArrayOf(1f, 0.33f, 0.14f, 0.06f).withIndex()) {
                s += a * sin(2 * PI * freq * vib * (2 * h + 1) * t).toFloat()
            }
            out[i] += level * env * s
        }
    }

    private fun normalize(out: FloatArray, peak: Float) {
        for (i in out.indices) out[i] = tanh(out[i].toDouble() * 1.2).toFloat()
        var m = 1e-6f
        for (v in out) m = max(m, abs(v))
        val g = peak / m
        for (i in out.indices) out[i] *= g
        // A few milliseconds of fade at the very end, so nothing clicks.
        val tail = min(out.size, RATE / 200)
        for (k in 0 until tail) out[out.size - 1 - k] *= k.toFloat() / tail
    }

    // 16-bit mono PCM in a WAV container, which SoundPool can load from disk.
    fun wav(samples: FloatArray): ByteArray {
        val data = samples.size * 2
        val b = ByteArray(44 + data)
        fun put32(at: Int, v: Int) { for (k in 0..3) b[at + k] = (v shr (8 * k)).toByte() }
        fun put16(at: Int, v: Int) { for (k in 0..1) b[at + k] = (v shr (8 * k)).toByte() }
        "RIFF".forEachIndexed { k, c -> b[k] = c.code.toByte() }
        put32(4, 36 + data)
        "WAVEfmt ".forEachIndexed { k, c -> b[8 + k] = c.code.toByte() }
        put32(16, 16)
        put16(20, 1)
        put16(22, 1)
        put32(24, RATE)
        put32(28, RATE * 2)
        put16(32, 2)
        put16(34, 16)
        "data".forEachIndexed { k, c -> b[36 + k] = c.code.toByte() }
        put32(40, data)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            put16(44 + i * 2, v)
        }
        return b
    }
}

// A one-pole low-pass filter whose cutoff can move while it runs.
private class OnePole {
    private var y = 0f
    private var a = 1f

    fun cutoff(hz: Float) {
        a = (1f - exp(-2f * PI.toFloat() * hz / Synth.RATE)).coerceIn(0.0001f, 1f)
    }

    fun next(x: Float): Float {
        y += a * (x - y)
        return y
    }
}
