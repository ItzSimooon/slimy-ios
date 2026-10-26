import Foundation

// MARK: - Jelly Dash

/// An endless run to the right. A tap jumps, holding the finger down jumps
/// higher and farther. Puddles and rocks cost a heart, coins and berries
/// give points, and the run gets faster the longer it lasts.
///
/// Things live in world units along the track (`x` grows to the right);
/// on screen a thing sits at `x - distance`, and the slime always runs at
/// `playerX`. Heights use the board's y, pointing down, with the track at
/// `ground`.
struct JellyDashEngine {
    enum ObstacleKind: Equatable {
        case puddle, rock
        /// Two rocks on top of each other: only a held jump clears it.
        case boulder

        var width: Double {
            switch self {
            case .puddle: 14
            case .rock: 9
            case .boulder: 11
            }
        }

        var height: Double {
            switch self {
            case .puddle: 2.5
            case .rock: 9
            case .boulder: 24
            }
        }
    }

    enum PickupKind: Equatable { case coin, berry }

    struct Obstacle: Identifiable {
        let id: Int
        /// Centre along the track.
        let x: Double
        let kind: ObstacleKind
        /// Already touched; it can't hurt twice.
        var touched = false
    }

    struct Pickup: Identifiable {
        let id: Int
        let x: Double
        /// Board y of its centre.
        let y: Double
        let kind: PickupKind
    }

    static let ground = 140.0
    static let playerX = 24.0
    static let gravity = 430.0
    static let jumpSpeed = 150.0
    /// Share of gravity while the finger stays down early in a jump.
    static let holdGravity = 0.45
    static let maxHold = 0.22
    static let startSpeed = 48.0
    static let maxSpeed = 112.0
    /// Speed gained per second.
    static let acceleration = 1.1
    static let lives = 3
    /// Track units per point.
    static let meter = 25.0
    static let coinValue = 2
    static let berryValue = 5
    /// Extra points for every tenth pickup without getting hurt.
    static let streakBonus = 10
    /// A tap this long before landing still jumps on touchdown.
    static let jumpBuffer = 0.12
    static let invulnerability = 1.4
    /// The slime's hit box: half its width and its height.
    static let halfWidth = 3.5
    static let bodyHeight = 9.0
    /// How close a pickup has to come to the slime's middle.
    static let reach = 8.0

    private var rng: SplitMix
    private(set) var obstacles: [Obstacle] = []
    private(set) var pickups: [Pickup] = []
    /// Track units run so far.
    private(set) var distance = 0.0
    private(set) var speed = JellyDashEngine.startSpeed
    /// The slime's feet.
    private(set) var y = JellyDashEngine.ground
    private(set) var vy = 0.0
    private(set) var time = 0.0
    private(set) var lives = JellyDashEngine.lives
    private(set) var invulnerable = 0.0
    /// Points from pickups and streaks.
    private(set) var bonus = 0
    /// Pickups since the last hit.
    private(set) var streak = 0
    private(set) var hits = 0
    private(set) var isOver = false
    private(set) var holding = false
    private var holdTime = JellyDashEngine.maxHold
    private var buffered = 0.0
    private var nextSpawn = 130.0
    private var nextID = 0
    private var events: [ArcadeEvent] = []

    init(seed: UInt64) {
        rng = SplitMix(seed: seed)
    }

    var isOnGround: Bool { y >= Self.ground - 0.001 && vy >= 0 }
    var meters: Int { Int(distance / Self.meter) }
    var score: Int { meters + bonus }
    /// 0 at the start, 1 from 4000 track units on.
    var difficulty: Double { min(1, distance / 4000) }

    // MARK: Input

    /// The finger went down: jump now, or right on landing.
    mutating func press() {
        guard !isOver else { return }
        holding = true
        if isOnGround { jump() } else { buffered = Self.jumpBuffer }
    }

    /// The finger came up: the jump stops rising early.
    mutating func release() {
        holding = false
        holdTime = Self.maxHold
    }

    private mutating func jump() {
        vy = -Self.jumpSpeed
        holdTime = 0
        buffered = 0
        events.append(.jump)
    }

    // MARK: Time

    mutating func step(_ dt: Double) {
        guard !isOver, dt > 0 else { return }
        time += dt
        speed = min(Self.maxSpeed, speed + Self.acceleration * dt)
        distance += speed * dt
        invulnerable = max(0, invulnerable - dt)
        buffered = max(0, buffered - dt)

        var g = Self.gravity
        if holding, vy < 0, holdTime < Self.maxHold {
            g *= Self.holdGravity
            holdTime += dt
        }
        let wasInAir = !isOnGround
        vy += g * dt
        y += vy * dt
        if y >= Self.ground {
            y = Self.ground
            if vy > 0 {
                vy = 0
                if wasInAir {
                    events.append(.land)
                    if buffered > 0 { jump() }
                }
            }
        }

        spawnAhead()
        collide()
        let left = distance - 16
        obstacles.removeAll { $0.x + $0.kind.width / 2 <= left }
        pickups.removeAll { $0.x <= left }
    }

    mutating func drainEvents() -> [ArcadeEvent] {
        defer { events.removeAll() }
        return events
    }

    // MARK: Track

    private enum Pattern {
        case puddle, rock, rockCoins, coinRow, boulder, highBerry, doubleRock, puddleRock, rockBoulder
    }

    private mutating func spawnAhead() {
        while nextSpawn - distance < Board.width + 40 {
            let end = place(at: nextSpawn)
            // Gaps leave room to land and react; they shrink as it gets harder.
            let gap = speed * (0.9 + rng.unit() * 0.8) * (1 - 0.3 * difficulty)
            nextSpawn = end + gap + 16
        }
    }

    /// Lays out one pattern starting at `x`; returns where it ends.
    private mutating func place(at x: Double) -> Double {
        let d = difficulty
        let g = Self.ground
        var options: [Pattern] = [.puddle, .rock, .rockCoins, .coinRow]
        if d >= 0.08 { options += [.boulder, .highBerry] }
        if d >= 0.2 { options.append(.doubleRock) }
        if d >= 0.3 { options.append(.puddleRock) }
        if d >= 0.5 { options.append(.rockBoulder) }
        switch options[rng.int(0...options.count - 1)] {
        case .puddle:
            add(.puddle, at: x)
            if rng.unit() < 0.5 { add(.coin, x: x, y: g - 20) }
            return x + 7
        case .rock:
            add(.rock, at: x)
            return x + 5
        case .rockCoins:
            add(.rock, at: x)
            add(.coin, x: x - 10, y: g - 16)
            add(.coin, x: x, y: g - 24)
            add(.coin, x: x + 10, y: g - 16)
            return x + 14
        case .coinRow:
            for i in 0..<5 { add(.coin, x: x + Double(i) * 9, y: g - 5) }
            return x + 36
        case .boulder:
            add(.boulder, at: x)
            add(.berry, x: x, y: g - 34)
            return x + 5
        case .highBerry:
            add(.berry, x: x, y: g - 36)
            add(.coin, x: x - 10, y: g - 26)
            add(.coin, x: x + 10, y: g - 26)
            return x + 14
        case .doubleRock:
            add(.rock, at: x)
            add(.rock, at: x + 15)
            add(.coin, x: x + 7.5, y: g - 30)
            return x + 20
        case .rockBoulder:
            add(.rock, at: x)
            let x2 = x + speed * 1.15 + 16
            add(.boulder, at: x2)
            add(.berry, x: x2, y: g - 34)
            return x2 + 6
        case .puddleRock:
            add(.puddle, at: x)
            let x2 = x + speed * 1.1 + 14
            add(.rock, at: x2)
            return x2 + 5
        }
    }

    private mutating func add(_ kind: ObstacleKind, at x: Double) {
        obstacles.append(Obstacle(id: nextID, x: x, kind: kind))
        nextID += 1
    }

    private mutating func add(_ kind: PickupKind, x: Double, y: Double) {
        pickups.append(Pickup(id: nextID, x: x, y: y, kind: kind))
        nextID += 1
    }

    // MARK: Contact

    private mutating func collide() {
        let px = distance + Self.playerX
        for i in obstacles.indices where !obstacles[i].touched {
            let o = obstacles[i]
            let across = abs(o.x - px) < o.kind.width / 2 + Self.halfWidth
            let low = y > Self.ground - o.kind.height + 0.5
            guard across, low else { continue }
            obstacles[i].touched = true
            guard invulnerable <= 0 else { continue }
            lives -= 1
            hits += 1
            invulnerable = Self.invulnerability
            streak = 0
            speed = max(Self.startSpeed, speed * 0.85)
            events.append(.bad)
            if lives <= 0 {
                isOver = true
                events.append(.lose)
                return
            }
        }

        let cy = y - Self.bodyHeight / 2
        var kept: [Pickup] = []
        for p in pickups {
            let dx = p.x - px, dy = p.y - cy
            guard dx * dx + dy * dy <= Self.reach * Self.reach else {
                kept.append(p)
                continue
            }
            bonus += p.kind == .coin ? Self.coinValue : Self.berryValue
            streak += 1
            events.append(p.kind == .coin ? .coin : .good)
            if streak % 10 == 0 {
                bonus += Self.streakBonus
                events.append(.great)
            }
        }
        pickups = kept
    }
}
