import Foundation

/// Today's plan: a new game, a quest and a short story. Comes from the
/// server (made by Claude) when it is reachable, otherwise the app makes it
/// with DailyPlanner. A plan never changes once you started playing it.
@MainActor
final class DailyStore: ObservableObject {
    static let shared = DailyStore()

    @Published private(set) var plan: DailyPlan

    private let defaults = UserDefaults.standard
    private let key = "slimy.daily.plan"

    init() {
        let today = DailyPlanner.dayKey(.now)
        if let data = UserDefaults.standard.data(forKey: key),
           let saved = try? HyprnodeAPI.decoder.decode(DailyPlan.self, from: data), saved.day == today {
            plan = saved.sanitized()
        } else {
            plan = DailyPlanner.plan(for: today)
        }
    }

    var progress: (value: Int, target: Int) { GameStore.shared.state.questProgress(plan) }
    var questDone: Bool { progress.value >= progress.target }
    var questClaimed: Bool { GameStore.shared.state.progress.quest?.claimed == true && GameStore.shared.state.progress.quest?.day == plan.day }
    var playedToday: Bool { (GameStore.shared.state.progress.quest?.best ?? 0) > 0 && GameStore.shared.state.progress.quest?.day == plan.day }

    /// Rolls over to a new day and asks the server for its plan.
    func refresh() async {
        let today = DailyPlanner.dayKey(.now)
        if plan.day != today {
            plan = DailyPlanner.plan(for: today)
            save()
        }
        GameStore.shared.update { $0.startQuest(plan) }

        guard plan.source != "ai", !playedToday else { return }
        do {
            let api = try await FriendsStore.shared.authorizedAPI()
            let remote = try await api.daily(day: today).sanitized()
            guard remote.day == today, !playedToday else { return }
            let questChanged = remote.quest != plan.quest
            plan = remote
            save()
            if questChanged {
                // A different quest: start counting from now.
                GameStore.shared.update { state in
                    state.progress.quest = nil
                    state.startQuest(remote)
                }
            }
        } catch {
            // No server or no AI plan today: the local plan stays.
        }
    }

    func claimQuest() {
        let plan = plan
        let out = GameStore.shared.apply({ $0.claimQuest(plan, now: .now) }, sound: .coin)
        GameStore.shared.toast = Toast(text: out.line, good: out.ok)
        objectWillChange.send()
    }

    private func save() {
        if let data = try? HyprnodeAPI.encoder.encode(plan) { defaults.set(data, forKey: key) }
    }
}
