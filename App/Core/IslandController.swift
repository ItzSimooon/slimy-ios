import ActivityKit
import Foundation
import UIKit
import os

/// Starts, updates and revives the one Live Activity.
///
/// Policy: there is only ever one Slimy activity. It shows your slime solo,
/// with up to three friends beside it, and switches to a shared moment
/// (reunion, hug, gift …) for a limited time before going back to solo.
///
/// iOS ends an activity after about 8 hours and only lets an app start one
/// while it is on screen or from a Live Activity intent: the island's
/// buttons, "Summon Slime" in Control Center or on the Action Button, Siri
/// and Shortcuts. Those are the moments the activity is started or renewed;
/// background runs only update the activity that is already there.
///
/// Alerts (the island opening with a sound, a banner on iPhones without a
/// Dynamic Island) go through `Notifier.mayInterrupt`, so quiet hours, the
/// notification switches and the daily limit apply to them as well.
@MainActor
final class IslandController: ObservableObject {
    /// Where this device shows the slime's Live Activity.
    enum Presence: Equatable {
        /// In the Dynamic Island and on the Lock Screen.
        case dynamicIsland
        /// iPhones without a Dynamic Island: on the Lock Screen, and as a
        /// banner when the slime has news.
        case lockScreen
        /// iPad and Mac: no Live Activities.
        case unavailable

        /// The place, for headings: "Dynamic Island", "Lock Screen".
        var title: String {
            switch self {
            case .dynamicIsland: "Dynamic Island"
            case .lockScreen: "Lock Screen"
            case .unavailable: "Live Activity"
            }
        }

        /// One sentence about where the slime appears outside the app.
        var explanation: String {
            switch self {
            case .dynamicIsland:
                "Your slime lives in the Dynamic Island and on the Lock Screen, even when Slimy is closed."
            case .lockScreen:
                "Your slime lives on the Lock Screen and pops up as a banner when it has news."
            case .unavailable:
                "Live Activities aren't available on this device, so your slime stays here in the app."
            }
        }
    }

    static let shared = IslandController()

    @Published private(set) var isLive = false
    @Published var problem: String?

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

    /// Where this device shows Live Activities.
    var presence: Presence {
        let device = UIDevice.current
        if ProcessInfo.processInfo.isiOSAppOnMac || device.userInterfaceIdiom == .pad || device.model.hasPrefix("iPad") {
            return .unavailable
        }
        return Self.hasDynamicIsland ? .dynamicIsland : .lockScreen
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
            animated: animated)
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

    /// Lifecycle events go to the system log (debug level, not kept on
    /// disk), so Console.app can follow the activity without any UI.
    func note(_ line: String) {
        Self.logger.debug("\(line, privacy: .public)")
    }

    private static let logger = Logger(subsystem: "de.hyprnode.slimy", category: "island")

    // MARK: Lifecycle

    /// Starts a fresh activity. Only call it where iOS allows starting one:
    /// with the app on screen, or from a LiveActivityIntent.
    func start() async {
        guard GameStore.shared.state.hatched else { return }
        let place = self.presence
        guard place != .unavailable else {
            problem = place.explanation
            note("Start skipped: this device has no Live Activities")
            refreshStatus()
            return
        }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            problem = "Live Activities are turned off for Slimy. Turn them on in the Settings app under Apps > Slimy > Live Activities."
            note("Start cancelled: Live Activities are off")
            refreshStatus()
            return
        }
        // Never more than one. The old activity goes only once the new one
        // is there, so a refused start doesn't leave the slime with no home.
        let previous = Activity<SlimeActivityAttributes>.activities
        do {
            let activity = try Activity.request(attributes: SlimeActivityAttributes(), content: content(), pushType: nil)
            for old in previous where old.id != activity.id {
                await old.end(nil, dismissalPolicy: .immediate)
            }
            startedAt = .now
            problem = nil
            note("Started: \(activity.id.prefix(6))")
            watch(activity)
        } catch {
            problem = "Your slime couldn't move to the \(place.title) just now. Open Slimy and try again."
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

    /// Makes sure the slime is out there, renewing the activity before iOS's
    /// 8-hour limit. Starts one if needed, so call it only from the
    /// foreground or a LiveActivityIntent; background runs use `sync()`.
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

    /// Updates the activity. An `alert` opens the island with a sound (a
    /// banner without a Dynamic Island), but only while the app isn't on
    /// screen and only if `Notifier` allows an interruption of `category`
    /// right now; otherwise the same update arrives silently.
    func sync(alert: AlertConfiguration? = nil, category: Notifier.Category? = nil) async {
        guard let activity = current else { return }
        if let alert, UIApplication.shared.applicationState != .active,
           Notifier.mayInterrupt(category, state: GameStore.shared.state) {
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
        await sync(alert: alert.map { AlertConfiguration(title: "\(name)", body: "\($0)", sound: .default) },
                   category: .friends)
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
        let friend = Double.random(in: 0..<1) < 0.7 ? friends[0] : friends.randomElement() ?? friends[0]
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

    /// For intents and live reactions: waits until the reaction has played,
    /// then settles the island, so it doesn't stay mid-reaction if iOS
    /// suspends the app. These runs may start activities, so an activity
    /// close to iOS's 8-hour limit is renewed here too.
    func settle() async {
        let slime = GameStore.shared.slime
        if slime.activeReaction(at: .now) != .none {
            let left = Slime.reactionWindow - Date.now.timeIntervalSince(slime.reactionAt)
            try? await Task.sleep(for: .seconds(max(0, left) + 0.3))
            await sync()
        }
        await renewIfOld()
    }

    private func renewIfOld() async {
        guard keepAlive, current != nil, let startedAt,
              Date.now.timeIntervalSince(startedAt) > 7 * 3600 else { return }
        note("Renewing before iOS's 8-hour limit")
        await start()
    }

    // MARK: Device

    /// Whether this iPhone has a Dynamic Island. There is no API for it, so
    /// it goes by model: every iPhone from the 14 Pro on (model 15,x) has
    /// one, except the iPhone 16e (17,5). Models newer than this list are
    /// judged by the top safe area, which is 59 points or more with a
    /// Dynamic Island and at most 50 with a notch.
    private static var hasDynamicIsland: Bool {
        let model = modelIdentifier
        let numbers = model.dropFirst("iPhone".count).split(separator: ",").compactMap { Int($0) }
        guard model.hasPrefix("iPhone"), let major = numbers.first else { return topInset.map { $0 >= 55 } ?? false }
        if major < 15 || model == "iPhone17,5" { return false }
        if major <= 17 { return true }
        return topInset.map { $0 >= 55 } ?? true
    }

    private static var modelIdentifier: String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return simulated }
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    private static var topInset: CGFloat? {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
        guard let top = (windows.first(where: { $0.isKeyWindow }) ?? windows.first)?.safeAreaInsets.top, top > 0 else { return nil }
        return top
    }
}
