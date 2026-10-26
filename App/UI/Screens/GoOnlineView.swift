import SwiftUI

/// Consent before anything goes to slime.hyprnode.de: what friends see, what
/// the server keeps, the names, and the Terms. Nothing is sent before the
/// player agrees and taps Go Online. Dismisses itself once the device is
/// online. Shown from Friends, Chat and the leaderboard.
struct GoOnlineView: View {
    @ObservedObject private var friends = FriendsStore.shared
    @ObservedObject private var store = GameStore.shared
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Field?

    @State private var ownerName = ""
    @State private var slimeName = ""
    @State private var agreed = false
    @State private var busy = false
    @State private var error: String?
    /// The server refused one of the names; shown next to them.
    @State private var nameError: String?
    @State private var prepared = false

    private enum Field: Hashable {
        case owner, slime
    }

    private static let slimeNameLimit = HyprnodeAPI.slimeNameLimit

    init() {}

    var body: some View {
        NavigationStack {
            Form {
                intro
                friendsSee
                serverKeeps
                names
                terms
                if let message = friends.banNotice ?? error {
                    Section {
                        Label(message, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(Theme.danger)
                    }
                }
                Section {
                    AppButton(title: busy ? "Going online…" : "Go Online", systemIcon: busy ? nil : "person.2.fill") {
                        Task { await submit() }
                    }
                    .disabled(!canSubmit)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                } footer: {
                    Text("You can go offline, or delete everything on the server, at any time in Friends under Privacy & blocked.")
                        .padding(.top, 8)
                }
            }
            .navigationTitle("Go Online")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(busy)
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .interactiveDismissDisabled(busy)
            .onAppear(perform: prepare)
        }
    }

    // MARK: Sections

    private var intro: some View {
        let slime = store.state.slime
        return Section {
            VStack(spacing: 12) {
                FriendPortrait(skin: slime.skin, stage: slime.stage, mood: .idle, accessories: slime.equipped)
                    .frame(width: 96, height: 80)
                    .accessibilityHidden(true)
                Text("Friends and chat use slime.hyprnode.de")
                    .font(.title3.bold())
                    .foregroundStyle(Theme.ink)
                    .multilineTextAlignment(.center)
                Text("Slimy works fully offline. Going online lets your slime meet friends, compare scores and talk with you. Here is exactly what that shares.")
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
    }

    private var friendsSee: some View {
        Section {
            GoOnlineInfoRow(symbol: "person.text.rectangle", title: "Your name",
                    detail: "The name you enter below, next to your slime.")
            GoOnlineInfoRow(symbol: "drop.fill", title: "Your slime",
                    detail: "Its name, colour, outfit, level and mood. You can hide the mood later.")
            GoOnlineInfoRow(symbol: "heart.fill", title: "What you do together",
                    detail: "Hugs, visits, gifts, greetings, love, challenge scores and your best game scores.")
        } header: {
            Text("What friends see")
        }
    }

    private var serverKeeps: some View {
        Section {
            GoOnlineInfoRow(symbol: "server.rack", title: "Stored on slime.hyprnode.de",
                    detail: "A random device ID, the names and slime details above, your friendships, best scores and reports you send, and what friends sent you for 30 days.")
            GoOnlineInfoRow(symbol: "eye.slash", title: "Never collected",
                    detail: "No Apple ID, phone number, email, contacts, location, advertising ID or tracking.")
            GoOnlineInfoRow(symbol: "bubble.left.and.bubble.right", title: "Chat",
                    detail: "If you talk to your slime, your messages go through the server to an AI service that writes the replies. You're asked before the first one. Chats aren't stored on the server.")
        } header: {
            Text("What goes to the server")
        }
    }

    private var names: some View {
        Section {
            LabeledContent("Your name") {
                TextField("First name or nickname", text: $ownerName)
                    .multilineTextAlignment(.trailing)
                    .textInputAutocapitalization(.words)
                    .textContentType(.nickname)
                    .submitLabel(.next)
                    .focused($focused, equals: .owner)
                    .onSubmit { focused = .slime }
                    .onChange(of: ownerName) { _, new in
                        if new.count > HyprnodeAPI.nameLimit { ownerName = String(new.prefix(HyprnodeAPI.nameLimit)) }
                        if prepared { nameError = nil }
                    }
            }
            LabeledContent("Your slime") {
                TextField("Slime's name", text: $slimeName)
                    .multilineTextAlignment(.trailing)
                    .textInputAutocapitalization(.words)
                    .submitLabel(.done)
                    .focused($focused, equals: .slime)
                    .onSubmit { focused = nil }
                    .onChange(of: slimeName) { _, new in
                        if new.count > Self.slimeNameLimit { slimeName = String(new.prefix(Self.slimeNameLimit)) }
                        if prepared { nameError = nil }
                    }
            }
            if let nameError {
                Label(nameError, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(Theme.danger)
            }
        } header: {
            Text("Names friends see")
        } footer: {
            Text("Please don't use your full name. Rude names, links, email addresses and phone numbers aren't accepted. Changing your slime's name here renames it everywhere.")
        }
    }

    private var terms: some View {
        Section {
            Link(destination: FriendsStore.termsURL) {
                Label("Terms of Use", systemImage: "doc.text")
            }
            Link(destination: FriendsStore.privacyURL) {
                Label("Privacy Policy", systemImage: "hand.raised")
            }
            Toggle("I agree to the Terms of Use and the Privacy Policy", isOn: $agreed)
        } footer: {
            Text("Be kind. Anyone can report a name or a person, and the Slimy team can reset names or block devices.")
        }
    }

    // MARK: Going online

    private var cleanedOwner: String { HyprnodeAPI.cleanedName(ownerName) }
    private var cleanedSlime: String { String(HyprnodeAPI.cleanedName(slimeName).prefix(Self.slimeNameLimit)) }

    private var canSubmit: Bool {
        agreed && !busy && !cleanedOwner.isEmpty && !cleanedSlime.isEmpty
    }

    private func prepare() {
        guard !prepared else { return }
        ownerName = friends.ownerName
        slimeName = store.state.slime.name
        prepared = true
        if ownerName.isEmpty { focused = .owner }
    }

    private func submit() async {
        guard canSubmit else { return }
        focused = nil
        error = nil
        nameError = nil
        busy = true
        let owner = cleanedOwner
        let slime = cleanedSlime
        let previousSlime = store.state.slime.name
        let previousOwner = friends.ownerName
        if slime != previousSlime { store.update { $0.slime.name = slime } }
        friends.ownerName = owner

        let ok = await friends.goOnline()
        busy = false
        if ok {
            Feedback.shared.play(.success)
            dismiss()
        } else {
            // Nothing changes when going online fails, not even the names;
            // the fields keep what was typed.
            if slime != previousSlime { store.update { $0.slime.name = previousSlime } }
            friends.ownerName = previousOwner
            if let refused = friends.nameError {
                nameError = refused
            } else {
                error = friends.lastError ?? "Couldn't go online. Please try again."
            }
            friends.nameError = nil
            friends.lastError = nil
        }
    }
}

/// An SF Symbol with a bold line and an explanation under it.
private struct GoOnlineInfoRow: View {
    let symbol: String
    let title: String
    let detail: String

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
        } icon: {
            Image(systemName: symbol)
                .foregroundStyle(Theme.accent)
        }
        .accessibilityElement(children: .combine)
    }
}
