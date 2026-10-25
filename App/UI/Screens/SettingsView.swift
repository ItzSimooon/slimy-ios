import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var island: IslandController
    @Environment(\.dismiss) private var dismiss
    @State private var confirmRelease = false

    var body: some View {
        NavigationStack {
            ZStack {
                MenuBackdrop()
                ScrollView {
                    VStack(spacing: 14) {
                        MenuHeader(title: "Einstellungen") { dismiss() }
                        soundSection
                        notificationSection
                        sleepSection
                        islandSection
                        eventsSection
                        aboutSection
                        resetSection
                    }
                    .padding(16)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
        }
        .tint(Theme.accent)
    }

    private func toggle(_ title: String, _ value: Bool, _ set: @escaping (Bool) -> Void) -> some View {
        Toggle(isOn: Binding(get: { value }, set: { set($0) })) {
            Text(title).font(Theme.label(15)).foregroundStyle(Theme.ink)
        }
    }

    private func note(_ text: String) -> some View {
        Text(text).font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
    }

    private func hourStepper(_ title: String, _ value: Int, _ set: @escaping (Int) -> Void) -> some View {
        Stepper(value: Binding(get: { value }, set: { set(($0 + 24) % 24) }), in: -1...24) {
            HStack {
                Text(title).font(Theme.label(15)).foregroundStyle(Theme.ink)
                Spacer()
                Text(String(format: "%02d:00", value)).font(Theme.label(15)).foregroundStyle(Theme.inkSoft).monospacedDigit()
            }
        }
    }

    // MARK: Sections

    private var soundSection: some View {
        let s = store.state.settings
        return PixelWindow(title: "Ton und Gefühl") {
            toggle("Geräusche", s.sound) { v in store.setSettings { $0.sound = v } }
            toggle("Musik", s.music) { v in
                store.setSettings { $0.music = v }
                Feedback.shared.updateMusic()
            }
            toggle("Vibration", s.haptics) { v in store.setSettings { $0.haptics = v } }
            note("Geräusche folgen dem Stummschalter deines iPhones.")
        }
    }

    private var notificationSection: some View {
        let n = store.state.settings.notifications
        return PixelWindow(title: "Mitteilungen") {
            toggle("Wenn er etwas braucht", n.needs) { v in store.setSettings { $0.notifications.needs = v } }
            toggle("Neue Tagesaufgaben", n.daily) { v in store.setSettings { $0.notifications.daily = v } }
            toggle("Freunde", n.friends) { v in store.setSettings { $0.notifications.friends = v } }
            toggle("Geschenke", n.gifts) { v in store.setSettings { $0.notifications.gifts = v } }
            toggle("Kleine Grüße", n.world) { v in store.setSettings { $0.notifications.world = v } }
            hourStepper("Ruhe ab", n.quietFrom) { v in store.setSettings { $0.notifications.quietFrom = v } }
            hourStepper("Ruhe bis", n.quietUntil) { v in store.setSettings { $0.notifications.quietUntil = v } }
            note("Höchstens \(Notifier.dailyLimit) am Tag. In der Ruhezeit kommt nichts.")
            if !n.asked {
                PixelButton(title: "Mitteilungen erlauben", kind: .secondary) {
                    Task { await Notifier.requestPermission() }
                }
            }
        }
        .onChange(of: store.state.settings) { _, _ in Notifier.reschedule(for: store.state) }
    }

    private var sleepSection: some View {
        let sleep = store.state.settings.sleep
        return PixelWindow(title: "Schlafenszeit") {
            toggle("Geht von selbst ins Bett", sleep.auto) { v in store.setSettings { $0.sleep.auto = v } }
            hourStepper("Bettzeit", sleep.bedtime) { v in store.setSettings { $0.sleep.bedtime = v } }
            hourStepper("Aufstehen", sleep.wake) { v in store.setSettings { $0.sleep.wake = v } }
            note("Du kannst ihn trotzdem jederzeit wecken oder ins Bett bringen.")
        }
    }

    private var islandSection: some View {
        PixelWindow(title: "Dynamic Island") {
            toggle("Immer zurückholen", island.keepAlive) { island.keepAlive = $0 }
            toggle("Animation", island.animated) { island.animated = $0 }
            note("iOS beendet Live-Aktivitäten nach etwa 8 Stunden. Slimy holt den Slime zurück, wenn du die App öffnest. Für den ganzen Tag: „Slime holen“ ins Kontrollzentrum legen oder Automationen einrichten (unten).")
            HStack(spacing: 10) {
                PixelButton(title: island.isLive ? "Neu starten" : "Starten", compact: true) {
                    Task { await island.ensureRunning(restart: true) }
                }
                if island.isLive {
                    PixelButton(title: "Ausblenden", kind: .quiet, compact: true) {
                        Task { await island.stop() }
                    }
                }
            }
            NavigationLink { DiagnosticsView() } label: { linkLabel("Diagnose", icon: "gear") }
                .buttonStyle(.plain)
        }
    }

    private var eventsSection: some View {
        PixelWindow(title: "Reaktionen aufs iPhone") {
            toggle("Reagieren, solange die App offen ist", store.state.settings.liveEvents) { v in
                store.setSettings { $0.liveEvents = v }
            }
            note("Akku, Ladekabel, WLAN, mobile Daten, kein Internet, Stromsparmodus und Uhrzeit.")
            NavigationLink { EventsGuideView() } label: { linkLabel("Reagieren, wenn die App zu ist", icon: "speech") }
                .buttonStyle(.plain)
        }
    }

    private var aboutSection: some View {
        PixelWindow(title: "Über Slimy") {
            HStack {
                Text("Version").font(Theme.label(15)).foregroundStyle(Theme.ink)
                Spacer()
                Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                    .font(Theme.label(15)).foregroundStyle(Theme.inkSoft)
            }
            note("Slimy ist von hyprnode · slime.hyprnode.de\nPixel-Schrift: Pixelify Sans (SIL Open Font License). Grafiken und Töne sind für Slimy gemacht.")
        }
    }

    private var resetSection: some View {
        PixelWindow(title: "Neu anfangen") {
            if confirmRelease {
                note("\(store.state.slime.name) ist dann für immer weg, mit Levels, Münzen und Sachen. Freundschaften bleiben auf dem Server. Das lässt sich nicht rückgängig machen.")
                HStack(spacing: 10) {
                    PixelButton(title: "Behalten", kind: .secondary) { confirmRelease = false }
                    PixelButton(title: "Freilassen", kind: .danger) {
                        dismiss()
                        store.releaseSlime()
                    }
                }
            } else {
                PixelButton(title: "\(store.state.slime.name) freilassen …", kind: .quiet) { confirmRelease = true }
            }
        }
    }

    private func linkLabel(_ title: String, icon: String) -> some View {
        HStack {
            PixelItem(name: icon).frame(width: 20, height: 20)
            Text(title).font(Theme.label(15)).foregroundStyle(Theme.ink)
            Spacer()
            Image(systemName: "chevron.right").foregroundStyle(Theme.inkSoft)
        }
        .padding(10)
        .background(NotchedRect().fill(Theme.paperShade))
        .overlay(NotchedRect().strokeBorder(Theme.border, lineWidth: 2))
    }
}

/// How to make the slime react while the app is closed, with Shortcuts.
struct EventsGuideView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss

    private let groups: [(String, [PhoneEvent])] = [
        ("Akku und Strom", [.batteryLow, .batteryCritical, .batteryFull, .chargerConnected, .chargerDisconnected, .lowPowerOn, .lowPowerOff]),
        ("Netz", [.wifiConnected, .wifiDisconnected, .cellular, .airplaneOn, .airplaneOff]),
        ("Zeit", [.goodMorning, .goodNight, .alarm, .newHour]),
    ]

    var body: some View {
        ZStack {
            MenuBackdrop()
            ScrollView {
                VStack(spacing: 14) {
                    MenuHeader(title: "Wenn die App zu ist") { dismiss() }
                    PixelWindow(title: "Einmal pro Ereignis einrichten") {
                        step(1, "Öffne Kurzbefehle, geh auf Automation und tippe auf +.")
                        step(2, "Wähl den Auslöser, der unten beim Ereignis steht.")
                        step(3, "Stell „Sofort ausführen“ ein und schalt „Bei Ausführung mitteilen“ aus.")
                        step(4, "Füg die Aktion „Slime reagieren“ von Slimy hinzu und wähl das Ereignis.")
                        step(5, "Optional: Häng „Text sprechen“ an, dann sagt er es laut.")
                        Link(destination: URL(string: "shortcuts://")!) {
                            Text("Kurzbefehle öffnen").font(Theme.label(15)).foregroundStyle(Theme.accentInk)
                        }
                    }
                    PixelWindow(title: "Den ganzen Tag in der Insel") {
                        Text("Leg drei Automationen mit „Tageszeit“ an, zum Beispiel 08:00, 15:00 und 22:00, jeweils mit „Slime holen“. Jede gibt ihm wieder 8 Stunden in der Insel. „Slime holen“ gibt es auch im Kontrollzentrum und für den Action Button.")
                            .font(Theme.text(14)).foregroundStyle(Theme.ink)
                    }
                    ForEach(groups, id: \.0) { title, events in
                        PixelWindow(title: title) {
                            ForEach(events) { event in
                                HStack(spacing: 10) {
                                    Image(systemName: event.symbol).frame(width: 24).foregroundStyle(Theme.accentInk)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(event.title).font(Theme.label(15)).foregroundStyle(Theme.ink)
                                        Text(event.shortcutTrigger).font(Theme.text(12)).foregroundStyle(Theme.inkSoft)
                                    }
                                    Spacer()
                                    PixelButton(title: "Testen", kind: .secondary, compact: true) {
                                        let said = store.handle(event)
                                        store.toast = Toast(text: said, good: true)
                                        Task { await IslandController.shared.sync() }
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(16)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text("\(n)").font(Theme.label(13)).foregroundStyle(.white)
                .frame(width: 22, height: 22).background(Theme.accent)
            Text(text).font(Theme.text(14)).foregroundStyle(Theme.ink)
        }
    }
}
