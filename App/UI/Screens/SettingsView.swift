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
                        MenuHeader(title: "Settings") { dismiss() }
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
            .fixedSize(horizontal: false, vertical: true)
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
        return PixelWindow(title: "Sound and feel") {
            toggle("Sounds", s.sound) { v in store.setSettings { $0.sound = v } }
            toggle("Music", s.music) { v in
                store.setSettings { $0.music = v }
                Feedback.shared.updateMusic()
            }
            toggle("Vibration", s.haptics) { v in store.setSettings { $0.haptics = v } }
            note("Sounds follow your iPhone's silent switch.")
        }
    }

    private var notificationSection: some View {
        let n = store.state.settings.notifications
        return PixelWindow(title: "Notifications") {
            toggle("When it needs something", n.needs) { v in store.setSettings { $0.notifications.needs = v } }
            toggle("New daily challenges", n.daily) { v in store.setSettings { $0.notifications.daily = v } }
            toggle("Friends", n.friends) { v in store.setSettings { $0.notifications.friends = v } }
            toggle("Gifts", n.gifts) { v in store.setSettings { $0.notifications.gifts = v } }
            toggle("Little hellos", n.world) { v in store.setSettings { $0.notifications.world = v } }
            hourStepper("Quiet from", n.quietFrom) { v in store.setSettings { $0.notifications.quietFrom = v } }
            hourStepper("Quiet until", n.quietUntil) { v in store.setSettings { $0.notifications.quietUntil = v } }
            note("At most \(Notifier.dailyLimit) a day. Nothing during quiet hours.")
            if !n.asked {
                PixelButton(title: "Allow notifications", kind: .secondary) {
                    Task { await Notifier.requestPermission() }
                }
            }
        }
        .onChange(of: store.state.settings) { _, _ in Notifier.reschedule(for: store.state) }
    }

    private var sleepSection: some View {
        let sleep = store.state.settings.sleep
        return PixelWindow(title: "Bedtime") {
            toggle("Goes to bed by itself", sleep.auto) { v in store.setSettings { $0.sleep.auto = v } }
            hourStepper("Bedtime", sleep.bedtime) { v in store.setSettings { $0.sleep.bedtime = v } }
            hourStepper("Wake up", sleep.wake) { v in store.setSettings { $0.sleep.wake = v } }
            note("You can still wake it up or put it to bed any time.")
        }
    }

    private var islandSection: some View {
        PixelWindow(title: "Dynamic Island") {
            toggle("Always bring it back", island.keepAlive) { island.keepAlive = $0 }
            toggle("Animation", island.animated) { island.animated = $0 }
            note("iOS ends Live Activities after about 8 hours. Slimy brings your slime back whenever it gets to run: when you open the app, in background refresh, or with \"Summon Slime\" from Control Center or the Action Button.")
            HStack(spacing: 10) {
                PixelButton(title: island.isLive ? "Restart" : "Start", compact: true) {
                    Task { await island.ensureRunning(restart: true) }
                }
                if island.isLive {
                    PixelButton(title: "Hide", kind: .quiet, compact: true) {
                        Task { await island.stop() }
                    }
                }
            }
            NavigationLink { DiagnosticsView() } label: { linkLabel("Diagnostics", icon: "gear") }
                .buttonStyle(.plain)
        }
    }

    private var eventsSection: some View {
        PixelWindow(title: "Reacting to your iPhone") {
            toggle("React to my iPhone", store.state.settings.liveEvents) { v in
                store.setSettings { $0.liveEvents = v }
            }
            note("Battery, charger, Low Power Mode, Wi-Fi, mobile data, no internet, morning and night. No setup needed.")
            note("While the app is open it reacts right away. When it's closed, it notices the next time iOS lets it run (background refresh, while charging, or when you tap the island), so a reaction can come a little later. Background App Refresh must be on for Slimy.")
            PixelButton(title: "Check now", kind: .secondary, compact: true) {
                PhoneMonitor.shared.check()
            }
        }
    }

    private var aboutSection: some View {
        PixelWindow(title: "About Slimy") {
            HStack {
                Text("Version").font(Theme.label(15)).foregroundStyle(Theme.ink)
                Spacer()
                Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                    .font(Theme.label(15)).foregroundStyle(Theme.inkSoft)
            }
            note("Slimy is made by hyprnode · slime.hyprnode.de\nPixel font: Pixelify Sans (SIL Open Font License). Art and sounds were made for Slimy.")
        }
    }

    private var resetSection: some View {
        PixelWindow(title: "Start over") {
            if confirmRelease {
                note("\(store.state.slime.name) will be gone for good, with its levels, coins and things. Friendships stay on the server. This can't be undone.")
                HStack(spacing: 10) {
                    PixelButton(title: "Keep", kind: .secondary) { confirmRelease = false }
                    PixelButton(title: "Release", kind: .danger) {
                        dismiss()
                        store.releaseSlime()
                    }
                }
            } else {
                PixelButton(title: "Release \(store.state.slime.name) …", kind: .quiet) { confirmRelease = true }
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
