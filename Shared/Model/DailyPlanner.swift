import Foundation

/// Makes the day's plan on the phone: game blocks picked by a generator
/// seeded with the date. Everyone gets the same game on the same day, and no
/// two days in a row feel alike.
enum DailyPlanner {
    static func dayKey(_ date: Date, calendar: Calendar = .current) -> String {
        Challenges.dayKey(date, calendar: calendar)
    }

    static func plan(for day: String) -> DailyPlan {
        var rng = SplitMix(seed: SplitMix.seed("slimy.daily." + day))
        // Rotate controls so neighbouring days differ; the seed picks the rest.
        let controls = GameControl.allCases
        let dayNumber = Int(SplitMix.seed(day) % 9973)
        let control = controls[(dayNumber + rng.int(0...1)) % controls.count]
        let theme = GameTheme.allCases[rng.int(0...GameTheme.allCases.count - 1)]
        let goal: GameGoal = rng.unit() < 0.65 ? .timed : .survive

        let pack = packs[rng.int(0...packs.count - 1)]
        var entities: [EntitySpec] = []
        let goodCount = rng.int(1...2)
        for i in 0..<goodCount {
            let sprite = pack.good[(i + rng.int(0...pack.good.count - 1)) % pack.good.count]
            entities.append(EntitySpec(
                sprite: sprite, role: .collect, power: nil,
                motion: pick(&rng, control == .lanes ? [.straight] : [.straight, .straight, .wave, .zigzag, .bounce]),
                speed: 0.8 + rng.unit() * 0.5, rate: i == 0 ? 0.9 + rng.unit() * 0.5 : 0.35 + rng.unit() * 0.3,
                points: i == 0 ? rng.int(1...3) : rng.int(4...8), size: i == 0 ? 1 : 0.85))
        }
        let badCount = rng.int(1...2)
        for i in 0..<badCount {
            let sprite = pack.bad[(i + rng.int(0...pack.bad.count - 1)) % pack.bad.count]
            let motions: [EntityMotion] = control == .lanes ? [.straight, .straight, .homing]
                : [.straight, .zigzag, .homing, .wave, .bounce]
            entities.append(EntitySpec(
                sprite: sprite, role: .avoid, power: nil, motion: pick(&rng, motions),
                speed: 0.9 + rng.unit() * 0.6, rate: 0.45 + rng.unit() * 0.4,
                points: rng.int(2...5), size: 0.9 + rng.unit() * 0.4))
        }
        let power = pick(&rng, control == .jump || control == .flap
                         ? [.shield, .double, .slow, .life] : PowerKind.allCases)
        entities.append(EntitySpec(sprite: powerSprite(power), role: .power, power: power, motion: .wave,
                                   speed: 0.8, rate: 0.12, points: 0, size: 1))

        let title = "\(pick(&rng, theme.adjectives)) \(pick(&rng, control.nouns))"
        var game = GameSpec(
            id: day, title: title, blurb: "", tutorial: [], control: control, goal: goal,
            duration: goal == .timed ? Double(rng.int(4...8) * 10) : 60,
            lives: goal == .survive ? 3 : (rng.unit() < 0.5 ? 3 : 0),
            theme: theme, entities: entities, ramp: 0.08 + rng.unit() * 0.17)
        game.blurb = blurb(for: game, pack: pack)
        game = game.sanitized()
        game.tutorial = tutorial(for: game)

        let quest = makeQuest(&rng, game: game)
        let story = pick(&rng, stories).replacingOccurrences(of: "{game}", with: game.title)
        return DailyPlan(day: day, game: game, quest: quest, story: story, source: "local").sanitized()
    }

    // MARK: Words and sets

    private struct Pack {
        let good: [String]
        let bad: [String]
        let flavor: String
    }

    private static let packs: [Pack] = [
        Pack(good: ["apple", "berry", "carrot", "cookie"], bad: ["rotten", "cactus"], flavor: "snacks"),
        Pack(good: ["coin", "gem"], bad: ["rock", "cactus"], flavor: "treasure"),
        Pack(good: ["bubbleItem", "starJelly"], bad: ["rock", "puddle"], flavor: "bubbles"),
        Pack(good: ["flower", "seed"], bad: ["mushroom", "rock"], flavor: "garden things"),
        Pack(good: ["noteItem", "heart"], bad: ["rock", "rotten"], flavor: "music"),
        Pack(good: ["cake", "pudding", "honey"], bad: ["cactus", "rotten"], flavor: "sweets"),
        Pack(good: ["chest", "coin"], bad: ["puddle", "rock"], flavor: "loot"),
        Pack(good: ["ball", "starJelly"], bad: ["cactus", "mushroom"], flavor: "toys"),
    ]

    private static func powerSprite(_ p: PowerKind) -> String {
        switch p {
        case .shield: "bubbleItem"
        case .magnet: "gem"
        case .slow: "starJelly"
        case .double: "coin"
        case .life: "heart"
        case .shrink: "mushroom"
        }
    }

    private static let stories = [
        "i dreamed about {game} all night. let's play it.",
        "today smells like adventure. and a little like snacks.",
        "i counted the clouds this morning. there were many.",
        "new day, new game. i practiced my wobble for it.",
        "i feel lucky today. maybe we beat yesterday's score?",
        "a bird looked at me. i looked back. we're friends now.",
        "i made up a game called {game}. you'll like it.",
        "i'm full of bounce today. careful.",
        "the island is cozy today. i rearranged my pixels.",
        "i found a shiny thing. then i lost it. now we find more.",
    ]

    private static func pick<T>(_ rng: inout SplitMix, _ list: [T]) -> T {
        list[rng.int(0...list.count - 1)]
    }

    private static func makeQuest(_ rng: inout SplitMix, game: GameSpec) -> DailyQuest {
        switch rng.int(0...4) {
        case 0, 1:
            let target = max(5, Int(Double(game.goodScore) * 0.8 / 5) * 5)
            return DailyQuest(title: "Score \(target) in \(game.title)", goal: .dailyScore(target), coins: 25)
        case 2:
            let n = rng.int(2...4)
            return DailyQuest(title: "Feed your slime \(n) times", goal: .stat(.feeds, n), coins: 15)
        case 3:
            let n = rng.int(6...10)
            return DailyQuest(title: "Pet your slime \(n) times", goal: .stat(.pets, n), coins: 15)
        default:
            let n = rng.int(2...4)
            return DailyQuest(title: "Play \(n) rounds in the arcade", goal: .stat(.plays, n), coins: 20)
        }
    }

    private static func blurb(for game: GameSpec, pack: Pack) -> String {
        switch game.control {
        case .drag: "\(pack.flavor.capitalizedFirst) rain down. Catch the good stuff."
        case .lanes: "Three lanes, lots of \(pack.flavor). Switch fast."
        case .steer: "Float around, grab \(pack.flavor), dodge the rest."
        case .jump: "Run, jump and collect \(pack.flavor) on the way."
        case .flap: "Flap through the sky and collect \(pack.flavor)."
        }
    }

    /// Plain rules from the blocks, so every game explains itself.
    static func tutorial(for game: GameSpec) -> [String] {
        var lines: [String] = []
        switch game.control {
        case .drag: lines.append("Drag left and right to move.")
        case .lanes: lines.append("Tap left or right to switch lanes.")
        case .steer: lines.append("Touch and drag; the slime floats after your finger.")
        case .jump: lines.append("Tap to jump. Tap again in the air for a double jump.")
        case .flap: lines.append("Tap to flap up. Don't touch the ground.")
        }
        let good = game.entities.filter { $0.role == .collect }.map { spriteName($0.sprite) }
        let bad = game.entities.filter { $0.role == .avoid }.map { spriteName($0.sprite) }
        var collect = "Collect \(list(good))"
        if !bad.isEmpty { collect += ", avoid \(list(bad))" }
        lines.append(collect + ".")
        if let power = game.entities.first(where: { $0.role == .power })?.power {
            lines.append(game.goal == .survive
                         ? "Grab the glowing one for \(power.title.lowercased()). Last as long as you can."
                         : "Grab the glowing one for \(power.title.lowercased()).")
        } else if game.goal == .survive {
            lines.append("Last as long as you can.")
        }
        return lines
    }

    static func spriteName(_ sprite: String) -> String {
        switch sprite {
        case "bubbleItem": "bubbles"
        case "noteItem": "notes"
        case "starJelly": "star jellies"
        case "rotten": "rotten food"
        case "cactus": "cacti"
        case "berry": "strawberries"
        case "chest": "chests"
        case "puddle": "puddles"
        case "honey": "honey"
        case "pudding": "pudding"
        case "soup": "soup"
        default: sprite + "s"
        }
    }

    private static func list(_ items: [String]) -> String {
        switch items.count {
        case 0: return "everything"
        case 1: return items[0]
        default: return items.dropLast().joined(separator: ", ") + " and " + items.last!
        }
    }
}

private extension GameTheme {
    var adjectives: [String] {
        switch self {
        case .dawn: ["Dawn", "Early", "Peachy"]
        case .day: ["Sunny", "Breezy", "Bright"]
        case .dusk: ["Dusk", "Golden", "Twilight"]
        case .night: ["Moonlight", "Midnight", "Sleepy"]
        case .ocean: ["Deep-Sea", "Splashy", "Coral"]
        case .forest: ["Mossy", "Leafy", "Forest"]
        case .candy: ["Candy", "Sugar", "Bubblegum"]
        case .lava: ["Lava", "Toasty", "Ember"]
        case .space: ["Cosmic", "Starry", "Orbit"]
        case .snow: ["Frosty", "Snowy", "Chilly"]
        }
    }
}

private extension GameControl {
    var nouns: [String] {
        switch self {
        case .drag: ["Munch", "Catch", "Snack Rain"]
        case .lanes: ["Rush", "Dash", "Lane Run"]
        case .steer: ["Drift", "Dodge", "Float"]
        case .jump: ["Hop", "Run", "Bounce"]
        case .flap: ["Flight", "Flutter", "Glide"]
        }
    }
}
