import Foundation

/// Plays any GameSpec. Like the other engines it is a plain value: the view
/// steps it, feeds it input and draws what it holds. Board: 100 × 160.
struct BlockEngine {
    struct Thing: Identifiable {
        let id: Int
        let kind: Int
        var x: Double
        var y: Double
        var vx: Double
        var vy: Double
        var phase: Double
        var age = 0.0
    }

    /// Something worth a sound, a particle burst or a shake.
    enum Event: Equatable {
        case collect(x: Double, y: Double, points: Int, sprite: String)
        case hit(x: Double, y: Double)
        case blocked(x: Double, y: Double)
        case power(PowerKind, x: Double, y: Double)
        case miss
        case jump
        case combo(Int)
    }

    static let ground = 146.0
    static let lanes = [20.0, 50.0, 80.0]

    let spec: GameSpec
    private var rng: SplitMix
    private(set) var things: [Thing] = []
    private(set) var x = 50.0
    private(set) var y = BlockEngine.ground
    private(set) var vy = 0.0
    private(set) var lane = 1
    /// Where the finger is (drag, steer).
    var target: (x: Double, y: Double) = (50, BlockEngine.ground)
    private(set) var time = 0.0
    private(set) var score = 0
    private(set) var lives: Int
    private(set) var combo = 0
    private(set) var bestCombo = 0
    private(set) var collected = 0
    private(set) var powers: [PowerKind: Double] = [:]
    private(set) var invulnerable = 0.0
    private(set) var isOver = false
    private var airJumps = 0
    private var spawnIn: [Double]
    private var nextID = 0
    private var survivalCarry = 0.0
    private var events: [Event] = []

    init(spec: GameSpec, seed: UInt64) {
        self.spec = spec
        rng = SplitMix(seed: seed)
        lives = spec.lives
        spawnIn = spec.entities.map { 0.6 + 1 / max(0.1, $0.rate) * 0.5 }
        switch spec.control {
        case .drag, .lanes: (x, y) = (50, Self.ground)
        case .steer: (x, y) = (50, 100)
        case .jump: (x, y) = (24, Self.ground)
        case .flap: (x, y) = (28, 80)
        }
        target = (x, y)
    }

    var timeLeft: Double? { spec.goal == .timed ? max(0, spec.duration - time) : nil }
    var multiplier: Double { 1 + min(2, Double(combo) / 10) }
    var playerRadius: Double { powers[.shrink] != nil ? 5 : 8 }
    var isOnGround: Bool { y >= Self.ground - 0.01 }
    /// How much faster and busier than at the start.
    var intensity: Double { 1 + spec.ramp * time / 10 }

    // MARK: Input

    mutating func tap() {
        guard !isOver else { return }
        switch spec.control {
        case .jump:
            if isOnGround {
                vy = -170
                airJumps = 1
                events.append(.jump)
            } else if airJumps > 0 {
                vy = -140
                airJumps -= 1
                events.append(.jump)
            }
        case .flap:
            vy = -105
            events.append(.jump)
        default:
            break
        }
    }

    mutating func moveLane(_ direction: Int) {
        guard spec.control == .lanes, !isOver else { return }
        let next = max(0, min(2, lane + direction))
        if next != lane { lane = next; events.append(.jump) }
    }

    // MARK: Time

    mutating func step(_ dt: Double) {
        guard !isOver, dt > 0 else { return }
        time += dt
        invulnerable = max(0, invulnerable - dt)
        for (p, left) in powers {
            powers[p] = left - dt > 0 ? left - dt : nil
        }
        movePlayer(dt)
        spawn(dt)
        moveThings(dt)
        collide()

        if spec.goal == .survive {
            survivalCarry += dt
            while survivalCarry >= 2 { survivalCarry -= 2; score += 1 }
        }
        if (spec.goal == .timed && time >= spec.duration) || (spec.lives > 0 && lives <= 0) || time >= 180 {
            isOver = true
        }
    }

    mutating func drainEvents() -> [Event] {
        defer { events.removeAll() }
        return events
    }

    private mutating func movePlayer(_ dt: Double) {
        switch spec.control {
        case .drag:
            let maxMove = 190 * dt
            x += max(-maxMove, min(maxMove, target.x - x))
            x = max(8, min(92, x))
        case .lanes:
            let goal = Self.lanes[lane]
            x += max(-260 * dt, min(260 * dt, goal - x))
        case .steer:
            let dx = target.x - x, dy = target.y - y
            let dist = (dx * dx + dy * dy).squareRoot()
            let maxMove = 130 * dt
            if dist > 0.5 {
                x += dx / dist * min(dist, maxMove)
                y += dy / dist * min(dist, maxMove)
            }
            x = max(8, min(92, x))
            y = max(16, min(150, y))
        case .jump:
            vy += 430 * dt
            y += vy * dt
            if y >= Self.ground { y = Self.ground; vy = 0 }
        case .flap:
            vy = min(160, vy + 280 * dt)
            y += vy * dt
            if y < 14 { y = 14; vy = max(0, vy) }
            if y > 150 {
                // Touching the ground hurts, then bounces you back up.
                y = 150
                vy = -120
                hurt(x: x, y: y)
            }
        }
    }

    private mutating func spawn(_ dt: Double) {
        for i in spec.entities.indices {
            spawnIn[i] -= dt * intensity
            guard spawnIn[i] <= 0 else { continue }
            let e = spec.entities[i]
            spawnIn[i] = (1 / e.rate) * (0.7 + rng.unit() * 0.6)
            things.append(make(kind: i, e))
            nextID += 1
        }
    }

    private mutating func make(kind: Int, _ e: EntitySpec) -> Thing {
        let speed = e.speed * min(2.2, intensity)
        let phase = rng.unit() * .pi * 2
        switch spec.control {
        case .drag, .lanes:
            let x = spec.control == .lanes ? Self.lanes[rng.int(0...2)] : 8 + rng.unit() * 84
            let side = e.motion == .bounce ? (rng.unit() < 0.5 ? -30.0 : 30) : 0
            return Thing(id: nextID, kind: kind, x: x, y: -8, vx: side, vy: 44 * speed, phase: phase)
        case .steer:
            // From a random edge toward a point in the middle.
            let edge = rng.int(0...3)
            let (sx, sy): (Double, Double) = switch edge {
            case 0: (rng.unit() * 100, -8)
            case 1: (108, 10 + rng.unit() * 140)
            case 2: (rng.unit() * 100, 168)
            default: (-8, 10 + rng.unit() * 140)
            }
            let tx = 25 + rng.unit() * 50, ty = 40 + rng.unit() * 80
            let len = max(1, ((tx - sx) * (tx - sx) + (ty - sy) * (ty - sy)).squareRoot())
            let v = 34 * speed
            return Thing(id: nextID, kind: kind, x: sx, y: sy, vx: (tx - sx) / len * v, vy: (ty - sy) / len * v, phase: phase)
        case .jump:
            let low = e.role == .avoid ? rng.unit() < 0.75 : rng.unit() < 0.35
            let y = low ? Self.ground - 2 : Self.ground - 26 - rng.unit() * 20
            return Thing(id: nextID, kind: kind, x: 108, y: y, vx: -48 * speed, vy: 0, phase: phase)
        case .flap:
            return Thing(id: nextID, kind: kind, x: 108, y: 20 + rng.unit() * 125, vx: -42 * speed, vy: 0, phase: phase)
        }
    }

    private mutating func moveThings(_ dt: Double) {
        let slow = powers[.slow] != nil ? 0.5 : 1
        let magnet = powers[.magnet] != nil
        let horizontalTravel = spec.control == .jump || spec.control == .flap
        var kept: [Thing] = []
        for var t in things {
            let e = spec.entities[t.kind]
            t.age += dt
            var dx = t.vx, dy = t.vy
            // The motion works across the direction of travel.
            switch e.motion {
            case .straight:
                break
            case .zigzag:
                let flip = Int(t.age / 0.6 + t.phase).isMultiple(of: 2) ? 1.0 : -1.0
                if horizontalTravel { dy += flip * 26 } else { dx += flip * 26 }
            case .wave:
                let w = cos(t.age * 3 + t.phase) * 30
                if horizontalTravel { dy += w } else { dx += w }
            case .homing:
                if horizontalTravel {
                    dy += max(-30, min(30, (y - t.y) * 1.2))
                } else {
                    dx += max(-30, min(30, (x - t.x) * 1.2))
                }
            case .bounce:
                if spec.control == .jump {
                    // Hops along the ground.
                    dy = 0
                    t.y = Self.ground - 2 - abs(sin(t.age * 4 + t.phase)) * 22
                }
            }
            if magnet, e.role == .collect {
                let ddx = x - t.x, ddy = y - t.y
                let d = (ddx * ddx + ddy * ddy).squareRoot()
                if d < 42, d > 0.5 { dx += ddx / d * 110; dy += ddy / d * 110 }
            }
            t.x += dx * dt * slow
            t.y += dy * dt * slow
            if e.motion == .bounce, spec.control != .jump {
                if t.x < 6 || t.x > 94 { t.vx = -t.vx; t.x = max(6, min(94, t.x)) }
                if spec.control == .flap, t.y < 16 || t.y > 148 { t.vy = t.vy == 0 ? 30 : -t.vy }
            }
            let gone = t.y > 172 || t.y < -20 || t.x < -16 || t.x > 116
            if gone {
                // A good thing that fell past you breaks the combo.
                if e.role == .collect, spec.control == .drag || spec.control == .lanes, combo > 0 {
                    combo = 0
                    events.append(.miss)
                }
                continue
            }
            kept.append(t)
        }
        things = kept
    }

    private mutating func collide() {
        var kept: [Thing] = []
        for t in things {
            let e = spec.entities[t.kind]
            let reach = playerRadius + 5 * e.size
            let dx = t.x - x, dy = t.y - (y - 6)
            guard dx * dx + dy * dy < reach * reach else { kept.append(t); continue }
            switch e.role {
            case .collect:
                combo += 1
                bestCombo = max(bestCombo, combo)
                collected += 1
                let points = Int((Double(e.points) * multiplier * (powers[.double] != nil ? 2 : 1)).rounded())
                score += points
                events.append(.collect(x: t.x, y: t.y, points: points, sprite: e.sprite))
                if combo % 10 == 0 { events.append(.combo(combo)) }
            case .avoid:
                guard invulnerable <= 0 else { kept.append(t); continue }
                if powers[.shield] != nil {
                    powers[.shield] = nil
                    invulnerable = 0.6
                    events.append(.blocked(x: t.x, y: t.y))
                } else {
                    if spec.lives == 0 { score = max(0, score - e.points) }
                    hurt(x: t.x, y: t.y)
                }
            case .power:
                let p = e.power ?? .shield
                if p == .life {
                    if spec.lives > 0 { lives = min(5, lives + 1) } else { score += 10 }
                } else {
                    powers[p] = p.seconds
                }
                events.append(.power(p, x: t.x, y: t.y))
            }
        }
        things = kept
    }

    private mutating func hurt(x: Double, y: Double) {
        guard invulnerable <= 0 else { return }
        combo = 0
        invulnerable = 1.2
        if spec.lives > 0 { lives -= 1 }
        events.append(.hit(x: x, y: y))
    }
}
