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
        .init(id: "firstMeal", title: "First meal", detail: "Feed your slime.", coins: 10) { $0.progress.stats.feeds >= 1 },
        .init(id: "favoriteFound", title: "Favourite food", detail: "Find out its favourite food.", coins: 25) { $0.inventory.favoriteFound },
        .init(id: "gourmet", title: "Gourmet", detail: "Try every food once.", coins: 60) { $0.inventory.foodsTasted.count == Food.allCases.count },
        .init(id: "bather", title: "Water baby", detail: "Give 20 baths.", coins: 40) { $0.progress.stats.baths >= 20 },
        .init(id: "cuddler", title: "Cuddle pro", detail: "Pet it 100 times.", coins: 40) { $0.progress.stats.pets >= 100 },
        .init(id: "sleepyhead", title: "Good night", detail: "Put it to bed 10 times.", coins: 30) { $0.progress.stats.sleeps >= 10 },
        .init(id: "arcadeRookie", title: "First game", detail: "Play a round in the arcade.", coins: 10) { $0.progress.stats.plays >= 1 },
        // A finished round, not just a look at the tutorial (which also creates a record).
        .init(id: "allGames", title: "Tried them all", detail: "Play every game once.", coins: 80) { state in
            GameID.classics.allSatisfy { (state.progress.records[$0]?.plays ?? 0) > 0 }
        },
        .init(id: "arcadeFan", title: "Regular", detail: "Play 50 rounds.", coins: 60) { $0.progress.stats.plays >= 50 },
        .init(id: "arcadeStar", title: "Arcade star", detail: "Play 25 really good rounds.", coins: 100) { $0.progress.stats.goodGames >= 25 },
        .init(id: "level5", title: "Level 5", detail: "Reach level 5.", coins: 30) { $0.slime.level >= 5 },
        .init(id: "level10", title: "Level 10", detail: "Reach level 10.", coins: 60) { $0.slime.level >= 10 },
        .init(id: "grown", title: "All grown up", detail: "Become a big slime.", coins: 100) { $0.slime.stage == .grown },
        .init(id: "close", title: "Close bond", detail: "Build a close bond.", coins: 50) { $0.slime.bond >= 50 },
        .init(id: "inseparable", title: "Inseparable", detail: "The closest bond there is.", coins: 120) { $0.slime.bond >= 80 },
        .init(id: "collector", title: "Collector", detail: "Own 12 things.", coins: 80) { $0.inventory.owned.count >= 12 },
        .init(id: "decorator", title: "Decorator", detail: "Put something on every spot.", coins: 60) { $0.inventory.furniture.count == FurnitureSlot.allCases.count },
        .init(id: "traveler", title: "Globetrotter", detail: "Own three homes.", coins: 80) { $0.inventory.habitats.count >= 3 },
        .init(id: "firstFriend", title: "First friend", detail: "Connect with someone.", coins: 40) { $0.progress.stats.friends >= 1 },
        .init(id: "giftGiver", title: "Generous", detail: "Give away 10 gifts.", coins: 60) { $0.progress.stats.giftsSent >= 10 },
        .init(id: "inLove", title: "In love", detail: "Two slimes, one heart.", coins: 100) { $0.memory.last(.inLove) != nil },
        .init(id: "eventWatcher", title: "Attentive", detail: "React to 10 things on your iPhone.", coins: 30) { $0.progress.stats.eventsSeen >= 10 },
        .init(id: "week", title: "One week", detail: "Visit on 7 different days.", coins: 50) { $0.player.daysActive.count >= 7 },
        .init(id: "rich", title: "Coin mountain", detail: "Earn 1000 coins in total.", coins: 50) { $0.wallet.earnedTotal >= 1000 },
        // Daily gifts and streaks
        .init(id: "streak3", title: "Three in a row", detail: "Open a daily gift three days in a row.", coins: 20) { $0.streak.best >= 3 },
        .init(id: "streak7", title: "Full week", detail: "Keep a seven-day streak.", coins: 50) { $0.streak.best >= 7 },
        .init(id: "streak30", title: "Every single day", detail: "Keep a 30-day streak.", coins: 150) { $0.streak.best >= 30 },
        .init(id: "gifts7", title: "Gift week", detail: "Open 7 daily gifts.", coins: 30) { $0.streak.opened >= 7 },
        .init(id: "gifts14", title: "Two weeks of gifts", detail: "Open 14 daily gifts.", coins: 40) { $0.streak.opened >= 14 },
        .init(id: "gifts21", title: "Three weeks of gifts", detail: "Open 21 daily gifts.", coins: 50) { $0.streak.opened >= 21 },
        // Special days
        .init(id: "birthday", title: "Happy birthday", detail: "Celebrate your slime's first birthday.", coins: 100) { $0.memory.last(.birthday) != nil },
        .init(id: "spooky", title: "Spooky season", detail: "Visit your slime in October.", coins: 25) { $0.player.seasonsSeen.contains(Season.halloween.rawValue) },
        .init(id: "cozy", title: "Cozy season", detail: "Visit your slime in December.", coins: 25) { $0.player.seasonsSeen.contains(Season.christmas.rawValue) },
        .init(id: "newYear", title: "Happy New Year", detail: "Visit on New Year's Eve or New Year's Day.", coins: 30) { $0.player.seasonsSeen.contains(Season.newYear.rawValue) },
        .init(id: "oldFriends", title: "Old friends", detail: "Visit on 30 different days.", coins: 100) { $0.player.daysActive.count >= 30 },
        // Games
        .init(id: "stackStar", title: "Tower builder", detail: "Play a really good round of Slime Stack.", coins: 50) { $0.isGood(.slimeStack) },
        .init(id: "dashStar", title: "Jelly sprinter", detail: "Play a really good round of Jelly Dash.", coins: 50) { $0.isGood(.jellyDash) },
        .init(id: "dailyRegular", title: "Daily player", detail: "Play 10 rounds of today's game.", coins: 40) { ($0.progress.records[.daily]?.plays ?? 0) >= 10 },
        // Care, style and friends
        .init(id: "feast", title: "Feast", detail: "Feed your slime 100 times.", coins: 50) { $0.progress.stats.feeds >= 100 },
        .init(id: "dressedUp", title: "Dressed up", detail: "Wear a hat, glasses and a scarf at once.", coins: 30) {
            $0.slime.equipped.hat != nil && $0.slime.equipped.glasses != nil && $0.slime.equipped.scarf != nil
        },
        .init(id: "shopper", title: "Big spender", detail: "Buy 10 things in the shop.", coins: 40) { $0.progress.stats.purchases >= 10 },
        .init(id: "level20", title: "Level 20", detail: "Reach level 20.", coins: 120) { $0.slime.level >= 20 },
        .init(id: "popular", title: "Popular", detail: "Make 5 friends.", coins: 60) { $0.progress.stats.friends >= 5 },
        .init(id: "squishy", title: "Squishy", detail: "Hug friends 10 times.", coins: 30) { $0.progress.stats.hugs >= 10 },
    ]

    static func achievement(_ id: String) -> Achievement? { all.first { $0.id == id } }
}

extension GameState {
    /// Whether the best round on record for a game counts as really good.
    func isGood(_ game: GameID) -> Bool {
        (progress.records[game]?.best ?? 0) >= game.goodScore
    }
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
        .init(id: "d.feed3", title: "Feed 3 times", goal: .stat(.feeds, 3), coins: 15),
        .init(id: "d.pet10", title: "Pet 10 times", goal: .stat(.pets, 10), coins: 12),
        .init(id: "d.bath1", title: "Give a bath", goal: .stat(.baths, 1), coins: 10),
        .init(id: "d.play3", title: "Play 3 rounds", goal: .stat(.plays, 3), coins: 18),
        .init(id: "d.good1", title: "Play one really good round", goal: .stat(.goodGames, 1), coins: 20),
        .init(id: "d.snack", title: "Snack Catcher: 30 points", goal: .score(.snackCatcher, 30), coins: 20),
        .init(id: "d.bubble", title: "Bubble Pop: 300 points", goal: .score(.bubblePop, 300), coins: 20),
        .init(id: "d.memory", title: "Memory Garden: round 6", goal: .score(.memoryGarden, 6), coins: 20),
        .init(id: "d.color", title: "Color Match: 20 points", goal: .score(.colorMatch, 20), coins: 20),
        .init(id: "d.jump", title: "Slime Jump: height 25", goal: .score(.slimeJump, 25), coins: 20),
        .init(id: "d.stack", title: "Slime Stack: 25 points", goal: .score(.slimeStack, 25), coins: 20),
        .init(id: "d.dash", title: "Jelly Dash: 120 points", goal: .score(.jellyDash, 120), coins: 20),
    ]

    /// The daily challenges a slime of this level can take on: score goals
    /// only for games it can already open in the arcade.
    static func dailyOptions(forLevel level: Int) -> [ChallengeTemplate] {
        dailyPool.filter { template in
            if case .score(let game, _) = template.goal { return level >= game.unlockLevel }
            return true
        }
    }

    static let weeklyPool: [ChallengeTemplate] = [
        .init(id: "w.play20", title: "Play 20 rounds", goal: .stat(.plays, 20), coins: 80),
        .init(id: "w.feed20", title: "Feed 20 times", goal: .stat(.feeds, 20), coins: 60),
        .init(id: "w.good8", title: "Play 8 really good rounds", goal: .stat(.goodGames, 8), coins: 90),
        .init(id: "w.gift3", title: "Give away 3 gifts", goal: .stat(.giftsSent, 3), coins: 70),
        .init(id: "w.bath7", title: "Give 7 baths", goal: .stat(.baths, 7), coins: 60),
        .init(id: "w.events5", title: "React to 5 things on your iPhone", goal: .stat(.eventsSeen, 5), coins: 50),
    ]

    static func template(_ id: String) -> ChallengeTemplate? {
        (dailyPool + weeklyPool).first { $0.id == id }
    }

    /// "yyyy-MM-dd" on the Gregorian calendar in the calendar's time zone, so
    /// a day has the same key whatever calendar the iPhone is set to.
    static func dayKey(_ date: Date, calendar: Calendar = .current) -> String {
        let c = Calendar.gregorian(like: calendar).dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    static func weekKey(_ date: Date, calendar: Calendar = .current) -> String {
        let c = Calendar.gregorian(like: calendar).dateComponents([.yearForWeekOfYear, .weekOfYear], from: date)
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
