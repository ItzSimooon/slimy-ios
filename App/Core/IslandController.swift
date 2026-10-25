import ActivityKit
import Foundation

/// Starts, updates and revives the one Live Activity.
///
/// Policy: there is only ever one Slimy activity. It shows your slime solo,
/// with up to three friends beside it, and switches to a shared moment
/// (reunion, hug, gift …) for a limited time before going back to solo.
/// iOS ends an activity after about 8 hours; without push (which needs a
/// paid developer account) it comes back when the app opens, through the
/// "Summon Slime" control (Control Center, Action Button) or a
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
        // When a moment's or a phone event's time is up the activity goes
        // stale and the island draws the everyday state, even if the app is
        // suspended by then.
        let slime = GameStore.shared.slime
        let eventEnds = state.event == nil ? nil : slime.eventAt.addingTimeInterval(Slime.eventWindow)
        let stale = [moment?.until, eventEnds].compactMap { $0 }.min()
        return ActivityContent(state: state, staleDate: stale, relevanceScore: moment == nil ? 50 : 100)
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
            problem = "Live Activities are off for Slimy. Turn them on in Settings → Slimy → Live Activities."
            note("Start cancelled: Live Activities are off")
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
            note("Started: \(activity.id.prefix(6))")
            watch(activity)
        } catch {
            problem = "The island didn't start: \(error.localizedDescription). Open Slimy and try again."
            note("Start failed: \(error)")
        }
        refreshStatus()
    }

    func stop() async {
        keepAlive = false
        note("Stopped by you")
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
                self?.note("Activity \(activity.id.prefix(6)): \(state)")
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

    /// Now and then a friend's slime drops by and the two meet in the
    /// island. Called whenever the app runs (opening, background refresh,
    /// island buttons); at most every two hours and five times a day.
    func maybeMeetup(now: Date = .now) async {
        let friends = FriendsStore.shared.islandFriends
        guard moment == nil, !friends.isEmpty, current != nil,
              !GameStore.shared.slime.isAsleep else { return }
        let last = defaults.object(forKey: "slimy.island.lastMeetup") as? Date ?? .distantPast
        let today = Calendar.current.isDate(last, inSameDayAs: now)
        let count = today ? defaults.integer(forKey: "slimy.island.meetupsToday") : 0
        guard now.timeIntervalSince(last) > 2 * 3600, count < 5, Double.random(in: 0..<1) < 0.5 else { return }
        defaults.set(now, forKey: "slimy.island.lastMeetup")
        defaults.set(count + 1, forKey: "slimy.island.meetupsToday")
        // Mostly the partner, sometimes another friend.
        let friend = Double.random(in: 0..<1) < 0.7 ? friends[0] : friends.randomElement()!
        note("Meetup with \(friend.name)")
        await show(.meetup, with: friend.id)
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
