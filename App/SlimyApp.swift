import AppIntents
import BackgroundTasks
import SwiftUI

@main
struct SlimyApp: App {
    @StateObject private var store = GameStore.shared
    @StateObject private var island = IslandController.shared
    @StateObject private var friends = FriendsStore.shared
    @StateObject private var chat = ChatStore.shared
    @Environment(\.scenePhase) private var phase

    init() {
        IslandFonts.register()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(island)
                .environmentObject(friends)
                .environmentObject(chat)
                .tint(Theme.accent)
        }
        .onChange(of: phase) { _, phase in
            switch phase {
            case .active:
                store.arrive()
                PhoneMonitor.shared.start()
                Feedback.shared.updateMusic(active: true)
                Notifier.reschedule(for: store.state)
                Task {
                    if store.state.hatched { await island.ensureRunning() }
                    await friends.refresh()
                }
            case .background:
                Feedback.shared.updateMusic(active: false)
                BackgroundRefresh.schedule()
            default:
                break
            }
        }
        .backgroundTask(.appRefresh(BackgroundRefresh.id)) {
            await BackgroundRefresh.run()
        }
    }
}

/// Every ~30 minutes (when iOS allows it; never guaranteed): age the slime,
/// check friends, keep the island alive, and let the slime say once that it
/// needs something.
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
        await FriendsStore.shared.refresh()
        var nudge: (need: Need, line: String)?
        store.update { nudge = $0.nudge(now: .now) }
        await IslandController.shared.ensureRunning()
        if let nudge {
            await IslandController.shared.sync(alert: .init(title: "\(store.slime.name)", body: "\(nudge.line)", sound: .default))
        }
        Notifier.reschedule(for: store.state)
    }
}

struct SlimyShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: SummonSlimeIntent(),
            phrases: ["Hol meinen Slime in \(.applicationName)", "\(.applicationName) zurückholen"],
            shortTitle: "Slime holen",
            systemImageName: "drop.fill")
        AppShortcut(
            intent: FeedSlimeIntent(),
            phrases: ["Füttere meinen Slime in \(.applicationName)"],
            shortTitle: "Slime füttern",
            systemImageName: "fork.knife")
        AppShortcut(
            intent: PetSlimeIntent(),
            phrases: ["Kraul meinen Slime in \(.applicationName)"],
            shortTitle: "Kraulen",
            systemImageName: "hand.raised.fill")
        AppShortcut(
            intent: SleepSlimeIntent(),
            phrases: ["Bring meinen Slime ins Bett in \(.applicationName)"],
            shortTitle: "Ins Bett",
            systemImageName: "moon.fill")
        AppShortcut(
            intent: SlimeReactsIntent(),
            phrases: ["Sag es meinem Slime in \(.applicationName)"],
            shortTitle: "Slime reagieren",
            systemImageName: "sparkles")
    }
}
