import ActivityKit
import AppIntents
import Foundation

// These intents are compiled into both targets so the island's buttons can
// reference them. As LiveActivityIntents they always run in the app's process,
// which is the only place the slime's state lives (SLIME_APP is set there).

struct FeedSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Slime füttern"
    static let description = IntentDescription("Füttert deinen Slime mit dem besten Essen, das du dir leisten kannst.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        _ = await MainActor.run { GameStore.shared.quickFeed() }
        await IslandController.shared.settle()
        #endif
        return .result()
    }
}

struct PlaySlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Mit Slime spielen"
    static let description = IntentDescription("Eine kurze Hüpfrunde mit deinem Slime.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        await MainActor.run { GameStore.shared.quickPlay() }
        await IslandController.shared.settle()
        #endif
        return .result()
    }
}

struct BathSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Slime baden"
    static let description = IntentDescription("Schaumbad für deinen Slime.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        _ = await MainActor.run { GameStore.shared.bath() }
        await IslandController.shared.settle()
        #endif
        return .result()
    }
}

struct PetSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Slime kraulen"
    static let description = IntentDescription("Ein bisschen Liebe für deinen Slime.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        _ = await MainActor.run { GameStore.shared.pet() }
        await IslandController.shared.settle()
        #endif
        return .result()
    }
}

struct SleepSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Slime ins Bett bringen"
    static let description = IntentDescription("Bringt deinen Slime ins Bett oder weckt ihn, wenn er schläft.")

    func perform() async throws -> some IntentResult {
        #if SLIME_APP
        _ = await MainActor.run { GameStore.shared.toggleSleep() }
        await IslandController.shared.settle()
        #endif
        return .result()
    }
}

/// Brings the slime back to the Dynamic Island. Use it from Control Center,
/// the Action Button or a Shortcuts automation to keep it always on.
struct SummonSlimeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Slime holen"
    static let description = IntentDescription("Holt deinen Slime in die Dynamic Island oder gibt ihm dort wieder 8 Stunden.")

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
    static let title: LocalizedStringResource = "Slime reagieren"
    static let description = IntentDescription("Sagt deinem Slime, dass am iPhone etwas passiert ist. Er reagiert in der Dynamic Island und gibt zurück, was er gesagt hat.")

    @Parameter(title: "Ereignis")
    var event: PhoneEvent

    init() {}

    init(event: PhoneEvent) {
        self.event = event
    }

    static var parameterSummary: some ParameterSummary {
        Summary("Slime reagiert auf \(\.$event)")
    }

    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        #if SLIME_APP
        let (said, name) = await MainActor.run { (GameStore.shared.handle(event), GameStore.shared.slime.name) }
        if event.alerts {
            await IslandController.shared.sync(alert: .init(title: "\(name)", body: "\(said)", sound: .default))
        }
        await IslandController.shared.settle()
        return .result(value: said)
        #else
        return .result(value: "")
        #endif
    }
}

extension PhoneEvent: AppEnum {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "iPhone-Ereignis"

    static let caseDisplayRepresentations: [PhoneEvent: DisplayRepresentation] = [
        .batteryLow: DisplayRepresentation(title: "Akku niedrig", image: .init(systemName: "battery.25percent")),
        .batteryCritical: DisplayRepresentation(title: "Akku fast leer", image: .init(systemName: "battery.0percent")),
        .batteryFull: DisplayRepresentation(title: "Akku voll", image: .init(systemName: "battery.100percent")),
        .chargerConnected: DisplayRepresentation(title: "Ladekabel dran", image: .init(systemName: "bolt.fill")),
        .chargerDisconnected: DisplayRepresentation(title: "Ladekabel ab", image: .init(systemName: "powerplug")),
        .wifiConnected: DisplayRepresentation(title: "WLAN verbunden", image: .init(systemName: "wifi")),
        .wifiDisconnected: DisplayRepresentation(title: "WLAN getrennt", image: .init(systemName: "wifi.slash")),
        .cellular: DisplayRepresentation(title: "Mobile Daten", image: .init(systemName: "antenna.radiowaves.left.and.right")),
        .offline: DisplayRepresentation(title: "Kein Internet", image: .init(systemName: "icloud.slash")),
        .backOnline: DisplayRepresentation(title: "Wieder online", image: .init(systemName: "checkmark.icloud")),
        .airplaneOn: DisplayRepresentation(title: "Flugmodus an", image: .init(systemName: "airplane")),
        .airplaneOff: DisplayRepresentation(title: "Flugmodus aus", image: .init(systemName: "airplane")),
        .lowPowerOn: DisplayRepresentation(title: "Stromsparen an", image: .init(systemName: "leaf.fill")),
        .lowPowerOff: DisplayRepresentation(title: "Stromsparen aus", image: .init(systemName: "leaf.fill")),
        .goodMorning: DisplayRepresentation(title: "Guten Morgen", image: .init(systemName: "sun.max.fill")),
        .goodNight: DisplayRepresentation(title: "Gute Nacht", image: .init(systemName: "moon.stars.fill")),
        .alarm: DisplayRepresentation(title: "Wecker", image: .init(systemName: "alarm.fill")),
        .newHour: DisplayRepresentation(title: "Neue Stunde", image: .init(systemName: "clock.fill")),
    ]
}
