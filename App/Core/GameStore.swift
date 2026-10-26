import Foundation
import SwiftUI
import UIKit
import WidgetKit

/// A moment worth a little fanfare on screen.
struct Celebration: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let detail: String
    let icon: String
    /// A happy moment: a good time to ask for an App Store rating.
    var asksForReview = false
}

struct Toast: Identifiable, Equatable {
    let id = UUID()
    var text: String
    var good: Bool
}

/// Owns the save. Every change goes through `apply` or `update`, which save,
/// refresh the widgets and the island, and turn game events into
/// celebrations.
@MainActor
final class GameStore: ObservableObject {
    static let shared = GameStore()

    @Published private(set) var state: GameState
    @Published var toast: Toast?
    @Published var celebrations: [Celebration] = []
    /// Where the save came from on launch.
    let loadSource: GameStorage.Source
    private(set) var saveError: String?

    private var lastWidgetReload = Date.distantPast
    private var lastChatterSave = Date.distantPast
    private let defaults = UserDefaults.standard

    /// Set by FriendsStore. Called after every care action done on this
    /// phone (feed, play, bath, pet, sleep, wake, gift) while you care for
    /// the slime together, to send it to the partner.
    var onCare: ((CareEvent) -> Void)? {
        didSet {
            if sendCare() { persist() }
        }
    }

    init() {
        let (state, source) = GameStorage.load()
        self.state = state
        self.loadSource = source
        if source == .migrated { persist() }
    }

    var slime: Slime { state.slime }

    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
    }

    // MARK: Changing the game

    /// Runs a rule, then saves and tells everything that cares.
    ///
    /// `toast: false` is for screens where the slime's speech bubble is
    /// already on screen: a line the slime is saying isn't repeated in a
    /// toast (VoiceOver still reads it out). Lines it isn't saying, like
    /// "not sleepy yet" answers, still get their toast.
    @discardableResult
    func apply(_ change: (inout GameState) -> Outcome, sound: Sound? = nil, island: Bool = true,
               toast: Bool = true) -> Outcome {
        let before = state
        var copy = state
        let out = change(&copy)
        Self.noteLook(since: before, in: &copy)
        state = copy
        afterChange(out, since: before, sound: sound, island: island, toast: toast)
        return out
    }

    /// For changes without an outcome.
    func update(_ change: (inout GameState) -> Void) {
        let before = state
        var copy = state
        change(&copy)
        Self.noteLook(since: before, in: &copy)
        state = copy
        sendCare()
        celebrate(unannounced(since: before, known: []))
        persist()
        Task { await IslandController.shared.sync() }
    }

    private func afterChange(_ out: Outcome, since before: GameState, sound: Sound?, island: Bool, toast showToast: Bool) {
        sendCare()
        persist()
        if !out.line.isEmpty {
            if showToast || state.slime.currentLine(at: .now) != out.line {
                toast = Toast(text: out.line, good: out.ok)
            } else {
                UIAccessibility.post(notification: .announcement, argument: out.line)
            }
        }
        if out.ok {
            if let sound { Feedback.shared.play(sound) }
        } else {
            Feedback.shared.play(.nope)
        }
        celebrate(out.events + unannounced(since: before, known: out.events))
        Notifier.reschedule(for: state)
        if island { Task { await IslandController.shared.sync() } }
    }

    func tick(now: Date = .now) {
        let before = state
        var copy = state
        let events = copy.tick(now: now)
        state = copy
        celebrate(events + unannounced(since: before, known: events))
        persist()
    }

    /// The app came to the front.
    func arrive(now: Date = .now) {
        let before = state
        var copy = state
        let events = copy.tick(now: now)
        let line = copy.arrive(now: now)
        state = copy
        celebrate(events + unannounced(since: before, known: events))
        persist()
        if let line { toast = Toast(text: line, good: true) }
    }

    // MARK: Care shortcuts

    @discardableResult
    func feed(_ food: Food, toast: Bool = true) -> Outcome {
        apply({ $0.feed(food, now: .now) }, sound: .eat, toast: toast)
    }

    @discardableResult
    func pet(toast: Bool = true) -> Outcome {
        apply({ $0.pet(now: .now) }, sound: .pet, toast: toast)
    }

    @discardableResult func bath() -> Outcome { apply({ $0.bath(now: .now) }, sound: .splash) }

    @discardableResult
    func toggleSleep(toast: Bool = true) -> Outcome {
        if state.slime.isAsleep {
            return apply({ $0.wake(now: .now) }, sound: .wake, toast: toast)
        }
        return apply({ $0.sleep(now: .now) }, sound: .sleep, toast: toast)
    }

    @discardableResult func finish(_ result: GameResult) -> Outcome { apply({ $0.finish(result) }, sound: .coin) }

    /// A quick bounce from the island's Play button: a small round of fun
    /// without the arcade.
    func quickPlay() {
        guard state.canPlay else {
            toast = Toast(text: "\(state.slime.name) is too tired to play.", good: false)
            return
        }
        finish(GameResult(game: state.slime.favoriteGame, score: 0, session: UUID(), finishedAt: .now))
    }

    /// Picks the best food the player has or can afford, for the island
    /// button. A free serving of the favourite from the pantry counts.
    func quickFeed() -> Outcome {
        let s = state
        let fav = s.slime.favorite
        let canHaveFavorite = s.servings(of: fav) > 0 || (s.wallet.coins >= fav.price && s.slime.level >= fav.unlockLevel)
        let food: Food = canHaveFavorite ? fav : .apple
        return feed(food)
    }

    func handle(_ event: PhoneEvent) -> String {
        var line = ""
        apply({ s in
            line = s.handle(event, now: .now)
            return Outcome(ok: true, line: "")
        }, sound: nil)
        return line
    }

    /// Small talk while you watch: Home calls this every 8 to 16 seconds,
    /// most of the time. The slime picks something that fits right now and
    /// never repeats one of its last 12 lines. Returns whether it said
    /// something: it stays quiet before hatching and when Talks on its own
    /// is off.
    ///
    /// A light path: no care is sent, and the save, the widgets and the
    /// island catch up at most once a minute (the next change saves the
    /// lines too). Coins the clock brought (a birthday, an achievement) are
    /// saved right away.
    @discardableResult
    func chatter() -> Bool {
        guard state.hatched, state.settings.chatty else { return false }
        let before = state
        var copy = state
        _ = copy.chatter(now: .now)
        state = copy
        celebrate(unannounced(since: before, known: []))
        let now = Date.now
        guard state.wallet != before.wallet || state.crates != before.crates
                || now.timeIntervalSince(lastChatterSave) > 60 else { return true }
        lastChatterSave = now
        persist()
        Task { await IslandController.shared.sync() }
        return true
    }

    // MARK: Feelings

    /// Adds to a feeling (negative amounts take away), for example the
    /// feeling the chat's reply expressed.
    func feel(_ e: Emotion, _ amount: Double) {
        guard amount.isFinite, amount != 0 else { return }
        update { $0.slime.feel(e, amount) }
    }

    // MARK: Crates

    /// Buys a crate for coins. False if it isn't for sale, the coins aren't
    /// enough or there's no room; then nothing changes.
    @discardableResult
    func buyCrate(_ kind: CrateKind) -> Bool {
        var bought = false
        apply({ s in
            bought = s.buyCrate(kind, now: .now)
            return Outcome(ok: bought, line: "")
        }, sound: .coin, island: false)
        return bought
    }

    /// Opens a crate and hands out what was in it. Nil if there is none of
    /// that kind. The crate screen shows the reward, so there's no toast.
    @discardableResult
    func openCrate(_ kind: CrateKind) -> CrateReward? {
        var reward: CrateReward?
        let seed = UInt64.random(in: 1...UInt64.max)
        apply({ s in
            guard let opened = s.openCrate(kind, now: .now, seed: seed) else { return Outcome(ok: false, line: "") }
            reward = opened.reward
            return Outcome(ok: true, line: "", events: opened.outcome.events)
        }, toast: false)
        return reward
    }

    // MARK: Caring together

    var isSharing: Bool { state.care != nil }

    /// The partner's care actions: applied to the shared slime (no coins),
    /// each only once, with a toast like "Anna fed Blob".
    func applyPartnerCare(_ events: [CareEvent]) {
        guard state.care != nil, !events.isEmpty else { return }
        let before = state
        var copy = state
        let now = Date.now
        var said: [String] = []
        var person = ""
        for event in events.sorted(by: { $0.at < $1.at }) {
            guard let summary = copy.applyPartnerCare(event, now: now) else { continue }
            said.append(summary)
            person = event.by.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        state = copy
        guard let last = said.last else {
            persist()
            return
        }
        celebrate(unannounced(since: before, known: []))
        persist()
        if said.count == 1 {
            toast = Toast(text: last, good: true)
        } else {
            let who = person.isEmpty ? (state.care?.partnerName ?? "Your partner") : person
            toast = Toast(text: "\(who) looked after \(state.slime.name) \(said.count) times", good: true)
        }
        Notifier.reschedule(for: state)
        Task { await IslandController.shared.sync() }
    }

    /// The shared slime to send to the partner's phone.
    func sharedSnapshot() -> SlimeSnapshot {
        state.sharedSnapshot(at: .now)
    }

    /// The joiner's side: the shared slime moves in, your own is kept safe
    /// in `link.ownBackup` until you stop sharing.
    func adoptShared(_ snapshot: SlimeSnapshot, link: CareLink) {
        let before = state
        var copy = state
        copy.adoptShared(snapshot, link: link, now: .now)
        state = copy
        // A different slime: the widgets show it right away.
        lastWidgetReload = .distantPast
        celebrate(unannounced(since: before, known: []))
        persist()
        Notifier.reschedule(for: state)
        Task { await IslandController.shared.sync() }
    }

    /// Takes over the partner's snapshot if it is newer than what this
    /// phone knows; feelings are blended.
    func reconcile(with snapshot: SlimeSnapshot) {
        let before = state
        var copy = state
        guard copy.reconcile(with: snapshot, now: .now) else { return }
        state = copy
        celebrate(unannounced(since: before, known: []))
        persist()
        Task { await IslandController.shared.sync() }
    }

    /// The host's side: someone joined with your code. Keeps the link.
    func startHosting(link: CareLink) {
        let before = state
        var copy = state
        copy.startHosting(link, now: .now)
        state = copy
        celebrate(unannounced(since: before, known: []))
        persist()
        Task { await IslandController.shared.sync() }
    }

    /// Ends caring together on this phone. The joiner's own slime comes
    /// back; the host keeps the slime.
    func stopSharing() {
        guard state.care != nil else { return }
        let before = state
        var copy = state
        copy.stopSharing(now: .now)
        state = copy
        lastWidgetReload = .distantPast
        celebrate(unannounced(since: before, known: []))
        persist()
        Notifier.reschedule(for: state)
        Task { await IslandController.shared.sync() }
    }

    // MARK: Daily gift

    /// The gift calendar and streak right now.
    var streak: StreakInfo { state.streakInfo(at: .now) }

    /// Opens today's gift. Returns what was in it, or nil if today's gift
    /// was already opened. What the slime says about it is in `state`.
    @discardableResult
    func claimDailyGift() -> DailyGift? {
        var gift: DailyGift?
        apply({ s in
            guard let claimed = s.claimDailyGift(now: .now) else { return Outcome(ok: false, line: "") }
            gift = claimed.gift
            // The gift screen shows the slime's words; no toast on top.
            return Outcome(ok: true, line: "", events: claimed.outcome.events)
        }, sound: .treasure)
        return gift
    }

    // MARK: Identity

    /// Hatches the slime. A random character unless `seed` fixes one.
    func hatch(name: String, skin: Skin, seed: UInt64? = nil) {
        var copy = state
        copy.hatch(name: name, skin: skin, seed: seed ?? UInt64.random(in: 1...UInt64.max), now: .now)
        state = copy
        // A new slime: the widgets show it right away, not after the next
        // reload budget allows.
        lastWidgetReload = .distantPast
        persist()
        Feedback.shared.play(.levelUp)
    }

    func rename(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        update { $0.slime.name = String(trimmed.prefix(16)) }
    }

    func wear(_ skin: Skin) {
        guard state.slime.isUnlocked(skin) else { return }
        update {
            $0.slime.skin = skin
            $0.slime.react(.blush, at: .now)
        }
    }

    func markTip(_ id: String) {
        guard !state.player.tips.contains(id) else { return }
        update { $0.player.tips.insert(id) }
    }

    func setSettings(_ change: (inout Settings) -> Void) {
        update { change(&$0.settings) }
    }

    /// Starts over. Saved coins and items go with the slime; nothing else
    /// (purchases, accounts) is tied to it. Does nothing while you care for
    /// the slime together: stop that first, so a joiner's own slime comes
    /// back instead of being lost.
    func releaseSlime() {
        guard state.care == nil else { return }
        GameStorage.reset()
        UserDefaults.standard.removeObject(forKey: "slimy.state.v1")
        celebrations.removeAll()
        state = .fresh()
        lastWidgetReload = .distantPast
        persist()
        // Reminders about the slime that's gone are taken back.
        Notifier.reschedule(for: state)
        Task { await IslandController.shared.stop() }
    }

    /// App Store screenshots: a ready-made slime with some coins, straight
    /// past onboarding. Made once with a fixed character and kept in the
    /// save, so every relaunch of a screenshot run shows the same slime.
    /// Does nothing outside screenshot runs.
    func prepareScreenshotDemo() {
        guard ScreenshotMode.isActive else { return }
        if !state.hatched { hatch(name: "Mochi", skin: .lime, seed: 2026) }
        markTip("onboarded")
        guard !state.wallet.hasClaimed("screenshot:demo") else { return }
        update { s in
            s.settings.notifications.asked = true
            s.wallet.claim("screenshot:demo", coins: 470, at: .now)
        }
    }

    // MARK: Save files

    /// Writes the whole game to a `.slimysave` file in the temporary folder,
    /// ready for the share sheet. Earlier exports there are removed first,
    /// so exporting again and again doesn't pile up files. (Only exports:
    /// a file waiting to be imported stays.)
    func exportSave() throws -> URL {
        let folder = FileManager.default.temporaryDirectory
        let old = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
        for file in old where file.pathExtension == SaveFile.fileExtension && file.lastPathComponent.hasPrefix("Slimy-") {
            try? FileManager.default.removeItem(at: file)
        }
        return try SaveFile.write(state, to: folder, at: .now, appVersion: Self.appVersion)
    }

    /// Replaces the game with the one in a `.slimysave` file. The file is
    /// checked and written to disk first, so if anything is wrong nothing
    /// changes. The game it replaces stays behind as the backup save.
    /// Errors are `SaveFile.Problem`, with a message to show as it is.
    /// Not while you care for the slime together (`.caringTogether`).
    func importSave(from url: URL) throws {
        guard state.care == nil else { throw SaveFile.Problem.caringTogether }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var imported = try SaveFile.read(from: url, now: .now)
        imported.tick(now: .now)
        // Coming home after a move isn't a reunion.
        imported.player.lastSeen = .now
        try GameStorage.save(imported)
        saveError = nil
        celebrations.removeAll()
        toast = nil
        state = imported
        GameStorage.writeSnapshot(WidgetSnapshot(state, at: .now))
        lastWidgetReload = .now
        WidgetCenter.shared.reloadAllTimelines()
        Notifier.reschedule(for: state)
        Task { await IslandController.shared.sync() }
    }

    // MARK: Asking for a rating

    private static let reviewKey = "slimy.review.askedVersion"

    /// A rating is asked for at most once per app version, and never while
    /// screenshots are taken.
    var mayAskForReview: Bool {
        !ScreenshotMode.isActive && defaults.string(forKey: Self.reviewKey) != Self.appVersion
    }

    func noteReviewAsked() {
        defaults.set(Self.appVersion, forKey: Self.reviewKey)
    }

    // MARK: Plumbing

    /// Sends the care actions waiting in the save to the partner, if
    /// FriendsStore is listening. Returns whether anything was sent.
    @discardableResult
    private func sendCare() -> Bool {
        guard let onCare, !state.careBook.outbox.isEmpty else { return false }
        var copy = state
        let events = copy.takeCareOutbox()
        state = copy
        for event in events { onCare(event) }
        return true
    }

    /// What the partner's phone needs to see of the shared slime's look.
    private struct ShareableLook: Equatable {
        var name: String
        var skin: Skin
        var xp: Int
        var bond: Double
        var equipped: Accessories
        var colours: Set<Skin>

        init(_ slime: Slime) {
            name = slime.name
            skin = slime.skin
            xp = slime.xp
            bond = slime.bond
            equipped = slime.equipped
            colours = slime.colours
        }
    }

    /// A change made on this phone that the partner should see (a new name,
    /// colour or accessory, xp from a challenge) makes the next snapshot win.
    private static func noteLook(since before: GameState, in state: inout GameState) {
        guard state.care != nil, ShareableLook(before.slime) != ShareableLook(state.slime) else { return }
        state.noteSharedChange(now: .now)
    }

    /// Moments a change brought that its outcome didn't mention: birthdays,
    /// holidays, and achievements earned on the side (a friend's hug, a phone
    /// event, the clock passing midnight).
    private func unannounced(since old: GameState, known: [GameEvent]) -> [GameEvent] {
        var out: [GameEvent] = []
        if let entry = state.memory.last(.birthday), entry.id != old.memory.last(.birthday)?.id,
           let years = Int(entry.detail) {
            out.append(.birthday(years))
        }
        if let entry = state.memory.last(.holiday), entry.id != old.memory.last(.holiday)?.id,
           let holiday = Holiday(rawValue: entry.detail) {
            out.append(.holiday(holiday))
        }
        let earned = state.progress.achievements
        for achievement in Achievements.all where earned[achievement.id] != nil && old.progress.achievements[achievement.id] == nil {
            out.append(.achievement(achievement.id))
        }
        return out.filter { !known.contains($0) }
    }

    private func celebrate(_ events: [GameEvent]) {
        guard !ScreenshotMode.isActive else { return }
        for event in events {
            switch event {
            case .levelUp(let level):
                celebrations.append(Celebration(title: "Level \(level)", detail: "+\(10 * level) coins", icon: "coin",
                                                asksForReview: level >= 3))
                Feedback.shared.play(.levelUp)
            case .stageUp(let stage):
                celebrations.append(Celebration(title: "\(state.slime.name) grew up", detail: stage.title, icon: "starJelly"))
                Feedback.shared.play(.levelUp)
            case .achievement(let id):
                if let a = Achievements.achievement(id) {
                    let crate = a.crate.map { " and a \($0.title.lowercased())" } ?? ""
                    celebrations.append(Celebration(title: a.title, detail: "\(a.detail) +\(a.coins) coins\(crate)",
                                                    icon: "furn.trophy",
                                                    asksForReview: id == "close" || id == "legendary"))
                    Feedback.shared.play(.success)
                }
            case .favoriteFound(let food):
                celebrations.append(Celebration(title: "Favourite food found", detail: food.title, icon: food.sprite))
            case .record(let game, let score):
                celebrations.append(Celebration(title: "New record", detail: "\(game.title): \(score)", icon: game.icon))
            case .challenge(let id):
                if let t = Challenges.template(id) {
                    celebrations.append(Celebration(title: "Challenge done", detail: t.title, icon: "coin"))
                }
            case .birthday(let years):
                let together = years == 1 ? "One year together" : "\(years) years together"
                let cake = years == 1 ? " and a cake for the room" : ""
                celebrations.append(Celebration(title: "Happy birthday, \(state.slime.name)",
                                                detail: "\(together). +\(Birthday.coins(years: years)) coins\(cake).",
                                                icon: "cake"))
                Feedback.shared.play(.levelUp)
            case .holiday(let holiday):
                celebrations.append(Celebration(title: holiday.greeting, detail: "A little gift: +\(holiday.coins) coins",
                                                icon: holiday.icon))
                Feedback.shared.play(.success)
            case .crate(let kind, let source):
                celebrations.append(Celebration(title: "A \(kind.title.lowercased())", detail: source.reason,
                                                icon: kind.sprite))
                Feedback.shared.play(.treasure)
            }
        }
    }

    private func persist() {
        do {
            try GameStorage.save(state)
            saveError = nil
        } catch {
            saveError = error.localizedDescription
        }
        GameStorage.writeSnapshot(WidgetSnapshot(state, at: .now))
        // Widgets get a budget of reloads; don't spend it on every tap.
        if Date.now.timeIntervalSince(lastWidgetReload) > 60 {
            lastWidgetReload = .now
            WidgetCenter.shared.reloadAllTimelines()
        }
    }
}
