import SwiftUI

/// Names, what friends see, who may ask, who is blocked, going offline and
/// deleting everything on the server.
struct FriendPrivacyView: View {
    @EnvironmentObject private var friends: FriendsStore
    @EnvironmentObject private var store: GameStore

    @State private var ownerDraft = ""
    @State private var slimeDraft = ""
    @State private var prepared = false
    @State private var savingNames = false
    /// The server refused one of the names; shown next to them.
    @State private var nameError: String?
    @State private var pendingShareMood: Bool?
    @State private var pendingAcceptRequests: Bool?
    @State private var unblocking: String?
    @State private var confirmOffline = false
    @State private var confirmDelete = false
    @State private var deleting = false
    @State private var showGoOnline = false
    @State private var toast: Toast?

    private static let slimeNameLimit = HyprnodeAPI.slimeNameLimit

    var body: some View {
        Form {
            namesSection
            if friends.isOnline {
                sharingSection
                blockedSection
            }
            connectionSection
            if friends.hasServerAccount {
                dataSection
            }
            documentsSection
        }
        .navigationTitle("Privacy & blocked")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: prepare)
        .task { await friends.loadBlocked() }
        .confirmationDialog("Go offline?", isPresented: $confirmOffline, titleVisibility: .visible) {
            Button("Go Offline", role: .destructive) {
                friends.goOffline()
                toast = Toast(text: "You're offline. Nothing is sent to slime.hyprnode.de.", good: true)
            }
        } message: {
            Text("Slimy stops talking to slime.hyprnode.de. Your friends stay saved on this iPhone and you can go online again any time.")
        }
        .confirmationDialog("Delete your data on slime.hyprnode.de?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete Data", role: .destructive) { deleteData() }
        } message: {
            Text("Your friendships, love, memories, scores and everything friends sent you are deleted for good, and Slimy goes offline. Your slime on this iPhone stays. This can't be undone.")
        }
        .sheet(isPresented: $showGoOnline) {
            GoOnlineView()
                .environmentObject(friends)
                .environmentObject(store)
        }
        .friendsToast($toast)
    }

    // MARK: Names

    private var namesSection: some View {
        Section {
            LabeledContent("Your name") {
                TextField("Your name", text: $ownerDraft)
                    .multilineTextAlignment(.trailing)
                    .textInputAutocapitalization(.words)
                    .textContentType(.nickname)
                    .submitLabel(.done)
                    .onChange(of: ownerDraft) { _, new in
                        if new.count > HyprnodeAPI.nameLimit { ownerDraft = String(new.prefix(HyprnodeAPI.nameLimit)) }
                        if !savingNames { nameError = nil }
                    }
            }
            LabeledContent("Your slime") {
                TextField("Slime's name", text: $slimeDraft)
                    .multilineTextAlignment(.trailing)
                    .textInputAutocapitalization(.words)
                    .submitLabel(.done)
                    .onChange(of: slimeDraft) { _, new in
                        if new.count > Self.slimeNameLimit { slimeDraft = String(new.prefix(Self.slimeNameLimit)) }
                        if !savingNames { nameError = nil }
                    }
            }
            if namesChanged {
                Button {
                    saveNames()
                } label: {
                    if savingNames {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("Saving…")
                        }
                    } else {
                        Text("Save Names")
                    }
                }
                .disabled(!namesValid || savingNames)
            }
            if let message = nameError ?? friends.profileNotice {
                Label(message, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(Theme.danger)
            }
        } header: {
            Text("Names friends see")
        } footer: {
            Text("Use a first name or a nickname. Names that are rude or contain links, email addresses or phone numbers aren't accepted.")
        }
    }

    private var cleanedOwner: String { HyprnodeAPI.cleanedName(ownerDraft) }
    private var cleanedSlime: String { String(HyprnodeAPI.cleanedName(slimeDraft).prefix(Self.slimeNameLimit)) }
    private var namesValid: Bool { !cleanedOwner.isEmpty && !cleanedSlime.isEmpty }
    private var namesChanged: Bool {
        prepared && (cleanedOwner != friends.ownerName || cleanedSlime != store.state.slime.name)
    }

    private func prepare() {
        guard !prepared else { return }
        ownerDraft = friends.ownerName
        slimeDraft = store.state.slime.name
        prepared = true
    }

    private func saveNames() {
        let owner = cleanedOwner
        let slime = cleanedSlime
        let previousSlime = store.state.slime.name
        savingNames = true
        nameError = nil
        Task {
            if slime != previousSlime { store.update { $0.slime.name = slime } }
            var ok = true
            if owner != friends.ownerName {
                ok = await friends.rename(to: owner)
            } else if slime != previousSlime, friends.isOnline {
                ok = await friends.uploadProfileNow()
            }
            savingNames = false
            if ok {
                ownerDraft = owner
                slimeDraft = slime
                toast = Toast(text: "Names saved.", good: true)
            } else {
                // Keep the old names everywhere. A refused name is shown next
                // to the fields, anything else as a toast.
                if slime != previousSlime { store.update { $0.slime.name = previousSlime } }
                if let refused = friends.nameError {
                    nameError = refused
                } else {
                    toast = Toast(text: friends.lastError ?? "The names couldn't be saved.", good: false)
                }
                friends.nameError = nil
                friends.lastError = nil
            }
        }
    }

    // MARK: Sharing

    private var sharingSection: some View {
        Section {
            Toggle("Show my slime's mood", isOn: Binding(
                get: { pendingShareMood ?? friends.privacy.shareMood },
                set: { value in
                    pendingShareMood = value
                    Task {
                        let ok = await friends.setPrivacy { $0.shareMood = value }
                        pendingShareMood = nil
                        if !ok { showStoreError() }
                    }
                }))
            Toggle("Allow new friend requests", isOn: Binding(
                get: { pendingAcceptRequests ?? friends.privacy.acceptRequests },
                set: { value in
                    pendingAcceptRequests = value
                    Task {
                        let ok = await friends.setPrivacy { $0.acceptRequests = value }
                        pendingAcceptRequests = nil
                        if !ok { showStoreError() }
                    }
                }))
        } header: {
            Text("What friends see")
        } footer: {
            Text("Friends always see your name and your slime's name, look and level. Never your location, your contacts or anything else on your iPhone. With requests off, nobody new can add you and you can't make a code.")
        }
    }

    // MARK: Blocked

    private var blockedSection: some View {
        Section {
            if friends.blocked.isEmpty {
                Text("Nobody is blocked.")
                    .foregroundStyle(Theme.inkSoft)
            }
            ForEach(friends.blocked) { device in
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(device.ownerName)
                            .foregroundStyle(Theme.ink)
                        Text("Blocked \(device.since.formatted(date: .abbreviated, time: .omitted))")
                            .font(.caption)
                            .foregroundStyle(Theme.inkSoft)
                    }
                    .accessibilityElement(children: .combine)
                    Spacer()
                    if unblocking == device.deviceId {
                        ProgressView()
                            .frame(minWidth: 44, minHeight: 44)
                    } else {
                        Button {
                            unblock(device)
                        } label: {
                            Text("Unblock")
                                .frame(minWidth: 44, minHeight: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Unblock \(device.ownerName)")
                    }
                }
            }
        } header: {
            Text("Blocked")
        } footer: {
            Text("Blocking ends the friendship. Blocked people can't send you requests, greetings or gifts, and your codes look unknown to them. They aren't notified.")
        }
    }

    private func unblock(_ device: BlockedDevice) {
        unblocking = device.deviceId
        Task {
            let ok = await friends.unblock(device)
            unblocking = nil
            if ok {
                toast = Toast(text: "\(device.ownerName) is unblocked.", good: true)
            } else {
                showStoreError()
            }
        }
    }

    // MARK: Connection and data

    private var connectionSection: some View {
        Section {
            if friends.isOnline {
                LabeledContent {
                    Text("slime.hyprnode.de")
                } label: {
                    Label("Online", systemImage: "checkmark.circle.fill")
                }
                Button("Go Offline", role: .destructive) { confirmOffline = true }
            } else {
                LabeledContent {
                    Text("Nothing is sent")
                } label: {
                    Label("Offline", systemImage: "wifi.slash")
                }
                Button("Go Online…") { showGoOnline = true }
            }
        } header: {
            Text("Connection")
        } footer: {
            Text(friends.isOnline
                 ? "Going offline stops all syncing. Your friends stay saved on this iPhone, and your data stays on the server until you delete it."
                 : "Friends, the leaderboard and chat need slime.hyprnode.de. Going online asks you to agree to the Terms first.")
        }
    }

    private var dataSection: some View {
        Section {
            Button(role: .destructive) {
                confirmDelete = true
            } label: {
                if deleting {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("Deleting…")
                    }
                } else {
                    Text("Delete My Data on the Server")
                }
            }
            .disabled(deleting)
        } header: {
            Text("Your data")
        } footer: {
            Text("Deletes your device, name, friendships, love, memories, scores and everything friends sent you from slime.hyprnode.de, and takes Slimy offline. Your slime on this iPhone stays.")
        }
    }

    private var documentsSection: some View {
        Section {
            Link(destination: FriendsStore.privacyURL) {
                Label("Privacy Policy", systemImage: "hand.raised")
            }
            Link(destination: FriendsStore.termsURL) {
                Label("Terms of Use", systemImage: "doc.text")
            }
            Link(destination: FriendsStore.supportURL) {
                Label("Support", systemImage: "questionmark.circle")
            }
        }
    }

    private func deleteData() {
        deleting = true
        Task {
            let ok = await friends.deleteAccount()
            deleting = false
            if ok {
                ownerDraft = ""
                toast = Toast(text: "Your data on slime.hyprnode.de is deleted.", good: true)
            } else {
                showStoreError()
            }
        }
    }

    private func showStoreError() {
        toast = Toast(text: friends.lastError ?? "Something went wrong. Please try again.", good: false)
        friends.lastError = nil
    }
}
