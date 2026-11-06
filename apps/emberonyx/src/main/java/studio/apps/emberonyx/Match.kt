package studio.apps.emberonyx

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class Phase { PLAYER, THINKING, DROPPING, CLEARING, OVER }

enum class Starter(val label: String) {
    YOU("You"),
    ONYX("Onyx"),
    TURNS("Take turns"),
}

// Heights on screen are in cells, measured down from the centre of the top
// row. A piece held over the board sits at HOLD_Y.
const val HOLD_Y = -1.4f
private const val GRAVITY = 46f
private const val BOUNCE = 0.3f

// A piece on its way down a column.
class Falling(val col: Int, val row: Int, val side: Int) {
    val target = (ROWS - 1 - row).toFloat()
    var y = HOLD_Y
    var vy = 0f
    var impacts = 0
}

// A piece sliding out of the bottom when the board is cleared.
class Tumbler(val col: Int, var y: Float, val side: Int, var wait: Float) {
    var vy = 0f
}

// A spark thrown off a winning line.
class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, val life: Float, val warm: Boolean) {
    var age = 0f
}

// Runs a game on screen: whose turn it is, the computer's thinking, the
// falling and bouncing pieces, the sounds that go with them, and the record.
class Match(
    private val sounds: Sounds,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
) {
    val board = Board()
    private val brain = Brain()
    // One thread for the computer, so two searches never share its memory.
    private val thinker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val rng = Random.Default

    var phase by mutableStateOf(Phase.PLAYER)
        private set
    var status by mutableStateOf("")
        private set
    var version by mutableIntStateOf(0)
        private set

    var difficulty by mutableStateOf(
        Difficulty.entries.firstOrNull { it.name == prefs.getString("difficulty", null) } ?: Difficulty.MEDIUM,
    )
        private set
    // The level this game is being played at. A change waits for a new game.
    var gameDifficulty by mutableStateOf(difficulty)
        private set
    var starter by mutableStateOf(
        Starter.entries.firstOrNull { it.name == prefs.getString("starter", null) } ?: Starter.TURNS,
    )
        private set
    var soundOn by mutableStateOf(prefs.getBoolean("sound", true))
        private set
    var winner by mutableStateOf<Int?>(null)
        private set

    // Animation state, read by the board drawing every frame.
    var time = 0f
        private set
    var falling: Falling? = null
        private set
    val tumblers = ArrayList<Tumbler>()
    val sparks = ArrayList<Spark>()
    var winLines: List<List<Spot>> = emptyList()
        private set
    var winTime = 0f
        private set
    var last: Spot? = null
        private set

    // The player's held piece: which column the finger is over, and where
    // the piece is drawn as it glides after it.
    var holdCol = 3
        private set
    var holdX = 3f
        private set
    var touching = false
        private set
    // The computer's held piece.
    var onyxX = 3f
        private set
    private var onyxTarget: Int? = null
    private var onyxLastCol = 3
    var refusedCol = -1
        private set
    var refusedAt = -10f
        private set

    var onImpact: (strong: Boolean) -> Unit = {}

    private var job: Job? = null
    private var generation = 0
    private var startedSide = EMBER

    private var started = false

    init {
        sounds.enabled = soundOn
    }

    // Called once the screen is up, so the computer can start thinking
    // straight away if it has the first move.
    fun start() {
        if (started) return
        started = true
        begin()
    }

    private fun changed() {
        version++
    }

    // ---- Settings --------------------------------------------------------

    fun chooseDifficulty(d: Difficulty) {
        difficulty = d
        prefs.edit().putString("difficulty", d.name).apply()
        // Before the first move, or between games, it takes effect at once.
        if (phase == Phase.OVER || (board.moves == 0 && phase == Phase.PLAYER)) gameDifficulty = d
        sounds.play(Sfx.TICK)
    }

    fun chooseStarter(s: Starter) {
        starter = s
        prefs.edit().putString("starter", s.name).apply()
        sounds.play(Sfx.TICK)
    }

    fun toggleSound() {
        soundOn = !soundOn
        sounds.enabled = soundOn
        prefs.edit().putBoolean("sound", soundOn).apply()
        if (soundOn) sounds.play(Sfx.TICK)
    }

    // Wins, draws and losses at one level, kept across launches.
    fun record(d: Difficulty): Triple<Int, Int, Int> = Triple(
        prefs.getInt("w_${d.name}", 0),
        prefs.getInt("d_${d.name}", 0),
        prefs.getInt("l_${d.name}", 0),
    )

    private fun bump(key: String) {
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    // ---- Game flow -------------------------------------------------------

    fun newGame() {
        generation++
        job?.cancel()
        onyxTarget = null
        falling = null
        winLines = emptyList()
        winner = null
        sparks.clear()
        if (board.moves == 0) {
            begin()
            return
        }
        // Pull the release: every piece slides out, bottom row first.
        tumblers.clear()
        for (c in 0 until COLS) for (r in 0 until board.height(c)) {
            val wait = r * 0.035f + abs(c - 3) * 0.02f + rng.nextFloat() * 0.03f
            tumblers.add(Tumbler(c, (ROWS - 1 - r).toFloat(), board.at(c, r), wait))
        }
        board.reset(EMBER)
        last = null
        phase = Phase.CLEARING
        status = "Clearing the board…"
        sounds.play(Sfx.CLEAR)
        changed()
    }

    private fun begin() {
        tumblers.clear()
        gameDifficulty = difficulty
        val first = when (starter) {
            Starter.YOU -> EMBER
            Starter.ONYX -> ONYX
            Starter.TURNS -> {
                val you = prefs.getBoolean("next_you", true)
                prefs.edit().putBoolean("next_you", !you).apply()
                if (you) EMBER else ONYX
            }
        }
        startedSide = first
        board.reset(first)
        last = null
        winLines = emptyList()
        winner = null
        nextTurn()
    }

    private fun nextTurn() {
        if (board.toMove == EMBER) {
            phase = Phase.PLAYER
            status = if (board.moves < 2) "Your move. Tap a column, or hold and slide to aim." else "Your move."
            changed()
        } else {
            think()
        }
    }

    private fun think() {
        phase = Phase.THINKING
        status = "Onyx is thinking…"
        onyxTarget = null
        changed()
        val gen = generation
        val snapshot = board.copy()
        val level = gameDifficulty
        job = scope.launch {
            val t0 = System.nanoTime()
            val col = withContext(thinker) { brain.choose(snapshot, level) }
            val spent = (System.nanoTime() - t0) / 1_000_000
            val pause = when (level) {
                Difficulty.EASY -> 650L
                Difficulty.MEDIUM -> 750L
                Difficulty.HARD -> 450L
            }
            delay(max(0L, pause - spent))
            if (gen == generation && phase == Phase.THINKING) onyxTarget = col
        }
    }

    // ---- Touch -----------------------------------------------------------

    fun touch(col: Int) {
        if (phase != Phase.PLAYER) return
        touching = true
        aim(col)
    }

    fun release(col: Int) {
        touching = false
        if (phase != Phase.PLAYER) return
        aim(col)
        if (!board.canPlay(col)) {
            refusedCol = col
            refusedAt = time
            sounds.play(Sfx.NOPE)
            return
        }
        drop(col)
    }

    private fun aim(col: Int) {
        val c = col.coerceIn(0, COLS - 1)
        if (c != holdCol) {
            holdCol = c
            sounds.play(Sfx.TICK, volume = 0.5f)
        }
    }

    private fun drop(col: Int) {
        val side = board.toMove
        val row = board.play(col)
        falling = Falling(col, row, side)
        last = Spot(col, row)
        phase = Phase.DROPPING
        changed()
    }

    private fun landed() {
        falling = null
        val w = board.winner()
        when {
            w != null -> finish(w)
            board.full -> finish(EMPTY)
            else -> nextTurn()
        }
    }

    private fun finish(w: Int) {
        phase = Phase.OVER
        winner = w
        winLines = board.winningLines()
        winTime = time
        val level = gameDifficulty
        when (w) {
            EMBER -> {
                bump("w_${level.name}")
                status = "Four embers in a row. You win!"
                sounds.play(Sfx.WIN)
                burst(warm = true, count = 16)
                onImpact(true)
            }
            ONYX -> {
                bump("l_${level.name}")
                status = "Onyx made four. This one goes to the computer."
                sounds.play(Sfx.LOSE)
                burst(warm = false, count = 8)
            }
            else -> {
                bump("d_${level.name}")
                status = "The board is full. It's a draw."
                sounds.play(Sfx.DRAW)
            }
        }
        changed()
    }

    private fun burst(warm: Boolean, count: Int) {
        for (spot in winLines.flatten().distinct()) {
            val x = spot.col.toFloat()
            val y = (ROWS - 1 - spot.row).toFloat()
            repeat(count) {
                val a = rng.nextFloat() * 6.2832f
                val speed = 1.5f + rng.nextFloat() * 4f
                sparks.add(Spark(x, y, cos(a) * speed, sin(a) * speed - 2.5f, 0.7f + rng.nextFloat() * 0.9f, warm))
            }
        }
    }

    // ---- Animation -------------------------------------------------------

    fun step(dt: Float) {
        time += dt

        // The held pieces glide after their columns.
        holdX += (holdCol - holdX) * min(1f, dt * 20f)
        val target = onyxTarget
        if (phase == Phase.THINKING) {
            if (target == null) {
                // Hovering while it thinks, drifting a little side to side.
                val wander = 3f + 2.2f * sin(time * 1.3f) * sin(time * 0.47f + 1f)
                onyxX += (wander - onyxX) * min(1f, dt * 2.5f)
            } else {
                val d = target - onyxX
                val stepX = 11f * dt
                onyxX = if (abs(d) <= stepX) target.toFloat() else onyxX + stepX * (if (d > 0) 1 else -1)
                val over = floor(onyxX + 0.5f).toInt()
                if (over != onyxLastCol) {
                    onyxLastCol = over
                    sounds.play(Sfx.TICK, volume = 0.35f)
                }
                if (onyxX == target.toFloat()) {
                    onyxTarget = null
                    drop(target)
                }
            }
        }

        falling?.let { f ->
            f.vy += GRAVITY * dt
            f.y += f.vy * dt
            if (f.y >= f.target) {
                f.y = f.target
                val speed = f.vy
                if (f.impacts == 0) {
                    sounds.play(Sfx.DROP)
                    onImpact(false)
                } else if (speed > 2f) {
                    sounds.play(Sfx.DROP, volume = min(0.5f, speed / 14f), rate = 1.25f)
                }
                f.impacts++
                f.vy = -speed * BOUNCE
                if (f.impacts >= 3 || speed < 2.2f) landed()
            }
        }

        if (phase == Phase.CLEARING) {
            var any = false
            for (t in tumblers) {
                if (t.wait > 0f) {
                    t.wait -= dt
                } else {
                    t.vy += GRAVITY * 0.8f * dt
                    t.y += t.vy * dt
                }
                if (t.y < ROWS + 4f) any = true
            }
            if (!any) begin()
        }

        if (sparks.isNotEmpty()) {
            val iter = sparks.iterator()
            while (iter.hasNext()) {
                val s = iter.next()
                s.age += dt
                if (s.age >= s.life) {
                    iter.remove()
                    continue
                }
                s.vy += 7f * dt
                s.vx *= 1f - 0.8f * dt
                s.x += s.vx * dt
                s.y += s.vy * dt
            }
        }
    }

    // Who goes first this game, for the screen.
    val firstSide: Int get() = startedSide

    fun dispose() {
        job?.cancel()
        thinker.close()
    }
}
