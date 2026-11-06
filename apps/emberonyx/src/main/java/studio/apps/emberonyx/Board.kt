package studio.apps.emberonyx

// The rules of the game, with no Android in them, so they can be tested on a
// desktop JVM. Seven columns, six rows, pieces fall to the lowest open hole,
// and four of one colour in a line (across, up, or on a slant) wins.

const val COLS = 7
const val ROWS = 6

const val EMPTY = 0
const val EMBER = 1 // the player's red pieces
const val ONYX = 2  // the computer's black pieces

fun other(side: Int) = if (side == EMBER) ONYX else EMBER

// A cell by column (0 is the left edge) and row (0 is the bottom).
data class Spot(val col: Int, val row: Int)

class Board {
    // cells[col * ROWS + row] holds EMPTY, EMBER or ONYX.
    private val cells = IntArray(COLS * ROWS)
    private val heights = IntArray(COLS)
    private val history = ArrayList<Int>()

    // Whose piece goes in next. Set by whoever starts the game.
    var toMove = EMBER
        private set

    val moves: Int get() = history.size
    val lastColumn: Int? get() = history.lastOrNull()
    val full: Boolean get() = history.size == COLS * ROWS

    fun reset(first: Int) {
        cells.fill(EMPTY)
        heights.fill(0)
        history.clear()
        toMove = first
    }

    fun at(col: Int, row: Int): Int = cells[col * ROWS + row]
    fun height(col: Int): Int = heights[col]
    fun canPlay(col: Int): Boolean = col in 0 until COLS && heights[col] < ROWS
    fun legalColumns(): List<Int> = (0 until COLS).filter { canPlay(it) }

    // Drops a piece for the side to move. Returns the row it lands in.
    fun play(col: Int): Int {
        require(canPlay(col)) { "column $col is full" }
        val row = heights[col]
        cells[col * ROWS + row] = toMove
        heights[col] = row + 1
        history.add(col)
        toMove = other(toMove)
        return row
    }

    fun undo() {
        val col = history.removeAt(history.size - 1)
        val row = heights[col] - 1
        cells[col * ROWS + row] = EMPTY
        heights[col] = row
        toMove = other(toMove)
    }

    fun copy(): Board {
        val b = Board()
        b.reset(firstMover())
        for (c in history) b.play(c)
        return b
    }

    private fun firstMover(): Int = if (history.size % 2 == 0) toMove else other(toMove)

    // Each line of four or more through the last piece played, in order
    // from one end to the other. Empty when that piece did not win.
    fun winningLines(): List<List<Spot>> {
        val col = lastColumn ?: return emptyList()
        val row = heights[col] - 1
        val side = at(col, row)
        val out = ArrayList<List<Spot>>()
        for ((dc, dr) in DIRECTIONS) {
            val line = ArrayList<Spot>()
            line.add(Spot(col, row))
            for (sign in intArrayOf(1, -1)) {
                var c = col + dc * sign
                var r = row + dr * sign
                while (c in 0 until COLS && r in 0 until ROWS && at(c, r) == side) {
                    line.add(Spot(c, r))
                    c += dc * sign
                    r += dr * sign
                }
            }
            if (line.size >= 4) out.add(line.sortedWith(compareBy({ it.col }, { it.row })))
        }
        return out
    }

    fun winningLine(): List<Spot> = winningLines().flatten().distinct()

    val won: Boolean get() = winningLine().isNotEmpty()

    // The winner, if the last move won. Draw when the board fills without one.
    fun winner(): Int? {
        val col = lastColumn ?: return null
        return if (won) at(col, heights[col] - 1) else null
    }

    // The position as two bit masks for the computer players: bit col*7+row.
    fun bits(side: Int): Long {
        var b = 0L
        for (c in 0 until COLS) for (r in 0 until heights[c]) {
            if (cells[c * ROWS + r] == side) b = b or (1L shl (c * 7 + r))
        }
        return b
    }

    companion object {
        private val DIRECTIONS = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
    }
}
