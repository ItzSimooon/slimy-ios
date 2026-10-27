package de.hyprnode.slimy.model.arcade

import de.hyprnode.slimy.model.SplitMix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// MARK: - Slime Stack

/**
 * Jelly blocks slide back and forth above the tower and a tap drops the
 * moving one. Whatever hangs over the edge is cut off, so the tower gets
 * narrower unless the drops are perfect; three perfect drops in a row let
 * it grow back. A drop that misses the tower ends the round.
 *
 * Board units like every action game (100 × 160, y down). The tower is
 * built in world units from the ground up; `scroll` moves the view so the
 * top stays in sight.
 */
class SlimeStackEngine(seed: ULong) {
    data class Block(
        val id: Int,
        /** Centre, board units. */
        val x: Double,
        val width: Double,
        /** 0 is the base on the ground. */
        val level: Int,
        /** Index into the view's jelly palette. */
        val color: Int,
        /** Gold jelly: placing it is worth extra points. */
        val gold: Boolean,
    ) {
        val left: Double get() = x - width / 2
        val right: Double get() = x + width / 2
    }

    /** A cut-off piece, or the block that missed, tumbling down. */
    data class Piece(
        val id: Int,
        val x: Double,
        /** World y of the piece's top edge. */
        val y: Double,
        val width: Double,
        val vx: Double,
        val vy: Double,
        val angle: Double,
        val spin: Double,
        val color: Int,
        val gold: Boolean,
    )

    enum class Drop {
        /** Landed within the tolerance: snapped onto the block below. */
        perfect,

        /** Landed off-centre: the overhang was cut off. */
        cut,

        /** Missed the tower: the round is over. */
        missed,

        /** Too soon after the last drop; nothing happened. */
        ignored,
    }

    private val rng = SplitMix(seed)
    private val _tower = mutableListOf<Block>()

    /** The base first, then every placed block. */
    val tower: List<Block> get() = _tower

    /** The block sliding above the tower. */
    var current: Block
        private set

    /** +1 right, -1 left. */
    var direction = 1.0
        private set
    var speed = 0.0
        private set
    private val _pieces = mutableListOf<Piece>()
    val pieces: List<Piece> get() = _pieces

    /** How far the world is shifted down on screen. */
    var scroll = 0.0
        private set
    var time = 0.0
        private set
    var score = 0
        private set

    /** Perfect drops in a row. */
    var combo = 0
        private set
    var bestCombo = 0
        private set
    var perfects = 0
        private set
    var isOver = false
        private set
    private var wait = 0.25
    private var nextID = 1
    private val events = mutableListOf<ArcadeEvent>()

    init {
        val base = Block(id = 0, x = 50.0, width = baseWidth, level = 0, color = 0, gold = false)
        _tower.add(base)
        current = base
        spawn()
    }

    /** Blocks placed on the base. */
    val height: Int get() = _tower.size - 1
    val top: Block get() = _tower[_tower.size - 1]

    /**
     * Where the sliding block's centre turns around: a little past the
     * tower on both sides, but always partly on the board.
     */
    val travel: ClosedFloatingPointRange<Double>
        get() {
            val reach = current.width + 6
            val low = max(top.x - reach, -current.width / 2 + 8)
            val high = min(top.x + reach, Board.width + current.width / 2 - 8)
            return low..max(low, high)
        }

    // MARK: Time

    fun step(dt: Double) {
        if (dt <= 0) return
        time += dt
        wait = max(0.0, wait - dt)
        if (!isOver) {
            current = current.copy(x = current.x + direction * speed * dt)
            val range = travel
            if (current.x > range.endInclusive) {
                current = current.copy(x = range.endInclusive)
                direction = -1.0
            }
            if (current.x < range.start) {
                current = current.copy(x = range.start)
                direction = 1.0
            }
        }
        for (i in _pieces.indices) {
            val p = _pieces[i]
            val vy = p.vy + 300 * dt
            _pieces[i] = p.copy(vy = vy, x = p.x + p.vx * dt, y = p.y + vy * dt, angle = p.angle + p.spin * dt)
        }
        val view = scroll
        _pieces.removeAll { it.y + view > Board.height + 40 }
        val target = max(0.0, focusY - y(current.level))
        scroll += (target - scroll) * min(1.0, dt * 5)
    }

    // MARK: Input

    fun drop(): Drop {
        if (isOver) return Drop.missed
        if (wait > 0) return Drop.ignored
        val below = top
        val left = max(current.left, below.left)
        val right = min(current.right, below.right)
        val overlap = right - left
        if (overlap <= 0.5) {
            // Nothing to stand on: the block falls and the round is over.
            isOver = true
            combo = 0
            val falling = piece(current, current.x, current.width, if (current.x < below.x) -1.0 else 1.0)
            _pieces.add(falling)
            events.add(ArcadeEvent.lose)
            return Drop.missed
        }

        var placed = current
        val result: Drop
        if (abs(current.x - below.x) <= perfectTolerance) {
            placed = placed.copy(x = below.x)
            combo += 1
            perfects += 1
            bestCombo = max(bestCombo, combo)
            if (combo >= 3) placed = placed.copy(width = min(baseWidth, placed.width + regrow))
            score += 1 + min(combo, 4)
            // Each perfect drop in a row rings one note higher.
            events.add(ArcadeEvent.tone(min(combo - 1, 8)))
            result = Drop.perfect
        } else {
            val cut = current.width - overlap
            val cutX = if (current.x > below.x) right + cut / 2 else left - cut / 2
            val falling = piece(current, cutX, cut, if (current.x > below.x) 1.0 else -1.0)
            _pieces.add(falling)
            placed = placed.copy(x = (left + right) / 2, width = overlap)
            combo = 0
            score += 1
            events.add(ArcadeEvent.land)
            result = Drop.cut
        }
        if (placed.gold) {
            score += goldBonus
            events.add(ArcadeEvent.coin)
        }
        _tower.add(placed)
        spawn()
        wait = cooldown
        return result
    }

    fun drainEvents(): List<ArcadeEvent> {
        val out = events.toList()
        events.clear()
        return out
    }

    // MARK: Building

    private fun spawn() {
        val level = _tower.size
        var color = rng.int(0..colors - 1)
        if (color == top.color) color = (color + 1) % colors
        val gold = level >= 4 && rng.unit() < 0.12
        current = Block(id = nextID, x = 0.0, width = top.width, level = level, color = color, gold = gold)
        nextID += 1
        direction = if (rng.unit() < 0.5) 1.0 else -1.0
        val range = travel
        current = current.copy(x = if (direction > 0) range.start else range.endInclusive)
        speed = min(maxSpeed, 58 + level.toDouble() * 2.2) * (0.94 + rng.unit() * 0.12)
    }

    private fun piece(block: Block, x: Double, width: Double, push: Double): Piece {
        val p = Piece(id = nextID, x = x, y = y(block.level), width = width,
            vx = push * 14, vy = -18.0, angle = 0.0, spin = push * 2.2, color = block.color, gold = block.gold)
        nextID += 1
        return p
    }

    companion object {
        const val blockHeight = 9.0
        const val baseWidth = 56.0

        /** World y of the ground, the bottom of the base block. */
        const val ground = 150.0

        /** How close to the block below counts as perfect. */
        const val perfectTolerance = 2.0

        /** Where the moving block sits on screen once the tower is tall. */
        const val focusY = 62.0
        const val colors = 6
        const val maxSpeed = 125.0
        const val goldBonus = 3

        /** Width won back by each perfect drop from the third in a row on. */
        const val regrow = 3.0

        /** Ignores a second tap right after a drop, so a double tap can't throw the next block away. */
        const val cooldown = 0.15

        /** World y of the top edge of a level. */
        fun y(level: Int): Double = ground - (level + 1).toDouble() * blockHeight
    }
}
