import Foundation

// MARK: - Phone events

/// Things the phone does that the slime reacts to. In the app these are
/// detected live; when the app is closed, Shortcuts automations send them.
enum PhoneEvent: String, Codable, CaseIterable, Identifiable, Hashable {
    case batteryLow, batteryCritical, batteryFull
    case chargerConnected, chargerDisconnected
    case wifiConnected, wifiDisconnected, cellular, offline, backOnline
    case airplaneOn, airplaneOff, lowPowerOn, lowPowerOff
    case goodMorning, goodNight, alarm, newHour

    var id: String { rawValue }

    var title: String {
        switch self {
        case .batteryLow: "Akku niedrig"
        case .batteryCritical: "Akku fast leer"
        case .batteryFull: "Akku voll"
        case .chargerConnected: "Ladekabel dran"
        case .chargerDisconnected: "Ladekabel ab"
        case .wifiConnected: "WLAN verbunden"
        case .wifiDisconnected: "WLAN getrennt"
        case .cellular: "Mobile Daten"
        case .offline: "Kein Internet"
        case .backOnline: "Wieder online"
        case .airplaneOn: "Flugmodus an"
        case .airplaneOff: "Flugmodus aus"
        case .lowPowerOn: "Stromsparen an"
        case .lowPowerOff: "Stromsparen aus"
        case .goodMorning: "Guten Morgen"
        case .goodNight: "Gute Nacht"
        case .alarm: "Wecker"
        case .newHour: "Neue Stunde"
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

    /// The Shortcuts automation trigger that sends this event.
    var shortcutTrigger: String {
        switch self {
        case .batteryLow: "Batterieladung · fällt unter 20 %"
        case .batteryCritical: "Batterieladung · fällt unter 5 %"
        case .batteryFull: "Batterieladung · ist gleich 100 %"
        case .chargerConnected: "Ladegerät · ist verbunden"
        case .chargerDisconnected: "Ladegerät · ist getrennt"
        case .wifiConnected: "WLAN · verbindet sich mit beliebigem Netzwerk"
        case .wifiDisconnected: "WLAN · trennt sich von beliebigem Netzwerk"
        case .cellular: "WLAN · trennt sich (statt „WLAN getrennt“)"
        case .offline: "erkennt die App selbst, solange sie offen ist"
        case .backOnline: "erkennt die App selbst, solange sie offen ist"
        case .airplaneOn: "Flugmodus · wird aktiviert"
        case .airplaneOff: "Flugmodus · wird deaktiviert"
        case .lowPowerOn: "Stromsparmodus · wird aktiviert"
        case .lowPowerOff: "Stromsparmodus · wird deaktiviert"
        case .goodMorning: "Tageszeit · Sonnenaufgang"
        case .goodNight: "Tageszeit · 22:00"
        case .alarm: "Wecker · wird gestoppt"
        case .newHour: "Tageszeit · zu jeder vollen Stunde, die du willst"
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

    /// Whether the island should pop open with a sound.
    var alerts: Bool {
        switch self {
        case .batteryLow, .batteryCritical, .batteryFull, .offline, .goodMorning, .alarm: true
        default: false
        }
    }

    var lines: [String] {
        switch self {
        case .batteryLow: ["akku 20 %. ich schmelze", "mir wird ganz flüssig", "steck mich an. bitte."]
        case .batteryCritical: ["5 %. ich bin eine pfütze", "das wars. sag allen tschüss", "akku. fast. leer."]
        case .batteryFull: ["100 %. ich summe", "voll geladen", "das kabel kann weg"]
        case .chargerConnected: ["strom. lecker.", "kribbelt", "*schlürft strom*"]
        case .chargerDisconnected: ["kabel ab. frei.", "tschüss kabel", "los gehts"]
        case .wifiConnected: ["wlan. schnell.", "hallo router", "verbunden"]
        case .wifiDisconnected: ["wo ist das wlan hin", "wlan weg", "hallo? wlan?"]
        case .cellular: ["jetzt mobile daten", "spar die daten", "über funk jetzt"]
        case .offline: ["kein internet", "offline. alles still.", "wo sind alle"]
        case .backOnline: ["wieder online", "internet ist zurück", "signal da"]
        case .airplaneOn: ["flugmodus. fliegen wir?", "anschnallen", "wir heben ab"]
        case .airplaneOff: ["gelandet", "boden unterm schleim", "zurück auf der erde"]
        case .lowPowerOn: ["stromsparen. ich beweg mich langsam", "sparmodus", "nur noch kleine wackler"]
        case .lowPowerOff: ["volle kraft", "sparmodus aus", "ich darf wieder wackeln"]
        case .goodMorning: ["guten morgen", "aufstehen", "neuer tag. neuer schleim."]
        case .goodNight: ["schlaf gut", "gute nacht", "zeit für schleimträume"]
        case .alarm: ["WECKER. AUFSTEHEN.", "der wecker. los.", "hoch mit dir"]
        case .newHour: ["schon wieder eine stunde", "tick. tack.", "stündlicher wackel-check"]
        }
    }
}
