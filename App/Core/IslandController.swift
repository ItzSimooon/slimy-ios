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

    func start() async {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            problem = "Live Activities are off for Slimy. Turn them on in Settings → Slimy → Live Activities."
            refreshStatus()
            return
        }
        for activity in Activity<SlimeActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
        do {
            _ = try Activity.request(attributes: SlimeActivityAttributes(), content: content, pushType: nil)
            startedAt = .now
            problem = nil
        } catch {
            problem = "The island didn't start: \(error.localizedDescription). Open Slimy and try again."
        }
        refreshStatus()
    }

    func stop() async {
        keepAlive = false
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
