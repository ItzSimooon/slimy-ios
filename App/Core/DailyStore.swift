import Foundation

/// Today's plan: a new game, a quest and a short story. DailyPlanner makes
/// it on the phone from the date, so everyone gets the same game on the same
/// day and nothing has to be fetched. A plan never changes during its day.
@MainActor
final class DailyStore: ObservableObject {
    static let shared = DailyStore()

    @Published private(set) var plan: DailyPlan

    private static let key = "slimy.daily.plan"

    init() {
        let today = DailyPlanner.dayKey(.now)
        // A plan saved earlier today stays, even if an older app version
        // made it differently.
        if let data = UserDefaults.standard.data(forKey: Self.key),
           let saved = try? JSONDecoder().decode(DailyPlan.self, from: data), saved.day == today {
            plan = saved.sanitized()
        } else {
            plan = DailyPlanner.plan(for: today)
        }
    }

    var progress: (value: Int, target: Int) { GameStore.shared.state.questProgress(plan) }
    var questDone: Bool { progress.value >= progress.target }

    var questClaimed: Bool {
        let quest = GameStore.shared.state.progress.quest
        return quest?.claimed == true && quest?.day == plan.day
    }

    var playedToday: Bool {
        let quest = GameStore.shared.state.progress.quest
        return (quest?.best ?? 0) > 0 && quest?.day == plan.day
    }

    /// Rolls over to the new day's plan and starts counting its quest.
    func refresh() async {
        let today = DailyPlanner.dayKey(.now)
        if plan.day != today {
            plan = DailyPlanner.plan(for: today)
            save()
        }
        let current = plan
        GameStore.shared.update { $0.startQuest(current) }
    }

    /// Pays the quest. `apply` already shows its line as a toast.
    func claimQuest() {
        let plan = plan
        GameStore.shared.apply({ $0.claimQuest(plan, now: .now) }, sound: .coin)
        objectWillChange.send()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(plan) { UserDefaults.standard.set(data, forKey: Self.key) }
    }
}
