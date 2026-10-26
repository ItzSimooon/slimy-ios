import AppIntents

/// Siri, Spotlight, the Shortcuts app and the Action Button: everything the
/// island's buttons do, without opening the app. The intents live in
/// Shared/Activity/SlimeIntents.swift. Apple requires the app's name in
/// every phrase; "Feed my Slimy" reads naturally with this app's name.
struct SlimyShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: SummonSlimeIntent(),
            phrases: [
                "Summon my slime in \(.applicationName)",
                "Bring back my \(.applicationName)",
                "Show my slime in \(.applicationName)",
            ],
            shortTitle: "Summon Slime",
            systemImageName: "drop.fill")
        AppShortcut(
            intent: FeedSlimeIntent(),
            phrases: [
                "Feed my slime in \(.applicationName)",
                "Feed my \(.applicationName)",
            ],
            shortTitle: "Feed",
            systemImageName: "fork.knife")
        AppShortcut(
            intent: PetSlimeIntent(),
            phrases: [
                "Pet my slime in \(.applicationName)",
                "Pet my \(.applicationName)",
            ],
            shortTitle: "Pet",
            systemImageName: "heart.fill")
        AppShortcut(
            intent: PlaySlimeIntent(),
            phrases: [
                "Play with my slime in \(.applicationName)",
                "Play with my \(.applicationName)",
            ],
            shortTitle: "Play",
            systemImageName: "tennisball.fill")
        AppShortcut(
            intent: BathSlimeIntent(),
            phrases: [
                "Give my slime a bath in \(.applicationName)",
                "Bathe my \(.applicationName)",
            ],
            shortTitle: "Bath",
            systemImageName: "bubbles.and.sparkles.fill")
        AppShortcut(
            intent: SleepSlimeIntent(),
            phrases: [
                "Put my slime to bed in \(.applicationName)",
                "Bedtime in \(.applicationName)",
            ],
            shortTitle: "Bedtime",
            systemImageName: "moon.zzz.fill")
    }

    static var shortcutTileColor: ShortcutTileColor { .lime }
}
