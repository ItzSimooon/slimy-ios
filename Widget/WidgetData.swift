import SwiftUI
import WidgetKit

/// What the Home Screen and Lock Screen widgets show at one moment. Built
/// from the snapshot the app writes into the shared App Group container,
/// with the needs projected forward so the rings keep moving between app
/// launches.
struct SlimeEntry: TimelineEntry {
    let date: Date
    /// The app's last save for widgets. Nil until the app has saved once, or
    /// when the App Group is missing (some sideloading setups).
    let snapshot: WidgetSnapshot?
    /// Needs as they probably are at `date`.
    let needs: Needs
    /// Whether the slime is probably asleep at `date`: it goes to bed and
    /// wakes up on its own.
    let isAsleep: Bool

    /// `schedule` is the bedtime the player chose, so the projected sleep
    /// matches what the app will show. Without it, the one saved in the
    /// snapshot (or the default bedtime) is used.
    init(_ snapshot: WidgetSnapshot?, at date: Date, schedule: SleepSchedule? = nil) {
        self.date = date
        self.snapshot = snapshot
        guard let snapshot else {
            needs = Needs()
            isAsleep = false
            return
        }
        var slime = Slime()
        slime.needs = snapshot.needs
        slime.isAsleep = snapshot.isAsleep
        slime.personality = snapshot.personality
        slime.lastUpdate = snapshot.writtenAt
        slime.advance(to: date, schedule: schedule ?? snapshot.sleep ?? SleepSchedule())
        needs = slime.needs
        isAsleep = slime.isAsleep
    }

    /// Lets the Smart Stack bring the widget forward when a need runs low.
    var relevance: TimelineEntryRelevance? {
        guard snapshot != nil, let need = lowNeed else { return nil }
        return TimelineEntryRelevance(score: Float(100 - needs[need]), duration: 0)
    }
}

extension SlimeEntry {
    var name: String { snapshot?.name ?? "Slime" }

    /// The need worth mentioning, if one is low.
    var lowNeed: Need? {
        guard !isAsleep else { return nil }
        let lowest = needs.lowest
        return lowest.value < 35 ? lowest.need : nil
    }

    var anim: SpriteAnim {
        guard snapshot != nil else { return .idle }
        if isAsleep { return .sleep }
        return lowNeed == nil ? .idle : .sad
    }

    /// The slime as it looks right now.
    @MainActor
    var slime: StillSlime {
        StillSlime(stage: snapshot?.stage ?? .young, anim: anim, skin: snapshot?.skin ?? .lime,
                   accessories: snapshot?.equipped ?? Accessories(), date: date)
    }

    /// The slime's own words, while they are fresh (saved in the last half
    /// hour and it's awake).
    var speech: String? {
        guard let snapshot, !isAsleep, date.timeIntervalSince(snapshot.writtenAt) < 1800 else { return nil }
        let line = snapshot.line.trimmingCharacters(in: .whitespacesAndNewlines)
        return line.isEmpty ? nil : line
    }

    var status: SlimeStatus {
        guard let snapshot else {
            return SlimeStatus(title: "Open Slimy", detail: "Open the app to bring your slime here.",
                               symbol: "drop.fill", tint: nil, sentence: "Open Slimy to see your slime")
        }
        let name = snapshot.name
        if isAsleep {
            return SlimeStatus(title: "Asleep", detail: "Wakes up on its own.",
                               symbol: "moon.zzz.fill", tint: .indigo, sentence: "\(name) is asleep")
        }
        if let need = lowNeed {
            let tint = Brand.tint(for: need)
            switch need {
            case .hunger:
                return SlimeStatus(title: "Hungry", detail: "A snack would help.",
                                   symbol: need.symbol, tint: tint, sentence: "\(name) is hungry")
            case .fun:
                return SlimeStatus(title: "Bored", detail: "A game would cheer it up.",
                                   symbol: need.symbol, tint: tint, sentence: "\(name) is bored")
            case .energy:
                return SlimeStatus(title: "Tired", detail: "A nap would help.",
                                   symbol: need.symbol, tint: tint, sentence: "\(name) is tired")
            case .clean:
                return SlimeStatus(title: "Sticky", detail: "Time for a bubble bath.",
                                   symbol: need.symbol, tint: tint, sentence: "\(name) needs a bath")
            case .love:
                return SlimeStatus(title: "Wants cuddles", detail: "Some petting would help.",
                                   symbol: need.symbol, tint: tint, sentence: "\(name) wants cuddles")
            }
        }
        if needs.average >= 70 {
            return SlimeStatus(title: "Happy", detail: "All needs looked after.",
                               symbol: "face.smiling", tint: .green, sentence: "\(name) is happy")
        }
        return SlimeStatus(title: "Doing fine", detail: "Say hi when you can.",
                           symbol: "face.smiling", tint: nil, sentence: "\(name) is doing fine")
    }

    /// One sentence for VoiceOver.
    var accessibilitySummary: String {
        let current = self.status
        guard snapshot != nil else { return "\(current.title). \(current.detail)" }
        return "\(name). \(current.title). \(current.detail)"
    }

    /// A made-up slime for the widget gallery and loading placeholders.
    static func sample(at date: Date) -> SlimeEntry {
        var snapshot = WidgetSnapshot(GameState(), at: date)
        snapshot.name = "Blob"
        snapshot.skin = .lime
        snapshot.stage = .young
        snapshot.level = 6
        snapshot.coins = 120
        snapshot.isAsleep = false
        snapshot.needs = Needs(hunger: 58, fun: 82, energy: 88, clean: 74, love: 91)
        snapshot.line = "hi! blub."
        snapshot.writtenAt = date
        snapshot.equipped = Accessories()
        return SlimeEntry(snapshot, at: date)
    }
}

/// How the slime is doing, in plain English.
struct SlimeStatus {
    let title: String
    let detail: String
    let symbol: String
    /// Colours the symbol; nil uses the secondary colour.
    let tint: Color?
    /// For the inline Lock Screen widget: "Blob is hungry".
    let sentence: String
}

struct SlimeProvider: TimelineProvider {
    func placeholder(in context: Context) -> SlimeEntry {
        .sample(at: .now)
    }

    func getSnapshot(in context: Context, completion: @escaping (SlimeEntry) -> Void) {
        let saved = GameStorage.readSnapshot()
        // The gallery shows your own slime, or a sample one before the first save.
        if saved == nil && context.isPreview {
            completion(.sample(at: .now))
        } else {
            completion(SlimeEntry(saved, at: .now, schedule: Self.sleepSchedule(for: saved)))
        }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SlimeEntry>) -> Void) {
        let now = Date.now
        let saved = GameStorage.readSnapshot()
        let schedule = Self.sleepSchedule(for: saved)
        // Every 30 minutes for six hours; WidgetKit decides the real pace, and
        // the app asks for a reload whenever it saves.
        let entries = (0..<12).map {
            SlimeEntry(saved, at: now.addingTimeInterval(Double($0) * 1800), schedule: schedule)
        }
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(6 * 3600))))
    }

    /// The bedtime set in the app's settings. Snapshots carry it; for one
    /// written by an older build it is read from the save, and the default
    /// one is used if that fails too.
    private static func sleepSchedule(for snapshot: WidgetSnapshot?) -> SleepSchedule {
        struct Save: Decodable {
            struct Prefs: Decodable {
                var sleep: SleepSchedule?
            }
            var settings: Prefs?
        }
        if let sleep = snapshot?.sleep { return sleep }
        guard snapshot != nil, GameStorage.sharedDirectory != nil,
              let data = try? Data(contentsOf: GameStorage.fileURL),
              let save = try? GameStorage.decoder.decode(Save.self, from: data) else { return SleepSchedule() }
        return save.settings?.sleep ?? SleepSchedule()
    }
}
