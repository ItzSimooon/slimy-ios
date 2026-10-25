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
                    SheetHeader(eyebrow: "settings", title: "Slimy") { dismiss() }

                    section("dynamic island") {
                        Toggle("Keep \(store.state.name) in the island", isOn: Binding(
                            get: { island.keepAlive },
                            set: { island.keepAlive = $0 }))
                        Text("iOS ends Live Activities after about 8 hours. Slimy brings it back every time you open the app. To keep it on all day, use the Summon Slime button in Control Center or set up the automations below.")
                            .settingsNote()
                        HStack(spacing: 10) {
                            JellyButton(title: island.isLive ? "Restart" : "Start", symbol: "play.fill", tint: Brand.hypr) {
                                Task { await island.ensureRunning(restart: true) }
                            }
                            if island.isLive {
                                JellyButton(title: "Hide", tint: Brand.muted, filled: false) {
                                    Task { await island.stop() }
                                }
                            }
                        }
                        NavigationLink {
                            DiagnosticsView()
                        } label: {
                            HStack {
                                Label("Diagnose: Warum ist nichts in der Insel?", systemImage: "stethoscope")
                                    .font(Brand.display(15, .bold))
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(Brand.muted)
                            }
                            .padding(14)
                            .background(Brand.raised, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }

                    section("phone reactions") {
                        Toggle("React while the app is open", isOn: $liveEvents)
                            .onChange(of: liveEvents) { _, on in PhoneMonitor.shared.enabled = on }
                        Text("Battery, charging, Wi-Fi, mobile data, going offline, Low Power Mode and the time of day.")
                            .settingsNote()
                        NavigationLink {
                            EventsGuideView()
                        } label: {
                            HStack {
                                Label("React while the app is closed", systemImage: "wand.and.stars")
                                    .font(Brand.display(15, .bold))
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(Brand.muted)
                            }
                            .padding(14)
                            .background(Brand.raised, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }

                    section("notifications") {
                        Toggle("Tell me when \(store.state.name) needs something", isOn: $notifications)
                            .onChange(of: notifications) { _, on in
                                Notifier.enabled = on
                                Task {
                                    if on { await Notifier.requestPermission() }
                                    Notifier.reschedule(for: store.state)
                                }
                            }
                    }

                    section("about") {
                        LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                        LabeledContent("Server", value: "slime.hyprnode.de")
                        Text("Slimy is made by hyprnode.")
                            .settingsNote()
                    }

                    section("start over") {
                        if confirmRelease {
                            Text("\(store.state.name) will be gone for good, with all levels and coins. This can't be undone.")
                                .settingsNote()
                            HStack(spacing: 10) {
                                JellyButton(title: "Keep \(store.state.name)", tint: Brand.hypr, filled: false) {
                                    confirmRelease = false
                                }
                                JellyButton(title: "Say goodbye", tint: Brand.danger) {
                                    dismiss()
                                    store.releaseSlime()
                                }
                            }
                        } else {
                            Button("Release \(store.state.name)…", role: .destructive) { confirmRelease = true }
                                .font(Brand.display(15, .bold))
                        }
                    }
                }
                .padding(20)
            }
            .toolbar(.hidden, for: .navigationBar)
            .background(Brand.void)
        }
        .tint(Brand.hypr)
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Eyebrow(text: title, color: Brand.hyprGlow)
            Panel {
                VStack(alignment: .leading, spacing: 12) {
                    content()
                }
                .font(Brand.display(15, .semibold))
                .foregroundStyle(Brand.text)
            }
        }
    }
}

private extension Text {
    func settingsNote() -> some View {
        self.font(Brand.display(13, .medium)).foregroundStyle(Brand.muted)
    }
}

/// How to make the slime react while the app is closed, using Shortcuts.
struct EventsGuideView: View {
    @EnvironmentObject private var store: SlimeStore
    @Environment(\.dismiss) private var dismiss

    private let groups: [(String, [PhoneEvent])] = [
        ("battery & power", [.batteryLow, .batteryCritical, .batteryFull, .chargerConnected, .chargerDisconnected, .lowPowerOn, .lowPowerOff]),
        ("network", [.wifiConnected, .wifiDisconnected, .cellular, .airplaneOn, .airplaneOff]),
        ("time", [.goodMorning, .goodNight, .alarm, .newHour]),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack {
                    Button { dismiss() } label: {
                        Label("Settings", systemImage: "chevron.left").font(Brand.display(15, .bold))
                    }
                    Spacer()
                }
                Text("React while closed")
                    .font(Brand.display(26, .black))
                    .foregroundStyle(Brand.text)

                Panel {
                    VStack(alignment: .leading, spacing: 10) {
                        Eyebrow(text: "set up once per event", color: Brand.hyprGlow)
                        step(1, "Open Shortcuts, go to Automation and tap +.")
                        step(2, "Pick the trigger listed under the event below.")
                        step(3, "Choose Run Immediately and turn off Notify When Run.")
                        step(4, "Add the action Slime Reacts (from Slimy) and pick the event.")
                        step(5, "Optional: add Speak Text after it and use the slime's reply, so it talks out loud.")
                        Link(destination: URL(string: "shortcuts://")!) {
                            Label("Open Shortcuts", systemImage: "arrow.up.forward.app.fill")
                                .font(Brand.display(15, .bold))
                        }
                        .padding(.top, 4)
                    }
                }

                Panel {
                    VStack(alignment: .leading, spacing: 10) {
                        Eyebrow(text: "keep it always on", color: Brand.warm)
                        Text("Make three Time of Day automations, for example 08:00, 15:00 and 22:00, each running Summon Slime. Every run gives the slime another 8 hours in the island. You can also put Summon Slime in Control Center or on the Action Button.")
                            .font(Brand.display(14, .medium))
                            .foregroundStyle(Brand.text)
                    }
                }

                ForEach(groups, id: \.0) { title, events in
                    Eyebrow(text: title)
                    VStack(spacing: 8) {
                        ForEach(events) { event in
                            EventRow(event: event) {
                                Task { await store.handle(event) }
                            }
                        }
                    }
                }
            }
            .padding(20)
        }
        .background(Brand.void)
        .toolbar(.hidden, for: .navigationBar)
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text("\(n)")
                .font(Brand.mono(12, .black))
                .foregroundStyle(Brand.void)
                .frame(width: 20, height: 20)
                .background(Brand.hyprGlow, in: Circle())
            Text(text)
                .font(Brand.display(14, .medium))
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
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(Brand.hyprGlow)
                .frame(width: 38, height: 38)
                .background(Brand.raised, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(event.title)
                    .font(Brand.display(15, .bold))
                    .foregroundStyle(Brand.text)
                Text(event.shortcutTrigger)
                    .font(Brand.mono(10, .semibold))
                    .foregroundStyle(Brand.muted)
            }
            Spacer()
            Button("Try", action: onTry)
                .font(Brand.display(13, .bold))
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(Brand.hypr.opacity(0.18), in: Capsule())
        }
        .padding(10)
        .background(Brand.deep, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
