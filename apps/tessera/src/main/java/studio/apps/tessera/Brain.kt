package studio.apps.tessera

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

// The three computer players.

enum class Difficulty(val label: String, val blurb: String) {
    EASY("Easy", "Plays any open square, at random."),
    MEDIUM("Medium", "Takes boards, blocks yours, and watches where it sends you."),
    HARD("Hard", "Plays out thousands of games in its head before each move."),
}

class Brain(seed: Long = System.nanoTime()) {
    private val rng = Random(seed)
    private val buf = IntArray(81)

    // Hard thinks for this long on each move.
    var hardMillis = 1000L

    fun choose(g: Game, level: Difficulty): Int = when (level) {
        Difficulty.EASY -> easy(g)
        Difficulty.MEDIUM -> medium(g)
        Difficulty.HARD -> hard(g, hardMillis)
    }

    // ---- Easy --------------------------------------------------------------

    fun easy(g: Game): Int {
        val n = g.legalMoves(buf)
        return buf[rng.nextInt(n)]
    }

    // ---- Medium ------------------------------------------------------------

    // Looks one move ahead with a handful of rules of thumb: win the game,
    // win a small board, block the other side's small board, and do not send
    // the other side somewhere it can win a board or play anywhere it likes.
    fun medium(g: Game): Int {
        val n = g.legalMoves(buf)
        val me = g.toMove
        val them = other(me)
        val after = Game()
        var best = buf[0]
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val m = buf[i]
            val score = mediumScore(g, after, m, me, them) + rng.nextDouble() * 6
            if (score > bestScore) {
                bestScore = score
                best = m
            }
        }
        return best
    }

    private fun mediumScore(g: Game, after: Game, m: Int, me: Int, them: Int): Double {
        val b = m / 9
        val c = m % 9
        val bit = 1 shl c
        var s = 0.0
        val myBefore = Lines.threats(g.mine(me, b), g.taken(b))
        val theirBefore = Lines.threats(g.mine(them, b), g.taken(b))

        after.copyFrom(g)
        val out = after.play(m)
        if (after.result == me) return 1e6
        if (out.claimed) {
            s += 1000
            // A square that lines up with squares already claimed is worth more.
            s += 150 * Integer.bitCount(Lines.threats(after.claimed(me), after.claimed(them) or after.claimed(me) or tiedMask(after)))
        } else {
            if (theirBefore and bit != 0) s += 520
            val myAfter = Lines.threats(after.mine(me, b), after.taken(b))
            s += 70 * Integer.bitCount(myAfter and myBefore.inv())
        }
        if (c == 4) s += 14 else if (c % 2 == 0) s += 7

        // Where this sends the other side.
        if (after.result == NONE) {
            if (after.forced < 0) {
                // They may play anywhere. Bad, and worse if they have a
                // small board ready to take somewhere.
                s -= 300
                var bestTheirs = 0
                for (d in 0 until 9) {
                    if (!after.boardOpen(d)) continue
                    if (Lines.threats(after.mine(them, d), after.taken(d)) != 0) {
                        bestTheirs = maxOf(bestTheirs, if (winsGameWith(after, them, d)) 2 else 1)
                    }
                }
                s -= when (bestTheirs) { 2 -> 1e5; 1 -> 700.0; else -> 0.0 }
            } else {
                val d = after.forced
                if (Lines.threats(after.mine(them, d), after.taken(d)) != 0) {
                    s -= if (winsGameWith(after, them, d)) 1e5 else 800.0
                } else if (Lines.threats(after.mine(me, d), after.taken(d)) != 0) {
                    // They will likely spend the move blocking us.
                    s += 40
                }
            }
        }
        return s
    }

    private fun tiedMask(g: Game): Int {
        var m = 0
        for (b in 0 until 9) if (g.owner[b] == TIED) m = m or (1 shl b)
        return m
    }

    // True if claiming square d would win the game for side.
    private fun winsGameWith(g: Game, side: Int, d: Int): Boolean =
        Lines.WINS[g.claimed(side) or (1 shl d)]

    // ---- Hard --------------------------------------------------------------

    // Monte Carlo tree search. It plays the rest of the game out at random
    // many thousands of times, spending more of its playouts on the moves
    // that keep doing well, and picks the move it explored most.
    private class Node(val move: Int, val parent: Node?, val mover: Int, game: Game, scratch: IntArray) {
        val untried: IntArray
        var untriedCount: Int
        var children: Array<Node?> = EMPTY
        var childCount = 0
        var visits = 0
        var wins = 0.0 // from the point of view of mover

        init {
            val n = game.legalMoves(scratch)
            untried = scratch.copyOf(n)
            untriedCount = n
        }

        fun add(child: Node) {
            if (childCount == children.size) children = children.copyOf(maxOf(4, children.size * 2))
            children[childCount++] = child
        }

        companion object {
            val EMPTY = arrayOfNulls<Node>(0)
        }
    }

    // Iterations of the last search, for testing.
    var lastIterations = 0
        private set

    fun hard(g: Game, millis: Long, maxIterations: Int = Int.MAX_VALUE): Int {
        val n = g.legalMoves(buf)
        if (n == 1) return buf[0]
        val me = g.toMove
        val probe = Game()
        // Take a game-winning move at once.
        for (i in 0 until n) {
            probe.copyFrom(g)
            probe.play(buf[i])
            if (probe.result == me) return buf[i]
        }

        val moves = IntArray(81)
        val root = Node(-1, null, other(me), g, moves)
        val sim = Game()
        val deadline = System.nanoTime() + millis * 1_000_000
        var iter = 0
        while (iter < maxIterations) {
            if (iter and 63 == 0 && System.nanoTime() > deadline) break
            iter++
            sim.copyFrom(g)
            var node = root
            // Selection.
            while (node.untriedCount == 0 && node.childCount > 0) {
                node = select(node)
                sim.play(node.move)
            }
            // Expansion.
            if (node.untriedCount > 0 && sim.result == NONE) {
                val k = rng.nextInt(node.untriedCount)
                val move = node.untried[k]
                node.untried[k] = node.untried[--node.untriedCount]
                val mover = sim.toMove
                sim.play(move)
                val child = Node(move, node, mover, sim, moves)
                node.add(child)
                node = child
            }
            // Playout.
            val result = playout(sim, moves)
            // Back up the result.
            var up: Node? = node
            while (up != null) {
                up.visits++
                up.wins += when (result) {
                    up.mover -> 1.0
                    TIED -> 0.5
                    else -> 0.0
                }
                up = up.parent
            }
        }
        lastIterations = iter

        var best = root.children[0]!!
        for (i in 1 until root.childCount) {
            val ch = root.children[i]!!
            if (ch.visits > best.visits) best = ch
        }
        return best.move
    }

    private fun select(node: Node): Node {
        val logN = ln(node.visits.toDouble())
        var best = node.children[0]!!
        var bestValue = Double.NEGATIVE_INFINITY
        for (i in 0 until node.childCount) {
            val ch = node.children[i]!!
            val v = ch.wins / ch.visits + EXPLORE * sqrt(logN / ch.visits)
            if (v > bestValue) {
                bestValue = v
                best = ch
            }
        }
        return best
    }

    // Plays the game to the end. Each side takes a small board when it can
    // and otherwise plays at random, which makes the playouts look a little
    // more like real games than pure chance does.
    private fun playout(sim: Game, moves: IntArray): Int {
        while (sim.result == NONE) {
            val side = sim.toMove
            val n = sim.legalMoves(moves)
            var pick = -1
            if (sim.forced >= 0) {
                val b = sim.forced
                val t = Lines.threats(sim.mine(side, b), sim.taken(b))
                if (t != 0 && rng.nextInt(4) != 0) pick = b * 9 + Integer.numberOfTrailingZeros(t)
            }
            sim.play(if (pick >= 0) pick else moves[rng.nextInt(n)])
        }
        return sim.result
    }

    companion object {
        const val EXPLORE = 0.9
    }
}
