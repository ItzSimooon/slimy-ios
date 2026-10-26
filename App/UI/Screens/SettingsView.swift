import ActivityKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import UserNotifications

/// Settings as a native form: the slime's chatter and Care Together, sound,
/// notifications, bedtime, the island, phone reactions, online and privacy,
/// backups, help and starting over.
struct SettingsView: View {
    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var island: IslandController
    @Environment(\.scenePhase) private var scenePhase
    @State private var system = SettingsSystemStatus()

    var body: some View {
        NavigationStack {
            Form {
                SettingsSlimeSection()
                SettingsSoundSection()
                SettingsNotificationSection(status: $system.notifications)
                SettingsBedtimeSection()
                SettingsIslandSection(activitiesEnabled: system.activitiesEnabled)
                SettingsPhoneSection(backgroundRefresh: system.backgroundRefresh)
                SettingsOnlineSection()
                SettingsPrivacySection()
                SettingsBackupSection()
                SettingsSupportSection()
                SettingsStartOverSection()
            }
            .sheetTitle("Settings")
        }
        .onChange(of: store.state.settings) { _, _ in
            Notifier.reschedule(for: store.state)
        }
        .task(id: scenePhase) {
            // Also runs when coming back from the Settings app.
            guard scenePhase == .active else { return }
            await refreshSystemStatus()
        }
    }

    private func refreshSystemStatus() async {
        let notifications = await UNUserNotificationCenter.current().notificationSettings()
        system.notifications = notifications.authorizationStatus
        system.activitiesEnabled = ActivityAuthorizationInfo().areActivitiesEnabled
        system.backgroundRefresh = UIApplication.shared.backgroundRefreshStatus
        island.refreshStatus()
    }
}

/// What iOS allows Slimy to do, read when Settings opens or comes back.
private struct SettingsSystemStatus {
    var notifications: UNAuthorizationStatus?
    var activitiesEnabled = true
    var backgroundRefresh: UIBackgroundRefreshStatus = .available
}

// MARK: - Your slime

private struct SettingsSlimeSection: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        let name = store.state.slime.name
        Section {
            Toggle(isOn: settingBinding(store, \.chatty)) {
                SettingsTitle(title: "Talks on its own",
                              detail: "\(name) says what's on its mind every few seconds while Slimy is open.")
            }
            NavigationLink {
                CareTogetherView()
            } label: {
                LabeledContent {
                    Text(partnerText)
                } label: {
                    Label("Care Together", systemImage: "person.2.fill")
                }
            }
        } header: {
            Text("Your slime")
        } footer: {
            Text("With Care Together, one other person looks after \(name) with you on their iPhone. Everything either of you does for it shows up on both. Coins and things stay your own.")
        }
    }

    private var partnerText: String {
        guard store.isSharing else { return "Off" }
        let partner = (store.state.care?.partnerName ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return partner.isEmpty ? "On" : "With \(partner)"
    }
}

// MARK: - Sound

private struct SettingsSoundSection: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        Section {
            Toggle("Sounds", isOn: settingBinding(store, \.sound))
            Toggle("Music", isOn: Binding(
                get: { store.state.settings.music },
                set: { value in
                    store.setSettings { $0.music = value }
                    Feedback.shared.updateMusic()
                }))
            Toggle("Vibration", isOn: settingBinding(store, \.haptics))
        } header: {
            Text("Sound & Haptics")
        } footer: {
            Text("Sounds and music are muted while your iPhone is in silent mode.")
        }
    }
}

// MARK: - Notifications

private struct SettingsNotificationSection: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.openURL) private var openURL
    @Binding var status: UNAuthorizationStatus?
    @State private var asking = false

    var body: some View {
        let prefs = store.state.settings.notifications
        Section {
            if status == .notDetermined || (status == nil && !prefs.asked) {
                Button {
                    Task { await allow() }
                } label: {
                    SettingsBusyLabel(title: "Allow Notifications", systemImage: "bell.badge", busy: asking)
                }
                .disabled(asking)
            } else if status == .denied {
                SettingsNotice(text: "Notifications are turned off for Slimy in the Settings app.")
                Button("Turn On in Settings") {
                    openSystemSettings(openURL, notifications: true)
                }
            }
            Toggle("When it needs something", isOn: settingBinding(store, \.notifications.needs))
            Toggle("Daily challenges and gift", isOn: settingBinding(store, \.notifications.daily))
            Toggle("Friends", isOn: settingBinding(store, \.notifications.friends))
            Toggle("Gifts", isOn: settingBinding(store, \.notifications.gifts))
            Toggle("Little hellos", isOn: settingBinding(store, \.notifications.world))
            SettingsHourPicker(title: "Quiet from", hour: settingBinding(store, \.notifications.quietFrom),
                               avoiding: prefs.quietUntil)
            SettingsHourPicker(title: "Quiet until", hour: settingBinding(store, \.notifications.quietUntil),
                               avoiding: prefs.quietFrom)
        } header: {
            Text("Notifications")
        } footer: {
            Text("Only the kinds you switch on, and nothing during quiet hours. Alerts from friends and gifts, and the island opening with a sound, stop after \(Notifier.dailyLimit) a day.")
        }
    }

    private func allow() async {
        asking = true
        let granted = await Notifier.requestPermission()
        status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        if granted { Notifier.reschedule(for: store.state) }
        asking = false
    }
}

// MARK: - Bedtime

private struct SettingsBedtimeSection: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        let schedule = store.state.settings.sleep
        Section {
            Toggle("Goes to bed by itself", isOn: settingBinding(store, \.sleep.auto))
            SettingsHourPicker(title: "Bedtime", hour: settingBinding(store, \.sleep.bedtime),
                               avoiding: schedule.wake)
            SettingsHourPicker(title: "Wake-up time", hour: settingBinding(store, \.sleep.wake),
                               avoiding: schedule.bedtime)
        } header: {
            Text("Bedtime")
        } footer: {
            Text("You can still wake \(store.state.slime.name) up or put it to bed any time.")
        }
    }
}

// MARK: - Dynamic Island

private struct SettingsIslandSection: View {
    @EnvironmentObject private var island: IslandController
    @Environment(\.openURL) private var openURL
    let activitiesEnabled: Bool
    @State private var working = false

    var body: some View {
        let presence = island.presence
        Section {
            if presence == .unavailable {
                SettingsNotice(text: presence.explanation)
            } else {
                LabeledContent("Status", value: statusText(presence))
                if !activitiesEnabled {
                    SettingsNotice(text: "Live Activities are turned off for Slimy, so your slime can't appear outside the app.")
                    Button("Turn On in Settings") {
                        openSystemSettings(openURL)
                    }
                } else if let problem = island.problem {
                    SettingsNotice(text: problem)
                }
                Toggle("Always bring it back", isOn: Binding(
                    get: { island.keepAlive },
                    set: { on in
                        island.keepAlive = on
                        if on { Task { await island.ensureRunning() } }
                    }))
                Toggle(isOn: Binding(get: { island.animated }, set: { island.animated = $0 })) {
                    SettingsTitle(title: "Animation", detail: "When it's off, your slime shows as a still picture.")
                }
                Button {
                    Task { await restart() }
                } label: {
                    SettingsBusyLabel(title: island.isLive ? "Restart" : "Start",
                                      systemImage: island.isLive ? "arrow.clockwise" : "play", busy: working)
                }
                .disabled(working || !activitiesEnabled)
                if island.isLive {
                    Button {
                        Task { await island.stop() }
                    } label: {
                        Label("Hide", systemImage: "eye.slash")
                    }
                    .disabled(working)
                }
            }
        } header: {
            Text(presence.title)
        } footer: {
            if presence != .unavailable {
                Text("iOS ends Live Activities after about 8 hours. Slimy brings your slime back when you open the app, or when you use Summon Slime from Control Center, the Action Button, Siri or the Shortcuts app.")
            }
        }
    }

    private func statusText(_ presence: IslandController.Presence) -> String {
        guard island.isLive else { return "Not showing" }
        return presence == .dynamicIsland ? "In the Dynamic Island" : "On the Lock Screen"
    }

    private func restart() async {
        working = true
        await island.ensureRunning(restart: true)
        working = false
    }
}

// MARK: - Phone reactions

private struct SettingsPhoneSection: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.openURL) private var openURL
    let backgroundRefresh: UIBackgroundRefreshStatus
    @State private var checked = false

    var body: some View {
        let enabled = store.state.settings.liveEvents
        Section {
            Toggle("React to my iPhone", isOn: settingBinding(store, \.liveEvents))
            Button {
                PhoneMonitor.shared.check()
                checked = true
            } label: {
                Label(checked ? "Checked" : "Check Now", systemImage: checked ? "checkmark" : "iphone")
            }
            .disabled(!enabled || checked)
            .task(id: checked) {
                guard checked else { return }
                try? await Task.sleep(for: .seconds(2))
                checked = false
            }
            if enabled, backgroundRefresh != .available {
                SettingsNotice(text: "Background App Refresh is off for Slimy, so it mostly reacts while the app is open.")
                if backgroundRefresh == .denied {
                    Button("Turn On in Settings") {
                        openSystemSettings(openURL)
                    }
                }
            }
        } header: {
            Text("Reacting to your iPhone")
        } footer: {
            Text("Battery, charger, Low Power Mode, Wi-Fi, mobile data, no internet, morning and night. No setup needed. While Slimy is open it reacts right away. When it's closed, it notices the next time iOS lets it run, so a reaction can come a little later.")
        }
    }
}

// MARK: - Online

private struct SettingsOnlineSection: View {
    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var friends: FriendsStore
    @State private var showGoOnline = false
    @State private var confirmOffline = false

    var body: some View {
        Section {
            LabeledContent("Status") {
                Label {
                    Text(friends.isOnline ? "Online" : "Offline")
                } icon: {
                    Image(systemName: "circle.fill")
                        .font(.caption2)
                        .foregroundStyle(friends.isOnline ? Theme.accent : Theme.inkSoft)
                        .accessibilityHidden(true)
                }
            }
            .sheet(isPresented: $showGoOnline) {
                GoOnlineView()
            }
            .onChange(of: friends.isOnline) { _, online in
                if online { showGoOnline = false }
            }

            if friends.isOnline {
                Button("Go Offline", role: .destructive) { confirmOffline = true }
                    .confirmationDialog("Go offline?", isPresented: $confirmOffline, titleVisibility: .visible) {
                        Button("Go Offline", role: .destructive) {
                            friends.goOffline()
                            store.toast = Toast(text: "You're offline. Nothing is sent to slime.hyprnode.de.", good: true)
                        }
                    } message: {
                        Text("Slimy stops talking to slime.hyprnode.de. Your friends stay saved on this iPhone and you can go online again any time.")
                    }
            } else {
                Button("Go Online…") { showGoOnline = true }
            }
        } header: {
            Text("Online")
        } footer: {
            Text("Friends, the leaderboard, Care Together and talking with your slime need slime.hyprnode.de. Your slime, coins and things always stay on this iPhone; with Care Together, your slime is also shared with your partner.")
        }
    }
}

// MARK: - Privacy

private struct SettingsPrivacySection: View {
    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var friends: FriendsStore
    @EnvironmentObject private var chat: ChatStore
    @State private var confirmDelete = false
    @State private var deleting = false

    var body: some View {
        Section {
            // Consent is only given in the chat's own consent sheet, before
            // the first message; here it can only be taken back.
            if chat.aiConsent {
                Toggle(isOn: Binding(
                    get: { chat.aiConsent },
                    set: { on in
                        if !on { chat.revokeConsent() }
                    })) {
                    SettingsTitle(title: "Talk with AI", detail: "Your slime answers what you write in chat.")
                }
            } else {
                LabeledContent {
                    Text("Off")
                } label: {
                    SettingsTitle(title: "Talk with AI",
                                  detail: "The chat asks you before your first message is sent.")
                }
            }
            if friends.hasServerAccount {
                Button(role: .destructive) {
                    confirmDelete = true
                } label: {
                    SettingsBusyLabel(title: "Delete My Data on the Server", systemImage: "trash", busy: deleting)
                }
                .disabled(deleting)
                .confirmationDialog("Delete your data on slime.hyprnode.de?", isPresented: $confirmDelete,
                                    titleVisibility: .visible) {
                    Button("Delete Data", role: .destructive) {
                        Task { await deleteServerData() }
                    }
                } message: {
                    Text("Your friendships, scores, Care Together and everything friends sent you are deleted for good, and Slimy goes offline. Your slime on this iPhone stays. This can't be undone.")
                }
            }
            SettingsLinkRow(title: "Privacy Policy", systemImage: "hand.raised", url: FriendsStore.privacyURL)
            SettingsLinkRow(title: "Terms of Use", systemImage: "doc.text", url: FriendsStore.termsURL)
        } header: {
            Text("Privacy")
        } footer: {
            Text("With Talk with AI on, your message, the recent chat and a few facts about your slime, like how it feels, go to Cloudflare Workers AI and Groq, which write the reply. Your name and device ID are never passed on to them, and slime.hyprnode.de doesn't keep the messages.")
        }
    }

    private func deleteServerData() async {
        deleting = true
        let done = await friends.deleteAccount()
        deleting = false
        store.toast = done
            ? Toast(text: "Your data on slime.hyprnode.de is deleted.", good: true)
            : Toast(text: friends.lastError ?? "Couldn't reach slime.hyprnode.de. Check your connection and try again.",
                    good: false)
        friends.lastError = nil
    }
}

// MARK: - Backup

private struct SettingsBackupSection: View {
    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var friends: FriendsStore
    @State private var exportFile: URL?
    @State private var exportFailed = false
    @State private var imports = 0
    @State private var picking = false
    @State private var staged: URL?
    @State private var confirmImport = false

    /// When the export file is written again: on opening, and whenever the
    /// save changes in a way Settings can cause.
    private struct ExportTrigger: Equatable {
        let settings: Settings
        let imports: Int
    }

    var body: some View {
        let name = store.state.slime.name
        Section {
            exportRow
                .task(id: ExportTrigger(settings: store.state.settings, imports: imports)) {
                    prepareExport()
                }
            Button {
                picking = true
            } label: {
                Label("Import Save…", systemImage: "square.and.arrow.down")
            }
            // A save would replace the slime you share; stop sharing first.
            .disabled(store.isSharing)
            .fileImporter(isPresented: $picking, allowedContentTypes: Self.importTypes) { result in
                picked(result)
            }
            .confirmationDialog("Replace \(name)?", isPresented: $confirmImport, titleVisibility: .visible) {
                Button("Replace with This Save", role: .destructive) { importStaged() }
                Button("Cancel", role: .cancel) { discardStaged() }
            } message: {
                Text("\(name) and everything it has will be replaced by the slime in this save. Export a save first if you want to keep \(name). This can't be undone.")
            }
        } header: {
            Text("Backup")
        } footer: {
            if store.isSharing {
                Text("Export a save to keep a copy in Files. While you care for \(name) together, a save can't be imported; stop sharing in Care Together first.")
            } else {
                Text("Your slime lives only on this iPhone. Export a save to keep a copy in Files or to move your slime to a new iPhone. Friends aren't part of a save.")
            }
        }
    }

    @ViewBuilder private var exportRow: some View {
        if let exportFile {
            ShareLink(item: exportFile) {
                Label("Export Save", systemImage: "square.and.arrow.up")
            }
        } else if exportFailed {
            Button {
                retryExport()
            } label: {
                Label("Export Save", systemImage: "square.and.arrow.up")
            }
        } else {
            Label("Export Save", systemImage: "square.and.arrow.up")
                .foregroundStyle(Theme.inkSoft)
        }
    }

    /// Slimy saves and plain JSON. If the save type isn't declared in the
    /// app's Info.plist, any file can be picked; the import checks it anyway.
    private static var importTypes: [UTType] {
        if let save = UTType(filenameExtension: SaveFile.fileExtension), save.isDeclared {
            return [save, .json]
        }
        return [.json, .data]
    }

    private func prepareExport() {
        do {
            exportFile = try store.exportSave()
            exportFailed = false
        } catch {
            exportFile = nil
            exportFailed = true
        }
    }

    private func retryExport() {
        prepareExport()
        store.toast = exportFailed
            ? Toast(text: "Couldn't create the save file. Try again in a moment.", good: false)
            : Toast(text: "Your save is ready. Tap Export Save to share it.", good: true)
    }

    private func picked(_ result: Result<URL, Error>) {
        switch result {
        case .success(let url):
            do {
                discardStaged()
                staged = try Self.stage(url)
                Task {
                    // Let the file picker finish closing before the dialog opens.
                    try? await Task.sleep(for: .milliseconds(350))
                    confirmImport = true
                }
            } catch {
                store.toast = Toast(text: (error as? SaveFile.Problem)?.errorDescription ?? "Couldn't read that file.",
                                    good: false)
            }
        case .failure(let error):
            if (error as? CocoaError)?.code == .userCancelled { return }
            store.toast = Toast(text: "Couldn't open that file.", good: false)
        }
    }

    /// Copies the picked file into the temporary folder while access to it is
    /// granted, so it can still be read after the confirmation.
    private static func stage(_ url: URL) throws -> URL {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > SaveFile.maxBytes {
            throw SaveFile.Problem.tooLarge
        }
        let data = try Data(contentsOf: url)
        guard data.count <= SaveFile.maxBytes else { throw SaveFile.Problem.tooLarge }
        let copy = FileManager.default.temporaryDirectory
            .appendingPathComponent("import-\(UUID().uuidString)")
            .appendingPathExtension(SaveFile.fileExtension)
        try data.write(to: copy, options: .atomic)
        return copy
    }

    private func importStaged() {
        guard let url = staged else { return }
        staged = nil
        defer { try? FileManager.default.removeItem(at: url) }
        do {
            // importSave also reschedules notifications and updates the island.
            try store.importSave(from: url)
            Feedback.shared.updateMusic()
            // Friends see the imported slime's name, look and stage. Does
            // nothing while offline.
            Task { await friends.refresh() }
            imports += 1
            Feedback.shared.play(.success)
            store.toast = Toast(text: "Save imported. Welcome back, \(store.state.slime.name).", good: true)
        } catch {
            store.toast = Toast(text: Self.message(for: error), good: false)
        }
    }

    private func discardStaged() {
        if let staged { try? FileManager.default.removeItem(at: staged) }
        staged = nil
    }

    private static func message(for error: Error) -> String {
        if let localized = error as? LocalizedError, let text = localized.errorDescription, !text.isEmpty {
            return text
        }
        return "That file isn't a Slimy save, or it's damaged."
    }
}

// MARK: - Help and About

private struct SettingsSupportSection: View {
    var body: some View {
        Section {
            NavigationLink {
                HelpView()
            } label: {
                Label("Help", systemImage: "questionmark.circle")
            }
            NavigationLink {
                AboutView()
            } label: {
                Label("About Slimy", systemImage: "info.circle")
            }
        } header: {
            Text("Support")
        }
    }
}

// MARK: - Start over

private struct SettingsStartOverSection: View {
    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var chat: ChatStore
    @Environment(\.dismiss) private var dismiss
    @State private var confirm = false

    var body: some View {
        let name = store.state.slime.name
        Section {
            Button("Release \(name)…", role: .destructive) { confirm = true }
                // A shared slime belongs to two people; stop sharing first.
                .disabled(store.isSharing)
                .confirmationDialog("Release \(name)?", isPresented: $confirm, titleVisibility: .visible) {
                    Button("Release \(name)", role: .destructive) {
                        dismiss()
                        store.releaseSlime()
                        // The conversation belonged to this slime.
                        chat.clear()
                        chat.draft = ""
                    }
                } message: {
                    Text("\(name) will be gone for good, with its levels, coins, things and your chat with it. Your friends and online profile aren't affected. This can't be undone.")
                }
        } header: {
            Text("Start over")
        } footer: {
            if store.isSharing {
                Text("You care for \(name) together with someone. Stop sharing in Care Together before you release a slime.")
            } else {
                Text("Export a save first if you might want \(name) back.")
            }
        }
    }
}

// MARK: - Rows

/// A row that opens a web page, with the arrow iOS uses for links that
/// leave the app.
struct SettingsLinkRow: View {
    let title: String
    let systemImage: String
    let url: URL

    var body: some View {
        Link(destination: url) {
            HStack {
                Label(title, systemImage: systemImage)
                Spacer()
                Image(systemName: "arrow.up.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityHint("Opens in your browser")
    }
}

/// A toggle or row title with a line of explanation under it.
private struct SettingsTitle: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            Text(detail)
                .font(.footnote)
                .foregroundStyle(Theme.inkSoft)
        }
    }
}

/// A button label that shows a spinner while its work runs.
private struct SettingsBusyLabel: View {
    let title: String
    let systemImage: String
    let busy: Bool

    var body: some View {
        HStack {
            Label(title, systemImage: systemImage)
            Spacer()
            if busy { ProgressView() }
        }
    }
}

/// Something iOS keeps Slimy from doing, and why it matters.
private struct SettingsNotice: View {
    let text: String

    var body: some View {
        Label {
            Text(text)
                .font(.subheadline)
                .foregroundStyle(Theme.ink)
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Color(uiColor: .systemOrange))
        }
    }
}

/// Picks a full hour, shown in the iPhone's own time format.
private struct SettingsHourPicker: View {
    let title: String
    @Binding var hour: Int
    /// An hour that can't be picked, so a range never starts where it ends.
    var avoiding: Int?

    var body: some View {
        let current = min(23, max(0, hour))
        Picker(title, selection: Binding(get: { current }, set: { hour = $0 })) {
            ForEach((0..<24).filter { $0 != avoiding || $0 == current }, id: \.self) { value in
                Text(Self.label(for: value)).tag(value)
            }
        }
    }

    static func label(for hour: Int) -> String {
        // A fixed winter day, so no hour is skipped by a daylight saving change.
        var components = DateComponents()
        components.year = 2001
        components.month = 1
        components.day = 1
        components.hour = hour
        let date = Calendar.current.date(from: components) ?? .now
        return date.formatted(date: .omitted, time: .shortened)
    }
}

// MARK: - Helpers

/// A binding to one setting that saves through the store.
@MainActor
private func settingBinding<Value>(_ store: GameStore, _ path: WritableKeyPath<Settings, Value>) -> Binding<Value> {
    Binding(
        get: { store.state.settings[keyPath: path] },
        set: { value in store.setSettings { $0[keyPath: path] = value } })
}

/// Opens Slimy's page in the Settings app, or its notification settings.
@MainActor
private func openSystemSettings(_ openURL: OpenURLAction, notifications: Bool = false) {
    let string = notifications ? UIApplication.openNotificationSettingsURLString : UIApplication.openSettingsURLString
    if let url = URL(string: string) { openURL(url) }
}
