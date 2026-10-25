import Foundation

// MARK: - Achievements

struct Achievement: Identifiable {
    let id: String
    let title: String
    let detail: String
    let coins: Int
    let check: (GameState) -> Bool
}

enum Achievements {
    static let all: [Achievement] = [
        .init(id: "firstMeal", title: "Erste Mahlzeit", detail: "Füttere deinen Slime.", coins: 10) { $0.progress.stats.feeds >= 1 },
        .init(id: "favoriteFound", title: "Lieblingsessen", detail: "Find sein Lieblingsessen heraus.", coins: 25) { $0.inventory.favoriteFound },
        .init(id: "gourmet", title: "Feinschmecker", detail: "Probier jedes Essen einmal.", coins: 60) { $0.inventory.foodsTasted.count == Food.allCases.count },
        .init(id: "bather", title: "Wasserratte", detail: "Bade 20-mal.", coins: 40) { $0.progress.stats.baths >= 20 },
        .init(id: "cuddler", title: "Kuschelprofi", detail: "Kraul 100-mal.", coins: 40) { $0.progress.stats.pets >= 100 },
        .init(id: "sleepyhead", title: "Gute Nacht", detail: "Bring ihn 10-mal ins Bett.", coins: 30) { $0.progress.stats.sleeps >= 10 },
        .init(id: "arcadeRookie", title: "Erstes Spiel", detail: "Spiel eine Runde in der Spielhalle.", coins: 10) { $0.progress.stats.plays >= 1 },
        .init(id: "allGames", title: "Alles ausprobiert", detail: "Spiel jedes Spiel einmal.", coins: 80) { $0.progress.records.count == GameID.allCases.count },
        .init(id: "arcadeFan", title: "Stammgast", detail: "Spiel 50 Runden.", coins: 60) { $0.progress.stats.plays >= 50 },
        .init(id: "arcadeStar", title: "Spielhallen-Star", detail: "Schaff 25 richtig gute Runden.", coins: 100) { $0.progress.stats.goodGames >= 25 },
        .init(id: "level5", title: "Level 5", detail: "Erreich Level 5.", coins: 30) { $0.slime.level >= 5 },
        .init(id: "level10", title: "Level 10", detail: "Erreich Level 10.", coins: 60) { $0.slime.level >= 10 },
        .init(id: "grown", title: "Ausgewachsen", detail: "Werd ein großer Slime.", coins: 100) { $0.slime.stage == .grown },
        .init(id: "close", title: "Eng verbunden", detail: "Bau eine enge Bindung auf.", coins: 50) { $0.slime.bond >= 50 },
        .init(id: "inseparable", title: "Unzertrennlich", detail: "Die engste Bindung.", coins: 120) { $0.slime.bond >= 80 },
        .init(id: "collector", title: "Sammler", detail: "Besitz 12 Dinge.", coins: 80) { $0.inventory.owned.count >= 12 },
        .init(id: "decorator", title: "Einrichter", detail: "Stell auf jeden Platz etwas.", coins: 60) { $0.inventory.furniture.count == FurnitureSlot.allCases.count },
        .init(id: "traveler", title: "Weltenbummler", detail: "Besitz drei Lebensräume.", coins: 80) { $0.inventory.habitats.count >= 3 },
        .init(id: "firstFriend", title: "Erster Freund", detail: "Verbinde dich mit jemandem.", coins: 40) { $0.progress.stats.friends >= 1 },
        .init(id: "giftGiver", title: "Großzügig", detail: "Verschenk 10 Dinge.", coins: 60) { $0.progress.stats.giftsSent >= 10 },
        .init(id: "inLove", title: "Verliebt", detail: "Zwei Slimes, ein Herz.", coins: 100) { $0.memory.last(.inLove) != nil },
        .init(id: "eventWatcher", title: "Aufmerksam", detail: "Reagier auf 10 Dinge am iPhone.", coins: 30) { $0.progress.stats.eventsSeen >= 10 },
        .init(id: "week", title: "Eine Woche", detail: "Schau an 7 Tagen vorbei.", coins: 50) { $0.player.daysActive.count >= 7 },
        .init(id: "rich", title: "Münzberg", detail: "Verdien insgesamt 1000 Münzen.", coins: 50) { $0.wallet.earnedTotal >= 1000 },
    ]

    static func achievement(_ id: String) -> Achievement? { all.first { $0.id == id } }
}

// MARK: - Challenges

struct ChallengeTemplate: Identifiable {
    enum Goal {
        case stat(StatKey, Int)
        case score(GameID, Int)
    }

    let id: String
    let title: String
    let goal: Goal
    let coins: Int
}

struct ChallengeState: Codable, Hashable, Identifiable {
    var id: String
    var baseline = 0
    var best = 0
    var claimed = false
}

struct ChallengeBoard: Codable, Hashable {
    var dayKey = ""
    var weekKey = ""
    var daily: [ChallengeState] = []
    var weekly: [ChallengeState] = []
}

enum Challenges {
    static let dailyPool: [ChallengeTemplate] = [
        .init(id: "d.feed3", title: "Füttere 3-mal", goal: .stat(.feeds, 3), coins: 15),
        .init(id: "d.pet10", title: "Kraul 10-mal", goal: .stat(.pets, 10), coins: 12),
        .init(id: "d.bath1", title: "Gib ein Bad", goal: .stat(.baths, 1), coins: 10),
        .init(id: "d.play3", title: "Spiel 3 Runden", goal: .stat(.plays, 3), coins: 18),
        .init(id: "d.good1", title: "Schaff eine richtig gute Runde", goal: .stat(.goodGames, 1), coins: 20),
        .init(id: "d.snack", title: "Snack-Fänger: 30 Punkte", goal: .score(.snackCatcher, 30), coins: 20),
        .init(id: "d.bubble", title: "Blasen-Platzer: 300 Punkte", goal: .score(.bubblePop, 300), coins: 20),
        .init(id: "d.memory", title: "Gedächtnisgarten: Runde 6", goal: .score(.memoryGarden, 6), coins: 20),
        .init(id: "d.color", title: "Farbenspiel: 20 Punkte", goal: .score(.colorMatch, 20), coins: 20),
        .init(id: "d.jump", title: "Slime-Sprung: Höhe 25", goal: .score(.slimeJump, 25), coins: 20),
    ]

    static let weeklyPool: [ChallengeTemplate] = [
        .init(id: "w.play20", title: "Spiel 20 Runden", goal: .stat(.plays, 20), coins: 80),
        .init(id: "w.feed20", title: "Füttere 20-mal", goal: .stat(.feeds, 20), coins: 60),
        .init(id: "w.good8", title: "Schaff 8 richtig gute Runden", goal: .stat(.goodGames, 8), coins: 90),
        .init(id: "w.gift3", title: "Verschenk 3 Dinge", goal: .stat(.giftsSent, 3), coins: 70),
        .init(id: "w.bath7", title: "Bade 7-mal", goal: .stat(.baths, 7), coins: 60),
        .init(id: "w.events5", title: "Reagier auf 5 Dinge am iPhone", goal: .stat(.eventsSeen, 5), coins: 50),
    ]

    static func template(_ id: String) -> ChallengeTemplate? {
        (dailyPool + weeklyPool).first { $0.id == id }
    }

    static func dayKey(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    static func weekKey(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.yearForWeekOfYear, .weekOfYear], from: date)
        return String(format: "%04d-W%02d", c.yearForWeekOfYear ?? 0, c.weekOfYear ?? 0)
    }

    /// The same picks for everyone on the same day; reproducible in tests.
    static func pick(_ pool: [ChallengeTemplate], count: Int, key: String) -> [ChallengeTemplate] {
        var rng = SplitMix(seed: SplitMix.seed(key))
        var options = pool
        var out: [ChallengeTemplate] = []
        while out.count < count, !options.isEmpty {
            out.append(options.remove(at: Int(rng.next() % UInt64(options.count))))
        }
        return out
    }

    static func progress(_ challenge: ChallengeState, in state: GameState) -> (value: Int, target: Int) {
        guard let template = template(challenge.id) else { return (0, 1) }
        switch template.goal {
        case .stat(let key, let target):
            return (min(target, max(0, state.progress.stats[key] - challenge.baseline)), target)
        case .score(_, let target):
            return (min(target, challenge.best), target)
        }
    }

    static func isDone(_ challenge: ChallengeState, in state: GameState) -> Bool {
        let p = progress(challenge, in: state)
        return p.value >= p.target
    }
}
