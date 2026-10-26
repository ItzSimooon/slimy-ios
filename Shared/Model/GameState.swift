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
    /// Seasons it has been visited in (Season raw values), for achievements.
    var seasonsSeen: Set<String> = []

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

    /// Adds coins that need no receipt, because what paid them can't happen
    /// twice (a crate is gone once it's opened).
    mutating func earn(_ amount: Int) {
        coins += max(0, amount)
        earnedTotal += max(0, amount)
    }

    /// Receipts that are kept forever: they can never be paid twice.
    static let permanentPrefixes = ["level:", "achievement:", "buy:", "birthday:", "holiday:"]

    /// Keeps the ledger small. Game and daily receipts expire after 60 days;
    /// permanent ones (levels, achievements, purchases, yearly gifts) are kept.
    mutating func prune(now: Date) {
        let cutoff = now.addingTimeInterval(-60 * 24 * 3600)
        ledger = ledger.filter { key, date in
            date > cutoff || Self.permanentPrefixes.contains { key.hasPrefix($0) }
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
    /// Free servings from crates. Feeding uses one before paying coins.
    var pantry: [Food: Int] = [:]
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
    /// Crates opened. Not a challenge goal, so not a `StatKey`.
    var cratesOpened = 0
    /// Care actions your Care Together partner did for the shared slime.
    var partnerCare = 0

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
    /// Entries of kinds this version doesn't know (like the old "inLove")
    /// are left out when a save loads.
    enum Kind: String, Codable {
        case hatched, fed, favoriteFound, played, highScore, bath, levelUp, stageUp
        case achievement, gift, giftReceived, visit, friendMade, event, reunion
        case birthday, streak, holiday
        /// A crate was opened. Detail: see `CrateFind`.
        case crate
        /// The Care Together partner did something. Detail: see `PartnerCareNote`.
        case partnerCare
        /// Caring together started or ended. Detail: the partner's name.
        case careStarted, careEnded
    }

    var id = UUID()
    var kind: Kind
    var at: Date
    var detail: String = ""
    /// Shown in the memory book. Only important moments are kept forever.
    var keepsake = false
}

extension MemoryEntry {
    /// How the memory book tells this moment.
    var summary: String {
        switch kind {
        case .hatched: return "\(detail) hatched"
        case .fed: return "Ate \(Food(rawValue: detail)?.title.lowercased() ?? "something")"
        case .favoriteFound: return "Found its favourite food: \(Food(rawValue: detail)?.title ?? detail)"
        case .played: return "Played \(Self.game(detail)?.title ?? "a game")"
        case .highScore: return "Record in \(Self.game(detail)?.title ?? "a game"): \(detail.split(separator: ":").last ?? "")"
        case .bath: return "Had a bath"
        case .levelUp: return "Reached level \(detail)"
        case .stageUp: return "Grew up: \(Stage(rawValue: detail)?.title ?? detail)"
        case .achievement: return "Achievement: \(Achievements.achievement(detail)?.title ?? detail)"
        case .gift: return "Sent a gift to \(detail)"
        case .giftReceived: return keepsake ? "First gift from \(detail)" : "Gift from \(detail)"
        case .visit: return "Visited \(detail)"
        case .friendMade: return "New friend: \(detail)"
        case .event: return PhoneEvent(rawValue: detail)?.title ?? "Something happened"
        case .reunion: return "Back together after \(detail) days"
        case .birthday: return detail == "1" ? "First birthday" : "Birthday: \(detail) years old"
        case .streak: return "\(detail) days in a row"
        case .holiday: return Holiday(rawValue: detail)?.greeting ?? "A holiday together"
        case .crate:
            guard let find = CrateFind(detail: detail) else { return "Opened a crate" }
            return find.rarity == .legendary ? "Legendary find: \(find.title)" : "\(find.crate.title): \(find.title)"
        case .partnerCare:
            return PartnerCareNote(detail: detail)?.summary ?? "Cared for together"
        case .careStarted: return detail.isEmpty ? "Started caring together" : "Started caring together with \(detail)"
        case .careEnded: return detail.isEmpty ? "Stopped caring together" : "Stopped caring together with \(detail)"
        }
    }

    /// Item sprite for the memory book.
    var icon: String {
        switch kind {
        case .hatched: return "starJelly"
        case .fed, .favoriteFound: return Food(rawValue: detail)?.sprite ?? "apple"
        case .played: return Self.game(detail)?.icon ?? "ball"
        case .highScore, .achievement: return "furn.trophy"
        case .bath: return "bubbleItem"
        case .levelUp, .stageUp: return "coin"
        case .gift, .giftReceived: return "chest"
        case .visit, .friendMade: return "heart"
        case .event: return "speech"
        case .reunion: return "flower"
        case .birthday: return "cake"
        case .streak: return "flag"
        case .holiday: return Holiday(rawValue: detail)?.icon ?? "starJelly"
        case .crate: return CrateFind(detail: detail)?.icon ?? "chest"
        case .partnerCare: return PartnerCareNote(detail: detail)?.kind.icon ?? "heart"
        case .careStarted, .careEnded: return "heart"
        }
    }

    private static func game(_ detail: String) -> GameID? {
        GameID(rawValue: String(detail.split(separator: ":").first ?? ""))
    }
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
    /// The slime talks to itself every few seconds while the app is open.
    var chatty = true
}

// MARK: - The save

struct GameState: Codable {
    /// 3: daily gift streak, seasons seen, birthdays and holidays.
    /// 4: feelings, crates, colours from crates, the pantry, Care Together.
    static let currentVersion = 4

    var version = GameState.currentVersion
    var hatched = false
    var slime = Slime()
    var player = Player()
    var wallet = Wallet()
    var inventory = Inventory()
    var progress = Progress()
    var memory = Memory()
    var settings = Settings()
    var streak = StreakState()
    /// Crates waiting to be opened.
    var crates: [CrateKind: Int] = [:]
    /// Caring for the slime together with someone, if you are.
    var care: CareLink?
    /// This phone's side of caring together: actions waiting to be sent,
    /// partner actions already applied.
    var careBook = CareBook()

    static func fresh() -> GameState { GameState() }

    /// Fixes anything a damaged or hand-edited save could contain.
    mutating func sanitize(now: Date) {
        slime.sanitize(now: now)
        wallet.coins = max(0, wallet.coins)
        player.xp = max(0, player.xp)
        streak.count = max(0, streak.count)
        streak.best = max(streak.best, streak.count)
        streak.opened = max(0, streak.opened)
        if inventory.habitats.isEmpty { inventory.habitats = [.room] }
        if !inventory.habitats.contains(inventory.habitat) { inventory.habitat = .room }
        for (slot, id) in inventory.furniture where Catalog.item(id)?.slot != slot || !inventory.owned.contains(id) {
            inventory.furniture[slot] = nil
        }
        if care == nil {
            for id in slime.equipped.all where !inventory.owned.contains(id) {
                unequip(id)
            }
        } else {
            // A shared slime may wear what the other person owns.
            for id in slime.equipped.all where Catalog.item(id) == nil {
                unequip(id)
            }
        }
        inventory.pantry = inventory.pantry.filter { $0.value > 0 }.mapValues { min($0, Crates.maxStack) }
        crates = crates.filter { $0.value > 0 }.mapValues { min($0, Crates.maxStack) }
        if var link = care {
            link.ownBackup?.sanitize(now: now)
            care = link
        }
        careBook.sanitize(now: now)
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
        seasonsSeen = c.value(.seasonsSeen, d.seasonsSeen)
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
        pantry = c.value(.pantry, d.pantry)
    }
}

extension Stats {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Stats()
        feeds = c.value(.feeds, d.feeds)
        plays = c.value(.plays, d.plays)
        baths = c.value(.baths, d.baths)
        pets = c.value(.pets, d.pets)
        sleeps = c.value(.sleeps, d.sleeps)
        giftsSent = c.value(.giftsSent, d.giftsSent)
        giftsReceived = c.value(.giftsReceived, d.giftsReceived)
        visits = c.value(.visits, d.visits)
        hugs = c.value(.hugs, d.hugs)
        friends = c.value(.friends, d.friends)
        eventsSeen = c.value(.eventsSeen, d.eventsSeen)
        purchases = c.value(.purchases, d.purchases)
        goodGames = c.value(.goodGames, d.goodGames)
        cratesOpened = c.value(.cratesOpened, d.cratesOpened)
        partnerCare = c.value(.partnerCare, d.partnerCare)
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

/// Decodes one element of a list, or nothing if it is damaged, so a single
/// bad entry can't wipe the whole list.
private struct Lossy<T: Decodable>: Decodable {
    let value: T?

    init(from decoder: Decoder) {
        value = try? T(from: decoder)
    }
}

extension Memory {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = Memory()
        let lossy: [Lossy<MemoryEntry>]? = c.value(.entries, nil)
        entries = lossy?.compactMap(\.value) ?? d.entries
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
        chatty = c.value(.chatty, d.chatty)
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
        streak = c.value(.streak, d.streak)
        crates = c.value(.crates, d.crates)
        care = c.value(.care, d.care)
        careBook = c.value(.careBook, d.careBook)
    }
}
