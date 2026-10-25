import Foundation

// MARK: - Parts of the save

struct Player: Codable, Hashable {
    var name = ""
    var createdAt = Date.now
    var xp = 0
    /// First-launch steps already shown.
    var tips: Set<String> = []
    /// When the app was last opened, for reunion greetings.
    var lastSeen = Date.now
    var daysActive: Set<String> = []

    var level: Int { Slime.level(forXP: xp / 2) }
}

/// Coins plus a ledger of every reward already paid out. A reward is only
/// ever paid once per id, so replays and duplicate requests can't farm coins.
struct Wallet: Codable, Hashable {
    var coins = 30
    var ledger: [String: Date] = [:]
    var earnedTotal = 0

    func hasClaimed(_ id: String) -> Bool { ledger[id] != nil }

    /// Pays a reward once. Returns false if this id was already paid.
    @discardableResult
    mutating func claim(_ id: String, coins amount: Int, at now: Date) -> Bool {
        guard ledger[id] == nil else { return false }
        ledger[id] = now
        coins += max(0, amount)
        earnedTotal += max(0, amount)
        return true
    }

    mutating func spend(_ amount: Int) -> Bool {
        guard amount >= 0, coins >= amount else { return false }
        coins -= amount
        return true
    }

    /// Keeps the ledger small. Game and daily receipts expire after 60 days;
    /// permanent ones (levels, achievements, purchases) are kept.
    mutating func prune(now: Date) {
        let cutoff = now.addingTimeInterval(-60 * 24 * 3600)
        ledger = ledger.filter { key, date in
            date > cutoff || key.hasPrefix("level:") || key.hasPrefix("achievement:") || key.hasPrefix("buy:")
        }
    }
}

struct Inventory: Codable, Hashable {
    var owned: Set<String> = []
    var foodsTasted: Set<Food> = []
    var favoriteFound = false
    var habitat: Habitat = .room
    var habitats: Set<Habitat> = [.room]
    var furniture: [FurnitureSlot: String] = [:]
}

struct Stats: Codable, Hashable {
    var feeds = 0
    var plays = 0
    var baths = 0
    var pets = 0
    var sleeps = 0
    var giftsSent = 0
    var giftsReceived = 0
    var visits = 0
    var hugs = 0
    var friends = 0
    var eventsSeen = 0
    var purchases = 0
    var goodGames = 0

    subscript(_ key: StatKey) -> Int {
        get {
            switch key {
            case .feeds: feeds
            case .plays: plays
            case .baths: baths
            case .pets: pets
            case .sleeps: sleeps
            case .giftsSent: giftsSent
            case .giftsReceived: giftsReceived
            case .visits: visits
            case .hugs: hugs
            case .friends: friends
            case .eventsSeen: eventsSeen
            case .purchases: purchases
            case .goodGames: goodGames
            }
        }
        set {
            switch key {
            case .feeds: feeds = newValue
            case .plays: plays = newValue
            case .baths: baths = newValue
            case .pets: pets = newValue
            case .sleeps: sleeps = newValue
            case .giftsSent: giftsSent = newValue
            case .giftsReceived: giftsReceived = newValue
            case .visits: visits = newValue
            case .hugs: hugs = newValue
            case .friends: friends = newValue
            case .eventsSeen: eventsSeen = newValue
            case .purchases: purchases = newValue
            case .goodGames: goodGames = newValue
            }
        }
    }
}

enum StatKey: String, Codable, CaseIterable {
    case feeds, plays, baths, pets, sleeps, giftsSent, giftsReceived, visits, hugs, friends, eventsSeen, purchases, goodGames
}

struct Progress: Codable, Hashable {
    var stats = Stats()
    var achievements: [String: Date] = [:]
    var records: [GameID: GameRecord] = [:]
    var challenges = ChallengeBoard()
    /// Today's quest from the daily plan.
    var quest: QuestState?
}

/// Things that happened, for dialogue and the memory book.
struct MemoryEntry: Codable, Hashable, Identifiable {
    enum Kind: String, Codable {
        case hatched, fed, favoriteFound, played, highScore, bath, levelUp, stageUp
        case achievement, gift, giftReceived, visit, friendMade, inLove, event, reunion
    }

    var id = UUID()
    var kind: Kind
    var at: Date
    var detail: String = ""
    /// Shown in the memory book. Only important moments are kept forever.
    var keepsake = false
}

struct Memory: Codable, Hashable {
    var entries: [MemoryEntry] = []
    var recentLines: [String] = []
    var lineUsedAt: [String: Date] = [:]

    mutating func add(_ entry: MemoryEntry) {
        entries.append(entry)
        // Keep the last 60 everyday entries plus every keepsake.
        let everyday = entries.filter { !$0.keepsake }
        if everyday.count > 60 {
            let drop = Set(everyday.prefix(everyday.count - 60).map(\.id))
            entries.removeAll { drop.contains($0.id) }
        }
    }

    func last(_ kind: MemoryEntry.Kind) -> MemoryEntry? { entries.last { $0.kind == kind } }

    var keepsakes: [MemoryEntry] { entries.filter(\.keepsake) }

    mutating func used(line id: String, at now: Date) {
        recentLines.append(id)
        if recentLines.count > 30 { recentLines.removeFirst(recentLines.count - 30) }
        lineUsedAt[id] = now
        if lineUsedAt.count > 200 {
            let oldest = lineUsedAt.sorted { $0.value < $1.value }.prefix(lineUsedAt.count - 200).map(\.key)
            for key in oldest { lineUsedAt[key] = nil }
        }
    }
}

struct NotificationPrefs: Codable, Hashable {
    var needs = true
    var daily = true
    var friends = true
    var gifts = true
    var world = false
    var quietFrom = 22
    var quietUntil = 8
    /// The player has been asked for permission (we only ask once, in context).
    var asked = false

    func isQuiet(hour: Int) -> Bool {
        quietFrom > quietUntil ? (hour >= quietFrom || hour < quietUntil) : (hour >= quietFrom && hour < quietUntil)
    }
}

struct Settings: Codable, Hashable {
    var sound = true
    var music = false
    var haptics = true
    var notifications = NotificationPrefs()
    var sleep = SleepSchedule()
    var liveEvents = true
}

// MARK: - The save

struct GameState: Codable {
    static let currentVersion = 2

    var version = GameState.currentVersion
    var hatched = false
    var slime = Slime()
    var player = Player()
    var wallet = Wallet()
    var inventory = Inventory()
    var progress = Progress()
    var memory = Memory()
    var settings = Settings()

    static func fresh() -> GameState { GameState() }

    /// Fixes anything a damaged or hand-edited save could contain.
    mutating func sanitize(now: Date) {
        slime.sanitize(now: now)
        wallet.coins = max(0, wallet.coins)
        player.xp = max(0, player.xp)
        if inventory.habitats.isEmpty { inventory.habitats = [.room] }
        if !inventory.habitats.contains(inventory.habitat) { inventory.habitat = .room }
        for (slot, id) in inventory.furniture where Catalog.item(id)?.slot != slot || !inventory.owned.contains(id) {
            inventory.furniture[slot] = nil
        }
        for id in slime.equipped.all where !inventory.owned.contains(id) {
            unequip(id)
        }
        wallet.prune(now: now)
    }
}

// MARK: - Tolerant decoding
//
// Every field falls back to its default if it is missing or damaged, so a
// save from an older or newer version still loads, and adding a field later
// needs no migration code.

extension KeyedDecodingContainer {
    func value<T: Decodable>(_ key: Key, _ fallback: @autoclosure () -> T) -> T {
        (try? decodeIfPresent(T.self, forKey: key)) ?? fallback()
    }
}

extension Player {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Player()
        name = c.value(.name, d.name)
        createdAt = c.value(.createdAt, d.createdAt)
        xp = c.value(.xp, d.xp)
        tips = c.value(.tips, d.tips)
        lastSeen = c.value(.lastSeen, d.lastSeen)
        daysActive = c.value(.daysActive, d.daysActive)
    }
}

extension Wallet {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Wallet()
        coins = c.value(.coins, d.coins)
        ledger = c.value(.ledger, d.ledger)
        earnedTotal = c.value(.earnedTotal, d.earnedTotal)
    }
}

extension Inventory {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Inventory()
        owned = c.value(.owned, d.owned)
        foodsTasted = c.value(.foodsTasted, d.foodsTasted)
        favoriteFound = c.value(.favoriteFound, d.favoriteFound)
        habitat = c.value(.habitat, d.habitat)
        habitats = c.value(.habitats, d.habitats)
        furniture = c.value(.furniture, d.furniture)
    }
}

extension Progress {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Progress()
        stats = c.value(.stats, d.stats)
        achievements = c.value(.achievements, d.achievements)
        records = c.value(.records, d.records)
        challenges = c.value(.challenges, d.challenges)
        quest = c.value(.quest, d.quest)
    }
}

extension Memory {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Memory()
        entries = c.value(.entries, d.entries)
        recentLines = c.value(.recentLines, d.recentLines)
        lineUsedAt = c.value(.lineUsedAt, d.lineUsedAt)
    }
}

extension NotificationPrefs {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = NotificationPrefs()
        needs = c.value(.needs, d.needs)
        daily = c.value(.daily, d.daily)
        friends = c.value(.friends, d.friends)
        gifts = c.value(.gifts, d.gifts)
        world = c.value(.world, d.world)
        quietFrom = c.value(.quietFrom, d.quietFrom)
        quietUntil = c.value(.quietUntil, d.quietUntil)
        asked = c.value(.asked, d.asked)
    }
}

extension Settings {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Settings()
        sound = c.value(.sound, d.sound)
        music = c.value(.music, d.music)
        haptics = c.value(.haptics, d.haptics)
        notifications = c.value(.notifications, d.notifications)
        sleep = c.value(.sleep, d.sleep)
        liveEvents = c.value(.liveEvents, d.liveEvents)
    }
}

extension GameState {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = GameState()
        version = c.value(.version, d.version)
        hatched = c.value(.hatched, d.hatched)
        slime = c.value(.slime, d.slime)
        player = c.value(.player, d.player)
        wallet = c.value(.wallet, d.wallet)
        inventory = c.value(.inventory, d.inventory)
        progress = c.value(.progress, d.progress)
        memory = c.value(.memory, d.memory)
        settings = c.value(.settings, d.settings)
    }
}
