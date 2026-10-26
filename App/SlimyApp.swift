import ActivityKit
import BackgroundTasks
import SwiftUI

@main
struct SlimyApp: App {
    @StateObject private var store = GameStore.shared
    @StateObject private var island = IslandController.shared
    @StateObject private var friends = FriendsStore.shared
    @StateObject private var chat = ChatStore.shared
    @StateObject private var daily = DailyStore.shared
    @Environment(\.scenePhase) private var phase

    init() {
        // Background tasks must be registered before launch finishes.
        PhoneMonitor.registerPowerTask()
        IslandFonts.registerInBackground()
        AppFonts.register()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(island)
                .environmentObject(friends)
                .environmentObject(chat)
                .environmentObject(daily)
                .tint(Theme.accent)
                .fontDesign(.rounded)
        }
        .onChange(of: phase) { _, phase in
            switch phase {
            case .active:
                becameActive()
            case .background:
                Feedback.shared.updateMusic(active: false)
                BackgroundRefresh.schedule()
                PhoneMonitor.schedulePowerCheck()
            default:
                break
            }
        }
        .backgroundTask(.appRefresh(BackgroundRefresh.id)) {
            await BackgroundRefresh.run()
        }
    }

    /// The app is on screen: the one time iOS lets it start or renew the
    /// Live Activity on its own.
    private func becameActive() {
        store.arrive()
        Feedback.shared.updateMusic(active: true)
        Notifier.reschedule(for: store.state)
        if ScreenshotMode.isActive {
            // A still, offline demo: no phone reactions, island or friends.
            Task { await daily.refresh() }
            return
        }
        PhoneMonitor.shared.start()
        Task {
            if store.state.hatched { await island.ensureRunning() }
            await friends.refresh()
            await daily.refresh()
            await island.maybeMeetup()
        }
    }
}

/// Every ~30 minutes (when iOS allows it; never guaranteed): age the slime,
/// check friends, update the island, and let the slime say once that it
/// needs something. iOS doesn't let a background run start a Live Activity,
/// so this only updates the one that is running; opening the app or "Summon
/// Slime" brings it back after iOS has ended it.
enum BackgroundRefresh {
    static let id = "de.hyprnode.slimy.refresh"

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: id)
        request.earliestBeginDate = .now.addingTimeInterval(30 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    @MainActor
    static func run() async {
        schedule()
        let store = GameStore.shared
        guard store.state.hatched else { return }
        store.tick()
        await PhoneMonitor.shared.checkInBackground()
        await FriendsStore.shared.refresh()
        var nudge: (need: Need, line: String)?
        store.update { nudge = $0.nudge(now: .now) }
        let island = IslandController.shared
        await island.maybeMeetup()
        if let nudge {
            // Speaks up with a sound only if need reminders are on, outside
            // quiet hours and within the day's limit; otherwise the line
            // just shows in the island.
            let alert = AlertConfiguration(title: "\(store.slime.name)", body: "\(nudge.line)", sound: .default)
            await island.sync(alert: alert, category: .needs)
        } else {
            await island.sync()
        }
        Notifier.reschedule(for: store.state)
    }
}
