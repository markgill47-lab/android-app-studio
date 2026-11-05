package studio.apps.irontide

import kotlin.random.Random

// The rules of the classic pencil-and-paper naval guessing game: two 10x10
// oceans, five ships each, one shot per turn, hits and misses called out, a
// sinking announced by ship type, and the first fleet wiped out loses.
// Nothing in this file touches Android, so it can be tested on a plain JVM.

const val SIZE = 10

enum class ShipType(val label: String, val length: Int) {
    CARRIER("Carrier", 5),
    DREADNOUGHT("Dreadnought", 4),
    CRUISER("Cruiser", 3),
    SUBMARINE("Submarine", 3),
    DESTROYER("Destroyer", 2),
}

data class Cell(val row: Int, val col: Int) {
    val inBounds: Boolean get() = row in 0 until SIZE && col in 0 until SIZE
    val index: Int get() = row * SIZE + col

    // "C7": the row letter, then the column number, the way the game is called.
    val name: String get() = "${'A' + row}${col + 1}"

    companion object {
        val all: List<Cell> = List(SIZE * SIZE) { Cell(it / SIZE, it % SIZE) }
        fun of(index: Int): Cell = all[index]
    }
}

data class Ship(val type: ShipType, val row: Int, val col: Int, val horizontal: Boolean) {
    val cells: List<Cell> = List(type.length) { i ->
        if (horizontal) Cell(row, col + i) else Cell(row + i, col)
    }

    fun fits(): Boolean = cells.all { it.inBounds }
    fun covers(cell: Cell): Boolean = cell in cells
}

sealed class ShotResult {
    data object Miss : ShotResult()
    data class Hit(val ship: Ship) : ShotResult()
    data class Sunk(val ship: Ship) : ShotResult()
}

// One side's own ocean: where its ships are and every shot fired into it.
class Fleet {
    private val placed = linkedMapOf<ShipType, Ship>()
    private val shotAt = BooleanArray(SIZE * SIZE)

    val ships: Collection<Ship> get() = placed.values
    val complete: Boolean get() = placed.size == ShipType.entries.size
    val allSunk: Boolean get() = complete && placed.values.all { isSunk(it) }

    fun ship(type: ShipType): Ship? = placed[type]
    fun shipAt(cell: Cell): Ship? = placed.values.firstOrNull { it.covers(cell) }
    fun wasShot(cell: Cell): Boolean = shotAt[cell.index]
    fun isSunk(ship: Ship): Boolean = ship.cells.all { shotAt[it.index] }

    // A ship may go anywhere on the grid that no other ship already takes.
    // Ships may touch, as in the classic rules.
    fun canPlace(ship: Ship): Boolean =
        ship.fits() && placed.values.none { other ->
            other.type != ship.type && other.cells.any { ship.covers(it) }
        }

    fun place(ship: Ship): Boolean {
        if (!canPlace(ship)) return false
        placed[ship.type] = ship
        return true
    }

    fun remove(type: ShipType) {
        placed.remove(type)
    }

    fun clear() {
        placed.clear()
        shotAt.fill(false)
    }

    fun clearShots() {
        shotAt.fill(false)
    }

    fun randomize(rng: Random) {
        placed.clear()
        // Largest first, so the hard fits are found while the ocean is empty.
        for (type in ShipType.entries.sortedByDescending { it.length }) {
            while (true) {
                val horizontal = rng.nextBoolean()
                val ship = Ship(
                    type,
                    row = rng.nextInt(if (horizontal) SIZE else SIZE - type.length + 1),
                    col = rng.nextInt(if (horizontal) SIZE - type.length + 1 else SIZE),
                    horizontal = horizontal,
                )
                if (place(ship)) break
            }
        }
    }

    fun receive(cell: Cell): ShotResult {
        require(!shotAt[cell.index]) { "${cell.name} was already shot" }
        shotAt[cell.index] = true
        val ship = shipAt(cell) ?: return ShotResult.Miss
        return if (isSunk(ship)) ShotResult.Sunk(ship) else ShotResult.Hit(ship)
    }
}

enum class Mark { UNKNOWN, MISS, HIT }

// What a shooter knows about the enemy ocean: only the calls it has heard.
// A hit says nothing about which ship was struck. A sinking names the ship,
// and the sunk ship's position is shown to the shooter as a wreck.
class Intel {
    val marks = Array(SIZE * SIZE) { Mark.UNKNOWN }
    val sunk = BooleanArray(SIZE * SIZE)
    val sunkShips = mutableListOf<Ship>()

    var shots = 0
        private set
    var hits = 0
        private set

    val remaining: List<ShipType>
        get() = ShipType.entries.filter { type -> sunkShips.none { it.type == type } }

    fun mark(cell: Cell): Mark = marks[cell.index]
    fun isOpen(cell: Cell): Boolean = marks[cell.index] == Mark.UNKNOWN
    fun openCells(): List<Cell> = Cell.all.filter { isOpen(it) }

    // Hits that do not yet belong to a ship known to be sunk.
    fun openHits(): List<Cell> = Cell.all.filter { marks[it.index] == Mark.HIT && !sunk[it.index] }

    fun record(cell: Cell, result: ShotResult) {
        shots++
        when (result) {
            ShotResult.Miss -> marks[cell.index] = Mark.MISS
            is ShotResult.Hit -> {
                marks[cell.index] = Mark.HIT
                hits++
            }
            is ShotResult.Sunk -> {
                marks[cell.index] = Mark.HIT
                hits++
                sunkShips += result.ship
                result.ship.cells.forEach { sunk[it.index] = true }
            }
        }
    }

    fun clear() {
        marks.fill(Mark.UNKNOWN)
        sunk.fill(false)
        sunkShips.clear()
        shots = 0
        hits = 0
    }
}
