package studio.apps.irontide

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Phase { SETUP, PLAYER, BUSY, OVER }

// A ship being dragged around the fleet grid during setup.
class Drag(val original: Ship, val grab: Int) {
    var ship by mutableStateOf(original)
    var valid by mutableStateOf(true)
}

// Runs a match on screen: placement, the turn order, the timing between a
// shot and its result, and the effects and sounds that go with each.
class Battle(
    private val sounds: Sounds,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
) {
    val engine = Engine()
    val homeFx = EffectLayer()
    val enemyFx = EffectLayer()

    var phase by mutableStateOf(Phase.SETUP)
        private set
    var status by mutableStateOf("Place your fleet.")
        private set
    var lastShotHome by mutableStateOf<Cell?>(null)
        private set
    var lastShotEnemy by mutableStateOf<Cell?>(null)
        private set

    // Bumped whenever the boards change, so the screen recomposes.
    var version by mutableIntStateOf(0)
        private set

    var difficulty by mutableStateOf(
        Difficulty.entries.firstOrNull { it.name == prefs.getString("difficulty", null) } ?: Difficulty.MEDIUM,
    )
        private set
    var soundOn by mutableStateOf(prefs.getBoolean("sound", true))
        private set
    var horizontal by mutableStateOf(true)
        private set
    var selected by mutableStateOf<ShipType?>(ShipType.CARRIER)
        private set
    var drag by mutableStateOf<Drag?>(null)
        private set
    var rejected by mutableStateOf<Ship?>(null)
        private set
    private var rejectedUntil = 0f
    var showResult by mutableStateOf(false)
    var enemyActing by mutableStateOf(false)
        private set
    var onHit: () -> Unit = {}
    private var job: Job? = null

    init {
        sounds.enabled = soundOn
    }

    private fun changed() {
        version++
    }

    fun step(dt: Float) {
        homeFx.step(dt, burningHome())
        enemyFx.step(dt, burningEnemy())
        if (rejected != null && homeFx.time > rejectedUntil) rejected = null
    }

    fun burningHome(): List<Cell> =
        engine.home.ships.flatMap { s -> s.cells.filter { engine.home.wasShot(it) } }

    fun burningEnemy(): List<Cell> =
        Cell.all.filter { engine.playerIntel.mark(it) == Mark.HIT }

    // ---- Settings --------------------------------------------------------

    fun chooseDifficulty(d: Difficulty) {
        difficulty = d
        prefs.edit().putString("difficulty", d.name).apply()
    }

    fun toggleSound() {
        soundOn = !soundOn
        sounds.enabled = soundOn
        prefs.edit().putBoolean("sound", soundOn).apply()
        if (soundOn) sounds.play(Sfx.TICK)
    }

    // ---- Setup -----------------------------------------------------------

    private val fleet get() = engine.home

    fun isPlaced(type: ShipType) = fleet.ship(type) != null
    val ready: Boolean get() = fleet.complete && drag == null

    private fun nextUnplaced(): ShipType? = ShipType.entries.firstOrNull { !isPlaced(it) }

    private fun fitted(type: ShipType, row: Int, col: Int, horizontal: Boolean) = Ship(
        type,
        row = if (horizontal) row.coerceIn(0, SIZE - 1) else row.coerceIn(0, SIZE - type.length),
        col = if (horizontal) col.coerceIn(0, SIZE - type.length) else col.coerceIn(0, SIZE - 1),
        horizontal = horizontal,
    )

    private fun reject(ship: Ship) {
        rejected = ship
        rejectedUntil = homeFx.time + 0.6f
        sounds.play(Sfx.BUZZ)
    }

    fun rotateNext() {
        horizontal = !horizontal
        sounds.play(Sfx.TICK)
    }

    // Choosing a ship in the tray. One already on the grid is lifted off so
    // it can be set down somewhere else.
    fun select(type: ShipType) {
        if (phase != Phase.SETUP) return
        if (isPlaced(type)) {
            fleet.remove(type)
            changed()
        }
        selected = type
        sounds.play(Sfx.TICK)
    }

    // A tap on the fleet grid: turns a ship that is there, or sets down the
    // chosen ship with its stern on the tapped cell.
    fun tapSetup(cell: Cell) {
        if (phase != Phase.SETUP) return
        val there = fleet.shipAt(cell)
        if (there != null) {
            val turned = fitted(there.type, there.row, there.col, !there.horizontal)
            if (fleet.canPlace(turned)) {
                fleet.place(turned)
                sounds.play(Sfx.TICK)
                changed()
            } else {
                reject(turned)
            }
            return
        }
        val type = selected ?: return
        val ship = fitted(type, cell.row, cell.col, horizontal)
        if (fleet.place(ship)) {
            selected = nextUnplaced()
            sounds.play(Sfx.TICK)
            changed()
        } else {
            reject(ship)
        }
    }

    fun beginDrag(cell: Cell): Boolean {
        if (phase != Phase.SETUP || drag != null) return false
        val ship = fleet.shipAt(cell) ?: return false
        fleet.remove(ship.type)
        drag = Drag(ship, ship.cells.indexOf(cell))
        changed()
        return true
    }

    fun moveDrag(cell: Cell) {
        val d = drag ?: return
        val s = d.original
        val ship = if (s.horizontal) {
            fitted(s.type, cell.row, cell.col - d.grab, true)
        } else {
            fitted(s.type, cell.row - d.grab, cell.col, false)
        }
        d.ship = ship
        d.valid = fleet.canPlace(ship)
    }

    fun endDrag() {
        val d = drag ?: return
        if (d.valid && fleet.place(d.ship)) {
            sounds.play(Sfx.TICK)
        } else {
            fleet.place(d.original)
            sounds.play(Sfx.BUZZ)
        }
        drag = null
        if (selected != null && isPlaced(selected!!)) selected = nextUnplaced()
        changed()
    }

    fun randomFleet() {
        if (phase != Phase.SETUP) return
        fleet.randomize(engine.rng)
        selected = null
        sounds.play(Sfx.TICK)
        changed()
    }

    fun clearFleet() {
        if (phase != Phase.SETUP) return
        fleet.clear()
        selected = ShipType.CARRIER
        sounds.play(Sfx.TICK)
        changed()
    }

    fun start() {
        if (phase != Phase.SETUP || !ready) return
        engine.difficulty = difficulty
        engine.startBattle()
        homeFx.clear()
        enemyFx.clear()
        lastShotHome = null
        lastShotEnemy = null
        phase = Phase.PLAYER
        status = "Your shot. Tap enemy waters to fire."
        sounds.play(Sfx.FIRE, volume = 0.5f)
        changed()
    }

    // ---- Battle ----------------------------------------------------------

    fun fire(cell: Cell) {
        if (phase != Phase.PLAYER || !engine.playerIntel.isOpen(cell)) return
        phase = Phase.BUSY
        job = scope.launch {
            sounds.play(Sfx.FIRE)
            status = "Firing at ${cell.name}…"
            enemyFx.incoming(cell, 0.55f)
            delay(550)
            val result = engine.playerFire(cell)
            lastShotEnemy = cell
            changed()
            val pause = when (result) {
                ShotResult.Miss -> {
                    enemyFx.splash(cell)
                    sounds.play(Sfx.SPLASH)
                    status = "${cell.name}: miss."
                    900L
                }
                is ShotResult.Hit -> {
                    enemyFx.explode(cell)
                    sounds.play(Sfx.HIT)
                    status = "${cell.name}: hit!"
                    1100L
                }
                is ShotResult.Sunk -> {
                    enemyFx.sink(result.ship)
                    sounds.play(Sfx.SINK)
                    status = "You sank their ${result.ship.type.label}!"
                    1900L
                }
            }
            if (engine.playerWon) {
                delay(1600)
                finish(won = true)
                return@launch
            }
            delay(pause)
            enemyTurn()
        }
    }

    private suspend fun enemyTurn() {
        enemyActing = true
        status = "The enemy is taking aim…"
        delay(650)
        val cell = engine.enemyAim()
        sounds.play(Sfx.INCOMING)
        homeFx.incoming(cell, 0.8f)
        delay(800)
        val result = engine.enemyFire(cell)
        lastShotHome = cell
        changed()
        when (result) {
            ShotResult.Miss -> {
                homeFx.splash(cell)
                sounds.play(Sfx.SPLASH, volume = 0.8f)
                status = "They fired at ${cell.name} and missed."
            }
            is ShotResult.Hit -> {
                homeFx.explode(cell)
                sounds.play(Sfx.HIT)
                onHit()
                status = "They hit your ${result.ship.type.label} at ${cell.name}!"
            }
            is ShotResult.Sunk -> {
                homeFx.sink(result.ship)
                sounds.play(Sfx.SINK)
                onHit()
                status = "They sank your ${result.ship.type.label}!"
            }
        }
        if (engine.enemyWon) {
            delay(1700)
            enemyActing = false
            finish(won = false)
            return
        }
        delay(if (result is ShotResult.Sunk) 1500L else 700L)
        enemyActing = false
        phase = Phase.PLAYER
    }

    private fun finish(won: Boolean) {
        phase = Phase.OVER
        status = if (won) "Victory! The enemy fleet is gone." else "Your fleet is lost."
        sounds.play(if (won) Sfx.WIN else Sfx.LOSE)
        showResult = true
        changed()
    }

    // Back to placement, keeping the fleet where it was.
    fun newGame() {
        job?.cancel()
        job = null
        enemyActing = false
        engine.home.clearShots()
        engine.playerIntel.clear()
        engine.enemyIntel.clear()
        engine.enemy.clear()
        homeFx.clear()
        enemyFx.clear()
        lastShotHome = null
        lastShotEnemy = null
        showResult = false
        selected = nextUnplaced()
        phase = Phase.SETUP
        status = "Place your fleet."
        changed()
    }
}
