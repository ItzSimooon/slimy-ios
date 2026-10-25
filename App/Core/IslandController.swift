import ActivityKit
import Foundation

/// Starts, updates and revives the one Live Activity.
///
/// Policy: there is only ever one Slimy activity. It shows your slime solo,
/// with up to three friends beside it, and switches to a shared moment
/// (reunion, hug, gift …) for a limited time before going back to solo.
/// iOS ends an activity after about 8 hours; without push (which needs a
/// paid developer account) it comes back when the app opens, through the
/// "Slime holen" intent (Control Center, Action Button, Shortcuts) or a
/// background refresh run that iOS allows.
@MainActor
final class IslandController: ObservableObject {
    static let shared = IslandController()

    @Published private(set) var isLive = false
    @Published var problem: String?
    @Published private(set) var log: [String] = []

    private let defaults = UserDefaults.standard
    private var settleTask: Task<Void, Never>?
    private var moment: (kind: SharedMoment, friend: String?, until: Date)?

    var keepAlive: Bool {
        get { defaults.object(forKey: "slimy.island.keepAlive") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "slimy.island.keepAlive"); objectWillChange.send() }
    }

    var animated: Bool {
        get { defaults.object(forKey: "slimy.island.animated") as? Bool ?? true }
        set {
            defaults.set(newValue, forKey: "slimy.island.animated")
            objectWillChange.send()
            Task { await sync() }
        }
    }

    /// Diagnostics: the timer test strip on the Lock Screen.
    var probe: Bool {
        get { defaults.bool(forKey: "slimy.island.probe") }
        set {
            defaults.set(newValue, forKey: "slimy.island.probe")
            objectWillChange.send()
            Task { await sync() }
        }
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

    private func content(at now: Date = .now) -> ActivityContent<SlimeActivityAttributes.ContentState> {
        if let m = moment, m.until <= now { moment = nil }
        let state = SlimeActivityAttributes.ContentState(
            GameStore.shared.state, at: now,
            friends: FriendsStore.shared.islandFriends,
            moment: moment?.kind, momentFriend: moment?.friend,
            animated: animated, probe: probe)
        return ActivityContent(state: state, staleDate: nil, relevanceScore: moment == nil ? 50 : 100)
    }

    func refreshStatus() {
        isLive = current != nil
    }

    func note(_ line: String) {
        let time = Date.now.formatted(date: .omitted, time: .standard)
        log.append("\(time)  \(line)")
        if log.count > 40 { log.removeFirst(log.count - 40) }
    }

    // MARK: Lifecycle

    func start() async {
        guard GameStore.shared.state.hatched else { return }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            problem = "Live-Aktivitäten sind für Slimy aus. Schalt sie ein unter Einstellungen → Slimy → Live-Aktivitäten."
            note("Start abgebrochen: Live-Aktivitäten sind ausgeschaltet")
            refreshStatus()
            return
        }
        // Never more than one: end any leftovers first.
        for activity in Activity<SlimeActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
        do {
            let activity = try Activity.request(attributes: SlimeActivityAttributes(), content: content(), pushType: nil)
            startedAt = .now
            problem = nil
            note("Gestartet: \(activity.id.prefix(6))")
            watch(activity)
        } catch {
            problem = "Die Insel ist nicht gestartet: \(error.localizedDescription). Öffne Slimy und versuch es nochmal."
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

    /// Makes sure the slime is in the island, restarting before iOS's 8-hour limit.
    func ensureRunning(restart: Bool = false) async {
        if restart { keepAlive = true }
        guard keepAlive, GameStore.shared.state.hatched else { refreshStatus(); return }
        let age = startedAt.map { Date.now.timeIntervalSince($0) } ?? .infinity
        if current == nil || age > 7 * 3600 || (restart && age > 60) {
            await start()
        } else {
            await sync()
            refreshStatus()
        }
    }

    private func watch(_ activity: Activity<SlimeActivityAttributes>) {
        Task { [weak self] in
            for await state in activity.activityStateUpdates {
                self?.note("Aktivität \(activity.id.prefix(6)): \(state)")
                self?.refreshStatus()
            }
        }
    }

    // MARK: Updates

    func sync(alert: AlertConfiguration? = nil) async {
        guard let activity = current else { return }
        if let alert {
            await activity.update(content(), alertConfiguration: alert)
        } else {
            await activity.update(content())
        }
        scheduleSettle()
    }

    /// Shows a shared moment with a friend for a while, then goes back to solo.
    func show(_ kind: SharedMoment, with friendID: String?, alert: String? = nil) async {
        moment = (kind, friendID, Date.now.addingTimeInterval(kind.duration))
        let name = GameStore.shared.slime.name
        await sync(alert: alert.map { AlertConfiguration(title: "\(name)", body: "\($0)", sound: .default) })
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(kind.duration + 1))
            await self?.sync()
        }
    }

    /// A reaction plays for a few seconds; one more update returns to the mood.
    private func scheduleSettle() {
        settleTask?.cancel()
        let slime = GameStore.shared.slime
        guard slime.activeReaction(at: .now) != .none else { return }
        let left = Slime.reactionWindow - Date.now.timeIntervalSince(slime.reactionAt)
        settleTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(max(0, left) + 0.3))
            guard !Task.isCancelled else { return }
            await self?.sync()
        }
    }

    /// For intents: waits until the reaction has played, then settles the
    /// island, so it doesn't stay mid-reaction if iOS suspends the app.
    func settle() async {
        let slime = GameStore.shared.slime
        guard slime.activeReaction(at: .now) != .none else { return }
        let left = Slime.reactionWindow - Date.now.timeIntervalSince(slime.reactionAt)
        try? await Task.sleep(for: .seconds(max(0, left) + 0.3))
        await sync()
    }
}
