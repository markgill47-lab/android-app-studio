package studio.apps.emberonyx

import kotlin.random.Random

// The three computer players. All of them work on bit masks: bit col*7+row
// is one cell, with a spare bit on top of each column so that shifting a
// mask by 7 moves it one column across without wrapping into the next.

enum class Difficulty(val label: String, val blurb: String) {
    EASY("Easy", "Drops its pieces anywhere, at random."),
    MEDIUM("Medium", "Takes a win, blocks yours, and likes the middle."),
    HARD("Hard", "Looks many moves ahead and sets traps."),
}

object Bits {
    private const val H = ROWS
    private const val H1 = ROWS + 1

    val BOTTOM: Long = (0 until COLS).fold(0L) { a, c -> a or (1L shl (c * H1)) }
    val FULL: Long = BOTTOM * ((1L shl H) - 1)

    // Rows counted from 1 at the bottom. The side that moved first wants its
    // threats on odd rows, the other side on even rows.
    val ODD_ROWS: Long = BOTTOM or (BOTTOM shl 2) or (BOTTOM shl 4)
    val EVEN_ROWS: Long = (BOTTOM shl 1) or (BOTTOM shl 3) or (BOTTOM shl 5)

    fun column(col: Int): Long = ((1L shl H) - 1) shl (col * H1)
    fun colOf(bit: Long): Int = java.lang.Long.numberOfTrailingZeros(bit) / H1
    fun count(x: Long): Int = java.lang.Long.bitCount(x)

    // The cell each column would take next.
    fun possible(mask: Long): Long = (mask + BOTTOM) and FULL

    fun moveIn(mask: Long, col: Int): Long = possible(mask) and column(col)

    // Every empty cell that would complete four for the stones in pos.
    fun winning(pos: Long, mask: Long): Long {
        var r = (pos shl 1) and (pos shl 2) and (pos shl 3)
        for (s in intArrayOf(H1, H, H + 2)) {
            var p = (pos shl s) and (pos shl (2 * s))
            r = r or (p and (pos shl (3 * s)))
            r = r or (p and (pos ushr s))
            p = (pos ushr s) and (pos ushr (2 * s))
            r = r or (p and (pos shl s))
            r = r or (p and (pos ushr (3 * s)))
        }
        return r and (FULL xor mask)
    }

    // The moves that do not hand the other side a win on its next turn.
    // Zero means every move loses: the other side has two threats ready.
    fun nonLosing(pos: Long, mask: Long): Long {
        var poss = possible(mask)
        val theirs = winning(pos xor mask, mask)
        val forced = poss and theirs
        if (forced != 0L) {
            if (forced and (forced - 1) != 0L) return 0L
            poss = forced
        }
        return poss and (theirs ushr 1).inv()
    }

    // Every run of four cells on the board, 69 of them.
    val WINDOWS: LongArray = run {
        val out = ArrayList<Long>()
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, -1))
        for (c in 0 until COLS) for (r in 0 until ROWS) for (d in dirs) {
            val ec = c + 3 * d[0]
            val er = r + 3 * d[1]
            if (ec !in 0 until COLS || er !in 0 until ROWS) continue
            var m = 0L
            for (k in 0..3) m = m or (1L shl ((c + k * d[0]) * H1 + r + k * d[1]))
            out.add(m)
        }
        out.toLongArray()
    }

    val CENTER_FIRST = intArrayOf(3, 2, 4, 1, 5, 0, 6)
}

class Brain(private val rng: Random = Random.Default) {
    private val deep = DeepSearch()

    // Depth the hard player reached on its last move, for testing.
    val lastDepth: Int get() = deep.lastDepth

    fun choose(board: Board, difficulty: Difficulty, budgetMs: Long = 900): Int = when (difficulty) {
        Difficulty.EASY -> board.legalColumns().random(rng)
        Difficulty.MEDIUM -> medium(board)
        Difficulty.HARD -> deep.choose(board, budgetMs)
    }

    // Medium: win if it can, block if it must, never set up a win for the
    // player directly on top of its own piece, and otherwise pick the move
    // that builds the most of its own lines, with a lean toward the middle
    // and a little chance thrown in so it does not play the same game twice.
    private fun medium(board: Board): Int {
        val pos = board.bits(board.toMove)
        val mask = pos or board.bits(other(board.toMove))
        val poss = Bits.possible(mask)
        val win = Bits.winning(pos, mask) and poss
        if (win != 0L) return Bits.colOf(win)
        val block = Bits.winning(pos xor mask, mask) and poss
        if (block != 0L) return Bits.colOf(block)
        var options = Bits.nonLosing(pos, mask)
        if (options == 0L) options = poss
        var bestCol = -1
        var bestScore = Int.MIN_VALUE
        while (options != 0L) {
            val move = options and -options
            options = options xor move
            val col = Bits.colOf(move)
            val mine = pos or move
            val m2 = mask or move
            var score = CENTER_WEIGHT[col] * 3
            score += 6 * Bits.count(Bits.winning(mine, m2))
            for (w in Bits.WINDOWS) {
                if (w and move == 0L || w and (m2 xor mine) != 0L) continue
                score += when (Bits.count(w and mine)) { 2 -> 2; 3 -> 3; else -> 0 }
            }
            score += rng.nextInt(5)
            if (score > bestScore) {
                bestScore = score
                bestCol = col
            }
        }
        return bestCol
    }

    companion object {
        private val CENTER_WEIGHT = intArrayOf(0, 1, 2, 3, 2, 1, 0)
    }
}

// Hard: negamax search with alpha-beta pruning, a transposition table, and
// iterative deepening against a time budget. Moves are tried best guess
// first: the table's remembered best, then moves that make the most threats,
// then the middle columns before the edges. Below the search horizon the
// position is scored by its threats (with the odd and even row rule that
// decides most endgames), its open lines and its hold on the middle.
class DeepSearch {
    private val tableBits = 20
    private val keys = LongArray(1 shl tableBits)
    private val values = IntArray(1 shl tableBits)
    private val info = IntArray(1 shl tableBits) // depth shl 8 | flag shl 4 | move + 1

    private var deadline = 0L
    private var nodes = 0L
    private var aborted = false
    var lastDepth = 0
        private set

    fun choose(board: Board, budgetMs: Long): Int {
        if (board.moves == 0) return 3
        val pos = board.bits(board.toMove)
        val mask = pos or board.bits(other(board.toMove))
        val moves = board.moves
        val poss = Bits.possible(mask)

        val win = Bits.winning(pos, mask) and poss
        if (win != 0L) return Bits.colOf(win)
        val safe = Bits.nonLosing(pos, mask)
        if (safe == 0L) {
            // Lost against best play. Block one threat and hope.
            val block = Bits.winning(pos xor mask, mask) and poss
            return Bits.colOf(if (block != 0L) block else poss)
        }
        if (safe and (safe - 1) == 0L) return Bits.colOf(safe)

        deadline = System.nanoTime() + budgetMs * 1_000_000
        aborted = false
        nodes = 0
        var order = ordered(pos, mask, safe, -1)
        var best = order[0]
        lastDepth = 0
        for (depth in 1..(COLS * ROWS - moves)) {
            var alpha = -INF
            var depthBest = -1
            var depthScore = -INF
            for (col in order) {
                val move = Bits.moveIn(mask, col)
                val v = -search(pos xor mask, mask or move, moves + 1, depth - 1, -INF, -alpha)
                if (aborted) break
                if (v > depthScore) {
                    depthScore = v
                    depthBest = col
                }
                if (v > alpha) alpha = v
            }
            if (aborted) {
                // A part-finished depth still counts if its first move,
                // last depth's best, was searched and something beat it.
                if (depthBest >= 0 && depthBest != best && depthScore > -WIN_LINE) best = depthBest
                break
            }
            best = depthBest
            lastDepth = depth
            order = ordered(pos, mask, safe, best)
            if (depthScore > WIN_LINE || depthScore < -WIN_LINE) break
        }
        return best
    }

    private fun search(pos: Long, mask: Long, moves: Int, depth: Int, alphaIn: Int, betaIn: Int): Int {
        if ((++nodes and 1023L) == 0L && System.nanoTime() > deadline) aborted = true
        if (aborted) return 0
        if (moves == COLS * ROWS) return 0
        val poss = Bits.possible(mask)
        if (Bits.winning(pos, mask) and poss != 0L) return WIN - moves
        val next = Bits.nonLosing(pos, mask)
        if (next == 0L) return -(WIN - moves - 1)
        if (moves >= COLS * ROWS - 2) return 0
        if (depth <= 0) return evaluate(pos, mask, moves)

        var alpha = alphaIn
        var beta = betaIn
        // No win this move, so the soonest win is two moves away.
        val cap = WIN - moves - 2
        if (beta > cap) {
            beta = cap
            if (alpha >= beta) return beta
        }

        val key = pos + mask
        val slot = ((key * -7046029254386353131L) ushr (64 - tableBits)).toInt()
        var ttMove = -1
        if (keys[slot] == key) {
            val inf = info[slot]
            ttMove = (inf and 0xF) - 1
            if ((inf ushr 8) >= depth) {
                val v = values[slot]
                when ((inf ushr 4) and 0xF) {
                    EXACT -> return v
                    LOWER -> if (v > alpha) alpha = v
                    UPPER -> if (v < beta) beta = v
                }
                if (alpha >= beta) return v
            }
        }

        val alphaStart = alpha
        var best = -INF
        var bestCol = -1
        for (col in ordered(pos, mask, next, ttMove)) {
            val move = Bits.moveIn(mask, col)
            val v = -search(pos xor mask, mask or move, moves + 1, depth - 1, -beta, -alpha)
            if (aborted) return 0
            if (v > best) {
                best = v
                bestCol = col
            }
            if (v > alpha) alpha = v
            if (alpha >= beta) break
        }

        val flag = when {
            best <= alphaStart -> UPPER
            best >= beta -> LOWER
            else -> EXACT
        }
        keys[slot] = key
        values[slot] = best
        info[slot] = (depth shl 8) or (flag shl 4) or (bestCol + 1)
        return best
    }

    // The candidate columns, best guess first.
    private fun ordered(pos: Long, mask: Long, candidates: Long, first: Int): IntArray {
        val cols = IntArray(COLS)
        val scores = IntArray(COLS)
        var n = 0
        for ((rank, col) in Bits.CENTER_FIRST.withIndex()) {
            val move = candidates and Bits.column(col)
            if (move == 0L) continue
            val threats = Bits.count(Bits.winning(pos or move, mask or move))
            cols[n] = col
            scores[n] = if (col == first) 1000 else threats * 16 - rank
            n++
        }
        // Insertion sort, highest score first. At most seven entries.
        for (i in 1 until n) {
            val c = cols[i]
            val s = scores[i]
            var j = i - 1
            while (j >= 0 && scores[j] < s) {
                cols[j + 1] = cols[j]
                scores[j + 1] = scores[j]
                j--
            }
            cols[j + 1] = c
            scores[j + 1] = s
        }
        return cols.copyOf(n)
    }

    // The position from the point of view of the side to move.
    private fun evaluate(pos: Long, mask: Long, moves: Int): Int {
        val me = pos
        val them = pos xor mask
        val myThreats = Bits.winning(me, mask)
        val theirThreats = Bits.winning(them, mask)
        val iMovedFirst = moves % 2 == 0
        val myGood = if (iMovedFirst) Bits.ODD_ROWS else Bits.EVEN_ROWS
        val theirGood = if (iMovedFirst) Bits.EVEN_ROWS else Bits.ODD_ROWS

        var s = 0
        s += 14 * Bits.count(myThreats) + 16 * Bits.count(myThreats and myGood)
        s -= 14 * Bits.count(theirThreats) + 16 * Bits.count(theirThreats and theirGood)
        // Two threats stacked in one column: the lower one forces the upper.
        s += 40 * Bits.count(myThreats and (myThreats shl 1))
        s -= 40 * Bits.count(theirThreats and (theirThreats shl 1))
        for (w in Bits.WINDOWS) {
            val a = w and me
            val b = w and them
            if (b == 0L) {
                if (a != 0L) s += LINE[Bits.count(a)]
            } else if (a == 0L) {
                s -= LINE[Bits.count(b)]
            }
        }
        s += 4 * (Bits.count(me and Bits.column(3)) - Bits.count(them and Bits.column(3)))
        val inner = Bits.column(2) or Bits.column(4)
        s += 2 * (Bits.count(me and inner) - Bits.count(them and inner))
        return s + 3
    }

    companion object {
        const val WIN = 100_000
        const val WIN_LINE = WIN - 100
        const val INF = 1_000_000
        private const val EXACT = 0
        private const val LOWER = 1
        private const val UPPER = 2
        private val LINE = intArrayOf(0, 1, 5, 9, 0)
    }
}
