import Foundation

/// A feeling the slime can have. It has all of them at once, each with its
/// own intensity; the strongest one leads.
enum Emotion: String, Codable, CaseIterable, Identifiable {
    case joy, sadness, anger, fear, love, curiosity, boredom, excitement, sleepiness

    var id: String { rawValue }

    var title: String {
        switch self {
        case .joy: "Joy"
        case .sadness: "Sadness"
        case .anger: "Anger"
        case .fear: "Fear"
        case .love: "Love"
        case .curiosity: "Curiosity"
        case .boredom: "Boredom"
        case .excitement: "Excitement"
        case .sleepiness: "Sleepiness"
        }
    }

    /// How the slime is while this feeling leads: "happy", "grumpy".
    var adjective: String {
        switch self {
        case .joy: "happy"
        case .sadness: "sad"
        case .anger: "grumpy"
        case .fear: "scared"
        case .love: "loving"
        case .curiosity: "curious"
        case .boredom: "bored"
        case .excitement: "excited"
        case .sleepiness: "sleepy"
        }
    }

    /// SF Symbol for the feeling.
    var symbol: String {
        switch self {
        case .joy: "sun.max.fill"
        case .sadness: "cloud.drizzle.fill"
        case .anger: "flame.fill"
        case .fear: "exclamationmark.circle.fill"
        case .love: "heart.fill"
        case .curiosity: "magnifyingglass"
        case .boredom: "hourglass"
        case .excitement: "sparkles"
        case .sleepiness: "moon.zzz.fill"
        }
    }

    /// Feelings that fade a little when this one rises: joy pushes sadness
    /// away, excitement chases off sleepiness.
    var opposites: [Emotion] {
        switch self {
        case .joy: [.sadness, .anger, .boredom]
        case .sadness: [.joy, .excitement]
        case .anger: [.love, .joy]
        case .fear: [.joy, .curiosity]
        case .love: [.anger, .sadness, .fear]
        case .curiosity: [.boredom]
        case .boredom: [.excitement, .curiosity]
        case .excitement: [.boredom, .sleepiness, .sadness]
        case .sleepiness: [.excitement]
        }
    }
}

/// How the slime feels right now: nine feelings, each 0...1. Events add to
/// them, and over time they settle toward a baseline that comes from its
/// needs and personality (half of the way every 30 minutes).
struct Emotions: Codable, Hashable {
    private var joy = 0.3
    private var sadness = 0.0
    private var anger = 0.0
    private var fear = 0.0
    private var love = 0.0
    private var curiosity = 0.0
    private var boredom = 0.0
    private var excitement = 0.0
    private var sleepiness = 0.0

    /// Content and calm: a little joy, nothing else.
    init() {}

    subscript(_ e: Emotion) -> Double {
        get {
            switch e {
            case .joy: joy
            case .sadness: sadness
            case .anger: anger
            case .fear: fear
            case .love: love
            case .curiosity: curiosity
            case .boredom: boredom
            case .excitement: excitement
            case .sleepiness: sleepiness
            }
        }
        set {
            let v = Self.clamp(newValue)
            switch e {
            case .joy: joy = v
            case .sadness: sadness = v
            case .anger: anger = v
            case .fear: fear = v
            case .love: love = v
            case .curiosity: curiosity = v
            case .boredom: boredom = v
            case .excitement: excitement = v
            case .sleepiness: sleepiness = v
            }
        }
    }

    /// The strongest feeling. On a tie, the one listed first wins.
    var dominant: Emotion {
        var best = Emotion.joy
        for e in Emotion.allCases where self[e] > self[best] { best = e }
        return best
    }

    /// How strong the leading feeling is.
    var intensity: Double { self[dominant] }

    /// How much of a rising feeling is taken from its opposites.
    static let damping = 0.3

    /// Adds to a feeling (or takes away, with a negative amount). A feeling
    /// that rises softens its opposites a little.
    mutating func feel(_ e: Emotion, _ amount: Double) {
        guard amount.isFinite, amount != 0 else { return }
        self[e] += amount
        guard amount > 0 else { return }
        for other in e.opposites { self[other] -= amount * Self.damping }
    }

    /// Feelings for the chat API: only those that are there at all (0.05 or
    /// more), rounded to two decimals.
    var wire: [String: Double] {
        var out: [String: Double] = [:]
        for e in Emotion.allCases where self[e] >= 0.05 {
            out[e.rawValue] = (self[e] * 100).rounded() / 100
        }
        return out
    }

    /// The strongest feelings at or above `threshold`, strongest first.
    func strongest(_ count: Int = 3, atLeast threshold: Double = 0.2) -> [Emotion] {
        Emotion.allCases
            .filter { self[$0] >= threshold }
            .sorted { self[$0] > self[$1] }
            .prefix(max(0, count))
            .map { $0 }
    }

    /// A mix of two moods: `weight` 0 keeps this one, 1 takes `other`.
    func blended(with other: Emotions, weight: Double) -> Emotions {
        let w = Self.clamp(weight)
        var out = self
        for e in Emotion.allCases { out[e] = self[e] * (1 - w) + other[e] * w }
        return out
    }

    // MARK: Time

    /// Feelings fade halfway back to the baseline in this time.
    static let halfLife: TimeInterval = 30 * 60

    /// Lets time pass: every feeling moves toward `target`, half of the way
    /// per `halfLife`.
    mutating func settle(toward target: Emotions, over seconds: TimeInterval,
                         halfLife: TimeInterval = Emotions.halfLife) {
        guard seconds.isFinite, seconds > 0, halfLife > 0 else { return }
        let pull = 1 - pow(0.5, seconds / halfLife)
        for e in Emotion.allCases { self[e] += (target[e] - self[e]) * pull }
    }

    /// Where the feelings drift when nothing happens: low needs bring
    /// sadness, boredom, sleepiness and a little grumpiness; a well-cared-for
    /// slime is quietly happy; personality and closeness colour the rest.
    static func baseline(needs: Needs, personality: Personality, bond: Double, asleep: Bool) -> Emotions {
        func low(_ value: Double, under limit: Double) -> Double {
            max(0, min(1, (limit - value) / limit))
        }
        let wellbeing = max(0, min(1, (needs.average - 30) / 60))
        let closeness = max(0, min(1, (bond.isFinite ? bond : 0) / 100))
        var b = Emotions()
        b[.joy] = 0.08 + 0.32 * wellbeing
        b[.sadness] = max(0.55 * low(needs.love, under: 45), 0.4 * low(needs.lowest.value, under: 30))
        b[.anger] = 0.4 * low(needs.hunger, under: 35) * (0.6 + 0.8 * personality.foodie)
            + 0.2 * low(needs.clean, under: 15)
        b[.fear] = 0
        b[.love] = 0.05 + 0.2 * closeness + 0.1 * personality.cuddly * (needs.love >= 50 ? 1 : 0.4)
        b[.curiosity] = 0.05 + 0.2 * personality.curious
        b[.boredom] = 0.65 * low(needs.fun, under: 50) * (0.7 + 0.6 * personality.playful)
        b[.excitement] = 0.03 + 0.1 * personality.playful
        b[.sleepiness] = 0.7 * low(needs.energy, under: 50) * (0.7 + 0.6 * personality.sleepy)
        if asleep {
            b[.sleepiness] = max(b[.sleepiness], 0.6)
            b[.excitement] = 0
            b[.boredom] = 0
            b[.anger] = 0
            b[.curiosity] = 0.02
        }
        return b
    }

    static func clamp(_ value: Double) -> Double {
        value.isFinite ? min(1, max(0, value)) : 0
    }

    // MARK: Coding

    private enum CodingKeys: String, CodingKey {
        case joy, sadness, anger, fear, love, curiosity, boredom, excitement, sleepiness
    }

    /// Missing or damaged values fall back to calm, so a save from before
    /// feelings existed still loads.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Emotions()
        joy = Self.clamp(c.value(.joy, d.joy))
        sadness = Self.clamp(c.value(.sadness, d.sadness))
        anger = Self.clamp(c.value(.anger, d.anger))
        fear = Self.clamp(c.value(.fear, d.fear))
        love = Self.clamp(c.value(.love, d.love))
        curiosity = Self.clamp(c.value(.curiosity, d.curiosity))
        boredom = Self.clamp(c.value(.boredom, d.boredom))
        excitement = Self.clamp(c.value(.excitement, d.excitement))
        sleepiness = Self.clamp(c.value(.sleepiness, d.sleepiness))
    }
}

extension Personality {
    /// Some slimes don't care for baths: a little grumpy for a while, though
    /// they like being clean.
    var dislikesBaths: Bool {
        curious < 0.3 || (sleepy > 0.7 && playful < 0.4)
    }
}
