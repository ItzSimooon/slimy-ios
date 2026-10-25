import Foundation

// The daily plan: every day there is a new game built from blocks, a quest
// and a short story for the slime. The server has Claude compose it (see
// server/src/daily.js); without the server, DailyPlanner makes one from the
// same blocks. Either way the app only ever receives data, never code, and
// everything is clamped to safe ranges before it is played.

/// How the player moves the slime.
enum GameControl: String, Codable, CaseIterable, Hashable {
    /// Slides along the bottom, following the finger.
    case drag
    /// Three lanes at the bottom; tap or swipe to switch.
    case lanes
    /// Floats freely after the finger; things come from every side.
    case steer
    /// Runs along the ground; tap to jump (twice for a double jump).
    case jump
    /// Hovers; every tap is a little flap upward.
    case flap
}

/// What touching a thing does.
enum EntityRole: String, Codable, Hashable {
    case collect, avoid, power
}

/// How a thing moves on top of travelling across the board.
enum EntityMotion: String, Codable, CaseIterable, Hashable {
    case straight, zigzag, wave, homing, bounce
}

enum PowerKind: String, Codable, CaseIterable, Hashable {
    /// Ignores the next bad hit.
    case shield
    /// Pulls good things in.
    case magnet
    /// Everything moves at half speed for a while.
    case slow
    /// Points count double for a while.
    case double
    /// One more life (or bonus points when there are no lives).
    case life
    /// The slime gets small and slips through gaps.
    case shrink

    var seconds: Double {
        switch self {
        case .shield: 10
        case .magnet: 7
        case .slow: 5
        case .double: 8
        case .life: 0
        case .shrink: 6
        }
    }

    var title: String {
        switch self {
        case .shield: "Shield"
        case .magnet: "Magnet"
        case .slow: "Slow-mo"
        case .double: "Double points"
        case .life: "Extra life"
        case .shrink: "Tiny"
        }
    }
}

enum GameGoal: String, Codable, Hashable {
    /// Score as much as you can before the time runs out.
    case timed
    /// Last as long as you can; it gets harder and harder.
    case survive
}

/// Background looks. Each is a palette plus drifting decoration.
enum GameTheme: String, Codable, CaseIterable, Hashable {
    case dawn, day, dusk, night, ocean, forest, candy, lava, space, snow

    /// Sky top, sky bottom, ground.
    var colors: (top: UInt32, bottom: UInt32, ground: UInt32) {
        switch self {
        case .dawn: (0xF6B48F, 0xFDE3C0, 0x8FBF6A)
        case .day: (0x8CC8E8, 0xDDF1F5, 0x6FBF5A)
        case .dusk: (0x5B4A8A, 0xF29A76, 0x5E7D4A)
        case .night: (0x141A33, 0x2E3A66, 0x2F4A3A)
        case .ocean: (0x1E5F8C, 0x4FB3C9, 0xD9C38A)
        case .forest: (0x2F6B4A, 0x9DD382, 0x4E7A36)
        case .candy: (0xF7A8C8, 0xFFE6F0, 0xC98AD9)
        case .lava: (0x3A1414, 0xC9502A, 0x2A1A1A)
        case .space: (0x07081A, 0x23164A, 0x4A3A6A)
        case .snow: (0xB9D6EA, 0xF3F8FC, 0xE8F0F6)
        }
    }

    enum Decor { case clouds, stars, bubbles, leaves, sparkles, embers, snowflakes }

    var decor: Decor {
        switch self {
        case .dawn, .day: .clouds
        case .dusk, .candy: .sparkles
        case .night, .space: .stars
        case .ocean: .bubbles
        case .forest: .leaves
        case .lava: .embers
        case .snow: .snowflakes
        }
    }
}

struct EntitySpec: Codable, Hashable {
    /// An item sprite name from the allowed list.
    var sprite: String
    var role: EntityRole
    var power: PowerKind?
    var motion: EntityMotion
    /// 0.5 (slow) … 2.0 (fast).
    var speed: Double
    /// Spawns per second at the start, 0.1 … 2.5.
    var rate: Double
    /// Points for collecting (1…25); for avoid things, points lost when
    /// there are no lives (0…10).
    var points: Int
    /// 0.6 … 1.6.
    var size: Double
}

struct GameSpec: Codable, Hashable, Identifiable {
    var id: String
    var title: String
    var blurb: String
    var tutorial: [String]
    var control: GameControl
    var goal: GameGoal
    /// Seconds for timed games, 20 … 90.
    var duration: Double
    /// 0 … 5. With 0 lives, bad hits cost points instead.
    var lives: Int
    var theme: GameTheme
    var entities: [EntitySpec]
    /// How much faster and busier it gets every 10 seconds, 0 … 0.5.
    var ramp: Double

    static let allowedSprites: Set<String> = [
        "apple", "carrot", "berry", "mushroom", "cookie", "soup", "honey", "pudding", "cake", "starJelly",
        "coin", "gem", "heart", "chest", "flower", "seed", "noteItem", "bubbleItem", "ball",
        "rock", "cactus", "rotten", "puddle",
    ]

    /// Every value inside its safe range, at least one thing to collect, and
    /// never so busy that it can't be played. The app runs this on every spec,
    /// wherever it came from.
    func sanitized() -> GameSpec {
        var s = self
        s.title = String(title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(28))
        if s.title.isEmpty { s.title = "Daily game" }
        s.blurb = String(blurb.prefix(90))
        s.tutorial = Array(tutorial.map { String($0.prefix(80)) }.filter { !$0.isEmpty }.prefix(3))
        s.duration = min(90, max(20, duration.isFinite ? duration : 45))
        s.lives = min(5, max(0, lives))
        if goal == .survive, s.lives == 0 { s.lives = 3 }
        s.ramp = min(0.5, max(0, ramp.isFinite ? ramp : 0.15))

        var things = entities.prefix(6).compactMap { e -> EntitySpec? in
            guard Self.allowedSprites.contains(e.sprite) else { return nil }
            var e = e
            e.speed = min(2, max(0.5, e.speed.isFinite ? e.speed : 1))
            e.rate = min(2.5, max(0.1, e.rate.isFinite ? e.rate : 0.6))
            e.size = min(1.6, max(0.6, e.size.isFinite ? e.size : 1))
            switch e.role {
            case .collect: e.points = min(25, max(1, e.points)); e.power = nil
            case .avoid: e.points = min(10, max(0, e.points)); e.power = nil
            case .power: e.points = 0; e.power = e.power ?? .shield; e.rate = min(e.rate, 0.25)
            }
            // Lanes keep things in their lane.
            if control == .lanes { e.motion = e.motion == .homing ? .homing : .straight }
            return e
        }
        if !things.contains(where: { $0.role == .collect }) {
            things.insert(EntitySpec(sprite: "coin", role: .collect, power: nil, motion: .straight,
                                     speed: 1, rate: 0.8, points: 3, size: 1), at: 0)
        }
        // Keep the screen readable: total and harmful spawn rates are capped.
        let avoidRate = things.filter { $0.role == .avoid }.map(\.rate).reduce(0, +)
        if avoidRate > 1.6 {
            for i in things.indices where things[i].role == .avoid { things[i].rate *= 1.6 / avoidRate }
        }
        let total = things.map(\.rate).reduce(0, +)
        if total > 3.5 {
            for i in things.indices { things[i].rate *= 3.5 / total }
        }
        s.entities = things
        if s.tutorial.isEmpty { s.tutorial = DailyPlanner.tutorial(for: s) }
        return s
    }

    /// A score that counts as a good round, estimated from what there is to
    /// collect. Used for rewards and the quest; never taken from the spec.
    var goodScore: Int {
        let seconds = goal == .timed ? duration : 60
        let expected = entities.filter { $0.role == .collect }
            .map { $0.rate * seconds * Double($0.points) * 0.45 }
            .reduce(0, +)
        let survival = goal == .survive ? seconds / 2 : 0
        return max(10, Int(((expected + survival) / 5).rounded()) * 5)
    }
}

/// A small task for the day, made with the plan.
struct DailyQuest: Codable, Hashable {
    /// JSON: {"kind": "stat", "stat": "feeds", "target": 3} or
    /// {"kind": "dailyScore", "target": 120}.
    enum Goal: Codable, Hashable {
        case stat(StatKey, Int)
        case dailyScore(Int)

        private enum Keys: String, CodingKey { case kind, stat, target }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: Keys.self)
            let target = try c.decode(Int.self, forKey: .target)
            if try c.decode(String.self, forKey: .kind) == "stat" {
                self = .stat(try c.decode(StatKey.self, forKey: .stat), target)
            } else {
                self = .dailyScore(target)
            }
        }

        func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: Keys.self)
            switch self {
            case .stat(let key, let n):
                try c.encode("stat", forKey: .kind)
                try c.encode(key, forKey: .stat)
                try c.encode(n, forKey: .target)
            case .dailyScore(let n):
                try c.encode("dailyScore", forKey: .kind)
                try c.encode(n, forKey: .target)
            }
        }
    }

    var title: String
    var goal: Goal
    var coins: Int

    func sanitized(goodScore: Int) -> DailyQuest {
        var q = self
        q.title = String(title.prefix(60))
        q.coins = min(40, max(5, coins))
        switch goal {
        case .stat(let key, let n): q.goal = .stat(key, min(15, max(1, n)))
        case .dailyScore(let n): q.goal = .dailyScore(min(goodScore * 2, max(5, n)))
        }
        return q
    }
}

struct DailyPlan: Codable, Hashable, Identifiable {
    /// The local day it belongs to, "yyyy-MM-dd".
    var day: String
    var game: GameSpec
    var quest: DailyQuest
    /// What the slime is thinking about today, one or two short sentences.
    var story: String
    /// "ai" when Claude made it on the server, "local" when the app did.
    var source: String

    var id: String { day }

    func sanitized() -> DailyPlan {
        var p = self
        p.game = game.sanitized()
        p.game.id = day
        p.quest = quest.sanitized(goodScore: p.game.goodScore)
        p.story = String(story.prefix(160))
        return p
    }
}

/// Progress on today's quest, kept in the save.
struct QuestState: Codable, Hashable {
    var day: String
    var baseline = 0
    var best = 0
    var claimed = false
}
