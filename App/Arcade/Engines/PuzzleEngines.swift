import Foundation

// The thinking games. Like the action engines these are plain values with
// seeded randomness; most of them only change when you tap.

// MARK: - Bubble Pop

struct BubblePopEngine {
    static let columns = 7
    static let rows = 9
    static let colors = 4
    static let clearBonus = 200

    /// grid[column][row], row 0 at the bottom; nil is empty.
    private(set) var grid: [[Int?]]
    private(set) var score = 0
    private(set) var moves = 0

    init(seed: UInt64) {
        var rng = SplitMix(seed: seed)
        grid = (0..<Self.columns).map { _ in (0..<Self.rows).map { _ in rng.int(0...Self.colors - 1) } }
    }

    init(grid: [[Int?]]) { self.grid = grid }

    func color(_ column: Int, _ row: Int) -> Int? {
        guard column >= 0, column < grid.count, row >= 0, row < grid[column].count else { return nil }
        return grid[column][row]
    }

    /// The connected group of the same colour, including the cell itself.
    func group(_ column: Int, _ row: Int) -> [(Int, Int)] {
        guard let target = color(column, row) else { return [] }
        var seen = Set<Int>()
        var stack = [(column, row)]
        var out: [(Int, Int)] = []
        while let (c, r) = stack.popLast() {
            let key = c * 100 + r
            guard !seen.contains(key), color(c, r) == target else { continue }
            seen.insert(key)
            out.append((c, r))
            stack += [(c + 1, r), (c - 1, r), (c, r + 1), (c, r - 1)]
        }
        return out
    }

    var hasMoves: Bool {
        for c in grid.indices {
            for r in grid[c].indices where grid[c][r] != nil {
                if color(c + 1, r) == grid[c][r] || color(c, r + 1) == grid[c][r] { return true }
            }
        }
        return false
    }

    var isCleared: Bool { grid.allSatisfy { $0.allSatisfy { $0 == nil } } }
    var isOver: Bool { !hasMoves }

    /// Pops the group under the tap. Returns how many bubbles popped (0 if
    /// the group was too small).
    @discardableResult
    mutating func pop(_ column: Int, _ row: Int) -> Int {
        let cells = group(column, row)
        guard cells.count >= 2 else { return 0 }
        for (c, r) in cells { grid[c][r] = nil }
        score += cells.count * (cells.count - 1)
        moves += 1
        // Gravity, then close empty columns to the left.
        grid = grid.map { column in
            let filled = column.compactMap { $0 }
            return filled.map { Optional($0) } + Array(repeating: nil, count: Self.rows - filled.count)
        }
        let kept = grid.filter { $0.contains { $0 != nil } }
        grid = kept + Array(repeating: Array(repeating: nil, count: Self.rows), count: Self.columns - kept.count)
        if isCleared { score += Self.clearBonus }
        return cells.count
    }
}

// MARK: - Memory Garden

struct MemoryGardenEngine {
    enum Phase: Equatable {
        case showing(step: Int, t: Double)
        case input(next: Int)
        case pause(t: Double)
        case over
    }

    static let flowers = 9
    static let lit = 0.5
    static let gap = 0.2

    private var rng: SplitMix
    private(set) var sequence: [Int] = []
    private(set) var phase: Phase = .pause(t: 0)
    /// Rounds completed.
    private(set) var score = 0

    init(seed: UInt64) {
        rng = SplitMix(seed: seed)
        extend()
    }

    var isOver: Bool { phase == .over }

    /// The flower that is glowing right now while the sequence plays.
    var glowing: Int? {
        guard case .showing(let step, let t) = phase, step < sequence.count, t < Self.lit else { return nil }
        return sequence[step]
    }

    var isListening: Bool {
        if case .input = phase { return true }
        return false
    }

    mutating func step(_ dt: Double) {
        guard dt > 0 else { return }
        switch phase {
        case .pause(let t):
            phase = t + dt >= 0.8 ? .showing(step: 0, t: 0) : .pause(t: t + dt)
        case .showing(let step, let t):
            // Faster as the sequence grows.
            let slot = (Self.lit + Self.gap) * max(0.6, 1 - Double(sequence.count) * 0.03)
            if t + dt >= slot {
                phase = step + 1 >= sequence.count ? .input(next: 0) : .showing(step: step + 1, t: 0)
            } else {
                phase = .showing(step: step, t: t + dt)
            }
        case .input, .over:
            break
        }
    }

    /// Returns true if the tap was right.
    @discardableResult
    mutating func tap(_ flower: Int) -> Bool {
        guard case .input(let next) = phase else { return false }
        guard sequence[next] == flower else {
            phase = .over
            return false
        }
        if next + 1 == sequence.count {
            score += 1
            extend()
            phase = .pause(t: 0)
        } else {
            phase = .input(next: next + 1)
        }
        return true
    }

    private mutating func extend() {
        var flower = rng.int(0...Self.flowers - 1)
        if flower == sequence.last { flower = (flower + 1 + rng.int(0...Self.flowers - 2)) % Self.flowers }
        sequence.append(flower)
    }
}

// MARK: - Garden Puzzle

struct GardenPuzzleEngine {
    struct Point: Hashable {
        var x: Int
        var y: Int
        func moved(_ d: Direction) -> Point { Point(x: x + d.dx, y: y + d.dy) }
    }

    enum Direction: CaseIterable {
        case up, down, left, right
        var dx: Int { self == .left ? -1 : self == .right ? 1 : 0 }
        var dy: Int { self == .up ? -1 : self == .down ? 1 : 0 }
    }

    enum MoveResult { case blocked, moved, pushed, seed, solved, finished }

    /// # wall, . grass, S slime, o stone, * seed, B bed. Every level is
    /// checked solvable by tools/check_puzzles.py.
    static let levels: [[String]] = [
        ["#######",
         "#S..*.#",
         "#.o...#",
         "#..#..#",
         "#*...B#",
         "#######"],
        ["########",
         "#S..#*.#",
         "#.o.o..#",
         "#..##..#",
         "#*....B#",
         "########"],
        ["#######",
         "#*.o.*#",
         "#.#.#.#",
         "#S.o..#",
         "###.#B#",
         "#*....#",
         "#######"],
        ["########",
         "#S.o...#",
         "##.##o.#",
         "#*.o..*#",
         "#.#..#.#",
         "#*..o.B#",
         "########"],
        ["#########",
         "#*..o..*#",
         "#.#.#.#.#",
         "#..oSo..#",
         "#.#.#.#.#",
         "#*..o..B#",
         "#########"],
        ["########",
         "#S#*..*#",
         "#.o.##.#",
         "#..o...#",
         "##.#o#.#",
         "#*...o.#",
         "###.##B#",
         "#*.....#",
         "########"],
    ]

    private(set) var level = 0
    private(set) var width = 0
    private(set) var height = 0
    private(set) var walls: Set<Point> = []
    private(set) var stones: Set<Point> = []
    private(set) var seeds: Set<Point> = []
    private(set) var slime = Point(x: 0, y: 0)
    private(set) var bed = Point(x: 0, y: 0)
    private(set) var moves = 0
    /// Levels solved this round.
    private(set) var score = 0
    private(set) var isOver = false

    init() { load(0) }

    mutating func load(_ index: Int) {
        level = index
        let rows = Self.levels[index]
        height = rows.count
        width = rows.map(\.count).max() ?? 0
        walls = []; stones = []; seeds = []
        moves = 0
        for (y, row) in rows.enumerated() {
            for (x, ch) in row.enumerated() {
                let p = Point(x: x, y: y)
                switch ch {
                case "#": walls.insert(p)
                case "o": stones.insert(p)
                case "*": seeds.insert(p)
                case "S": slime = p
                case "B": bed = p
                default: break
                }
            }
        }
    }

    mutating func restartLevel() { load(level) }

    @discardableResult
    mutating func move(_ d: Direction) -> MoveResult {
        guard !isOver else { return .blocked }
        let next = slime.moved(d)
        guard !walls.contains(next) else { return .blocked }
        var result = MoveResult.moved
        if stones.contains(next) {
            let beyond = next.moved(d)
            guard !walls.contains(beyond), !stones.contains(beyond), !seeds.contains(beyond), beyond != bed else {
                return .blocked
            }
            stones.remove(next)
            stones.insert(beyond)
            result = .pushed
        }
        slime = next
        moves += 1
        if seeds.remove(next) != nil { result = .seed }
        if slime == bed, seeds.isEmpty {
            score += 1
            if level + 1 < Self.levels.count {
                load(level + 1)
                return .solved
            }
            isOver = true
            return .finished
        }
        return result
    }
}

// MARK: - Treasure Dig

struct TreasureDigEngine {
    enum Cell: Equatable {
        case hidden
        case empty(hint: Int)
        case treasure
    }

    static let size = 7
    static let treasureCount = 5
    static let shovelCount = 18
    static let treasureValue = 15
    static let spareShovelValue = 3

    private(set) var cells: [Cell]
    private let treasures: Set<Int>
    private(set) var shovels = shovelCount
    private(set) var found = 0
    private(set) var score = 0

    init(seed: UInt64) {
        var rng = SplitMix(seed: seed)
        var spots = Set<Int>()
        while spots.count < Self.treasureCount { spots.insert(rng.int(0...Self.size * Self.size - 1)) }
        treasures = spots
        cells = Array(repeating: .hidden, count: Self.size * Self.size)
    }

    var isOver: Bool { shovels == 0 || found == Self.treasureCount }

    /// Steps to the nearest treasure not yet dug up, as shown on an empty
    /// hole. Updated live, so old hints stay useful.
    func hint(at index: Int) -> Int {
        let (x, y) = (index % Self.size, index / Self.size)
        let remaining = treasures.filter { cells[$0] != .treasure }
        return remaining.map { abs($0 % Self.size - x) + abs($0 / Self.size - y) }.min() ?? 0
    }

    @discardableResult
    mutating func dig(_ index: Int) -> Cell? {
        guard !isOver, cells.indices.contains(index), cells[index] == .hidden else { return nil }
        shovels -= 1
        if treasures.contains(index) {
            cells[index] = .treasure
            found += 1
            score += Self.treasureValue
            if found == Self.treasureCount { score += shovels * Self.spareShovelValue }
        } else {
            cells[index] = .empty(hint: hint(at: index))
        }
        return cells[index]
    }
}

// MARK: - Color Match

struct ColorMatchEngine {
    enum Rule: CaseIterable { case color, shape }

    struct Piece: Equatable {
        var color: Int
        var shape: Int
    }

    static let colorNames = ["Red", "Blue", "Yellow", "Green"]
    static let shapeNames = ["Circle", "Square", "Triangle", "Star"]
    static let duration = 40.0
    static let penalty = 3.0

    private var rng: SplitMix
    private(set) var rule: Rule = .color
    private(set) var target = Piece(color: 0, shape: 0)
    private(set) var piece = Piece(color: 0, shape: 0)
    private(set) var time = 0.0
    private(set) var score = 0
    private(set) var streak = 0
    private(set) var answered = 0
    private(set) var correct = 0

    init(seed: UInt64) {
        rng = SplitMix(seed: seed)
        next()
    }

    var timeLeft: Double { max(0, Self.duration - time) }
    var isOver: Bool { time >= Self.duration }

    var matches: Bool {
        rule == .color ? piece.color == target.color : piece.shape == target.shape
    }

    mutating func step(_ dt: Double) {
        guard !isOver, dt > 0 else { return }
        time += dt
    }

    /// Returns true when the answer was right.
    @discardableResult
    mutating func answer(_ yes: Bool) -> Bool {
        guard !isOver else { return false }
        answered += 1
        let right = yes == matches
        if right {
            correct += 1
            streak += 1
            score += 1 + (streak % 5 == 0 ? 1 : 0)
        } else {
            streak = 0
            time += Self.penalty
        }
        next()
        return right
    }

    private mutating func next() {
        // Change the rule every few cards so you have to keep reading it.
        if answered % 4 == 0 { rule = rng.unit() < 0.5 ? .color : .shape }
        target = Piece(color: rng.int(0...3), shape: rng.int(0...3))
        var p = Piece(color: rng.int(0...3), shape: rng.int(0...3))
        let shouldMatch = rng.unit() < 0.5
        switch rule {
        case .color:
            if shouldMatch { p.color = target.color } else if p.color == target.color { p.color = (p.color + 1 + rng.int(0...2)) % 4 }
        case .shape:
            if shouldMatch { p.shape = target.shape } else if p.shape == target.shape { p.shape = (p.shape + 1 + rng.int(0...2)) % 4 }
        }
        piece = p
    }
}
