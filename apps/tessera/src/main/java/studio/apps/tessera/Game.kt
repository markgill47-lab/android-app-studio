package studio.apps.tessera

// The rules. The big board is nine small boards, numbered 0 to 8 left to
// right and top to bottom, and each small board has nine cells numbered the
// same way. A move is board * 9 + cell.
//
// Win a small board to claim that square of the big board. Three claimed
// squares in a row wins the game. The cell you play in sends the other side
// to the small board in the same position. If that board is already won or
// full, they may play in any open board. A drawn small board counts for
// nobody.

const val NONE = 0
const val SUN = 1   // the player: amber crosses
const val JADE = 2  // the computer: jade rings
const val TIED = 3  // a small board that filled up with no line

fun other(side: Int) = 3 - side

object Lines {
    // The eight lines of a 3 by 3 grid, as masks of cell bits.
    val ALL = intArrayOf(
        0b000000111, 0b000111000, 0b111000000,
        0b001001001, 0b010010010, 0b100100100,
        0b100010001, 0b001010100,
    )

    // WINS[mask] is true when the cells in mask hold a full line.
    val WINS = BooleanArray(512) { m -> ALL.any { (m and it) == it } }

    // The line found in mask, or 0.
    fun lineIn(mask: Int): Int = ALL.firstOrNull { (mask and it) == it } ?: 0

    // Cells that would complete a line for mask, given the cells already
    // taken by either side.
    val THREATS = IntArray(512 * 512)

    fun threats(mine: Int, taken: Int): Int = THREATS[(mine shl 9) or taken]

    init {
        for (mine in 0 until 512) {
            for (taken in 0 until 512) {
                if (mine and taken.inv() != 0) continue
                var t = 0
                for (c in 0 until 9) {
                    val bit = 1 shl c
                    if (taken and bit == 0 && WINS[mine or bit]) t = t or bit
                }
                THREATS[(mine shl 9) or taken] = t
            }
        }
    }
}

class Game {
    // Cells held by each side in each small board, as 9-bit masks.
    val sun = IntArray(9)
    val jade = IntArray(9)
    // NONE while a small board is open, otherwise SUN, JADE or TIED.
    val owner = IntArray(9)
    // The small board the side to move must play in, or -1 for any open one.
    var forced = -1
        private set
    var toMove = SUN
        private set
    // NONE while the game runs, then SUN, JADE or TIED.
    var result = NONE
        private set
    var moves = 0
        private set

    fun reset(first: Int) {
        sun.fill(0)
        jade.fill(0)
        owner.fill(NONE)
        forced = -1
        toMove = first
        result = NONE
        moves = 0
    }

    fun copyFrom(g: Game) {
        g.sun.copyInto(sun)
        g.jade.copyInto(jade)
        g.owner.copyInto(owner)
        forced = g.forced
        toMove = g.toMove
        result = g.result
        moves = g.moves
    }

    fun copy(): Game = Game().also { it.copyFrom(this) }

    fun taken(b: Int): Int = sun[b] or jade[b]

    fun cell(b: Int, c: Int): Int = when {
        sun[b] and (1 shl c) != 0 -> SUN
        jade[b] and (1 shl c) != 0 -> JADE
        else -> NONE
    }

    fun mine(side: Int, b: Int): Int = if (side == SUN) sun[b] else jade[b]

    // The big-board squares one side has claimed, as a 9-bit mask.
    fun claimed(side: Int): Int {
        var m = 0
        for (b in 0 until 9) if (owner[b] == side) m = m or (1 shl b)
        return m
    }

    fun boardOpen(b: Int): Boolean = owner[b] == NONE

    // True when the side to move may play in small board b.
    fun allowed(b: Int): Boolean =
        result == NONE && owner[b] == NONE && (forced < 0 || forced == b)

    fun legal(move: Int): Boolean {
        val b = move / 9
        val c = move % 9
        return allowed(b) && taken(b) and (1 shl c) == 0
    }

    // Writes the legal moves into out and returns how many there are.
    fun legalMoves(out: IntArray): Int {
        if (result != NONE) return 0
        var n = 0
        if (forced >= 0) {
            var free = 511 and taken(forced).inv()
            while (free != 0) {
                val c = Integer.numberOfTrailingZeros(free)
                out[n++] = forced * 9 + c
                free = free and (free - 1)
            }
        } else {
            for (b in 0 until 9) {
                if (owner[b] != NONE) continue
                var free = 511 and taken(b).inv()
                while (free != 0) {
                    val c = Integer.numberOfTrailingZeros(free)
                    out[n++] = b * 9 + c
                    free = free and (free - 1)
                }
            }
        }
        return n
    }

    // What a move did, for the screen to animate.
    class Outcome(val claimed: Boolean, val tied: Boolean, val ended: Boolean)

    // Plays a legal move for the side to move.
    fun play(move: Int): Outcome {
        val b = move / 9
        val c = move % 9
        val side = toMove
        val bit = 1 shl c
        var claimed = false
        var tied = false
        if (side == SUN) sun[b] = sun[b] or bit else jade[b] = jade[b] or bit
        if (Lines.WINS[mine(side, b)]) {
            owner[b] = side
            claimed = true
            if (Lines.WINS[claimed(side)]) result = side
        } else if (taken(b) == 511) {
            owner[b] = TIED
            tied = true
        }
        moves++
        toMove = other(side)
        forced = if (owner[c] == NONE) c else -1
        if (result == NONE && owner.none { it == NONE }) result = TIED
        return Outcome(claimed, tied, result != NONE)
    }

    // The big-board line that won the game, as a mask of squares, or 0.
    fun winningLine(): Int = if (result == SUN || result == JADE) Lines.lineIn(claimed(result)) else 0
}
