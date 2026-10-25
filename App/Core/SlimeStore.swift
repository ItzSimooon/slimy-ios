import ActivityKit
import Foundation
import SwiftUI

struct Toast: Identifiable, Equatable {
    let id = UUID()
    var text: String
    var good: Bool
}

/// Owns the slime. Everything that changes it goes through here so the
/// island, notifications and friends stay in step.
@MainActor
final class SlimeStore: ObservableObject {
    static let shared = SlimeStore()

    @Published private(set) var state: SlimeState
    @Published var toast: Toast?
    @Published var hasHatched: Bool {
        didSet { defaults.set(hasHatched, forKey: Keys.hatched) }
    }

    private let defaults = UserDefaults.standard

    private enum Keys {
        static let state = "slimy.state.v1"
        static let hatched = "slimy.hatched"
    }

    init() {
        let defaults = UserDefaults.standard
        if let data = defaults.data(forKey: Keys.state),
           let saved = try? JSONDecoder().decode(SlimeState.self, from: data) {
            state = saved
        } else {
            state = .newborn()
        }
        hasHatched = defaults.bool(forKey: Keys.hatched)
        state.advance(to: .now)
    }

    // MARK: Time

    func tick(now: Date = .now) {
        state.advance(to: now)
        save()
    }

    // MARK: Care

    /// For buttons in the app: applies right away, updates the island after.
    @discardableResult
    func act(_ action: SlimeAction) -> ActionOutcome {
        let out = apply(action)
        Task { await IslandController.shared.sync() }
        return out
    }

    /// For intents: waits until the island shows the result.
    @discardableResult
    func perform(_ action: SlimeAction) async -> ActionOutcome {
        let out = apply(action)
        await IslandController.shared.sync()
        await IslandController.shared.settle()
        return out
    }

    func quickFeed() async {
        let food: Food = state.coins >= state.favorite.price ? state.favorite : .apple
        await perform(.feed(food))
    }

    func toggleSleep() async {
        await perform(state.isAsleep ? .wake : .sleep)
    }

    /// A phone event happened. Returns what the slime said about it.
    @discardableResult
    func handle(_ event: PhoneEvent) async -> String {
        state.apply(event)
        save()
        let line = state.eventLine ?? ""
        let alert = event.alerts
            ? AlertConfiguration(title: "\(state.name)", body: "\(line)", sound: .default)
            : nil
        await IslandController.shared.sync(alert: alert)
        await IslandController.shared.settle()
        return line
    }

    private func apply(_ action: SlimeAction) -> ActionOutcome {
        let out = state.apply(action)
        save()
        toast = Toast(text: out.line, good: out.ok)
        Notifier.reschedule(for: state)
        return out
    }

    // MARK: Identity

    func hatch(name: String, skin: Skin) {
        var s = SlimeState.newborn(named: name.isEmpty ? "Blob" : name)
        s.skin = skin
        s.react(.wave, at: .now)
        state = s
        hasHatched = true
        save()
    }

    func rename(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        state.name = String(trimmed.prefix(16))
        save()
        Task { await IslandController.shared.sync() }
    }

    func wear(_ skin: Skin) {
        guard state.isUnlocked(skin) else { return }
        state.skin = skin
        state.react(.love, at: .now)
        save()
        Task { await IslandController.shared.sync() }
    }

    func receiveGift(_ food: Food, from friend: String) {
        state.needs.add(.hunger, food.nutrition)
        state.needs.add(.love, 8)
        state.react(.eat, at: .now)
        save()
        toast = Toast(text: "\(friend) hat dir \(food.title.lowercased()) geschickt", good: true)
    }

    func spend(_ coins: Int) {
        state.coins = max(0, state.coins - coins)
        save()
    }

    /// A friend interaction landed. Love goes up and the slime swoons.
    func loveMoment(fellInLove: Bool) {
        state.needs.add(.love, fellInLove ? 40 : 10)
        state.needs.add(.fun, 5)
        state.xp += fellInLove ? 50 : 4
        state.react(.love, at: .now)
        save()
    }

    /// Plays an animation without changing any needs (used by chat replies).
    func playReaction(_ reaction: Reaction) {
        guard !state.isAsleep else { return }
        state.react(reaction, at: .now)
        save()
        Task { await IslandController.shared.sync() }
    }

    func releaseSlime() {
        state = .newborn()
        hasHatched = false
        save()
        Task { await IslandController.shared.stop() }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(state) {
            defaults.set(data, forKey: Keys.state)
        }
    }
}
