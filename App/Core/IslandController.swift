import ActivityKit
import Foundation

/// Starts, updates and revives the Live Activity.
///
/// iOS ends a Live Activity after about 8 hours. Without push notifications
/// (which need a paid developer account) it comes back whenever the app opens,
/// or when the Summon Slime intent runs from Control Center, the Action Button
/// or a Shortcuts automation.
@MainActor
final class IslandController: ObservableObject {
    static let shared = IslandController()

    @Published private(set) var isLive = false
    @Published var problem: String?
    /// What happened with the island, newest last. Shown on the diagnostics screen.
    @Published private(set) var log: [String] = []

    private let defaults = UserDefaults.standard

    /// Bring the slime back automatically whenever we get the chance.
    var keepAlive: Bool {
        get { defaults.object(forKey: "slimy.island.keepAlive") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "slimy.island.keepAlive"); objectWillChange.send() }
    }

    private var startedAt: Date? {
        get { defaults.object(forKey: "slimy.island.startedAt") as? Date }
        set { defaults.set(newValue, forKey: "slimy.island.startedAt") }
    }

    private var current: Activity<SlimeActivityAttributes>? {
        Activity<SlimeActivityAttributes>.activities.first {
            $0.activityState == .active || $0.activityState == .stale
        }
    }

    private var content: ActivityContent<SlimeActivityAttributes.ContentState> {
        let state = SlimeStore.shared.state.activityState(partner: FriendsStore.shared.partnerSnapshot)
        return ActivityContent(state: state, staleDate: nil, relevanceScore: 100)
    }

    func refreshStatus() {
        isLive = current != nil
    }

    func note(_ line: String) {
        let time = Date.now.formatted(date: .omitted, time: .standard)
        log.append("\(time)  \(line)")
        if log.count > 40 { log.removeFirst(log.count - 40) }
    }

    /// Logs every state change iOS reports for the activity (active, ended, dismissed…).
    private func watch(_ activity: Activity<SlimeActivityAttributes>) {
        Task { [weak self] in
            for await state in activity.activityStateUpdates {
                self?.note("Aktivität \(activity.id.prefix(6)): \(state)")
                self?.refreshStatus()
            }
        }
    }

    func start() async {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            problem = "Live Activities are off for Slimy. Turn them on in Settings → Slimy → Live Activities."
            note("Start abgebrochen: Live-Aktivitäten sind ausgeschaltet")
            refreshStatus()
            return
        }
        for activity in Activity<SlimeActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
        do {
            let activity = try Activity.request(attributes: SlimeActivityAttributes(), content: content, pushType: nil)
            startedAt = .now
            problem = nil
            note("Gestartet: \(activity.id.prefix(6)), Zustand \(activity.activityState)")
            watch(activity)
        } catch {
            problem = "The island didn't start: \(error.localizedDescription). Open Slimy and try again."
            note("Start fehlgeschlagen: \(error)")
        }
        refreshStatus()
    }

    func stop() async {
        keepAlive = false
        note("Beendet von dir")
        for activity in Activity<SlimeActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
        refreshStatus()
    }

    func sync(alert: AlertConfiguration? = nil) async {
        guard let activity = current else { return }
        if let alert {
            await activity.update(content, alertConfiguration: alert)
        } else {
            await activity.update(content)
        }
        scheduleSettle()
    }

    private var settleTask: Task<Void, Never>?

    /// A reaction only plays for a few seconds; send one more update to go
    /// back to the mood animation once it's over.
    private func scheduleSettle() {
        settleTask?.cancel()
        let slime = SlimeStore.shared.state
        guard slime.activeReaction(at: .now) != .none else { return }
        let left = SlimeState.reactionWindow - Date.now.timeIntervalSince(slime.reactionAt)
        settleTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(max(0, left) + 0.3))
            guard !Task.isCancelled else { return }
            await self?.sync()
        }
    }

    /// For intents: waits until the reaction has played, then settles the
    /// island, so it doesn't get stuck mid-reaction if iOS suspends the app.
    func settle() async {
        let slime = SlimeStore.shared.state
        guard slime.activeReaction(at: .now) != .none else { return }
        let left = SlimeState.reactionWindow - Date.now.timeIntervalSince(slime.reactionAt)
        try? await Task.sleep(for: .seconds(max(0, left) + 0.3))
        await sync()
    }

    /// Makes sure the slime is in the island, restarting it before iOS's
    /// 8-hour limit runs out.
    func ensureRunning(restart: Bool = false) async {
        if restart { keepAlive = true }
        guard keepAlive else { refreshStatus(); return }
        let age = startedAt.map { Date.now.timeIntervalSince($0) } ?? .infinity
        if current == nil || age > 7 * 3600 || (restart && age > 60) {
            await start()
        } else {
            await sync()
            refreshStatus()
        }
    }
}
