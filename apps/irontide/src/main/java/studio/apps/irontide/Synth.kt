package studio.apps.irontide

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
    FIRE(0.8f),       // our gun firing
    INCOMING(0.9f),   // their shell whistling down onto our fleet
    SPLASH(1.0f),     // a miss
    HIT(1.4f),        // an explosion on a hull
    SINK(2.8f),       // a ship going down
    WIN(1.8f),
    LOSE(2.2f),
    TICK(0.06f),      // a ship set down during placement
    BUZZ(0.16f),      // a ship that will not fit there
}

object Synth {
    const val RATE = 22050

    fun render(sfx: Sfx): FloatArray {
        val n = (sfx.seconds * RATE).toInt()
        val out = FloatArray(n)
        val rng = Random(sfx.ordinal * 7919 + 17)
        when (sfx) {
            Sfx.FIRE -> {
                boom(out, 0, rng, body = 1.0f, crack = 0.9f, decay = 5.5f, pitch = 95f)
            }
            Sfx.INCOMING -> {
                // A falling whistle, then a distant thud as the gun is heard.
                var phase = 0.0
                for (i in 0 until n) {
                    val t = i.toFloat() / RATE
                    val f = 1900.0 - 1300.0 * (t / sfx.seconds) + 18.0 * sin(2 * PI * 7 * t)
                    phase += 2 * PI * f / RATE
                    val env = min(t / 0.25f, 1f) * max(0f, 1f - t / sfx.seconds)
                    out[i] += (0.22 * sin(phase) * env).toFloat()
                }
                boom(out, 0, rng, body = 0.35f, crack = 0.15f, decay = 7f, pitch = 70f)
            }
            Sfx.SPLASH -> splash(out, 0, rng)
            Sfx.HIT -> explosion(out, 0, rng, level = 1f, length = 1.3f)
            Sfx.SINK -> {
                explosion(out, 0, rng, level = 1f, length = 1.3f)
                explosion(out, (0.35f * RATE).toInt(), rng, level = 0.7f, length = 1.2f)
                explosion(out, (0.85f * RATE).toInt(), rng, level = 0.85f, length = 1.4f)
                groan(out, (0.6f * RATE).toInt(), 2.1f)
                bubbles(out, (1.4f * RATE).toInt(), rng, count = 14, spread = 1.3f)
            }
            Sfx.WIN -> {
                // A rising bugle call that lands on a held major chord.
                val notes = floatArrayOf(523.25f, 659.25f, 783.99f, 1046.5f)
                notes.forEachIndexed { k, f -> brass(out, (k * 0.13f * RATE).toInt(), f, 0.16f, 0.5f) }
                val chord = (0.52f * RATE).toInt()
                for (f in floatArrayOf(523.25f, 659.25f, 783.99f, 1046.5f)) brass(out, chord, f, 1.2f, 0.32f)
            }
            Sfx.LOSE -> {
                // A slow fall through a minor key, ending low and wavering.
                val notes = floatArrayOf(392f, 311.13f, 261.63f)
                notes.forEachIndexed { k, f -> brass(out, (k * 0.32f * RATE).toInt(), f, 0.34f, 0.45f, soft = true) }
                brass(out, (0.96f * RATE).toInt(), 196f, 1.2f, 0.5f, soft = true, wobble = true)
                brass(out, (0.96f * RATE).toInt(), 233.08f, 1.2f, 0.25f, soft = true, wobble = true)
            }
            Sfx.TICK -> {
                for (i in 0 until n) {
                    val t = i.toFloat() / RATE
                    out[i] = (sin(2 * PI * 1400 * t) * exp(-t * 90.0)).toFloat()
                }
            }
            Sfx.BUZZ -> {
                for (i in 0 until n) {
                    val t = i.toFloat() / RATE
                    val square = if (sin(2 * PI * 140 * t) >= 0) 1f else -1f
                    out[i] = 0.5f * square * min(1f, (sfx.seconds - t) / 0.03f)
                }
            }
        }
        normalize(out, if (sfx == Sfx.TICK || sfx == Sfx.BUZZ) 0.5f else 0.92f)
        return out
    }

    // A gun: a low thump that drops in pitch, under a bright crack of noise.
    private fun boom(out: FloatArray, start: Int, rng: Random, body: Float, crack: Float, decay: Float, pitch: Float) {
        var phase = 0.0
        val lp = OnePole()
        for (i in start until out.size) {
            val t = (i - start).toFloat() / RATE
            val f = pitch * (0.4f + 0.6f * exp(-t * 9f))
            phase += 2 * PI * f / RATE
            val thump = sin(phase).toFloat() * exp(-t * decay)
            lp.cutoff(4000f * exp(-t * 6f) + 200f)
            val noise = lp.next(rng.nextFloat() * 2 - 1) * exp(-t * (decay * 2.5f))
            out[i] += body * thump + crack * 1.6f * noise
        }
    }

    // A splash: a hiss of water that darkens as it falls, with a few drips.
    private fun splash(out: FloatArray, start: Int, rng: Random) {
        val lp = OnePole()
        for (i in start until out.size) {
            val t = (i - start).toFloat() / RATE
            val env = min(t / 0.012f, 1f) * exp(-t * 3.6f)
            lp.cutoff(3200f * exp(-t * 2.2f) + 300f)
            out[i] += lp.next(rng.nextFloat() * 2 - 1) * env * 1.8f
        }
        bubbles(out, start + (0.18f * RATE).toInt(), rng, count = 7, spread = 0.6f)
    }

    // An explosion: a deep thud, a long roar of low noise and some crackle.
    private fun explosion(out: FloatArray, start: Int, rng: Random, level: Float, length: Float) {
        val lp = OnePole()
        val lp2 = OnePole()
        var phase = 0.0
        val end = min(out.size, start + (length * RATE).toInt())
        for (i in start until end) {
            val t = (i - start).toFloat() / RATE
            val fade = 1f - t / length
            val env = min(t / 0.006f, 1f) * exp(-t * 3.2f) * fade
            lp.cutoff(1500f * exp(-t * 3f) + 140f)
            lp2.cutoff(lp.cutoffHz * 0.6f)
            val roar = lp2.next(lp.next(rng.nextFloat() * 2 - 1)) * 4.5f
            phase += 2 * PI * (62f * (0.5f + 0.5f * exp(-t * 6f))) / RATE
            val thump = sin(phase).toFloat() * exp(-t * 5f)
            val crackle = if (rng.nextFloat() < 0.0025f * exp(-t * 2f)) (rng.nextFloat() * 2 - 1) * 0.9f else 0f
            out[i] += level * (env * roar + 0.9f * thump * fade + crackle)
        }
    }

    // Steel giving way: a low, sliding growl that swells and fades.
    private fun groan(out: FloatArray, start: Int, length: Float) {
        var phase = 0.0
        val lp = OnePole().apply { cutoff(380f) }
        val end = min(out.size, start + (length * RATE).toInt())
        for (i in start until end) {
            val t = (i - start).toFloat() / RATE
            val f = 72.0 - 34.0 * (t / length) + 4.0 * sin(2 * PI * 1.7 * t)
            phase += 2 * PI * f / RATE
            val saw = ((phase / (2 * PI)) % 1.0 * 2 - 1).toFloat()
            val env = sin(PI * (t / length)).toFloat()
            out[i] += 0.55f * lp.next(saw) * env
        }
    }

    // Short rising chirps, like air escaping under water.
    private fun bubbles(out: FloatArray, start: Int, rng: Random, count: Int, spread: Float) {
        repeat(count) {
            val at = start + (rng.nextFloat() * spread * RATE).toInt()
            val f0 = 260f + rng.nextFloat() * 420f
            val len = (0.035f + rng.nextFloat() * 0.03f)
            var phase = 0.0
            val end = min(out.size, at + (len * RATE).toInt())
            for (i in at until end) {
                val t = (i - at).toFloat() / RATE
                phase += 2 * PI * (f0 * (1f + 2.2f * t / len)) / RATE
                out[i] += 0.16f * sin(phase).toFloat() * sin(PI * t / len).toFloat()
            }
        }
    }

    // A horn-like note from a few harmonics under a soft attack.
    private fun brass(
        out: FloatArray, start: Int, freq: Float, length: Float, level: Float,
        soft: Boolean = false, wobble: Boolean = false,
    ) {
        val end = min(out.size, start + (length * RATE).toInt())
        val harmonics = if (soft) floatArrayOf(1f, 0.3f, 0.1f) else floatArrayOf(1f, 0.55f, 0.35f, 0.2f)
        for (i in start until end) {
            val t = (i - start).toFloat() / RATE
            val env = min(t / 0.03f, 1f) * min(1f, (length - t) / 0.12f) * (if (soft) 1f else 1f - 0.25f * t / length)
            val vib = if (wobble) 1f + 0.012f * sin(2 * PI * 5.5 * t).toFloat() else 1f
            var s = 0f
            harmonics.forEachIndexed { h, a -> s += a * sin(2 * PI * freq * vib * (h + 1) * t).toFloat() }
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
    var cutoffHz = 1000f
        private set

    fun cutoff(hz: Float) {
        cutoffHz = hz
        a = (1f - exp(-2f * PI.toFloat() * hz / Synth.RATE)).coerceIn(0.0001f, 1f)
    }

    fun next(x: Float): Float {
        y += a * (x - y)
        return y
    }
}
