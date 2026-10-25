import Foundation
import SwiftUI
import WidgetKit

/// A moment worth a little fanfare on screen.
struct Celebration: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let detail: String
    let icon: String
}

struct Toast: Identifiable, Equatable {
    let id = UUID()
    var text: String
    var good: Bool
}

/// Owns the save. Every change goes through `apply`, which saves, refreshes
/// the widgets and the island, and turns game events into celebrations.
@MainActor
final class GameStore: ObservableObject {
    static let shared = GameStore()

    @Published private(set) var state: GameState
    @Published var toast: Toast?
    @Published var celebrations: [Celebration] = []
    /// Where the save came from on launch (shown in Diagnose).
    let loadSource: GameStorage.Source
    private(set) var saveError: String?

    private var lastWidgetReload = Date.distantPast

    init() {
        let (state, source) = GameStorage.load()
        self.state = state
        self.loadSource = source
        if source == .migrated { persist() }
    }

    var slime: Slime { state.slime }

    // MARK: Changing the game

    /// Runs a rule, then saves and tells everything that cares.
    @discardableResult
    func apply(_ change: (inout GameState) -> Outcome, sound: Sound? = nil, island: Bool = true) -> Outcome {
        var copy = state
        let out = change(&copy)
        state = copy
        afterChange(out, sound: sound, island: island)
        return out
    }

    /// For changes without an outcome.
    func update(_ change: (inout GameState) -> Void) {
        var copy = state
        change(&copy)
        state = copy
        persist()
        Task { await IslandController.shared.sync() }
    }

    private func afterChange(_ out: Outcome, sound: Sound?, island: Bool) {
        persist()
        if !out.line.isEmpty { toast = Toast(text: out.line, good: out.ok) }
        if out.ok {
            if let sound { Feedback.shared.play(sound) }
        } else {
            Feedback.shared.play(.nope)
        }
        celebrate(out.events)
        Notifier.reschedule(for: state)
        if island { Task { await IslandController.shared.sync() } }
    }

    func tick(now: Date = .now) {
        var copy = state
        let events = copy.tick(now: now)
        state = copy
        celebrate(events)
        persist()
    }

    /// The app came to the front.
    func arrive(now: Date = .now) {
        var copy = state
        let events = copy.tick(now: now)
        let line = copy.arrive(now: now)
        state = copy
        celebrate(events)
        persist()
        if let line { toast = Toast(text: line, good: true) }
    }

    // MARK: Care shortcuts

    @discardableResult func feed(_ food: Food) -> Outcome { apply({ $0.feed(food, now: .now) }, sound: .eat) }
    @discardableResult func pet() -> Outcome { apply({ $0.pet(now: .now) }, sound: .pet) }
    @discardableResult func bath() -> Outcome { apply({ $0.bath(now: .now) }, sound: .splash) }

    @discardableResult
    func toggleSleep() -> Outcome {
        state.slime.isAsleep ? apply({ $0.wake(now: .now) }, sound: .wake) : apply({ $0.sleep(now: .now) }, sound: .sleep)
    }

    @discardableResult func finish(_ result: GameResult) -> Outcome { apply({ $0.finish(result) }, sound: .coin) }

    /// A quick bounce from the island's Play button: a small round of fun
    /// without the arcade.
    func quickPlay() {
        guard state.canPlay else {
            toast = Toast(text: "too tired to play", good: false)
            return
        }
        finish(GameResult(game: state.slime.favoriteGame, score: 0, session: UUID(), finishedAt: .now))
    }

    /// Picks the best food the player can afford, for the island button.
    func quickFeed() -> Outcome {
        let s = state
        let fav = s.slime.favorite
        let food: Food = s.wallet.coins >= fav.price && s.slime.level >= fav.unlockLevel ? fav : .apple
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

    func chatter() -> String {
        var line = ""
        update { line = $0.chatter(now: .now) }
        return line
    }

    // MARK: Identity

    func hatch(name: String, skin: Skin) {
        var copy = state
        copy.hatch(name: name, skin: skin, seed: UInt64.random(in: 1...UInt64.max), now: .now)
        state = copy
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
    /// (purchases, accounts) is tied to it.
    func releaseSlime() {
        GameStorage.reset()
        UserDefaults.standard.removeObject(forKey: "slimy.state.v1")
        state = .fresh()
        persist()
        Task { await IslandController.shared.stop() }
    }

    // MARK: Plumbing

    private func celebrate(_ events: [GameEvent]) {
        for event in events {
            switch event {
            case .levelUp(let level):
                celebrations.append(Celebration(title: "Level \(level)", detail: "+\(10 * level) coins", icon: "coin"))
                Feedback.shared.play(.levelUp)
            case .stageUp(let stage):
                celebrations.append(Celebration(title: "\(state.slime.name) grew up", detail: stage.title, icon: "starJelly"))
                Feedback.shared.play(.levelUp)
            case .achievement(let id):
                if let a = Achievements.achievement(id) {
                    celebrations.append(Celebration(title: a.title, detail: "Achievement · +\(a.coins) coins", icon: "furn.trophy"))
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
