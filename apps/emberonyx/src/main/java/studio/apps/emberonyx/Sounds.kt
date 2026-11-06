package studio.apps.emberonyx

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.concurrent.thread

// Plays the synthesized effects. The first launch renders each one to a WAV
// file in app storage; later launches load the files straight into SoundPool.
class Sounds(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val ids = AtomicIntegerArray(Sfx.entries.size)

    @Volatile
    var enabled = true

    @Volatile
    private var released = false

    init {
        val dir = File(context.filesDir, "sfx").apply { mkdirs() }
        thread(name = "sfx-render") {
            for (sfx in Sfx.entries) {
                if (released) break
                // Bump the version when Synth changes, so old files are redone.
                val file = File(dir, "v1_${sfx.name.lowercase()}.wav")
                if (!file.exists()) {
                    val tmp = File(dir, file.name + ".tmp")
                    tmp.writeBytes(Synth.wav(Synth.render(sfx)))
                    tmp.renameTo(file)
                }
                try {
                    ids.set(sfx.ordinal, pool.load(file.path, 1))
                } catch (e: RuntimeException) {
                    break
                }
            }
        }
    }

    fun play(sfx: Sfx, volume: Float = 1f, rate: Float = 1f) {
        if (!enabled || released) return
        val id = ids.get(sfx.ordinal)
        if (id != 0) pool.play(id, volume, volume, 1, 0, rate)
    }

    fun release() {
        released = true
        pool.release()
    }
}
