package studio.apps.swappuzzle

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

const val MIN_SIZE = 4
const val MAX_SIZE = 9

enum class Screen { SETUP, PLAY }

data class Best(val swaps: Int?, val timeMs: Long?)

// Holds one game at a time. The activity owns it and pauses the clock while
// the app is in the background.
class Game(private val prefs: SharedPreferences) {
    var screen by mutableStateOf(Screen.SETUP)
        private set
    var size by mutableIntStateOf(prefs.getInt(KEY_LAST_SIZE, 5).coerceIn(MIN_SIZE, MAX_SIZE))
        private set
    var hidden by mutableStateOf<List<Glyph>>(emptyList())
        private set
    var row by mutableStateOf<List<Glyph>>(emptyList())
        private set
    var selected by mutableStateOf<Int?>(null)
        private set
    var swaps by mutableIntStateOf(0)
        private set
    var lastChange by mutableStateOf<Int?>(null)
        private set
    var solved by mutableStateOf(false)
        private set
    var newBestSwaps by mutableStateOf(false)
        private set
    var newBestTime by mutableStateOf(false)
        private set

    private var accumulatedMs = 0L
    private var runningSince: Long? = null

    val correct: Int
        get() = row.indices.count { row[it] == hidden[it] }

    fun chooseSize(n: Int) {
        size = n.coerceIn(MIN_SIZE, MAX_SIZE)
        prefs.edit().putInt(KEY_LAST_SIZE, size).apply()
    }

    fun best(n: Int): Best {
        val swaps = prefs.getInt(keySwaps(n), -1).takeIf { it >= 0 }
        val time = prefs.getLong(keyTime(n), -1L).takeIf { it >= 0 }
        return Best(swaps, time)
    }

    fun start() {
        val glyphs = Glyph.all.shuffled().take(size)
        val target = glyphs.shuffled()
        // The starting row must have nothing in the right place.
        var start: List<Glyph>
        do {
            start = target.shuffled()
        } while (start.indices.any { start[it] == target[it] })

        hidden = target
        row = start
        selected = null
        swaps = 0
        lastChange = null
        solved = false
        newBestSwaps = false
        newBestTime = false
        accumulatedMs = 0L
        runningSince = SystemClock.elapsedRealtime()
        screen = Screen.PLAY
    }

    fun quit() {
        pause()
        selected = null
        screen = Screen.SETUP
    }

    // Tap one tile to pick it up, tap another to swap, tap it again to drop it.
    fun tap(index: Int) {
        if (solved) return
        val current = selected
        when {
            current == null -> selected = index
            current == index -> selected = null
            else -> swap(current, index)
        }
    }

    fun swap(from: Int, to: Int) {
        if (solved || from == to) return
        val before = correct
        row = row.toMutableList().also {
            val t = it[from]
            it[from] = it[to]
            it[to] = t
        }
        selected = null
        swaps += 1
        lastChange = correct - before
        if (correct == size) finish()
    }

    fun elapsedMs(): Long =
        accumulatedMs + (runningSince?.let { SystemClock.elapsedRealtime() - it } ?: 0L)

    fun pause() {
        runningSince?.let { accumulatedMs += SystemClock.elapsedRealtime() - it }
        runningSince = null
    }

    fun resume() {
        if (screen == Screen.PLAY && !solved && runningSince == null) {
            runningSince = SystemClock.elapsedRealtime()
        }
    }

    private fun finish() {
        pause()
        solved = true
        val time = accumulatedMs
        val previous = best(size)
        val editor = prefs.edit()
        if (previous.swaps == null || swaps < previous.swaps) {
            editor.putInt(keySwaps(size), swaps)
            newBestSwaps = true
        }
        if (previous.timeMs == null || time < previous.timeMs) {
            editor.putLong(keyTime(size), time)
            newBestTime = true
        }
        editor.apply()
    }

    private companion object {
        const val KEY_LAST_SIZE = "last_size"
        fun keySwaps(n: Int) = "best_swaps_$n"
        fun keyTime(n: Int) = "best_time_$n"
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
