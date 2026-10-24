import AppIntents
import BackgroundTasks
import SwiftUI

@main
struct SlimyApp: App {
    @StateObject private var slime = SlimeStore.shared
    @StateObject private var island = IslandController.shared
    @StateObject private var friends = FriendsStore.shared
    @StateObject private var chat = ChatStore.shared
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(slime)
                .environmentObject(island)
                .environmentObject(friends)
                .environmentObject(chat)
                .preferredColorScheme(.dark)
                .tint(Brand.hypr)
        }
        .onChange(of: phase) { _, phase in
            switch phase {
            case .active:
                slime.tick()
                PhoneMonitor.shared.start()
                Notifier.reschedule(for: slime.state)
                Task {
                    if slime.hasHatched { await island.ensureRunning() }
                    await friends.refresh()
                }
            case .background:
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

/// Every ~30 minutes (when iOS allows it): age the slime, refresh friends,
/// and nudge the island, popping it open if the slime just got sad.
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
        let store = SlimeStore.shared
        let moodBefore = store.state.mood
        store.tick()
        await FriendsStore.shared.refresh()
        let s = store.state
        if s.mood == .sad, moodBefore != .sad, let wish = s.wish {
            await IslandController.shared.sync(alert: .init(title: "\(s.name)", body: "\(wish.wish)", sound: .default))
        } else {
            await IslandController.shared.ensureRunning()
        }
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
            shortTitle: "Feed",
            systemImageName: "fork.knife")
        AppShortcut(
            intent: PetSlimeIntent(),
            phrases: ["Pet my slime in \(.applicationName)"],
            shortTitle: "Pet",
            systemImageName: "hand.raised.fill")
        AppShortcut(
            intent: SleepSlimeIntent(),
            phrases: ["Put my slime to bed in \(.applicationName)"],
            shortTitle: "Bedtime",
            systemImageName: "moon.fill")
        AppShortcut(
            intent: SlimeReactsIntent(),
            phrases: ["Tell my slime in \(.applicationName)"],
            shortTitle: "Slime Reacts",
            systemImageName: "sparkles")
    }
}
