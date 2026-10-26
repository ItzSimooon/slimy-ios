import Foundation

/// A crate to open. Wooden and golden crates are sold in the shop for coins
/// (never for real money). Mystery crates can't be bought: they come with
/// the seventh daily gift, every fifth level, now and then after a great
/// round, and with some achievements.
enum CrateKind: String, Codable, CaseIterable, Identifiable {
    case wooden, golden, mystery

    var id: String { rawValue }

    var title: String {
        switch self {
        case .wooden: "Wooden crate"
        case .golden: "Golden crate"
        case .mystery: "Mystery crate"
        }
    }

    /// Price in coins, or nil when it can't be bought.
    var price: Int? {
        switch self {
        case .wooden: 80
        case .golden: 300
        case .mystery: nil
        }
    }

    /// Item sprite.
    var sprite: String { "crate.\(rawValue)" }

    /// What's inside, in a sentence for the shop.
    var blurb: String {
        switch self {
        case .wooden: "Coins, treats and things for your slime and its home."
        case .golden: "Bigger finds, and a tiny chance of something legendary."
        case .mystery: "Can't be bought. The best chance of something legendary."
        }
    }
}

/// How rare a crate's reward is.
enum Rarity: String, Codable, CaseIterable, Comparable {
    case common, rare, epic, legendary

    var title: String {
        switch self {
        case .common: "Common"
        case .rare: "Rare"
        case .epic: "Epic"
        case .legendary: "Legendary"
        }
    }

    /// Colour for the UI, as 0xRRGGBB.
    var tint: UInt32 {
        switch self {
        case .common: 0x8E8E93
        case .rare: 0x0A84FF
        case .epic: 0xBF5AF2
        case .legendary: 0xFF9F0A
        }
    }

    private var order: Int {
        switch self {
        case .common: 0
        case .rare: 1
        case .epic: 2
        case .legendary: 3
        }
    }

    static func < (a: Rarity, b: Rarity) -> Bool { a.order < b.order }
}

/// What came out of a crate.
struct CrateReward: Codable, Equatable {
    enum Kind: Codable, Equatable {
        case coins(Int)
        /// Free servings of a food, kept in the pantry.
        case food(Food, Int)
        /// A catalog item: something to wear or furniture.
        case item(String)
        /// A colour for the slime.
        case skin(Skin)
        case crate(CrateKind)
    }

    let kind: Kind
    let rarity: Rarity
    /// "120 coins", "Cookie × 3", "Crown", "Grape colour", "Axolotl".
    let title: String
    /// Item sprite name.
    let icon: String
    /// Set when it was already yours: the coins you got instead.
    let convertedCoins: Int?

    var isDuplicate: Bool { convertedCoins != nil }

    /// The crate that comes along with the coins when the secret colour is
    /// found a second time.
    var bonusCrate: CrateKind? {
        guard convertedCoins != nil, case .skin(let skin) = kind, skin.isSecret else { return nil }
        return Crates.legendaryBonusCrate
    }

    /// What you got, in a sentence.
    var detail: String {
        if let coins = convertedCoins {
            if let bonus = bonusCrate {
                return "Already yours, so you get \(coins) coins and a \(bonus.title.lowercased()) instead."
            }
            return "Already yours, so you get \(coins) coins instead."
        }
        switch kind {
        case .coins: return "Added to your coins."
        case .food: return "Free servings in the kitchen."
        case .item(let id):
            return Catalog.item(id)?.kind == .furniture ? "New furniture for your home." : "Something new to wear."
        case .skin(let skin): return skin.isSecret ? "The secret colour." : "A new colour."
        case .crate: return "Another crate to open."
        }
    }
}

/// Where a crate came from, for the celebration.
enum CrateSource: String, Codable, Hashable {
    case shop, dailyGift, levelUp, greatRound, achievement, crate

    var reason: String {
        switch self {
        case .shop: "Bought in the shop."
        case .dailyGift: "The seventh day's gift."
        case .levelUp: "Every fifth level brings one. Open it whenever you like."
        case .greatRound: "Found after a great round. Open it whenever you like."
        case .achievement: "A reward for an achievement."
        case .crate: "Found inside another crate."
        }
    }
}

/// A crate opening as the memory book keeps it: "golden|epic|hat.crown|Crown".
struct CrateFind: Hashable {
    var crate: CrateKind
    var rarity: Rarity
    var icon: String
    var title: String

    init(crate: CrateKind, rarity: Rarity, icon: String, title: String) {
        self.crate = crate
        self.rarity = rarity
        self.icon = icon
        self.title = title
    }

    init?(detail: String) {
        let parts = detail.split(separator: "|", maxSplits: 3, omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4, let crate = CrateKind(rawValue: parts[0]),
              let rarity = Rarity(rawValue: parts[1]), !parts[3].isEmpty else { return nil }
        self.init(crate: crate, rarity: rarity, icon: parts[2].isEmpty ? "chest" : parts[2], title: parts[3])
    }

    var detail: String { "\(crate.rawValue)|\(rarity.rawValue)|\(icon)|\(title)" }
}

/// Drop rates and rewards. Rolls are made with a seeded `SplitMix`, so the
/// same seed always gives the same reward (and tests are stable).
enum Crates {
    /// Most crates of a kind, and most servings of a food, kept at once.
    static let maxStack = 99
    /// The one legendary reward. It is in no shop.
    static let legendarySkin: Skin = .axolotl
    /// Finding the secret colour again pays this, plus a golden crate.
    static let legendaryDuplicateCoins = 500
    static let legendaryBonusCrate: CrateKind = .golden
    /// Chance of a mystery crate after a round at or above the good score.
    static let greatRoundChance = 0.04

    /// The chance of each rarity. Every list adds up to 1. Wooden crates
    /// never hold anything legendary.
    static func dropRates(_ kind: CrateKind) -> [(rarity: Rarity, chance: Double)] {
        switch kind {
        case .wooden:
            return [(rarity: .common, chance: 0.72), (rarity: .rare, chance: 0.24), (rarity: .epic, chance: 0.04)]
        case .golden:
            return [(rarity: .common, chance: 0.35), (rarity: .rare, chance: 0.444), (rarity: .epic, chance: 0.2),
                    (rarity: .legendary, chance: 0.006)]
        case .mystery:
            return [(rarity: .common, chance: 0.5), (rarity: .rare, chance: 0.33), (rarity: .epic, chance: 0.15),
                    (rarity: .legendary, chance: 0.02)]
        }
    }

    /// The rarity a roll in 0..<1 lands on.
    static func rarity(_ kind: CrateKind, roll: Double) -> Rarity {
        let rates = dropRates(kind)
        var left = roll.isFinite ? roll : 0
        for row in rates {
            if left < row.chance { return row.rarity }
            left -= row.chance
        }
        // Only rounding gets here; it never makes anything rarer.
        return rates.first?.rarity ?? .common
    }

    /// Rolls a crate: the rarity, then what of that rarity. `state` decides
    /// what is already yours. Changes nothing.
    static func roll(_ kind: CrateKind, for state: GameState, using rng: inout SplitMix) -> CrateReward {
        let tier = rarity(kind, roll: rng.unit())
        return reward(kind, rarity: tier, for: state, using: &rng)
    }

    /// A reward of the given rarity from this kind of crate.
    static func reward(_ kind: CrateKind, rarity tier: Rarity, for state: GameState,
                       using rng: inout SplitMix) -> CrateReward {
        if tier == .legendary { return legendary(for: state) }
        var options = prizes(tier)
        while !options.isEmpty {
            let index = pick(options.map { $0.weight }, using: &rng)
            let prize = options.remove(at: index).prize
            if let reward = make(prize, from: kind, rarity: tier, state: state, using: &rng) { return reward }
        }
        return coins(from: kind, rarity: tier, using: &rng)
    }

    /// The secret colour, or what it turns into when the slime has it already.
    static func legendary(for state: GameState) -> CrateReward {
        let skin = legendarySkin
        let owned = state.slime.isUnlocked(skin)
        return CrateReward(kind: .skin(skin), rarity: .legendary, title: skin.title, icon: "axolotl",
                           convertedCoins: owned ? legendaryDuplicateCoins : nil)
    }

    /// Whether a finished round brings a mystery crate. Decided by the
    /// round's session, so a round can't be replayed for another try.
    static func dropsAfterRound(_ session: UUID) -> Bool {
        var rng = SplitMix(seed: SplitMix.seed("crate:\(session.uuidString)"))
        return rng.unit() < greatRoundChance
    }

    // MARK: Pools

    /// Catalog items a crate can hold at this rarity. Only things the shop
    /// sells by level: seasonal things and achievement rewards stay where
    /// they are earned.
    static func items(_ tier: Rarity) -> [CatalogItem] {
        Catalog.items.filter { item in
            guard case .level = item.unlock else { return false }
            switch tier {
            case .common: return !item.rare && item.price <= 45
            case .rare: return !item.rare && item.price > 45
            case .epic: return item.rare
            case .legendary: return false
            }
        }
    }

    /// Foods a crate can hold at this rarity for a slime of this level.
    static func foods(_ tier: Rarity, level: Int) -> [Food] {
        let pool: [Food]
        switch tier {
        case .common: pool = [.carrot, .berry, .mushroom, .cookie]
        case .rare: pool = [.soup, .honey, .pudding, .cake]
        case .epic, .legendary: pool = []
        }
        return pool.filter { $0.unlockLevel <= level }
    }

    /// Colours an epic reward can be: everything but the hatching colours
    /// and the secret one.
    static var colours: [Skin] {
        Skin.allCases.filter { !$0.isSecret && !Skin.hatchChoices.contains($0) }
    }

    static func coinRange(_ kind: CrateKind, _ tier: Rarity) -> ClosedRange<Int> {
        switch (kind, tier) {
        case (.wooden, .common): 20...40
        case (.wooden, .rare): 60...100
        case (.wooden, _): 150...220
        case (.golden, .common): 60...100
        case (.golden, .rare): 140...220
        case (.golden, _): 320...480
        case (.mystery, .common): 40...70
        case (.mystery, .rare): 100...160
        case (.mystery, _): 240...360
        }
    }

    /// Coins for something that was already yours.
    static func duplicateCoins(price: Int, rarity tier: Rarity) -> Int {
        let floor: Int
        switch tier {
        case .common: floor = 15
        case .rare: floor = 40
        case .epic: floor = 100
        case .legendary: floor = legendaryDuplicateCoins
        }
        return max(floor, price / 2)
    }

    /// Coins for a colour the slime already has.
    static let duplicateColourCoins = 150

    // MARK: Making rewards

    private enum Prize {
        case coins, food, item, colour, crate
    }

    private static func prizes(_ tier: Rarity) -> [(prize: Prize, weight: Double)] {
        switch tier {
        case .common:
            return [(prize: .coins, weight: 0.45), (prize: .food, weight: 0.25), (prize: .item, weight: 0.3)]
        case .rare:
            return [(prize: .coins, weight: 0.35), (prize: .food, weight: 0.15), (prize: .item, weight: 0.35),
                    (prize: .crate, weight: 0.15)]
        case .epic:
            return [(prize: .coins, weight: 0.25), (prize: .item, weight: 0.35), (prize: .colour, weight: 0.25),
                    (prize: .crate, weight: 0.15)]
        case .legendary:
            return []
        }
    }

    private static func make(_ prize: Prize, from kind: CrateKind, rarity tier: Rarity, state: GameState,
                             using rng: inout SplitMix) -> CrateReward? {
        switch prize {
        case .coins:
            return coins(from: kind, rarity: tier, using: &rng)
        case .food:
            let pool = foods(tier, level: state.slime.level)
            guard !pool.isEmpty else { return nil }
            let food = pool[Int(rng.next() % UInt64(pool.count))]
            let count = tier == .common ? 3 : 2
            return CrateReward(kind: .food(food, count), rarity: tier, title: "\(food.title) × \(count)",
                               icon: food.sprite, convertedCoins: nil)
        case .item:
            let pool = items(tier)
            guard !pool.isEmpty else { return nil }
            let item = pool[Int(rng.next() % UInt64(pool.count))]
            let owned = state.owns(item.id)
            return CrateReward(kind: .item(item.id), rarity: tier, title: item.title, icon: item.sprite,
                               convertedCoins: owned ? duplicateCoins(price: item.price, rarity: tier) : nil)
        case .colour:
            let pool = colours
            guard !pool.isEmpty else { return nil }
            let skin = pool[Int(rng.next() % UInt64(pool.count))]
            let owned = state.slime.isUnlocked(skin)
            return CrateReward(kind: .skin(skin), rarity: tier, title: "\(skin.title) colour", icon: "gem",
                               convertedCoins: owned ? duplicateColourCoins : nil)
        case .crate:
            let inside: CrateKind = tier == .epic ? .golden : .wooden
            guard state.crateCount(inside) < maxStack else { return nil }
            return CrateReward(kind: .crate(inside), rarity: tier, title: inside.title, icon: inside.sprite,
                               convertedCoins: nil)
        }
    }

    private static func coins(from kind: CrateKind, rarity tier: Rarity, using rng: inout SplitMix) -> CrateReward {
        let amount = rng.int(coinRange(kind, tier))
        return CrateReward(kind: .coins(amount), rarity: tier, title: "\(amount) coins", icon: "coin",
                           convertedCoins: nil)
    }

    /// Index of a weighted choice.
    private static func pick(_ weights: [Double], using rng: inout SplitMix) -> Int {
        let total = weights.reduce(0, +)
        guard total > 0 else { return 0 }
        var roll = rng.unit() * total
        for (i, weight) in weights.enumerated() {
            roll -= weight
            if roll < 0 { return i }
        }
        return weights.count - 1
    }
}

// MARK: - Rules

extension GameState {
    /// Crates of a kind waiting to be opened.
    func crateCount(_ kind: CrateKind) -> Int { max(0, crates[kind] ?? 0) }

    /// Crates of every kind waiting to be opened.
    var cratesWaiting: Int { CrateKind.allCases.reduce(0) { $0 + crateCount($1) } }

    /// Adds crates, up to `Crates.maxStack` of a kind. Returns whether there
    /// was room.
    @discardableResult
    mutating func addCrate(_ kind: CrateKind, _ count: Int = 1) -> Bool {
        let have = crateCount(kind)
        guard count > 0, have < Crates.maxStack else { return false }
        crates[kind] = min(Crates.maxStack, have + count)
        return true
    }

    /// Buys a crate for coins. False if it isn't for sale, there isn't
    /// room, or the coins aren't enough; then nothing changes.
    mutating func buyCrate(_ kind: CrateKind, now: Date) -> Bool {
        guard let price = kind.price, crateCount(kind) < Crates.maxStack, wallet.spend(price) else { return false }
        addCrate(kind)
        progress.stats.purchases += 1
        checkAchievements(now: now)
        return true
    }

    /// Opens one crate of a kind and hands out what was in it. Nil if there
    /// is none. The same seed always gives the same reward.
    mutating func openCrate(_ kind: CrateKind, now: Date, seed: UInt64) -> (reward: CrateReward, outcome: Outcome)? {
        guard crateCount(kind) > 0 else { return nil }
        tick(now: now)
        let left = crateCount(kind) - 1
        crates[kind] = left > 0 ? left : nil
        var rng = SplitMix(seed: seed)
        let reward = Crates.roll(kind, for: self, using: &rng)
        receive(reward)
        progress.stats.cratesOpened += 1
        let find = CrateFind(crate: kind, rarity: reward.rarity, icon: reward.icon, title: reward.title)
        memory.add(MemoryEntry(kind: .crate, at: now, detail: find.detail, keepsake: reward.rarity >= .epic))

        switch reward.rarity {
        case .common:
            slime.feel(.excitement, 0.3)
            slime.feel(.joy, 0.1)
        case .rare:
            slime.feel(.excitement, 0.4)
            slime.feel(.joy, 0.15)
        case .epic:
            slime.feel(.excitement, 0.55)
            slime.feel(.joy, 0.25)
        case .legendary:
            slime.feel(.excitement, 1)
            slime.feel(.joy, 0.8)
            slime.feel(.love, 0.3)
        }

        var line = ""
        if !slime.isAsleep {
            let category: LineCategory
            if reward.rarity == .legendary && !reward.isDuplicate {
                category = .crateLegendary
            } else if reward.isDuplicate {
                category = .crateDuplicate
            } else {
                category = .crateOpened
            }
            line = Dialogue.say(category, in: &self, context: LineContext(item: reward.title.lowercased()), at: now)
            slime.react(reward.rarity >= .epic ? .celebrate : .curious, at: now)
            slime.say = Utterance(text: line, at: now)
        }
        let events = checkAchievements(now: now)
        return (reward, Outcome(ok: true, line: line, events: events))
    }

    /// Hands out a crate's reward.
    mutating func receive(_ reward: CrateReward) {
        if let coins = reward.convertedCoins {
            wallet.earn(coins)
            if let bonus = reward.bonusCrate { addCrate(bonus) }
            return
        }
        switch reward.kind {
        case .coins(let amount):
            wallet.earn(amount)
        case .food(let food, let count):
            inventory.pantry[food] = min(Crates.maxStack, servings(of: food) + max(0, count))
        case .item(let id):
            inventory.owned.insert(id)
        case .skin(let skin):
            slime.colours.insert(skin)
            // Found while caring together: it comes home with your own slime.
            if care != nil { careBook.coloursFound.insert(skin) }
            // The legendary find changes the slime right away.
            if skin.isSecret { slime.skin = skin }
        case .crate(let kind):
            addCrate(kind)
        }
    }

    /// Free servings of a food in the pantry.
    func servings(of food: Food) -> Int { max(0, inventory.pantry[food] ?? 0) }
}
