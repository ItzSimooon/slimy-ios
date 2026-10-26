import Foundation

// MARK: - Slime Stack

/// Jelly blocks slide back and forth above the tower and a tap drops the
/// moving one. Whatever hangs over the edge is cut off, so the tower gets
/// narrower unless the drops are perfect; three perfect drops in a row let
/// it grow back. A drop that misses the tower ends the round.
///
/// Board units like every action game (100 × 160, y down). The tower is
/// built in world units from the ground up; `scroll` moves the view so the
/// top stays in sight.
struct SlimeStackEngine {
    struct Block: Identifiable, Equatable {
        let id: Int
        /// Centre, board units.
        var x: Double
        var width: Double
        /// 0 is the base on the ground.
        let level: Int
        /// Index into the view's jelly palette.
        let color: Int
        /// Gold jelly: placing it is worth extra points.
        let gold: Bool

        var left: Double { x - width / 2 }
        var right: Double { x + width / 2 }
    }

    /// A cut-off piece, or the block that missed, tumbling down.
    struct Piece: Identifiable {
        let id: Int
        var x: Double
        /// World y of the piece's top edge.
        var y: Double
        let width: Double
        var vx: Double
        var vy: Double
        var angle: Double
        let spin: Double
        let color: Int
        let gold: Bool
    }

    enum Drop: Equatable {
        /// Landed within the tolerance: snapped onto the block below.
        case perfect
        /// Landed off-centre: the overhang was cut off.
        case cut
        /// Missed the tower: the round is over.
        case missed
        /// Too soon after the last drop; nothing happened.
        case ignored
    }

    static let blockHeight = 9.0
    static let baseWidth = 56.0
    /// World y of the ground, the bottom of the base block.
    static let ground = 150.0
    /// How close to the block below counts as perfect.
    static let perfectTolerance = 2.0
    /// Where the moving block sits on screen once the tower is tall.
    static let focusY = 62.0
    static let colors = 6
    static let maxSpeed = 125.0
    static let goldBonus = 3
    /// Width won back by each perfect drop from the third in a row on.
    static let regrow = 3.0
    /// Ignores a second tap right after a drop, so a double tap can't throw
    /// the next block away the moment it appears.
    static let cooldown = 0.15

    private var rng: SplitMix
    /// The base first, then every placed block.
    private(set) var tower: [Block]
    /// The block sliding above the tower.
    private(set) var current: Block
    /// +1 right, -1 left.
    private(set) var direction = 1.0
    private(set) var speed = 0.0
    private(set) var pieces: [Piece] = []
    /// How far the world is shifted down on screen.
    private(set) var scroll = 0.0
    private(set) var time = 0.0
    private(set) var score = 0
    /// Perfect drops in a row.
    private(set) var combo = 0
    private(set) var bestCombo = 0
    private(set) var perfects = 0
    private(set) var isOver = false
    private var wait = 0.25
    private var nextID = 1
    private var events: [ArcadeEvent] = []

    init(seed: UInt64) {
        rng = SplitMix(seed: seed)
        let base = Block(id: 0, x: 50, width: Self.baseWidth, level: 0, color: 0, gold: false)
        tower = [base]
        current = base
        spawn()
    }

    /// Blocks placed on the base.
    var height: Int { tower.count - 1 }
    var top: Block { tower[tower.count - 1] }

    /// World y of the top edge of a level.
    static func y(level: Int) -> Double { ground - Double(level + 1) * blockHeight }

    /// Where the sliding block's centre turns around: a little past the
    /// tower on both sides, but always partly on the board.
    var travel: ClosedRange<Double> {
        let reach = current.width + 6
        let low = max(top.x - reach, -current.width / 2 + 8)
        let high = min(top.x + reach, Board.width + current.width / 2 - 8)
        return low...max(low, high)
    }

    // MARK: Time

    mutating func step(_ dt: Double) {
        guard dt > 0 else { return }
        time += dt
        wait = max(0, wait - dt)
        if !isOver {
            current.x += direction * speed * dt
            let range = travel
            if current.x > range.upperBound {
                current.x = range.upperBound
                direction = -1
            }
            if current.x < range.lowerBound {
                current.x = range.lowerBound
                direction = 1
            }
        }
        for i in pieces.indices {
            pieces[i].vy += 300 * dt
            pieces[i].x += pieces[i].vx * dt
            pieces[i].y += pieces[i].vy * dt
            pieces[i].angle += pieces[i].spin * dt
        }
        let view = scroll
        pieces.removeAll { $0.y + view > Board.height + 40 }
        let target = max(0, Self.focusY - Self.y(level: current.level))
        scroll += (target - scroll) * min(1, dt * 5)
    }

    // MARK: Input

    @discardableResult
    mutating func drop() -> Drop {
        guard !isOver else { return .missed }
        guard wait <= 0 else { return .ignored }
        let below = top
        let left = max(current.left, below.left)
        let right = min(current.right, below.right)
        let overlap = right - left
        guard overlap > 0.5 else {
            // Nothing to stand on: the block falls and the round is over.
            isOver = true
            combo = 0
            let falling = piece(of: current, x: current.x, width: current.width, push: current.x < below.x ? -1 : 1)
            pieces.append(falling)
            events.append(.lose)
            return .missed
        }

        var placed = current
        let result: Drop
        if abs(current.x - below.x) <= Self.perfectTolerance {
            placed.x = below.x
            combo += 1
            perfects += 1
            bestCombo = max(bestCombo, combo)
            if combo >= 3 { placed.width = min(Self.baseWidth, placed.width + Self.regrow) }
            score += 1 + min(combo, 4)
            // Each perfect drop in a row rings one note higher.
            events.append(.tone(min(combo - 1, 8)))
            result = .perfect
        } else {
            let cut = current.width - overlap
            let cutX = current.x > below.x ? right + cut / 2 : left - cut / 2
            let falling = piece(of: current, x: cutX, width: cut, push: current.x > below.x ? 1 : -1)
            pieces.append(falling)
            placed.x = (left + right) / 2
            placed.width = overlap
            combo = 0
            score += 1
            events.append(.land)
            result = .cut
        }
        if placed.gold {
            score += Self.goldBonus
            events.append(.coin)
        }
        tower.append(placed)
        spawn()
        wait = Self.cooldown
        return result
    }

    mutating func drainEvents() -> [ArcadeEvent] {
        defer { events.removeAll() }
        return events
    }

    // MARK: Building

    private mutating func spawn() {
        let level = tower.count
        var color = rng.int(0...Self.colors - 1)
        if color == top.color { color = (color + 1) % Self.colors }
        let gold = level >= 4 && rng.unit() < 0.12
        current = Block(id: nextID, x: 0, width: top.width, level: level, color: color, gold: gold)
        nextID += 1
        direction = rng.unit() < 0.5 ? 1 : -1
        let range = travel
        current.x = direction > 0 ? range.lowerBound : range.upperBound
        speed = min(Self.maxSpeed, 58 + Double(level) * 2.2) * (0.94 + rng.unit() * 0.12)
    }

    private mutating func piece(of block: Block, x: Double, width: Double, push: Double) -> Piece {
        defer { nextID += 1 }
        return Piece(id: nextID, x: x, y: Self.y(level: block.level), width: width,
                     vx: push * 14, vy: -18, angle: 0, spin: push * 2.2, color: block.color, gold: block.gold)
    }
}
