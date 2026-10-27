package de.hyprnode.slimy.model.arcade

import de.hyprnode.slimy.model.SplitMix
import kotlin.math.abs
import kotlin.math.max

// The thinking games. Like the action engines these are plain objects with
// seeded randomness; most of them only change when you tap.

// MARK: - Bubble Pop

class BubblePopEngine(grid: List<List<Int?>>) {
    /** grid[column][row], row 0 at the bottom; null is empty. */
    var grid: List<List<Int?>> = grid.map { it.toList() }
        private set
    var score = 0
        private set
    var moves = 0
        private set

    constructor(seed: ULong) : this(randomGrid(seed))

    fun color(column: Int, row: Int): Int? {
        if (column < 0 || column >= grid.size || row < 0 || row >= grid[column].size) return null
        return grid[column][row]
    }

    /** The connected group of the same colour, including the cell itself. */
    fun group(column: Int, row: Int): List<Pair<Int, Int>> {
        val target = color(column, row) ?: return emptyList()
        val seen = HashSet<Int>()
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(column to row)
        val out = mutableListOf<Pair<Int, Int>>()
        while (stack.isNotEmpty()) {
            val (c, r) = stack.removeLast()
            val key = c * 100 + r
            if (seen.contains(key) || color(c, r) != target) continue
            seen.add(key)
            out.add(c to r)
            stack.addLast(c + 1 to r)
            stack.addLast(c - 1 to r)
            stack.addLast(c to r + 1)
            stack.addLast(c to r - 1)
        }
        return out
    }

    val hasMoves: Boolean
        get() {
            for (c in grid.indices) {
                for (r in grid[c].indices) {
                    val here = grid[c][r] ?: continue
                    if (color(c + 1, r) == here || color(c, r + 1) == here) return true
                }
            }
            return false
        }

    val isCleared: Boolean get() = grid.all { column -> column.all { it == null } }
    val isOver: Boolean get() = !hasMoves

    /** Pops the group under the tap. Returns how many bubbles popped (0 if the group was too small). */
    fun pop(column: Int, row: Int): Int {
        val cells = group(column, row)
        if (cells.size < 2) return 0
        val g = grid.map { it.toMutableList() }
        for ((c, r) in cells) g[c][r] = null
        score += cells.size * (cells.size - 1)
        moves += 1
        // Gravity, then close empty columns to the left.
        val fallen: List<List<Int?>> = g.map { col ->
            val filled = col.filterNotNull()
            filled.map<Int, Int?> { it } + List(rows - filled.size) { null }
        }
        val kept = fallen.filter { col -> col.any { it != null } }
        grid = kept + List(columns - kept.size) { List(rows) { null } }
        if (isCleared) score += clearBonus
        return cells.size
    }

    companion object {
        const val columns = 7
        const val rows = 9
        const val colors = 4
        const val clearBonus = 200

        private fun randomGrid(seed: ULong): List<List<Int?>> {
            val rng = SplitMix(seed)
            return List(columns) { List<Int?>(rows) { rng.int(0..colors - 1) } }
        }
    }
}

// MARK: - Memory Garden

class MemoryGardenEngine(seed: ULong) {
    sealed class Phase {
        data class showing(val step: Int, val t: Double) : Phase()
        data class input(val next: Int) : Phase()
        data class pause(val t: Double) : Phase()
        data object over : Phase()
    }

    private val rng = SplitMix(seed)
    private val _sequence = mutableListOf<Int>()
    val sequence: List<Int> get() = _sequence
    var phase: Phase = Phase.pause(0.0)
        private set

    /** Rounds completed. */
    var score = 0
        private set

    init {
        extend()
    }

    val isOver: Boolean get() = phase == Phase.over

    /** The flower that is glowing right now while the sequence plays. */
    val glowing: Int?
        get() {
            val p = phase as? Phase.showing ?: return null
            if (p.step >= _sequence.size || p.t >= lit) return null
            return _sequence[p.step]
        }

    val isListening: Boolean get() = phase is Phase.input

    fun step(dt: Double) {
        if (dt <= 0) return
        when (val p = phase) {
            is Phase.pause -> phase = if (p.t + dt >= 0.8) Phase.showing(0, 0.0) else Phase.pause(p.t + dt)
            is Phase.showing -> {
                // Faster as the sequence grows.
                val slot = (lit + gap) * max(0.6, 1 - _sequence.size.toDouble() * 0.03)
                phase = if (p.t + dt >= slot) {
                    if (p.step + 1 >= _sequence.size) Phase.input(0) else Phase.showing(p.step + 1, 0.0)
                } else {
                    Phase.showing(p.step, p.t + dt)
                }
            }
            is Phase.input, Phase.over -> Unit
        }
    }

    /** Returns true if the tap was right. */
    fun tap(flower: Int): Boolean {
        val p = phase as? Phase.input ?: return false
        if (_sequence[p.next] != flower) {
            phase = Phase.over
            return false
        }
        if (p.next + 1 == _sequence.size) {
            score += 1
            extend()
            phase = Phase.pause(0.0)
        } else {
            phase = Phase.input(p.next + 1)
        }
        return true
    }

    private fun extend() {
        var flower = rng.int(0..flowers - 1)
        if (flower == _sequence.lastOrNull()) flower = (flower + 1 + rng.int(0..flowers - 2)) % flowers
        _sequence.add(flower)
    }

    companion object {
        const val flowers = 9
        const val lit = 0.5
        const val gap = 0.2
    }
}

// MARK: - Garden Puzzle

class GardenPuzzleEngine {
    data class Point(val x: Int, val y: Int) {
        fun moved(d: Direction): Point = Point(x + d.dx, y + d.dy)
    }

    enum class Direction {
        up, down, left, right;

        val dx: Int get() = if (this == left) -1 else if (this == right) 1 else 0
        val dy: Int get() = if (this == up) -1 else if (this == down) 1 else 0

        companion object {
            val allCases: List<Direction> get() = entries
        }
    }

    enum class MoveResult { blocked, moved, pushed, seed, solved, finished }

    var level = 0
        private set
    var width = 0
        private set
    var height = 0
        private set
    var walls: Set<Point> = emptySet()
        private set
    var stones: Set<Point> = emptySet()
        private set
    var seeds: Set<Point> = emptySet()
        private set
    var slime = Point(0, 0)
        private set
    var bed = Point(0, 0)
        private set
    var moves = 0
        private set

    /** Levels solved this round. */
    var score = 0
        private set
    var isOver = false
        private set

    init {
        load(0)
    }

    /** A copy to try moves on (Swift's value semantics). */
    fun copy(): GardenPuzzleEngine {
        val c = GardenPuzzleEngine()
        c.level = level
        c.width = width
        c.height = height
        c.walls = walls
        c.stones = stones
        c.seeds = seeds
        c.slime = slime
        c.bed = bed
        c.moves = moves
        c.score = score
        c.isOver = isOver
        return c
    }

    fun load(index: Int) {
        level = index
        val rows = levels[index]
        height = rows.size
        width = rows.maxOfOrNull { it.length } ?: 0
        val w = HashSet<Point>()
        val st = HashSet<Point>()
        val se = HashSet<Point>()
        moves = 0
        for ((y, row) in rows.withIndex()) {
            for ((x, ch) in row.withIndex()) {
                val p = Point(x, y)
                when (ch) {
                    '#' -> w.add(p)
                    'o' -> st.add(p)
                    '*' -> se.add(p)
                    'S' -> slime = p
                    'B' -> bed = p
                }
            }
        }
        walls = w
        stones = st
        seeds = se
    }

    fun restartLevel() = load(level)

    fun move(d: Direction): MoveResult {
        if (isOver) return MoveResult.blocked
        val next = slime.moved(d)
        if (walls.contains(next)) return MoveResult.blocked
        var result = MoveResult.moved
        if (stones.contains(next)) {
            val beyond = next.moved(d)
            if (walls.contains(beyond) || stones.contains(beyond) || seeds.contains(beyond) || beyond == bed) {
                return MoveResult.blocked
            }
            stones = stones - next + beyond
            result = MoveResult.pushed
        }
        slime = next
        moves += 1
        if (seeds.contains(next)) {
            seeds = seeds - next
            result = MoveResult.seed
        }
        if (slime == bed && seeds.isEmpty()) {
            score += 1
            if (level + 1 < levels.size) {
                load(level + 1)
                return MoveResult.solved
            }
            isOver = true
            return MoveResult.finished
        }
        return result
    }

    companion object {
        /** # wall, . grass, S slime, o stone, * seed, B bed. Every level is checked solvable by tools/check_puzzles.py. */
        val levels: List<List<String>> = listOf(
            listOf(
                "#######",
                "#S..*.#",
                "#.o...#",
                "#..#..#",
                "#*...B#",
                "#######",
            ),
            listOf(
                "########",
                "#S..#*.#",
                "#.o.o..#",
                "#..##..#",
                "#*....B#",
                "########",
            ),
            listOf(
                "#######",
                "#*.o.*#",
                "#.#.#.#",
                "#S.o..#",
                "###.#B#",
                "#*....#",
                "#######",
            ),
            listOf(
                "########",
                "#S.o...#",
                "##.##o.#",
                "#*.o..*#",
                "#.#..#.#",
                "#*..o.B#",
                "########",
            ),
            listOf(
                "#########",
                "#*..o..*#",
                "#.#.#.#.#",
                "#..oSo..#",
                "#.#.#.#.#",
                "#*..o..B#",
                "#########",
            ),
            listOf(
                "########",
                "#S#*..*#",
                "#.o.##.#",
                "#..o...#",
                "##.#o#.#",
                "#*...o.#",
                "###.##B#",
                "#*.....#",
                "########",
            ),
        )
    }
}

// MARK: - Treasure Dig

class TreasureDigEngine(seed: ULong) {
    sealed class Cell {
        data object hidden : Cell()
        data class empty(val hint: Int) : Cell()
        data object treasure : Cell()
    }

    private val _cells: MutableList<Cell> = MutableList(size * size) { Cell.hidden }
    val cells: List<Cell> get() = _cells
    private val treasures: Set<Int>
    var shovels = shovelCount
        private set
    var found = 0
        private set
    var score = 0
        private set

    init {
        val rng = SplitMix(seed)
        val spots = LinkedHashSet<Int>()
        while (spots.size < treasureCount) spots.add(rng.int(0..size * size - 1))
        treasures = spots
    }

    val isOver: Boolean get() = shovels == 0 || found == treasureCount

    /** Steps to the nearest treasure not yet dug up, as shown on an empty hole. Updated live, so old hints stay useful. */
    fun hint(index: Int): Int {
        val x = index % size
        val y = index / size
        val remaining = treasures.filter { _cells[it] != Cell.treasure }
        return remaining.minOfOrNull { abs(it % size - x) + abs(it / size - y) } ?: 0
    }

    fun dig(index: Int): Cell? {
        if (isOver || index !in _cells.indices || _cells[index] != Cell.hidden) return null
        shovels -= 1
        if (treasures.contains(index)) {
            _cells[index] = Cell.treasure
            found += 1
            score += treasureValue
            if (found == treasureCount) score += shovels * spareShovelValue
        } else {
            _cells[index] = Cell.empty(hint(index))
        }
        return _cells[index]
    }

    companion object {
        const val size = 7
        const val treasureCount = 5
        const val shovelCount = 18
        const val treasureValue = 15
        const val spareShovelValue = 3
    }
}

// MARK: - Color Match

class ColorMatchEngine(seed: ULong) {
    enum class Rule {
        color, shape;

        companion object {
            val allCases: List<Rule> get() = entries
        }
    }

    data class Piece(val color: Int, val shape: Int)

    private val rng = SplitMix(seed)
    var rule = Rule.color
        private set
    var target = Piece(0, 0)
        private set
    var piece = Piece(0, 0)
        private set
    var time = 0.0
        private set
    var score = 0
        private set
    var streak = 0
        private set
    var answered = 0
        private set
    var correct = 0
        private set

    init {
        next()
    }

    val timeLeft: Double get() = max(0.0, duration - time)
    val isOver: Boolean get() = time >= duration

    val matches: Boolean
        get() = if (rule == Rule.color) piece.color == target.color else piece.shape == target.shape

    fun step(dt: Double) {
        if (isOver || dt <= 0) return
        time += dt
    }

    /** Returns true when the answer was right. */
    fun answer(yes: Boolean): Boolean {
        if (isOver) return false
        answered += 1
        val right = yes == matches
        if (right) {
            correct += 1
            streak += 1
            score += 1 + (if (streak % 5 == 0) 1 else 0)
        } else {
            streak = 0
            time += penalty
        }
        next()
        return right
    }

    private fun next() {
        // Change the rule every few cards so you have to keep reading it.
        if (answered % 4 == 0) rule = if (rng.unit() < 0.5) Rule.color else Rule.shape
        val tc = rng.int(0..3)
        val ts = rng.int(0..3)
        target = Piece(tc, ts)
        var pc = rng.int(0..3)
        var ps = rng.int(0..3)
        val shouldMatch = rng.unit() < 0.5
        when (rule) {
            Rule.color ->
                if (shouldMatch) pc = target.color
                else if (pc == target.color) pc = (pc + 1 + rng.int(0..2)) % 4
            Rule.shape ->
                if (shouldMatch) ps = target.shape
                else if (ps == target.shape) ps = (ps + 1 + rng.int(0..2)) % 4
        }
        piece = Piece(pc, ps)
    }

    companion object {
        val colorNames = listOf("Red", "Blue", "Yellow", "Green")
        val shapeNames = listOf("Circle", "Square", "Triangle", "Star")
        const val duration = 40.0
        const val penalty = 3.0
    }
}
