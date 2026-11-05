package studio.apps.irontide

import kotlin.random.Random

// The computer opponent. Each difficulty is a different way of choosing the
// next cell from what the computer has been told so far. None of them can see
// the player's ships.

enum class Difficulty(val label: String, val blurb: String) {
    EASY("Easy", "Fires at random"),
    MEDIUM("Medium", "Hunts around every hit"),
    HARD("Hard", "Charts where ships can hide"),
}

fun interface Admiral {
    fun choose(intel: Intel, rng: Random): Cell

    companion object {
        fun of(difficulty: Difficulty): Admiral = when (difficulty) {
            Difficulty.EASY -> Admiral { intel, rng -> intel.openCells().random(rng) }
            Difficulty.MEDIUM -> Admiral { intel, rng -> hunter(intel, rng) }
            Difficulty.HARD -> Admiral { intel, rng -> strategist(intel, rng) }
        }
    }
}

private val steps = listOf(Cell(-1, 0), Cell(1, 0), Cell(0, -1), Cell(0, 1))

private fun Cell.step(d: Cell, n: Int = 1) = Cell(row + d.row * n, col + d.col * n)

// Medium: random shots until something is hit. Then it tries the cells next
// to the hit, and once two hits line up it keeps firing along that line in
// both directions until a ship goes down.
private fun hunter(intel: Intel, rng: Random): Cell {
    val hits = intel.openHits()
    if (hits.isEmpty()) return intel.openCells().random(rng)
    val hitSet = hits.toSet()

    // Ends of a line of two or more hits.
    val lineEnds = mutableListOf<Cell>()
    for (hit in hits) {
        for (d in steps) {
            if (hit.step(d) !in hitSet) continue
            // Walk back from this hit, away from its neighbor, past any hits.
            var n = 1
            while (hit.step(d, -n) in hitSet) n++
            val end = hit.step(d, -n)
            if (end.inBounds && intel.isOpen(end)) lineEnds += end
        }
    }
    if (lineEnds.isNotEmpty()) return lineEnds.random(rng)

    val neighbors = hits.flatMap { hit -> steps.map { hit.step(it) } }
        .filter { it.inBounds && intel.isOpen(it) }
    if (neighbors.isNotEmpty()) return neighbors.random(rng)
    return intel.openCells().random(rng)
}

// Hard: for every ship still afloat, count every way it could lie on the grid
// given the misses and wrecks seen so far. Cells that more placements cover
// are more likely to hold a ship.
//
// With no open hits it hunts on a lattice spaced by the shortest ship left,
// since every remaining ship must cross that lattice, and picks the lattice
// cell with the most placements. After a hit it counts only placements that
// run through the open hits, weighting those that cover more of them, which
// follows a line of hits and finishes the ship.
fun densityMap(intel: Intel): DoubleArray {
    val open = intel.openHits().toSet()
    val weights = DoubleArray(SIZE * SIZE)
    for (type in intel.remaining) {
        for (horizontal in listOf(true, false)) {
            for (row in 0 until SIZE) for (col in 0 until SIZE) {
                val ship = Ship(type, row, col, horizontal)
                if (!ship.fits()) continue
                val cells = ship.cells
                if (cells.any { intel.mark(it) == Mark.MISS || intel.sunk[it.index] }) continue
                val covered = cells.count { it in open }
                val w = when {
                    open.isEmpty() -> 1.0
                    covered == 0 -> continue
                    else -> Math.pow(20.0, covered.toDouble())
                }
                for (c in cells) if (intel.isOpen(c)) weights[c.index] += w
            }
        }
    }
    return weights
}

private fun strategist(intel: Intel, rng: Random): Cell {
    val weights = densityMap(intel)
    val open = intel.openCells()
    var candidates = open
    if (intel.openHits().isEmpty()) {
        val spacing = intel.remaining.minOf { it.length }
        val lattice = open.filter { (it.row + it.col) % spacing == 0 && weights[it.index] > 0 }
        if (lattice.isNotEmpty()) candidates = lattice
    }
    val best = candidates.maxOf { weights[it.index] }
    if (best <= 0.0) return open.random(rng)
    return candidates.filter { weights[it.index] >= best * 0.999 }.random(rng)
}

// A whole match without the screen: the player's fleet, the computer's fleet,
// and what each side knows of the other.
class Engine(val rng: Random = Random.Default) {
    val home = Fleet()
    val enemy = Fleet()
    val playerIntel = Intel()
    val enemyIntel = Intel()
    var difficulty = Difficulty.MEDIUM

    val playerWon: Boolean get() = enemy.allSunk
    val enemyWon: Boolean get() = home.allSunk
    val over: Boolean get() = playerWon || enemyWon

    fun startBattle() {
        enemy.clear()
        enemy.randomize(rng)
        home.clearShots()
        playerIntel.clear()
        enemyIntel.clear()
    }

    fun playerFire(cell: Cell): ShotResult {
        val result = enemy.receive(cell)
        playerIntel.record(cell, result)
        return result
    }

    fun enemyAim(): Cell = Admiral.of(difficulty).choose(enemyIntel, rng)

    fun enemyFire(cell: Cell): ShotResult {
        val result = home.receive(cell)
        enemyIntel.record(cell, result)
        return result
    }
}
