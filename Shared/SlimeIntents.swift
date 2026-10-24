import ActivityKit
import AppIntents
import Foundation

// These intents are compiled into both targets so the island's buttons can
// reference them. As LiveActivityIntents they always run in the app's process,
// which is the only place the slime's state lives (SLIME_APP is set there).

struct FeedSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Feed Slime"
    static let description = IntentDescription("Feed your slime the best snack you can afford.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        await SlimeStore.shared.quickFeed()
        #endif
        return .result()
    }
}

struct PlaySlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Play with Slime"
    static let description = IntentDescription("A quick bounce session with your slime.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        await SlimeStore.shared.perform(.play(score: 0))
        #endif
        return .result()
    }
}

struct BathSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Give Slime a Bath"
    static let description = IntentDescription("Bubble bath for your slime.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        await SlimeStore.shared.perform(.bath)
        #endif
        return .result()
    }
}

struct PetSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Pet Slime"
    static let description = IntentDescription("Give your slime some love.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        await SlimeStore.shared.perform(.pet)
        #endif
        return .result()
    }
}

struct SleepSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Put Slime to Bed"
    static let description = IntentDescription("Tuck your slime in, or wake it up if it's sleeping.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        await SlimeStore.shared.toggleSleep()
        #endif
        return .result()
    }
}

/// Brings the slime back to the Dynamic Island. Use it from Control Center,
/// the Action Button or a Shortcuts automation to keep it always on.
struct SummonSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Summon Slime"
    static let description = IntentDescription("Put your slime in the Dynamic Island, or give it a fresh 8 hours there.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        await IslandController.shared.ensureRunning(restart: true)
        #endif
        return .result()
    }
}

/// Sent by Shortcuts automations (battery, charger, Wi-Fi, alarms...) so the
/// slime can react while the app is closed.
struct SlimeReactsIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Slime Reacts"
    static let description = IntentDescription("Tell your slime something happened on your phone. It reacts in the Dynamic Island and returns what it said.")

    @Parameter(title: "Event")
    var event: PhoneEvent

    init() {}

    init(event: PhoneEvent) {
        self.event = event
    }

    static var parameterSummary: some ParameterSummary {
        Summary("Slime reacts to \(\.$event)")
    }

    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        #if SLIME_APP
        let said = await SlimeStore.shared.handle(event)
        return .result(value: said)
        #else
        return .result(value: "")
        #endif
    }
}

extension PhoneEvent: AppEnum {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Phone Event"

    static let caseDisplayRepresentations: [PhoneEvent: DisplayRepresentation] = [
        .batteryLow: DisplayRepresentation(title: "Battery low", image: .init(systemName: "battery.25percent")),
        .batteryCritical: DisplayRepresentation(title: "Battery almost empty", image: .init(systemName: "battery.0percent")),
        .batteryFull: DisplayRepresentation(title: "Battery full", image: .init(systemName: "battery.100percent")),
        .chargerConnected: DisplayRepresentation(title: "Charger connected", image: .init(systemName: "bolt.fill")),
        .chargerDisconnected: DisplayRepresentation(title: "Charger disconnected", image: .init(systemName: "powerplug")),
        .wifiConnected: DisplayRepresentation(title: "Wi-Fi connected", image: .init(systemName: "wifi")),
        .wifiDisconnected: DisplayRepresentation(title: "Wi-Fi disconnected", image: .init(systemName: "wifi.slash")),
        .cellular: DisplayRepresentation(title: "Switched to mobile data", image: .init(systemName: "antenna.radiowaves.left.and.right")),
        .offline: DisplayRepresentation(title: "No internet", image: .init(systemName: "icloud.slash")),
        .backOnline: DisplayRepresentation(title: "Back online", image: .init(systemName: "checkmark.icloud")),
        .airplaneOn: DisplayRepresentation(title: "Airplane Mode on", image: .init(systemName: "airplane")),
        .airplaneOff: DisplayRepresentation(title: "Airplane Mode off", image: .init(systemName: "airplane")),
        .lowPowerOn: DisplayRepresentation(title: "Low Power Mode on", image: .init(systemName: "leaf.fill")),
        .lowPowerOff: DisplayRepresentation(title: "Low Power Mode off", image: .init(systemName: "leaf.fill")),
        .goodMorning: DisplayRepresentation(title: "Good morning", image: .init(systemName: "sun.max.fill")),
        .goodNight: DisplayRepresentation(title: "Good night", image: .init(systemName: "moon.stars.fill")),
        .alarm: DisplayRepresentation(title: "Alarm", image: .init(systemName: "alarm.fill")),
        .newHour: DisplayRepresentation(title: "New hour", image: .init(systemName: "clock.fill")),
    ]
}
