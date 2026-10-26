import SwiftUI
import UIKit

/// Care codes and the text the share sheet sends for them.
enum CareInvite {
    /// What the share sheet sends with a care code.
    static func message(for code: String, slimeName: String) -> String {
        "Care for my slime \(slimeName) with me in Slimy! Code: \(code) — get Slimy at https://slime.hyprnode.de"
    }

    /// The code in a pasted invite ("… Code: ABC123 …"), or nil when the
    /// text isn't one.
    static func code(in text: String) -> String? {
        guard let range = text.range(of: "Code:", options: .caseInsensitive) else { return nil }
        let rest = text[range.upperBound...].trimmingCharacters(in: .whitespaces)
        let candidate = String(rest.prefix(FriendInvite.length)).uppercased()
        guard candidate.count == FriendInvite.length,
              candidate.allSatisfy({ FriendInvite.alphabet.contains($0) }) else { return nil }
        return candidate
    }
}

/// Care Together: two people look after one slime. The host gets a code,
/// the joiner enters it and adopts the host's slime (their own waits safely
/// on their iPhone). Once paired it shows the partner, what they did
/// recently, and how to stop. Reachable from Friends and Settings, so it
/// lives inside the caller's NavigationStack.
struct CareTogetherView: View {
    @ObservedObject private var friends = FriendsStore.shared
    @ObservedObject private var store = GameStore.shared
    @FocusState private var codeFocused: Bool

    @State private var joinCode = ""
    @State private var joining = false
    @State private var makingCode = false
    @State private var copied = false
    @State private var showGoOnline = false
    @State private var confirmStop = false
    @State private var stopping = false
    @State private var showJoinConfirm = false
    /// The pairing the join question was already asked for, so it pops up once.
    @State private var askedJoin: String?
    @State private var answeringJoin = false
    @State private var toast: Toast?
    @State private var now = Date.now

    /// How long to wait for the partner's slime before asking anyway.
    private static let joinAskDelay: TimeInterval = 12

    init() {}

    var body: some View {
        List {
            header
            if let link = store.state.care {
                if !friends.isOnline { offlineNotice(link) }
                if let problem = friends.careProblem, friends.isOnline { problemSection(problem) }
                partnerSection(link)
                activitySection(link)
                stopSection(link)
            } else if let pending = friends.pendingCare {
                if !friends.isOnline { goOnlineSection }
                pendingSection(pending)
            } else {
                howItWorks
                if friends.isOnline {
                    hostSection
                    joinSection
                } else {
                    goOnlineSection
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Care Together")
        .navigationBarTitleDisplayMode(.inline)
        .scrollDismissesKeyboard(.interactively)
        .refreshable { await friends.syncCare() }
        .task { await friends.syncCare() }
        .task {
            // Keeps "5 min ago" and the code's validity current.
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .seconds(20))
                } catch {
                    return
                }
                now = .now
            }
        }
        .task(id: friends.pendingCare?.partnerID) { await askToJoinWhenReady() }
        .onChange(of: friends.pendingCare?.snapshot != nil) { _, arrived in
            if arrived { askToJoinIfNeeded() }
        }
        .confirmationDialog(joinQuestion, isPresented: $showJoinConfirm, titleVisibility: .visible) {
            Button("Care for \(pendingSlimeTitle)") { confirmJoin() }
            // Only closes the question; "Don't Join" below declines.
            Button("Not Now", role: .cancel) {}
        } message: {
            Text(joinExplanation)
        }
        .confirmationDialog(stopTitle, isPresented: $confirmStop, titleVisibility: .visible) {
            Button("Stop Sharing", role: .destructive) { stopSharing() }
        } message: {
            Text(stopExplanation)
        }
        .sheet(isPresented: $showGoOnline) {
            GoOnlineView()
        }
        .friendsToast($toast)
    }

    // MARK: Header

    private var slime: Slime { store.state.slime }

    private var header: some View {
        Section {
            VStack(spacing: 12) {
                FriendPortrait(skin: slime.skin, stage: slime.stage, mood: slime.mood, accessories: slime.equipped)
                    .frame(width: 112, height: 92)
                    .accessibilityHidden(true)
                Text(headline)
                    .font(.title2.bold())
                    .foregroundStyle(Theme.ink)
                    .multilineTextAlignment(.center)
                Text(subheadline)
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .accessibilityElement(children: .combine)
        }
    }

    private var headline: String {
        if store.state.care != nil { return "\(slime.name) has two caretakers" }
        return "One slime, two caretakers"
    }

    private var subheadline: String {
        if let link = store.state.care {
            return "You and \(link.partnerName) look after \(slime.name) together. Whatever either of you does reaches the other iPhone."
        }
        return "Share \(slime.name) with one person you trust, or join theirs, and look after it together."
    }

    // MARK: Not sharing

    private var howItWorks: some View {
        Section {
            CareInfoRow(symbol: "person.2.fill", title: "One slime, two caretakers",
                        detail: "Feeding, playing, baths, pets and bedtime on either iPhone reach the other one, and you both see what the other did.")
            CareInfoRow(symbol: "bag.fill", title: "Coins and things stay yours",
                        detail: "Coins, things you own, records, challenges and settings stay personal. Only the slime is shared.")
            CareInfoRow(symbol: "lock.shield", title: "Your own slime waits safely",
                        detail: "If you join someone's slime, yours stays on this iPhone and comes back as soon as you stop sharing.")
        } header: {
            Text("How it works")
        }
    }

    private var goOnlineSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 10) {
                Text("Care Together needs slime.hyprnode.de")
                    .font(.headline)
                    .foregroundStyle(Theme.ink)
                Text("Both iPhones talk through the server to share the slime. Going online asks you to agree to the Terms first.")
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                if let ban = friends.banNotice {
                    Label(ban, systemImage: "exclamationmark.octagon.fill")
                        .font(.subheadline)
                        .foregroundStyle(Theme.danger)
                }
                AppButton(title: "Go Online", systemIcon: "person.2.fill") { showGoOnline = true }
                    .padding(.top, 4)
            }
            .padding(.vertical, 6)
        }
    }

    @ViewBuilder
    private var hostSection: some View {
        Section {
            if let code = friends.activeCareCode {
                codeCard(code)
            } else {
                AppButton(title: makingCode ? "Getting a code…" : "Get a Care Code", systemIcon: "number",
                          action: makeCode)
                    .disabled(makingCode)
            }
        } header: {
            Text("Share \(slime.name)")
        } footer: {
            Text("Whoever enters your code cares for \(slime.name) with you. \(slime.name) stays yours; their own slime waits on their iPhone. Codes work once, for 10 minutes.")
        }
    }

    private func codeCard(_ code: PairCode) -> some View {
        VStack(spacing: 10) {
            Text("Your care code")
                .font(.subheadline)
                .foregroundStyle(Theme.inkSoft)
            Text(code.code)
                .font(.system(.largeTitle, design: .monospaced).weight(.bold))
                .tracking(6)
                .foregroundStyle(Theme.ink)
                .textSelection(.enabled)
                .accessibilityLabel("Your care code: \(code.code.map(String.init).joined(separator: " "))")
            Text("Valid until \(code.expiresAt.formatted(date: .omitted, time: .shortened)). Works once.")
                .font(.footnote)
                .foregroundStyle(Theme.inkSoft)
            HStack(spacing: 10) {
                AppButton(title: copied ? "Copied" : "Copy", systemIcon: copied ? "checkmark" : "doc.on.doc",
                          kind: .secondary) {
                    UIPasteboard.general.string = code.code
                    copied = true
                }
                ShareLink(item: CareInvite.message(for: code.code, slimeName: slime.name)) {
                    Label("Share", systemImage: "square.and.arrow.up")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Theme.onAccent)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .tint(Theme.accent)
            }
            HStack(spacing: 8) {
                ProgressView()
                Text("Waiting for someone to join…")
                    .font(.footnote)
                    .foregroundStyle(Theme.inkSoft)
            }
            .accessibilityElement(children: .combine)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .task(id: copied) {
            guard copied else { return }
            do {
                try await Task.sleep(for: .seconds(2))
            } catch {
                return
            }
            copied = false
        }
        .task(id: code.code) {
            // Hides the card once the code runs out.
            let wait = code.expiresAt.timeIntervalSinceNow
            guard wait > 0 else { return }
            do {
                try await Task.sleep(for: .seconds(wait + 1))
            } catch {
                return
            }
            now = .now
            friends.objectWillChange.send()
        }
    }

    private var joinSection: some View {
        Section {
            HStack(spacing: 10) {
                TextField("Partner's code", text: $joinCode)
                    .font(.title3.monospaced().weight(.semibold))
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .keyboardType(.asciiCapable)
                    .submitLabel(.join)
                    .focused($codeFocused)
                    .onSubmit(join)
                    .onChange(of: joinCode) { _, new in
                        let clean = CareInvite.code(in: new) ?? FriendInvite.sanitize(new)
                        if clean != new { joinCode = clean }
                    }
                    .accessibilityLabel("Partner's care code")
                    .accessibilityHint("Six letters and numbers.")
                AppButton(title: joining ? "Joining…" : "Join", compact: true, action: join)
                    .disabled(joinCode.count < FriendInvite.length || joining)
            }
            .frame(minHeight: 44)
        } header: {
            Text("Join someone's slime")
        } footer: {
            Text("Enter the code your partner got here in Care Together. You'll look after their slime; \(slime.name) waits safely on this iPhone and comes back if you stop sharing.")
        }
    }

    // MARK: Joining

    private var pendingSlimeTitle: String {
        guard let pending = friends.pendingCare else { return "the slime" }
        if let name = pending.slimeName, !name.isEmpty { return name }
        return "\(pending.partnerName)'s slime"
    }

    private var joinQuestion: String {
        guard let pending = friends.pendingCare else { return "Join?" }
        return "Care for \(pendingSlimeTitle) with \(pending.partnerName)?"
    }

    private var joinExplanation: String {
        "\(pendingSlimeTitle) moves onto this iPhone and you both look after it. Your own slime, \(slime.name), waits safely on this iPhone and comes back if you stop sharing. Coins and things stay your own."
    }

    private func pendingSection(_ pending: PendingCare) -> some View {
        Section {
            if pending.confirmed {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Waiting for \(pendingSlimeTitle) to arrive…")
                        .foregroundStyle(Theme.ink)
                }
                .accessibilityElement(children: .combine)
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    Text(joinQuestion)
                        .font(.headline)
                        .foregroundStyle(Theme.ink)
                    Text(joinExplanation)
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSoft)
                    if pending.snapshot == nil {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("Getting \(pending.partnerName)'s slime…")
                                .font(.footnote)
                                .foregroundStyle(Theme.inkSoft)
                        }
                        .padding(.top, 2)
                        .accessibilityElement(children: .combine)
                    }
                }
                .padding(.vertical, 4)
                AppButton(title: "Care for \(pendingSlimeTitle)", systemIcon: "checkmark", action: confirmJoin)
                    .disabled(answeringJoin)
            }
            AppButton(title: pending.confirmed ? "Cancel" : "Don't Join", kind: .secondary, action: cancelJoin)
                .disabled(answeringJoin)
        } header: {
            Text("Joining \(pending.partnerName)")
        } footer: {
            Text("The slime comes over from \(pending.partnerName)'s iPhone. That happens while \(pending.partnerName) has Slimy open.")
        }
    }

    /// Asks once the partner's slime is here, or after a short wait anyway.
    private func askToJoinWhenReady() async {
        guard let pending = friends.pendingCare, !pending.confirmed else { return }
        if pending.snapshot == nil {
            let waited = Date.now.timeIntervalSince(pending.joinedAt)
            if waited < Self.joinAskDelay {
                do {
                    try await Task.sleep(for: .seconds(Self.joinAskDelay - waited))
                } catch {
                    return
                }
            }
        }
        askToJoinIfNeeded()
    }

    private func askToJoinIfNeeded() {
        guard let pending = friends.pendingCare, !pending.confirmed, askedJoin != pending.partnerID,
              !answeringJoin else { return }
        askedJoin = pending.partnerID
        showJoinConfirm = true
    }

    // MARK: Sharing

    private func offlineNotice(_ link: CareLink) -> some View {
        Section {
            Label("You're offline. What you do for \(slime.name) reaches \(link.partnerName) once you go online again.",
                  systemImage: "wifi.slash")
                .font(.subheadline)
                .foregroundStyle(Theme.ink)
            Button("Go Online…") { showGoOnline = true }
        }
    }

    private func problemSection(_ problem: String) -> some View {
        Section {
            Label(problem, systemImage: "exclamationmark.triangle.fill")
                .font(.subheadline)
                .foregroundStyle(Theme.ink)
            if friends.careNameNotice != nil {
                // The server refused the shared slime's name: rename it
                // where the names friends see are edited.
                NavigationLink {
                    FriendPrivacyView()
                        .environmentObject(friends)
                        .environmentObject(store)
                } label: {
                    Label("Choose a Different Name", systemImage: "pencil")
                }
            }
        }
    }

    private func partnerSection(_ link: CareLink) -> some View {
        Section {
            HStack(spacing: 12) {
                Image(systemName: "person.crop.circle.fill")
                    .font(.largeTitle)
                    .foregroundStyle(Theme.accent)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(link.partnerName)
                            .font(.headline)
                            .foregroundStyle(Theme.ink)
                            .lineLimit(1)
                        Tag(text: link.role == CareRole.host ? "Joined you" : "Host")
                    }
                    Text(roleDetail(link))
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSoft)
                }
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)
            if let since = friends.careSince {
                LabeledContent("Together since") {
                    Text(since.formatted(date: .abbreviated, time: .omitted))
                }
            }
        } header: {
            Text("Caring with")
        }
    }

    private func roleDetail(_ link: CareLink) -> String {
        if link.role == CareRole.host {
            return "Joined to care for \(slime.name) with you."
        }
        return "\(slime.name) came from \(link.partnerName)'s iPhone. You care for it together."
    }

    private func activitySection(_ link: CareLink) -> some View {
        Section {
            if friends.careActivity.isEmpty {
                Text("Nothing yet. When \(link.partnerName) feeds, plays with or pets \(slime.name), it shows up here.")
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
            }
            ForEach(friends.careActivity) { entry in
                CareActivityRow(entry: entry, now: now)
            }
        } header: {
            Text("Recent activity")
        } footer: {
            Text(activityFooter)
        }
    }

    private var activityFooter: String {
        let kept = "What \(store.state.care?.partnerName ?? "your partner") did in the last 7 days."
        guard let synced = friends.careSyncedAt else { return kept }
        return "\(kept) Updated \(CareActivity.ago(synced, now: max(now, synced)))."
    }

    private func stopSection(_ link: CareLink) -> some View {
        Section {
            Button(role: .destructive) {
                confirmStop = true
            } label: {
                if stopping {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("Stopping…")
                    }
                } else {
                    Text("Stop Sharing")
                }
            }
            .disabled(stopping || !friends.isOnline)
        } footer: {
            Text(link.role == CareRole.host
                 ? "\(slime.name) stays with you if you stop. \(link.partnerName) gets their own slime back."
                 : "If you stop, \(slime.name) stays with \(link.partnerName) and your own slime comes back.")
        }
    }

    private var stopTitle: String { "Stop sharing \(slime.name)?" }

    private var stopExplanation: String {
        guard let link = store.state.care else { return "" }
        if link.role == CareRole.host {
            return "\(link.partnerName) stops caring for \(slime.name) and gets their own slime back. \(slime.name) stays with you on this iPhone, with everything you both did for it."
        }
        let own = link.ownBackup?.name ?? "your own slime"
        return "\(slime.name) goes back to \(link.partnerName) only. \(own) comes back to this iPhone right away. Coins and things stay as they are."
    }

    // MARK: Actions

    private func makeCode() {
        makingCode = true
        Task {
            let ok = await friends.makeCareCode()
            makingCode = false
            if !ok { showError() }
        }
    }

    private func join() {
        let code = FriendInvite.sanitize(joinCode)
        guard code.count == FriendInvite.length, !joining else { return }
        codeFocused = false
        joining = true
        Task {
            let ok = await friends.joinCare(code: code)
            joining = false
            if ok {
                joinCode = ""
                Feedback.shared.play(.success)
            } else {
                showError()
            }
        }
    }

    private func confirmJoin() {
        answeringJoin = true
        Task {
            await friends.confirmJoin()
            answeringJoin = false
        }
    }

    private func cancelJoin() {
        answeringJoin = true
        Task {
            let ok = await friends.cancelJoin()
            answeringJoin = false
            if ok {
                toast = Toast(text: "You didn't join. \(slime.name) stays as it was.", good: true)
            } else {
                showError()
            }
        }
    }

    private func stopSharing() {
        stopping = true
        let partner = store.state.care?.partnerName ?? "your partner"
        Task {
            let ok = await friends.leaveCare()
            stopping = false
            if ok {
                Feedback.shared.play(.success)
                toast = Toast(text: "You stopped sharing with \(partner). \(slime.name) is here with you.", good: true)
            } else {
                showError()
            }
        }
    }

    private func showError() {
        toast = Toast(text: friends.lastError ?? "Something went wrong. Please try again.", good: false)
        friends.lastError = nil
    }
}

/// An SF Symbol with a bold line and an explanation under it.
private struct CareInfoRow: View {
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

/// "Anna fed Blob · 5 min ago", with the food or game it was about.
private struct CareActivityRow: View {
    let entry: CareActivity
    let now: Date

    var body: some View {
        HStack(spacing: 12) {
            icon
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.text)
                    .font(.body)
                    .foregroundStyle(Theme.ink)
                + Text(" · \(CareActivity.ago(entry.at, now: max(now, entry.at)))")
                    .font(.body)
                    .foregroundStyle(Theme.inkSoft)
                if let detail = entry.detail {
                    Text(detail)
                        .font(.footnote)
                        .foregroundStyle(Theme.inkSoft)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var icon: some View {
        if let sprite = entry.sprite {
            ItemIcon(name: sprite, size: 36)
        } else {
            Image(systemName: entry.symbol)
                .font(.body.weight(.semibold))
                .foregroundStyle(Theme.accent)
                .frame(width: 36, height: 36)
                .background(Theme.well, in: RoundedRectangle(cornerRadius: 36 * 0.26, style: .continuous))
        }
    }
}
