import Foundation

/// Reads and writes the save. It lives in the App Group container so the
/// widgets can read a snapshot; if the group isn't available (some sideload
/// tools strip it), it falls back to the app's own folder.
enum GameStorage {
    static let appGroup = "group.de.hyprnode.slimy"

    enum Source: String {
        case file, backup, migrated, fresh
    }

    static var sharedDirectory: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)
    }

    static var directory: URL {
        if let shared = sharedDirectory { return shared }
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        return support
    }

    static var fileURL: URL { directory.appendingPathComponent("slimy-game.json") }
    static var backupURL: URL { directory.appendingPathComponent("slimy-game.backup.json") }
    static var snapshotURL: URL { directory.appendingPathComponent("slimy-widget.json") }

    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .secondsSince1970
        return e
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .secondsSince1970
        return d
    }()

    static func load(now: Date = .now, defaults: UserDefaults = .standard) -> (GameState, Source) {
        if let state = read(fileURL) { return (prepared(state, now: now), .file) }
        if let state = read(backupURL) { return (prepared(state, now: now), .backup) }
        if let state = migrateV1(defaults: defaults, now: now) { return (prepared(state, now: now), .migrated) }
        return (GameState.fresh(), .fresh)
    }

    static func save(_ state: GameState) throws {
        let data = try encoder.encode(state)
        let fm = FileManager.default
        if fm.fileExists(atPath: fileURL.path) {
            try? fm.removeItem(at: backupURL)
            try? fm.copyItem(at: fileURL, to: backupURL)
        }
        try data.write(to: fileURL, options: [.atomic])
    }

    static func reset() {
        try? FileManager.default.removeItem(at: fileURL)
        try? FileManager.default.removeItem(at: backupURL)
        try? FileManager.default.removeItem(at: snapshotURL)
    }

    private static func read(_ url: URL) -> GameState? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(GameState.self, from: data)
    }

    private static func prepared(_ state: GameState, now: Date) -> GameState {
        var s = state
        s.version = GameState.currentVersion
        s.sanitize(now: now)
        return s
    }

    // MARK: Widget snapshot

    static func writeSnapshot(_ snapshot: WidgetSnapshot) {
        guard sharedDirectory != nil, let data = try? encoder.encode(snapshot) else { return }
        try? data.write(to: snapshotURL, options: [.atomic])
    }

    static func readSnapshot() -> WidgetSnapshot? {
        guard sharedDirectory != nil, let data = try? Data(contentsOf: snapshotURL) else { return nil }
        return try? decoder.decode(WidgetSnapshot.self, from: data)
    }

    // MARK: Version 1 (builds 1–11 kept a single slime in UserDefaults)

    struct LegacyV1: Decodable {
        struct Counters: Decodable {
            var feeds: Int?
            var plays: Int?
            var baths: Int?
            var pets: Int?
            var bestScore: Int?
        }

        var name: String?
        var skin: Skin?
        var birthday: Date?
        var needs: Needs?
        var xp: Int?
        var coins: Int?
        var isAsleep: Bool?
        var lastUpdate: Date?
        var favorite: String?
        var counters: Counters?
    }

    static func migrateV1(defaults: UserDefaults, now: Date) -> GameState? {
        guard let data = defaults.data(forKey: "slimy.state.v1"),
              let old = try? JSONDecoder().decode(LegacyV1.self, from: data) else { return nil }
        var state = GameState()
        let seed = SplitMix.seed((old.name ?? "Blob") + "\(old.birthday?.timeIntervalSince1970 ?? 0)")
        state.slime = .newborn(named: old.name ?? "Blob", skin: old.skin ?? .lime, seed: seed)
        state.slime.birthday = old.birthday ?? now
        state.slime.needs = old.needs ?? Needs()
        state.slime.xp = old.xp ?? 0
        state.slime.isAsleep = old.isAsleep ?? false
        state.slime.lastUpdate = old.lastUpdate ?? now
        if let raw = old.favorite, let food = Food(rawValue: raw) { state.slime.favorite = food }
        state.wallet.coins = old.coins ?? 30
        state.progress.stats.feeds = old.counters?.feeds ?? 0
        state.progress.stats.plays = old.counters?.plays ?? 0
        state.progress.stats.baths = old.counters?.baths ?? 0
        state.progress.stats.pets = old.counters?.pets ?? 0
        if let best = old.counters?.bestScore, best > 0 {
            state.progress.records[.snackCatcher] = GameRecord(best: best, plays: 1, lastPlayed: nil, tutorialSeen: true)
        }
        state.hatched = defaults.bool(forKey: "slimy.hatched")
        if state.hatched { state.player.tips.insert("onboarded") }
        state.memory.add(MemoryEntry(kind: .hatched, at: state.slime.birthday, detail: state.slime.name, keepsake: true))
        return state
    }
}

/// What the Home Screen and Lock Screen widgets show. Written by the app
/// whenever the save changes; widgets project needs forward from it.
struct WidgetSnapshot: Codable, Hashable {
    var name: String
    var skin: Skin
    var stage: Stage
    var level: Int
    var needs: Needs
    var isAsleep: Bool
    var line: String
    var coins: Int
    var writtenAt: Date
    var personality: Personality
    var equipped: Accessories
    var habitat: Habitat
    /// The player's bedtime, so widgets know when it sleeps. Optional:
    /// snapshots written before it existed still load.
    var sleep: SleepSchedule?

    init(_ state: GameState, at now: Date) {
        name = state.slime.name
        skin = state.slime.skin
        stage = state.slime.stage
        level = state.slime.level
        needs = state.slime.needs
        isAsleep = state.slime.isAsleep
        line = state.line(at: now)
        coins = state.wallet.coins
        writtenAt = now
        personality = state.slime.personality
        equipped = state.slime.equipped
        habitat = state.inventory.habitat
        sleep = state.settings.sleep
    }

    /// Needs as they are probably now, without the app running. Uses the
    /// saved bedtime unless `schedule` gives another.
    func projectedNeeds(at now: Date, schedule: SleepSchedule? = nil) -> Needs {
        var slime = Slime()
        slime.needs = needs
        slime.isAsleep = isAsleep
        slime.personality = personality
        slime.lastUpdate = writtenAt
        slime.advance(to: now, schedule: schedule ?? sleep ?? SleepSchedule())
        return slime.needs
    }
}
