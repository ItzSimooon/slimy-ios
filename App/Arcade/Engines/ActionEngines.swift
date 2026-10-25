import Foundation

// The real-time games. Each engine is a plain value with no UI: the view
// feeds it time and input, draws what it holds and plays the events it
// reports. Everything random comes from a seeded generator, so a round can
// be replayed exactly (async challenges, tests).
//
// Boards are 100 units wide and 160 high, y pointing down.

enum Board {
    static let width = 100.0
    static let height = 160.0
}

/// Sounds and effects an engine wants played after a step.
enum ArcadeEvent: Equatable {
    case good, great, bad, coin, miss, jump, land, tone(Int), win, lose
}

// MARK: - Snack-Fänger

struct SnackCatcherEngine {
    enum Kind { case food, coin, bad }

    struct Drop: Identifiable {
        let id: Int
        var x: Double
        var y: Double
        let kind: Kind
        let sprite: String
        let speed: Double
    }

    static let duration = 45.0
    static let catchY = 142.0
    static let reach = 12.0
    private static let foods = ["apple", "carrot", "berry", "mushroom", "cookie", "honey", "cake", "pudding"]

    private var rng: SplitMix
    private(set) var drops: [Drop] = []
    var playerX = 50.0
    /// Where the finger is; the slime slides there.
    var targetX = 50.0
    private(set) var score = 0
    private(set) var combo = 0
    private(set) var bestCombo = 0
    private(set) var time = 0.0
    private var spawnIn = 0.5
    private var nextID = 0
    private var events: [ArcadeEvent] = []

    init(seed: UInt64) { rng = SplitMix(seed: seed) }

    var isOver: Bool { time >= Self.duration }
    var timeLeft: Double { max(0, Self.duration - time) }

    mutating func step(_ dt: Double) {
        guard !isOver, dt > 0 else { return }
        time += dt
        let maxMove = 170 * dt
        playerX += max(-maxMove, min(maxMove, targetX - playerX))
        playerX = max(8, min(Board.width - 8, playerX))

        spawnIn -= dt
        if spawnIn <= 0 { spawn() }

        var kept: [Drop] = []
        for var drop in drops {
            let before = drop.y
            drop.y += drop.speed * dt
            // Caught when it passes the slime's mouth this step.
            if before < Self.catchY, drop.y >= Self.catchY - 8, abs(drop.x - playerX) <= Self.reach {
                collect(drop)
                continue
            }
            if drop.y > Board.height + 10 {
                if drop.kind == .food, combo > 0 { combo = 0; events.append(.miss) }
                continue
            }
            kept.append(drop)
        }
        drops = kept
    }

    mutating func drainEvents() -> [ArcadeEvent] {
        defer { events.removeAll() }
        return events
    }

    private mutating func spawn() {
        let progress = time / Self.duration
        spawnIn = 0.8 - progress * 0.4 + rng.unit() * 0.25
        let roll = rng.unit()
        let kind: Kind = roll < 0.66 ? .food : (roll < 0.78 ? .coin : .bad)
        let sprite: String
        switch kind {
        case .food: sprite = Self.foods[rng.int(0...Self.foods.count - 1)]
        case .coin: sprite = "coin"
        case .bad: sprite = rng.unit() < 0.5 ? "rotten" : "cactus"
        }
        let speed = 48 + progress * 60 + rng.unit() * 18
        drops.append(Drop(id: nextID, x: 8 + rng.unit() * (Board.width - 16), y: -8, kind: kind, sprite: sprite, speed: speed))
        nextID += 1
    }

    private mutating func collect(_ drop: Drop) {
        switch drop.kind {
        case .food:
            combo += 1
            bestCombo = max(bestCombo, combo)
            // Every five in a row adds one point per catch.
            score += 1 + combo / 5
            events.append(combo % 5 == 0 ? .great : .good)
        case .coin:
            score += 3
            events.append(.coin)
        case .bad:
            score = max(0, score - 3)
            combo = 0
            events.append(.bad)
        }
    }
}

// MARK: - Slime-Sprung

struct SlimeJumpEngine {
    struct Platform: Identifiable {
        let id: Int
        var x: Double
        let y: Double
        let width: Double
        /// Horizontal speed; 0 for still platforms.
        var drift: Double
        var coin: Bool
    }

    static let gravity = 190.0
    static let jumpSpeed = 150.0
    static let steerSpeed = 75.0

    private var rng: SplitMix
    private(set) var platforms: [Platform] = []
    /// Position in world units; y grows downward like the screen, so going
    /// up means y gets smaller.
    private(set) var x = 50.0
    private(set) var y = 140.0
    private(set) var vy = -SlimeJumpEngine.jumpSpeed
    /// -1 left, 0 none, 1 right.
    var steer = 0.0
    /// Top of the visible window in world units.
    private(set) var cameraY = 0.0
    private(set) var score = 0
    private(set) var coins = 0
    private(set) var isOver = false
    private var highest = 0
    private var nextID = 0
    private var events: [ArcadeEvent] = []

    init(seed: UInt64) {
        rng = SplitMix(seed: seed)
        platforms.append(Platform(id: 0, x: 50, y: 150, width: 40, drift: 0, coin: false))
        nextID = 1
        fill()
    }

    mutating func step(_ dt: Double) {
        guard !isOver, dt > 0 else { return }
        for i in platforms.indices where platforms[i].drift != 0 {
            platforms[i].x += platforms[i].drift * dt
            let half = platforms[i].width / 2
            if platforms[i].x < half || platforms[i].x > Board.width - half {
                platforms[i].drift = -platforms[i].drift
                platforms[i].x = max(half, min(Board.width - half, platforms[i].x))
            }
        }

        x += steer * Self.steerSpeed * dt
        if x < 0 { x += Board.width }
        if x > Board.width { x -= Board.width }

        let before = y
        vy += Self.gravity * dt
        y += vy * dt

        if vy > 0 {
            for p in platforms where before <= p.y && y >= p.y && abs(x - p.x) <= p.width / 2 + 3 {
                y = p.y
                vy = -Self.jumpSpeed
                events.append(.jump)
                if p.id > highest {
                    score += p.id - highest
                    highest = p.id
                }
                if p.coin, let i = platforms.firstIndex(where: { $0.id == p.id }) {
                    platforms[i].coin = false
                    coins += 1
                    score += 2
                    events.append(.coin)
                }
                break
            }
        }

        // The camera only follows upward.
        cameraY = min(cameraY, y - 90)
        if y > cameraY + Board.height + 12 {
            isOver = true
            events.append(.lose)
        }
        platforms.removeAll { $0.y > cameraY + Board.height + 30 }
        fill()
    }

    mutating func drainEvents() -> [ArcadeEvent] {
        defer { events.removeAll() }
        return events
    }

    private mutating func fill() {
        var top = platforms.map(\.y).min() ?? 150
        while top > cameraY - 60 {
            let difficulty = min(1, Double(nextID) / 60)
            top -= 20 + rng.unit() * (10 + difficulty * 14)
            let width = 26 - difficulty * 10 + rng.unit() * 6
            let half = width / 2
            let moving = nextID > 12 && rng.unit() < 0.15 + difficulty * 0.3
            platforms.append(Platform(id: nextID, x: half + rng.unit() * (Board.width - width), y: top, width: width,
                                      drift: moving ? (rng.unit() < 0.5 ? -1 : 1) * (14 + difficulty * 18) : 0,
                                      coin: rng.unit() < 0.18))
            nextID += 1
        }
    }
}

// MARK: - Slime-Rennen

struct SlimeRacingEngine {
    enum Kind { case rock, puddle }

    struct Obstacle: Identifiable {
        let id: Int
        let lane: Int
        let distance: Double
        let kind: Kind
        var hit = false
    }

    static let length = 1500.0
    static let lanes = 3
    static let cruise = 55.0
    static let ghostSpeed = 50.0
    static let timeLimit = 60.0

    private(set) var obstacles: [Obstacle] = []
    private(set) var lane = 1
    private(set) var distance = 0.0
    private(set) var speed = 20.0
    private(set) var time = 0.0
    private(set) var finished = false
    private var events: [ArcadeEvent] = []

    init(seed: UInt64) {
        var rng = SplitMix(seed: seed)
        var d = 90.0
        var id = 0
        while d < Self.length - 40 {
            let lane = rng.int(0...Self.lanes - 1)
            obstacles.append(Obstacle(id: id, lane: lane, distance: d, kind: rng.unit() < 0.75 ? .rock : .puddle))
            id += 1
            // Sometimes a second rock beside it, never all three lanes.
            if rng.unit() < 0.3 {
                let other = (lane + 1 + rng.int(0...1)) % Self.lanes
                obstacles.append(Obstacle(id: id, lane: other, distance: d, kind: .rock))
                id += 1
            }
            d += 55 + rng.unit() * 55
        }
    }

    var ghostDistance: Double { min(Self.length, time * Self.ghostSpeed) }
    var isOver: Bool { finished || time >= Self.timeLimit }

    /// Equal to the ghost is a good race (60); every second faster adds 6.
    var score: Int {
        if finished {
            let ghostTime = Self.length / Self.ghostSpeed
            return max(5, min(150, Int((60 + (ghostTime - time) * 6).rounded())))
        }
        return Int(distance / Self.length * 40)
    }

    mutating func move(_ direction: Int) {
        guard !isOver else { return }
        let next = max(0, min(Self.lanes - 1, lane + direction))
        if next != lane { lane = next; events.append(.jump) }
    }

    mutating func step(_ dt: Double) {
        guard !isOver, dt > 0 else { return }
        time += dt
        if speed < Self.cruise {
            speed = min(Self.cruise, speed + 28 * dt)
        } else {
            speed = max(Self.cruise, speed - 14 * dt)
        }
        let before = distance
        distance += speed * dt
        for i in obstacles.indices where !obstacles[i].hit && obstacles[i].lane == lane
            && before < obstacles[i].distance && distance >= obstacles[i].distance {
            obstacles[i].hit = true
            switch obstacles[i].kind {
            case .rock:
                speed = 14
                events.append(.bad)
            case .puddle:
                speed = 90
                events.append(.good)
            }
        }
        if distance >= Self.length {
            distance = Self.length
            finished = true
            events.append(time <= Self.length / Self.ghostSpeed ? .win : .lose)
        }
    }

    mutating func drainEvents() -> [ArcadeEvent] {
        defer { events.removeAll() }
        return events
    }
}

// MARK: - Rhythmus-Hüpfer

struct RhythmBounceEngine {
    enum Judgement { case perfect, good, miss }

    struct Note: Identifiable {
        let id: Int
        let time: Double
        let lane: Int
        var judged: Judgement?
    }

    static let lanes = 3
    static let bpm = 112.0
    static let perfectWindow = 0.07
    static let goodWindow = 0.16
    /// Seconds a note is visible before it reaches the line.
    static let lead = 1.6

    private(set) var notes: [Note] = []
    private(set) var time = -1.0
    private(set) var score = 0
    private(set) var combo = 0
    private(set) var bestCombo = 0
    private(set) var perfects = 0
    private(set) var lastJudgement: (Judgement, Double)?
    private var events: [ArcadeEvent] = []

    init(seed: UInt64) {
        var rng = SplitMix(seed: seed)
        let beat = 60 / Self.bpm
        var id = 0
        var lane = 1
        for i in 0..<56 {
            let t = 1.5 + Double(i) * beat
            // Rests now and then, quicker eighths later in the song.
            guard rng.unit() < 0.82 else { continue }
            lane = (lane + 1 + rng.int(0...1)) % Self.lanes
            notes.append(Note(id: id, time: t, lane: lane))
            id += 1
            if i > 24, rng.unit() < 0.25 {
                notes.append(Note(id: id, time: t + beat / 2, lane: (lane + 1) % Self.lanes))
                id += 1
            }
        }
    }

    var duration: Double { (notes.last?.time ?? 0) + 1.2 }
    var isOver: Bool { time >= duration }

    mutating func step(_ dt: Double) {
        guard !isOver, dt > 0 else { return }
        time += dt
        for i in notes.indices where notes[i].judged == nil && notes[i].time < time - Self.goodWindow {
            notes[i].judged = .miss
            combo = 0
            lastJudgement = (.miss, time)
            events.append(.miss)
        }
    }

    mutating func tap(lane: Int) {
        guard !isOver else { return }
        let candidates = notes.indices.filter { notes[$0].judged == nil && notes[$0].lane == lane }
        guard let i = candidates.min(by: { abs(notes[$0].time - time) < abs(notes[$1].time - time) }),
              abs(notes[i].time - time) <= Self.goodWindow else { return }
        let off = abs(notes[i].time - time)
        let judgement: Judgement = off <= Self.perfectWindow ? .perfect : .good
        notes[i].judged = judgement
        combo += 1
        bestCombo = max(bestCombo, combo)
        if judgement == .perfect {
            perfects += 1
            score += 10 + min(combo, 10)
        } else {
            score += 5
        }
        lastJudgement = (judgement, time)
        events.append(.tone(lane * 3 + (judgement == .perfect ? 2 : 0)))
    }

    mutating func drainEvents() -> [ArcadeEvent] {
        defer { events.removeAll() }
        return events
    }
}

// MARK: - Slime-Basketball

struct BasketballEngine {
    static let duration = 45.0
    static let gravity = 230.0
    static let start = (x: 50.0, y: 140.0)
    static let hoopY = 42.0
    static let rim = 9.0

    private(set) var time = 0.0
    private(set) var hoopX = 50.0
    private(set) var ball = BasketballEngine.start
    private(set) var velocity = (x: 0.0, y: 0.0)
    private(set) var flying = false
    private(set) var score = 0
    private(set) var streak = 0
    private(set) var hits = 0
    private(set) var shots = 0
    private var scoredThisShot = false
    private var phase: Double
    private var events: [ArcadeEvent] = []

    init(seed: UInt64) {
        var rng = SplitMix(seed: seed)
        phase = rng.unit() * .pi * 2
    }

    var isOver: Bool { time >= Self.duration && !flying }
    var timeLeft: Double { max(0, Self.duration - time) }

    /// A swipe from the ball, in board units. Longer swipes throw harder.
    mutating func throwBall(dx: Double, dy: Double) {
        guard !flying, !isOver, dy < -4 else { return }
        var vx = dx * 2.5
        var vy = dy * 2.5
        let length = (vx * vx + vy * vy).squareRoot()
        if length > 330 { vx *= 330 / length; vy *= 330 / length }
        velocity = (vx, vy)
        flying = true
        scoredThisShot = false
        shots += 1
        events.append(.jump)
    }

    mutating func step(_ dt: Double) {
        guard dt > 0 else { return }
        if time < Self.duration { time += dt }
        // The hoop drifts faster the more you score.
        let pace = 0.9 + Double(min(hits, 15)) * 0.08
        hoopX = 50 + sin(time * pace + phase) * 30
        guard flying else { return }

        let beforeY = ball.y
        velocity.y += Self.gravity * dt
        ball.x += velocity.x * dt
        ball.y += velocity.y * dt

        if velocity.y > 0, beforeY < Self.hoopY, ball.y >= Self.hoopY, !scoredThisShot {
            let off = abs(ball.x - hoopX)
            if off <= Self.rim - 2 {
                scoredThisShot = true
                hits += 1
                streak += 1
                score += streak >= 2 ? 2 : 1
                events.append(streak >= 2 ? .great : .good)
            } else if off <= Self.rim + 3 {
                // Clanks off the rim.
                velocity.y = -velocity.y * 0.35
                velocity.x = (ball.x < hoopX ? -1 : 1) * (abs(velocity.x) * 0.5 + 20)
                events.append(.land)
            }
        }

        if ball.y > Board.height + 10 || ball.x < -20 || ball.x > Board.width + 20 {
            if !scoredThisShot {
                streak = 0
                events.append(.miss)
            }
            flying = false
            ball = Self.start
            velocity = (0, 0)
        }
    }

    mutating func drainEvents() -> [ArcadeEvent] {
        defer { events.removeAll() }
        return events
    }
}
