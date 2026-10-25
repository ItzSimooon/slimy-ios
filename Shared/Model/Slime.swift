import Foundation

/// The looping animation the slime falls back to.
enum Mood: String, Codable, Hashable {
    case idle, sad, sleep
}

/// A short animation that plays after something happens.
enum Reaction: String, Codable, Hashable, CaseIterable {
    case none, eat, play, love, bath, melt, zap, confused, wave
    case celebrate, stretch, sulk, hide, curious, blush
}

/// Evolution stage. Each one has its own sprite set.
enum Stage: String, Codable, CaseIterable, Comparable {
    case baby, young, grown

    static func forLevel(_ level: Int) -> Stage {
        switch level {
        case ..<4: .baby
        case ..<12: .young
        default: .grown
        }
    }

    var title: String {
        switch self {
        case .baby: "Baby-Slime"
        case .young: "Slime"
        case .grown: "Großer Slime"
        }
    }

    var firstLevel: Int {
        switch self {
        case .baby: 1
        case .young: 4
        case .grown: 12
        }
    }

    static func < (a: Stage, b: Stage) -> Bool {
        allCases.firstIndex(of: a)! < allCases.firstIndex(of: b)!
    }
}

/// What kind of slime it is. Shapes decay rates, dialogue and favourites,
/// and drifts slowly with how it is cared for.
struct Personality: Codable, Hashable {
    var playful: Double
    var cuddly: Double
    var foodie: Double
    var sleepy: Double
    var curious: Double

    enum Trait: String, CaseIterable, Codable {
        case playful, cuddly, foodie, sleepy, curious

        var title: String {
            switch self {
            case .playful: "verspielt"
            case .cuddly: "verkuschelt"
            case .foodie: "verfressen"
            case .sleepy: "verschlafen"
            case .curious: "neugierig"
            }
        }

        var blurb: String {
            switch self {
            case .playful: "Langweilt sich schnell und liebt Spiele."
            case .cuddly: "Will viel gekrault werden."
            case .foodie: "Denkt die ganze Zeit ans Essen."
            case .sleepy: "Braucht viel Schlaf."
            case .curious: "Will alles anschauen und ausprobieren."
            }
        }
    }

    subscript(trait: Trait) -> Double {
        get {
            switch trait {
            case .playful: playful
            case .cuddly: cuddly
            case .foodie: foodie
            case .sleepy: sleepy
            case .curious: curious
            }
        }
        set {
            let v = min(1, max(0, newValue))
            switch trait {
            case .playful: playful = v
            case .cuddly: cuddly = v
            case .foodie: foodie = v
            case .sleepy: sleepy = v
            case .curious: curious = v
            }
        }
    }

    static func random(using rng: inout SplitMix) -> Personality {
        Personality(playful: rng.unit(), cuddly: rng.unit(), foodie: rng.unit(),
                    sleepy: rng.unit(), curious: rng.unit())
    }

    /// The two strongest traits, strongest first.
    var top: [Trait] {
        Trait.allCases.sorted { self[$0] > self[$1] }.prefix(2).map { $0 }
    }

    var dominant: Trait { top[0] }

    /// How much faster than normal a need drains for this personality.
    func decayFactor(_ need: Need) -> Double {
        switch need {
        case .hunger: 0.85 + 0.35 * foodie
        case .fun: 0.85 + 0.35 * playful
        case .energy: 0.85 + 0.35 * sleepy
        case .clean: 1
        case .love: 0.85 + 0.35 * cuddly
        }
    }

    /// Care shapes character, a little at a time.
    mutating func drift(_ trait: Trait, by amount: Double = 0.004) {
        self[trait] += amount
    }
}

/// Something the slime is saying right now.
struct Utterance: Codable, Hashable {
    var text: String
    var at: Date
    var lineID: String?
}

struct Accessories: Codable, Hashable {
    var hat: String?
    var glasses: String?
    var scarf: String?
    var effect: String?

    var all: [String] { [hat, glasses, scarf, effect].compactMap { $0 } }
}

struct Slime: Codable, Hashable {
    var name = "Blob"
    var skin: Skin = .lime
    var birthday = Date.now
    var needs = Needs()
    var xp = 0
    var isAsleep = false
    var lastUpdate = Date.now
    var reaction: Reaction = .none
    var reactionAt = Date.distantPast
    var favorite: Food = .berry
    var favoriteGame: GameID = .snackCatcher
    var personality = Personality(playful: 0.5, cuddly: 0.5, foodie: 0.5, sleepy: 0.5, curious: 0.5)
    /// 0...100: how close the slime feels to you. Only grows.
    var bond = 0.0
    var say: Utterance?
    var lastEvent: PhoneEvent?
    var eventAt = Date.distantPast
    var recentPets: [Date] = []
    var equipped = Accessories()
    /// The need it last told you about, so it doesn't repeat itself.
    var lastNudged: Need?
    var lastNudgeAt = Date.distantPast

    static func newborn(named name: String, skin: Skin, seed: UInt64) -> Slime {
        var rng = SplitMix(seed: seed)
        var s = Slime()
        s.name = name
        s.skin = skin
        s.personality = .random(using: &rng)
        s.favorite = Food.discoverable[Int(rng.next() % UInt64(Food.discoverable.count))]
        s.favoriteGame = GameID.allCases[Int(rng.next() % UInt64(GameID.allCases.count))]
        return s
    }

    // MARK: Derived

    var level: Int { Self.level(forXP: xp) }
    var stage: Stage { .forLevel(level) }

    static func level(forXP xp: Int) -> Int {
        // 40 xp to level 2, then each level costs 40 more than the last.
        Int((sqrt(1 + 8 * Double(max(0, xp)) / 40) - 1) / 2) + 1
    }

    static func xpNeeded(for level: Int) -> Int { 40 * level * (level - 1) / 2 }

    var levelProgress: Double {
        let a = Self.xpNeeded(for: level), b = Self.xpNeeded(for: level + 1)
        return Double(xp - a) / Double(b - a)
    }

    func ageDays(at now: Date = .now) -> Int {
        max(0, Calendar.current.dateComponents([.day], from: birthday, to: now).day ?? 0)
    }

    var mood: Mood {
        if isAsleep { return .sleep }
        return needs.lowest.value < 25 ? .sad : .idle
    }

    /// The need it is asking for, if anything is getting low.
    var wish: Need? {
        let low = needs.lowest
        return low.value < 50 ? low.need : nil
    }

    enum BondStage: Int, Comparable {
        case new, familiar, close, inseparable

        var title: String {
            switch self {
            case .new: "Neu"
            case .familiar: "Vertraut"
            case .close: "Eng"
            case .inseparable: "Unzertrennlich"
            }
        }

        static func < (a: BondStage, b: BondStage) -> Bool { a.rawValue < b.rawValue }
    }

    var bondStage: BondStage {
        switch bond {
        case ..<20: .new
        case ..<50: .familiar
        case ..<80: .close
        default: .inseparable
        }
    }

    func isUnlocked(_ skin: Skin) -> Bool { level >= skin.unlockLevel }

    /// How long a reaction plays in the island before it goes back to the mood.
    static let reactionWindow: TimeInterval = 8

    func activeReaction(at now: Date) -> Reaction {
        now.timeIntervalSince(reactionAt) < Self.reactionWindow ? reaction : .none
    }

    /// The phone event it is still talking about, for 15 minutes.
    func recentEvent(at now: Date) -> PhoneEvent? {
        now.timeIntervalSince(eventAt) < 15 * 60 ? lastEvent : nil
    }

    /// What it's saying, if it said something recently.
    func currentLine(at now: Date) -> String? {
        guard let say, now.timeIntervalSince(say.at) < 20 * 60 else { return nil }
        return say.text
    }

    mutating func react(_ r: Reaction, at now: Date) {
        reaction = r
        reactionAt = now
    }

    mutating func sanitize(now: Date) {
        needs.sanitize()
        xp = max(0, xp)
        bond = min(100, max(0, bond.isFinite ? bond : 0))
        if lastUpdate > now.addingTimeInterval(3600) { lastUpdate = now }
        if birthday > now { birthday = now }
        recentPets = recentPets.filter { $0 <= now }.suffix(12)
    }

    // MARK: Time

    /// Needs never drop below this from time passing alone. Being away
    /// shouldn't feel like a punishment; the slime is just glad you're back.
    static let restingFloor = 12.0
    /// At most this much time is simulated (a longer gap counts as this).
    static let maxSimulated: TimeInterval = 21 * 24 * 3600

    /// Runs the clock forward. Safe against clocks set backwards, huge gaps
    /// and missed background runs: it only ever looks at the saved time.
    mutating func advance(to now: Date, schedule: SleepSchedule, calendar: Calendar = .current) {
        let elapsed = now.timeIntervalSince(lastUpdate)
        if elapsed < 0 {
            // Clock moved backwards. Start again from here without decay.
            lastUpdate = now
            return
        }
        guard elapsed >= 30 else { return }
        let simulated = min(elapsed, Self.maxSimulated)
        var t = now.addingTimeInterval(-simulated)
        var remaining = simulated
        while remaining > 0 {
            let step = min(remaining, 15 * 60)
            let hours = step / 3600
            for need in Need.allCases {
                let decay = need.baseDecay(asleep: isAsleep) * (need == .energy && isAsleep ? 1 : personality.decayFactor(need))
                var value = needs[need] - decay * hours
                if decay > 0 { value = max(value, min(needs[need], Self.restingFloor)) }
                needs[need] = value
            }
            t = t.addingTimeInterval(step)
            remaining -= step

            let hour = calendar.component(.hour, from: t)
            if isAsleep {
                if !schedule.isNight(hour: hour), needs.energy >= 98 || hour == schedule.wake {
                    isAsleep = false
                }
            } else if schedule.auto, schedule.isNight(hour: hour), hour == schedule.bedtime || needs.energy < 40 {
                isAsleep = true
            }
        }
        lastUpdate = now
    }
}

struct SleepSchedule: Codable, Hashable {
    var bedtime = 22
    var wake = 7
    /// Goes to bed by itself at bedtime.
    var auto = true

    func isNight(hour: Int) -> Bool {
        bedtime > wake ? (hour >= bedtime || hour < wake) : (hour >= bedtime && hour < wake)
    }
}

/// Small deterministic random generator, so worlds and challenges are
/// reproducible from a seed and testable.
struct SplitMix: RandomNumberGenerator {
    var state: UInt64

    init(seed: UInt64) { state = seed }

    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }

    mutating func unit() -> Double { Double(next() >> 11) / Double(1 << 53) }

    mutating func int(_ range: ClosedRange<Int>) -> Int {
        range.lowerBound + Int(next() % UInt64(range.count))
    }

    static func seed(_ string: String) -> UInt64 {
        var h: UInt64 = 0xcbf29ce484222325
        for b in string.utf8 { h = (h ^ UInt64(b)) &* 0x100000001b3 }
        return h
    }
}

extension Slime {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Slime()
        name = c.value(.name, d.name)
        skin = c.value(.skin, d.skin)
        birthday = c.value(.birthday, d.birthday)
        needs = c.value(.needs, d.needs)
        xp = c.value(.xp, d.xp)
        isAsleep = c.value(.isAsleep, d.isAsleep)
        lastUpdate = c.value(.lastUpdate, d.lastUpdate)
        reaction = c.value(.reaction, d.reaction)
        reactionAt = c.value(.reactionAt, d.reactionAt)
        favorite = c.value(.favorite, d.favorite)
        favoriteGame = c.value(.favoriteGame, d.favoriteGame)
        personality = c.value(.personality, d.personality)
        bond = c.value(.bond, d.bond)
        say = c.value(.say, d.say)
        lastEvent = c.value(.lastEvent, d.lastEvent)
        eventAt = c.value(.eventAt, d.eventAt)
        recentPets = c.value(.recentPets, d.recentPets)
        equipped = c.value(.equipped, d.equipped)
        lastNudged = c.value(.lastNudged, d.lastNudged)
        lastNudgeAt = c.value(.lastNudgeAt, d.lastNudgeAt)
    }
}
