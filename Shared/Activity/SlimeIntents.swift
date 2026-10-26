import ActivityKit
import AppIntents
import Foundation

// These intents are compiled into both targets so the island's buttons, the
// interactive widgets, Control Center and App Shortcuts can reference them.
// As LiveActivityIntents they always run in the app's process, which is the
// only place the slime's state lives (SLIME_APP is set there). Every run is
// also a chance to notice what the phone did meanwhile.

#if SLIME_APP
@MainActor
private func noticePhone() async {
    await PhoneMonitor.shared.checkInBackground()
}
#endif

struct FeedSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Feed Slime"
    static let description = IntentDescription("Feeds your slime its favourite food if you can afford it, otherwise an apple.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        _ = await MainActor.run { GameStore.shared.quickFeed() }
        await noticePhone()
        await IslandController.shared.settle()
        #endif
        return .result()
    }
}

struct PlaySlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Play with Slime"
    static let description = IntentDescription("Plays a quick round with your slime for some fun, without opening the arcade.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        await MainActor.run { GameStore.shared.quickPlay() }
        await noticePhone()
        await IslandController.shared.settle()
        #endif
        return .result()
    }
}

struct BathSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Bathe Slime"
    static let description = IntentDescription("Gives your slime a bubble bath.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        _ = await MainActor.run { GameStore.shared.bath() }
        await noticePhone()
        await IslandController.shared.settle()
        #endif
        return .result()
    }
}

struct PetSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Pet Slime"
    static let description = IntentDescription("Pets your slime and gives it a cuddle.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        _ = await MainActor.run { GameStore.shared.pet() }
        await noticePhone()
        await IslandController.shared.settle()
        #endif
        return .result()
    }
}

struct SleepSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Put Slime to Bed"
    static let description = IntentDescription("Puts your slime to bed, or wakes it up if it's asleep.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        _ = await MainActor.run { GameStore.shared.toggleSleep() }
        await noticePhone()
        await IslandController.shared.settle()
        #endif
        return .result()
    }
}

/// Brings the slime back to the Dynamic Island, from Control Center or the
/// Action Button.
struct SummonSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Summon Slime"
    static let description = IntentDescription("Brings your slime into the Dynamic Island, or gives it another 8 hours there.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        await IslandController.shared.ensureRunning(restart: true)
        await noticePhone()
        #endif
        return .result()
    }
}
