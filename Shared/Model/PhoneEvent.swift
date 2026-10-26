import Foundation

// MARK: - Phone events

/// Things the phone does that the slime reacts to, noticed by PhoneMonitor.
/// Airplane mode and alarms can't be observed by apps; those cases stay so
/// older saves still load.
enum PhoneEvent: String, Codable, CaseIterable, Identifiable, Hashable {
    case batteryLow, batteryCritical, batteryFull
    case chargerConnected, chargerDisconnected
    case wifiConnected, wifiDisconnected, cellular, offline, backOnline
    case airplaneOn, airplaneOff, lowPowerOn, lowPowerOff
    case goodMorning, goodNight, alarm, newHour

    var id: String { rawValue }

    var title: String {
        switch self {
        case .batteryLow: "Battery low"
        case .batteryCritical: "Battery almost empty"
        case .batteryFull: "Battery full"
        case .chargerConnected: "Charger plugged in"
        case .chargerDisconnected: "Charger unplugged"
        case .wifiConnected: "Wi-Fi connected"
        case .wifiDisconnected: "Wi-Fi disconnected"
        case .cellular: "Mobile data"
        case .offline: "No internet"
        case .backOnline: "Back online"
        case .airplaneOn: "Airplane mode on"
        case .airplaneOff: "Airplane mode off"
        case .lowPowerOn: "Low Power Mode on"
        case .lowPowerOff: "Low Power Mode off"
        case .goodMorning: "Good morning"
        case .goodNight: "Good night"
        case .alarm: "Alarm"
        case .newHour: "New hour"
        }
    }

    var symbol: String {
        switch self {
        case .batteryLow: "battery.25percent"
        case .batteryCritical: "battery.0percent"
        case .batteryFull: "battery.100percent"
        case .chargerConnected: "bolt.fill"
        case .chargerDisconnected: "powerplug"
        case .wifiConnected: "wifi"
        case .wifiDisconnected: "wifi.slash"
        case .cellular: "antenna.radiowaves.left.and.right"
        case .offline: "icloud.slash"
        case .backOnline: "checkmark.icloud"
        case .airplaneOn, .airplaneOff: "airplane"
        case .lowPowerOn, .lowPowerOff: "leaf.fill"
        case .goodMorning: "sun.max.fill"
        case .goodNight: "moon.stars.fill"
        case .alarm: "alarm.fill"
        case .newHour: "clock.fill"
        }
    }

    var reaction: Reaction {
        switch self {
        case .batteryLow, .batteryCritical: .melt
        case .batteryFull, .chargerConnected: .zap
        case .chargerDisconnected, .lowPowerOff, .airplaneOff, .backOnline: .wave
        case .wifiConnected: .play
        case .wifiDisconnected, .cellular, .offline, .airplaneOn: .confused
        case .lowPowerOn: .melt
        case .goodMorning, .alarm, .newHour: .wave
        case .goodNight: .love
        }
    }

    var wakesUp: Bool {
        switch self {
        case .batteryCritical, .goodMorning, .alarm: true
        default: false
        }
    }

    var effect: (need: Need, amount: Double)? {
        switch self {
        case .chargerConnected: (.energy, 6)
        case .batteryFull: (.fun, 5)
        case .wifiConnected: (.fun, 4)
        case .goodMorning: (.love, 4)
        default: nil
        }
    }

    /// How it makes the slime feel.
    var feeling: (emotion: Emotion, amount: Double)? {
        switch self {
        case .batteryLow: (.sadness, 0.1)
        case .batteryCritical: (.fear, 0.3)
        case .batteryFull: (.excitement, 0.2)
        case .chargerConnected: (.joy, 0.15)
        case .chargerDisconnected: (.curiosity, 0.1)
        case .wifiConnected: (.curiosity, 0.15)
        case .wifiDisconnected: (.fear, 0.1)
        case .cellular: (.curiosity, 0.1)
        case .offline: (.fear, 0.15)
        case .backOnline: (.joy, 0.15)
        case .airplaneOn: (.excitement, 0.2)
        case .airplaneOff: (.joy, 0.1)
        case .lowPowerOn: (.sleepiness, 0.15)
        case .lowPowerOff: (.excitement, 0.1)
        case .goodMorning: (.joy, 0.15)
        case .goodNight: (.love, 0.1)
        case .alarm: (.fear, 0.15)
        case .newHour: nil
        }
    }

    /// Whether the island should pop open with a sound.
    var alerts: Bool {
        switch self {
        case .batteryLow, .batteryCritical, .batteryFull, .offline, .goodMorning, .alarm: true
        default: false
        }
    }

    var lines: [String] {
        switch self {
        case .batteryLow: ["battery 20%. i'm melting", "getting all runny", "plug me in. please."]
        case .batteryCritical: ["5%. i'm a puddle", "this is it. tell everyone bye", "battery. almost. gone."]
        case .batteryFull: ["100%. i'm buzzing", "fully charged", "you can unplug now"]
        case .chargerConnected: ["power. yum.", "tingly", "*slurps electricity*"]
        case .chargerDisconnected: ["unplugged. free.", "bye cable", "let's go"]
        case .wifiConnected: ["wi-fi. zoom.", "hi router", "connected"]
        case .wifiDisconnected: ["where did the wi-fi go", "wi-fi gone", "hello? wi-fi?"]
        case .cellular: ["on mobile data now", "careful with the data", "over the air now"]
        case .offline: ["no internet", "offline. so quiet.", "where did everyone go"]
        case .backOnline: ["back online", "the internet is back", "signal!"]
        case .airplaneOn: ["airplane mode. are we flying?", "buckle up", "taking off"]
        case .airplaneOff: ["landed", "ground under my goo", "back on earth"]
        case .lowPowerOn: ["power saving. moving slowly", "saver mode", "only tiny wobbles now"]
        case .lowPowerOff: ["full power", "saver mode off", "i can wobble again"]
        case .goodMorning: ["good morning", "rise and shine", "new day. new goo."]
        case .goodNight: ["sleep well", "good night", "time for slime dreams"]
        case .alarm: ["ALARM. GET UP.", "the alarm. go.", "up you get"]
        case .newHour: ["another hour already", "tick. tock.", "hourly wobble check"]
        }
    }
}
