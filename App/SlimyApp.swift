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
        PhoneMonitor.registerPowerTask()
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
                    await island.maybeMeetup()
                }
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
        await PhoneMonitor.shared.checkInBackground()
        await FriendsStore.shared.refresh()
        var nudge: (need: Need, line: String)?
        store.update { nudge = $0.nudge(now: .now) }
        await IslandController.shared.ensureRunning()
        await IslandController.shared.maybeMeetup()
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
            phrases: ["Summon my slime in \(.applicationName)", "Bring back \(.applicationName)"],
            shortTitle: "Summon Slime",
            systemImageName: "drop.fill")
        AppShortcut(
            intent: FeedSlimeIntent(),
            phrases: ["Feed my slime in \(.applicationName)"],
            shortTitle: "Feed Slime",
            systemImageName: "fork.knife")
        AppShortcut(
            intent: PetSlimeIntent(),
            phrases: ["Pet my slime in \(.applicationName)"],
            shortTitle: "Pet Slime",
            systemImageName: "hand.raised.fill")
        AppShortcut(
            intent: SleepSlimeIntent(),
            phrases: ["Put my slime to bed in \(.applicationName)"],
            shortTitle: "Bedtime",
            systemImageName: "moon.fill")
    }
}
