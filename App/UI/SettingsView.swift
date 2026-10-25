import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: SlimeStore
    @EnvironmentObject private var island: IslandController
    @Environment(\.dismiss) private var dismiss

    @State private var liveEvents = PhoneMonitor.shared.enabled
    @State private var notifications = Notifier.enabled
    @State private var confirmRelease = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    SheetHeader(eyebrow: "einstellungen", title: "Slimy") { dismiss() }

                    section("dynamic island") {
                        Toggle("\(store.state.name) immer zurückholen", isOn: Binding(
                            get: { island.keepAlive },
                            set: { island.keepAlive = $0 }))
                        note("iOS beendet Live-Aktivitäten nach etwa 8 Stunden. Slimy holt den Slime jedes Mal zurück, wenn du die App öffnest. Damit er den ganzen Tag bleibt: „Slime holen“ ins Kontrollzentrum legen oder die Automationen unten einrichten.")
                        HStack(spacing: 10) {
                            PixelButton(title: island.isLive ? "Neu starten" : "Starten", symbol: "play.fill") {
                                Task { await island.ensureRunning(restart: true) }
                            }
                            if island.isLive {
                                PixelButton(title: "Ausblenden", tint: Brand.muted, filled: false) {
                                    Task { await island.stop() }
                                }
                            }
                        }
                        link("Diagnose", symbol: "stethoscope") { DiagnosticsView() }
                    }

                    section("reaktionen aufs iphone") {
                        Toggle("Reagieren, solange die App offen ist", isOn: $liveEvents)
                            .onChange(of: liveEvents) { _, on in PhoneMonitor.shared.enabled = on }
                        note("Akku, Ladekabel, WLAN, mobile Daten, kein Internet, Stromsparmodus und Uhrzeit.")
                        link("Reagieren, wenn die App zu ist", symbol: "wand.and.stars") { EventsGuideView() }
                    }

                    section("mitteilungen") {
                        Toggle("Bescheid sagen, wenn \(store.state.name) etwas braucht", isOn: $notifications)
                            .onChange(of: notifications) { _, on in
                                Notifier.enabled = on
                                Task {
                                    if on { await Notifier.requestPermission() }
                                    Notifier.reschedule(for: store.state)
                                }
                            }
                    }

                    section("über slimy") {
                        LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                        LabeledContent("Server", value: "slime.hyprnode.de")
                        note("Slimy ist von hyprnode. Pixel-Schrift: Pixelify Sans (SIL Open Font License).")
                    }

                    section("neu anfangen") {
                        if confirmRelease {
                            note("\(store.state.name) ist dann für immer weg, mit allen Levels und Münzen. Das lässt sich nicht rückgängig machen.")
                            HStack(spacing: 10) {
                                PixelButton(title: "Behalten", tint: Brand.text, filled: false) {
                                    confirmRelease = false
                                }
                                PixelButton(title: "Tschüss sagen", tint: Brand.danger) {
                                    dismiss()
                                    store.releaseSlime()
                                }
                            }
                        } else {
                            Button("\(store.state.name) freilassen …", role: .destructive) { confirmRelease = true }
                                .font(Brand.pixel(15))
                        }
                    }
                }
                .padding(18)
            }
            .toolbar(.hidden, for: .navigationBar)
            .background(Brand.bg)
        }
        .tint(Brand.slime)
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Eyebrow(text: title, color: Brand.slime)
            Panel {
                VStack(alignment: .leading, spacing: 12) {
                    content()
                }
                .font(Brand.pixel(15))
                .foregroundStyle(Brand.text)
            }
        }
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(Brand.body(14))
            .foregroundStyle(Brand.muted)
    }

    private func link<Destination: View>(_ title: String, symbol: String,
                                         @ViewBuilder destination: @escaping () -> Destination) -> some View {
        NavigationLink(destination: destination) {
            HStack {
                Image(systemName: symbol)
                Text(title).font(Brand.pixel(15))
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(Brand.muted)
            }
            .foregroundStyle(Brand.text)
            .padding(12)
            .background(Brand.raised)
            .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 2))
        }
        .buttonStyle(.plain)
    }
}

/// How to make the slime react while the app is closed, using Shortcuts.
struct EventsGuideView: View {
    @EnvironmentObject private var store: SlimeStore
    @Environment(\.dismiss) private var dismiss

    private let groups: [(String, [PhoneEvent])] = [
        ("akku und strom", [.batteryLow, .batteryCritical, .batteryFull, .chargerConnected, .chargerDisconnected, .lowPowerOn, .lowPowerOff]),
        ("netz", [.wifiConnected, .wifiDisconnected, .cellular, .airplaneOn, .airplaneOff]),
        ("zeit", [.goodMorning, .goodNight, .alarm, .newHour]),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Button { dismiss() } label: {
                    Label("Zurück", systemImage: "chevron.left").font(Brand.pixel(15))
                }
                .foregroundStyle(Brand.slime)

                Text("Reagieren, wenn die App zu ist")
                    .font(Brand.pixel(26))
                    .foregroundStyle(Brand.text)

                Panel {
                    VStack(alignment: .leading, spacing: 10) {
                        Eyebrow(text: "einmal pro ereignis einrichten", color: Brand.slime)
                        step(1, "Öffne Kurzbefehle, geh auf Automation und tippe auf +.")
                        step(2, "Wähl den Auslöser, der unten beim Ereignis steht.")
                        step(3, "Stell „Sofort ausführen“ ein und schalt „Bei Ausführung mitteilen“ aus.")
                        step(4, "Füg die Aktion „Slime reagiert“ von Slimy hinzu und wähl das Ereignis.")
                        step(5, "Optional: Häng „Text sprechen“ dran und nimm die Antwort vom Slime. Dann sagt er es laut.")
                        Link(destination: URL(string: "shortcuts://")!) {
                            Label("Kurzbefehle öffnen", systemImage: "arrow.up.forward.app.fill")
                                .font(Brand.pixel(15))
                                .foregroundStyle(Brand.slime)
                        }
                        .padding(.top, 4)
                    }
                }

                Panel {
                    VStack(alignment: .leading, spacing: 8) {
                        Eyebrow(text: "den ganzen tag in der insel", color: Brand.warm)
                        Text("Leg drei Automationen mit „Tageszeit“ an, zum Beispiel 08:00, 15:00 und 22:00, jeweils mit der Aktion „Slime holen“. Jede gibt dem Slime wieder 8 Stunden in der Insel. „Slime holen“ geht auch im Kontrollzentrum und auf dem Action Button.")
                            .font(Brand.body(15))
                            .foregroundStyle(Brand.text)
                    }
                }

                ForEach(groups, id: \.0) { title, events in
                    Eyebrow(text: title)
                    VStack(spacing: 6) {
                        ForEach(events) { event in
                            EventRow(event: event) {
                                Task { await store.handle(event) }
                            }
                        }
                    }
                }
            }
            .padding(18)
        }
        .background(Brand.bg)
        .toolbar(.hidden, for: .navigationBar)
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text("\(n)")
                .font(Brand.pixel(13))
                .foregroundStyle(Brand.bg)
                .frame(width: 20, height: 20)
                .background(Brand.slime)
            Text(text)
                .font(Brand.body(15))
                .foregroundStyle(Brand.text)
        }
    }
}

private struct EventRow: View {
    let event: PhoneEvent
    let onTry: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: event.symbol)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(Brand.slime)
                .frame(width: 36, height: 36)
                .background(Brand.raised)
            VStack(alignment: .leading, spacing: 2) {
                Text(event.title)
                    .font(Brand.pixel(15))
                    .foregroundStyle(Brand.text)
                Text(event.shortcutTrigger)
                    .font(Brand.body(12))
                    .foregroundStyle(Brand.muted)
            }
            Spacer()
            Button("Testen", action: onTry)
                .font(Brand.pixel(13))
                .foregroundStyle(Brand.slime)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .overlay(Rectangle().strokeBorder(Brand.slime.opacity(0.6), lineWidth: 2))
        }
        .padding(8)
        .background(Brand.panel)
        .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 2))
    }
}
