package studio.apps.tessera

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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class Phase { PLAYER, THINKING, OVER, CLEARING }

enum class Starter(val label: String) {
    YOU("You"),
    JADE("Jade"),
    TURNS("Take turns"),
}

val BOARD_NAMES = arrayOf(
    "top-left", "top", "top-right",
    "left", "center", "right",
    "bottom-left", "bottom", "bottom-right",
)

// A spark thrown off a claimed square or the winning line, in big-board
// units: 0 to 3 across and down.
class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, val life: Float, val side: Int) {
    var age = 0f
}

// Runs a game on screen: whose turn it is, the computer's thinking, the
// timing of every animation, the sounds, and the record.
class Match(
    private val sounds: Sounds,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
) {
    val game = Game()
    private val brain = Brain()
    // One thread for the computer, so two searches never overlap.
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
    var showRules by mutableStateOf(!prefs.getBoolean("seen_rules", false))
        private set

    // ---- Animation state, read by the drawing every frame -----------------

    var time = 0f
        private set
    // When each cell got its mark, and when each small board was decided.
    val placedAt = FloatArray(81) { -100f }
    val decidedAt = FloatArray(9) { -100f }
    var last = -1
        private set
    // The glowing frame that shows where the side to move may play. It
    // glides from the board just played in to the one the move sends to.
    var frameX = 1f
        private set
    var frameY = 1f
        private set
    var frameShown = 0f
        private set
    var winLine = 0
        private set
    var winTime = 0f
        private set
    val sparks = ArrayList<Spark>()
    // A tap on a board you may not play flashes the boards you may.
    var refusedAt = -10f
        private set
    // The tiles of the last game, swept off while a new one starts.
    val ghost = Game()
    var clearAt = -10f
        private set

    var onImpact: (strong: Boolean) -> Unit = {}

    private var job: Job? = null
    private var generation = 0
    private var started = false

    init {
        sounds.enabled = soundOn
    }

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
        if (phase == Phase.OVER || (game.moves == 0 && phase == Phase.PLAYER)) gameDifficulty = d
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

    fun openRules() {
        showRules = true
        sounds.play(Sfx.TICK)
    }

    fun closeRules() {
        showRules = false
        prefs.edit().putBoolean("seen_rules", true).apply()
        sounds.play(Sfx.TICK)
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
        sparks.clear()
        winLine = 0
        if (game.moves == 0) {
            begin()
            return
        }
        // Sweep the old tiles off, from the middle outward.
        ghost.copyFrom(game)
        clearAt = time
        game.reset(SUN)
        placedAt.fill(-100f)
        decidedAt.fill(-100f)
        last = -1
        phase = Phase.CLEARING
        status = "Clearing the board…"
        sounds.play(Sfx.CLEAR)
        changed()
    }

    private fun begin() {
        gameDifficulty = difficulty
        val first = when (starter) {
            Starter.YOU -> SUN
            Starter.JADE -> JADE
            Starter.TURNS -> {
                val you = prefs.getBoolean("next_you", true)
                prefs.edit().putBoolean("next_you", !you).apply()
                if (you) SUN else JADE
            }
        }
        game.reset(first)
        placedAt.fill(-100f)
        decidedAt.fill(-100f)
        last = -1
        winLine = 0
        frameX = 1f
        frameY = 1f
        nextTurn()
    }

    private fun nextTurn() {
        if (game.toMove == SUN) {
            phase = Phase.PLAYER
            status = when {
                game.moves == 0 -> "Your move. Play any square on any board."
                game.forced >= 0 -> "Your move, in the ${BOARD_NAMES[game.forced]} board."
                else -> "That board is closed. Play in any open board."
            }
            changed()
        } else {
            think()
        }
    }

    private fun think() {
        phase = Phase.THINKING
        status = "Jade is thinking…"
        changed()
        val gen = generation
        val snapshot = game.copy()
        val level = gameDifficulty
        job = scope.launch {
            val t0 = System.nanoTime()
            val move = withContext(thinker) { brain.choose(snapshot, level) }
            val spent = (System.nanoTime() - t0) / 1_000_000
            val pause = when (level) {
                Difficulty.EASY -> 650L
                Difficulty.MEDIUM -> 750L
                Difficulty.HARD -> 350L
            }
            // Wait at least long enough for the last move's animation.
            delay(max(0L, pause - spent))
            if (gen == generation && phase == Phase.THINKING) place(move)
        }
    }

    // ---- Touch -----------------------------------------------------------

    fun tap(move: Int) {
        if (phase != Phase.PLAYER || move < 0) return
        val b = move / 9
        if (!game.allowed(b)) {
            refusedAt = time
            sounds.play(Sfx.NOPE)
            onImpact(false)
            status = if (game.forced >= 0) {
                "Not there. This move has to go in the ${BOARD_NAMES[game.forced]} board."
            } else {
                "That board is already decided. Pick an open one."
            }
            changed()
            return
        }
        if (!game.legal(move)) {
            sounds.play(Sfx.NOPE)
            return
        }
        place(move)
    }

    private fun place(move: Int) {
        val side = game.toMove
        val b = move / 9
        val out = game.play(move)
        placedAt[move] = time
        last = move
        sounds.play(Sfx.PLACE, rate = if (side == SUN) 1f else 0.84f)
        onImpact(false)
        // The frame jumps to the board just played in, then glides on.
        frameX = (b % 3).toFloat()
        frameY = (b / 3).toFloat()
        if (out.claimed || out.tied) {
            decidedAt[b] = time
            if (out.claimed) {
                scope.launch {
                    delay(170)
                    sounds.play(if (side == SUN) Sfx.CLAIM else Sfx.YIELD)
                    onImpact(side == SUN)
                }
                burst(b, side, 14)
            } else {
                scope.launch {
                    delay(170)
                    sounds.play(Sfx.STALE)
                }
            }
        }
        if (out.ended) finish(out.claimed) else nextTurn()
    }

    private fun finish(lastClaimed: Boolean) {
        phase = Phase.OVER
        val w = game.result
        winLine = game.winningLine()
        winTime = time + if (lastClaimed) 0.55f else 0.2f
        val level = gameDifficulty
        val gen = generation
        when (w) {
            SUN -> {
                bump("w_${level.name}")
                status = "Three in a row on the big board. You win!"
            }
            JADE -> {
                bump("l_${level.name}")
                status = "Jade lined up three. This one goes to the computer."
            }
            else -> {
                bump("d_${level.name}")
                status = "Every board is decided and nobody has a line. A draw."
            }
        }
        scope.launch {
            delay(if (lastClaimed) 650 else 250)
            if (gen != generation) return@launch
            when (w) {
                SUN -> {
                    sounds.play(Sfx.WIN)
                    onImpact(true)
                    for (b in 0 until 9) if (winLine and (1 shl b) != 0) burst(b, SUN, 22)
                }
                JADE -> {
                    sounds.play(Sfx.LOSE)
                    for (b in 0 until 9) if (winLine and (1 shl b) != 0) burst(b, JADE, 8)
                }
                else -> sounds.play(Sfx.DRAW)
            }
        }
        changed()
    }

    private fun burst(b: Int, side: Int, count: Int) {
        val x = (b % 3) + 0.5f
        val y = (b / 3) + 0.5f
        repeat(count) {
            val a = rng.nextFloat() * 6.2832f
            val speed = 0.5f + rng.nextFloat() * 1.4f
            sparks.add(Spark(x, y, cos(a) * speed, sin(a) * speed - 0.6f, 0.6f + rng.nextFloat() * 0.8f, side))
        }
    }

    // ---- Animation -------------------------------------------------------

    fun step(dt: Float) {
        time += dt

        // The frame glides to the board the side to move must play in, and
        // fades out when there is no single board to show.
        val target = game.forced
        val wantFrame = phase != Phase.OVER && phase != Phase.CLEARING && target >= 0
        if (target >= 0) {
            val k = min(1f, dt * 9f)
            frameX += ((target % 3) - frameX) * k
            frameY += ((target / 3) - frameY) * k
        }
        frameShown += ((if (wantFrame) 1f else 0f) - frameShown) * min(1f, dt * 10f)

        if (phase == Phase.CLEARING && time - clearAt > CLEAR_SECONDS) {
            ghost.reset(SUN)
            begin()
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
                s.vy += 1.6f * dt
                s.vx *= 1f - 1.2f * dt
                s.x += s.vx * dt
                s.y += s.vy * dt
            }
        }
    }

    fun dispose() {
        job?.cancel()
        thinker.close()
    }

    companion object {
        const val CLEAR_SECONDS = 0.9f
    }
}
