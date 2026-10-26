import SwiftUI
import UIKit

/// Where Friends can go from its list.
enum FriendsRoute: Hashable {
    case friend(String)
    case leaderboard
    case privacy
    case care
}

/// Friends: requests, challenges, the friends themselves, pairing with a
/// code, caring for one slime together, the leaderboard and privacy.
/// Offline it explains what going online means and shows the friends from
/// last time.
struct FriendsView: View {
    @EnvironmentObject private var friends: FriendsStore
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss

    @StateObject private var actions = FriendActionCenter()
    @State private var path: [FriendsRoute] = []
    @State private var joinCode = ""
    @State private var joining = false
    @State private var makingCode = false
    @State private var copied = false
    @State private var showGoOnline = false
    @State private var busyRequests: Set<String> = []
    @FocusState private var codeFocused: Bool

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if friends.isOnline {
                    statusSection
                    requestsSection
                    challengesSection
                    friendsSection
                    teamUpSection
                    waitingSection
                    moreSection
                } else {
                    goOnlineSection
                    offlineFriendsSection
                    offlineMoreSection
                }
            }
            .listStyle(.insetGrouped)
            .sheetTitle("Friends")
            .refreshable { await friends.refresh() }
            .navigationDestination(for: FriendsRoute.self) { route in
                destination(route)
            }
            .scrollDismissesKeyboard(.interactively)
            .friendActions(actions)
        }
        .task {
            friends.hasNews = false
            if let code = friends.pendingInviteCode { joinCode = code }
            await friends.refresh()
        }
        .onChange(of: friends.pendingInviteCode) { _, code in
            if let code { joinCode = code }
        }
        .sheet(isPresented: $showGoOnline) {
            GoOnlineView()
                .environmentObject(friends)
                .environmentObject(store)
        }
    }

    @ViewBuilder
    private func destination(_ route: FriendsRoute) -> some View {
        switch route {
        case .friend(let id):
            FriendDetailView(friendID: id)
        case .leaderboard:
            LeaderboardView()
        case .privacy:
            FriendPrivacyView()
        case .care:
            CareTogetherView()
        }
    }

    // MARK: Online

    @ViewBuilder
    private var statusSection: some View {
        if case .unavailable(let reason) = friends.status {
            Section {
                Label {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Can't sync right now")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Theme.ink)
                        Text(reason)
                            .font(.footnote)
                            .foregroundStyle(Theme.inkSoft)
                        if !friends.queue.isEmpty {
                            Text(friends.queue.count == 1
                                 ? "1 thing is waiting to be sent."
                                 : "\(friends.queue.count) things are waiting to be sent.")
                                .font(.footnote)
                                .foregroundStyle(Theme.inkSoft)
                        }
                    }
                } icon: {
                    Image(systemName: "wifi.exclamationmark")
                        .foregroundStyle(Theme.gold)
                }
                .accessibilityElement(children: .combine)
            }
        }
        if let notice = friends.profileNotice {
            Section {
                Label(notice, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(Theme.ink)
                NavigationLink(value: FriendsRoute.privacy) {
                    Text("Change Names")
                }
            }
        }
    }

    @ViewBuilder
    private var requestsSection: some View {
        if !friends.requests.incoming.isEmpty {
            Section {
                ForEach(friends.requests.incoming) { request in
                    FriendRequestRow(
                        request: request,
                        busy: busyRequests.contains(request.id),
                        accept: { answer(request, accept: true) },
                        decline: { answer(request, accept: false) },
                        report: { actions.reportTarget = ReportTarget(request: request) },
                        block: { actions.blockTarget = ReportTarget(request: request) })
                }
            } header: {
                Text("Friend requests")
            } footer: {
                Text("Accepting shares your name and your slime's name, look and level with them.")
            }
        }
    }

    @ViewBuilder
    private var challengesSection: some View {
        let open = friends.openChallenges
        let waiting = friends.waitingChallenges
        let finished = Array(friends.finishedChallenges.prefix(3))
        if !open.isEmpty || !waiting.isEmpty || !finished.isEmpty {
            Section {
                ForEach(open) { challenge in
                    FriendChallengeRow(challenge: challenge) {
                        friends.playChallenge = challenge
                        dismiss()
                    }
                }
                ForEach(waiting) { challenge in
                    FriendChallengeResultRow(challenge: challenge)
                }
                ForEach(finished) { challenge in
                    FriendChallengeResultRow(challenge: challenge)
                }
            } header: {
                Text("Challenges")
            } footer: {
                Text("You both play the same board. Send a challenge from the arcade after a round.")
            }
        }
    }

    /// The friend in your island first, then by name.
    private var sortedFriends: [Friend] {
        let partnerID = friends.partner?.id
        return friends.friends.sorted { a, b in
            if a.id == partnerID { return true }
            if b.id == partnerID { return false }
            return a.slime.name.localizedCaseInsensitiveCompare(b.slime.name) == .orderedAscending
        }
    }

    private var friendsSection: some View {
        Section {
            if friends.friends.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("No friends yet")
                        .font(.headline)
                        .foregroundStyle(Theme.ink)
                    Text("Share your code with someone who has Slimy, or enter theirs below.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSoft)
                }
                .padding(.vertical, 6)
                .accessibilityElement(children: .combine)
            }
            ForEach(sortedFriends) { friend in
                friendRow(friend)
            }
        } header: {
            HStack {
                Text("Friends")
                Spacer()
                if !friends.friends.isEmpty {
                    Text("\(friends.friends.count) of \(FriendsStore.maxFriends)")
                        .monospacedDigit()
                }
            }
        } footer: {
            Text("Hug, visit, send gifts and greetings. Swipe a friend for more.")
        }
    }

    private func friendRow(_ friend: Friend) -> some View {
        let isPartner = friends.partner?.id == friend.id
        return FriendListRow(
            friend: friend,
            isPartner: isPartner,
            busy: actions.busy.contains(friend.id),
            open: { path.append(.friend(friend.id)) },
            action: { item in actions.quick(item, on: friend) })
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button {
                actions.blockTarget = ReportTarget(friend: friend)
            } label: {
                Label("Block", systemImage: "hand.raised.fill")
            }
            .tint(Theme.danger)
            Button {
                actions.removeFor = friend
            } label: {
                Label("Remove", systemImage: "person.badge.minus")
            }
            .tint(Color(uiColor: .systemGray))
            Button {
                actions.reportTarget = ReportTarget(friend: friend)
            } label: {
                Label("Report", systemImage: "exclamationmark.bubble.fill")
            }
            .tint(Theme.gold)
        }
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            if !isPartner {
                Button {
                    showInIsland(friend)
                } label: {
                    Label("Island", systemImage: "sparkles")
                }
                .tint(Theme.accent)
            }
        }
        .contextMenu {
            Button {
                showInIsland(friend)
            } label: {
                Label(isPartner ? "In Your Island" : "Show in My Island", systemImage: "sparkles")
            }
            .disabled(isPartner)
            Divider()
            Button {
                actions.reportTarget = ReportTarget(friend: friend)
            } label: {
                Label("Report…", systemImage: "exclamationmark.bubble")
            }
            Button(role: .destructive) {
                actions.removeFor = friend
            } label: {
                Label("Remove Friend", systemImage: "person.badge.minus")
            }
            Button(role: .destructive) {
                actions.blockTarget = ReportTarget(friend: friend)
            } label: {
                Label("Block", systemImage: "hand.raised")
            }
        }
    }

    private var teamUpSection: some View {
        Section {
            if !friends.privacy.acceptRequests {
                Label("New requests are off, so you can't make a code. Turn them on under Privacy & blocked.",
                      systemImage: "person.crop.circle.badge.xmark")
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
            } else if let code = friends.activePairCode {
                codeCard(code)
            } else {
                AppButton(title: makingCode ? "Getting your code…" : "Get My Code", systemIcon: "number",
                          kind: .secondary, action: makeCode)
                    .disabled(makingCode)
            }

            HStack(spacing: 10) {
                TextField("Friend's code", text: $joinCode)
                    .font(.title3.monospaced().weight(.semibold))
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .keyboardType(.asciiCapable)
                    .submitLabel(.go)
                    .focused($codeFocused)
                    .onSubmit(connect)
                    .onChange(of: joinCode) { _, new in
                        let clean = FriendInvite.sanitize(new)
                        if clean != new { joinCode = clean }
                    }
                    .accessibilityLabel("Friend's code")
                    .accessibilityHint("Six letters and numbers.")
                AppButton(title: joining ? "Connecting…" : "Connect", compact: true, action: connect)
                    .disabled(joinCode.count < FriendInvite.length || joining)
            }
            .frame(minHeight: 44)
        } header: {
            Text("Team up")
        } footer: {
            Text("Codes work once, for 10 minutes. You can have up to \(FriendsStore.maxFriends) friends.")
        }
    }

    private func codeCard(_ code: PairCode) -> some View {
        VStack(spacing: 10) {
            Text("Your code")
                .font(.subheadline)
                .foregroundStyle(Theme.inkSoft)
            Text(code.code)
                .font(.system(.largeTitle, design: .monospaced).weight(.bold))
                .tracking(6)
                .foregroundStyle(Theme.ink)
                .textSelection(.enabled)
                .accessibilityLabel("Your code: \(code.code.map(String.init).joined(separator: " "))")
            Text("Valid until \(code.expiresAt.formatted(date: .omitted, time: .shortened)). Works once.")
                .font(.footnote)
                .foregroundStyle(Theme.inkSoft)
            HStack(spacing: 10) {
                AppButton(title: copied ? "Copied" : "Copy", systemIcon: copied ? "checkmark" : "doc.on.doc",
                          kind: .secondary) {
                    UIPasteboard.general.string = code.code
                    copied = true
                }
                ShareLink(item: FriendInvite.message(for: code.code)) {
                    Label("Share", systemImage: "square.and.arrow.up")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Theme.onAccent)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .tint(Theme.accent)
            }
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
            // Clears the card once the code expires.
            let wait = code.expiresAt.timeIntervalSinceNow
            guard wait > 0 else { return }
            do {
                try await Task.sleep(for: .seconds(wait + 1))
            } catch {
                return
            }
            friends.objectWillChange.send()
        }
    }

    @ViewBuilder
    private var waitingSection: some View {
        if !friends.requests.outgoing.isEmpty {
            Section {
                ForEach(friends.requests.outgoing) { request in
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(request.slime?.name ?? request.ownerName)
                                .font(.body.weight(.semibold))
                                .foregroundStyle(Theme.ink)
                            Text("Sent \(request.createdAt.formatted(.relative(presentation: .named)))")
                                .font(.caption)
                                .foregroundStyle(Theme.inkSoft)
                        }
                        .accessibilityElement(children: .combine)
                        Spacer()
                        Button {
                            actions.perform(success: "Request withdrawn.") { await friends.decline(request) }
                        } label: {
                            Text("Withdraw")
                                .frame(minWidth: 44, minHeight: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Withdraw request to \(request.ownerName)")
                    }
                }
            } header: {
                Text("Waiting for an answer")
            }
        }
    }

    private var moreSection: some View {
        Section {
            careLink
            NavigationLink(value: FriendsRoute.leaderboard) {
                Label("Leaderboard", systemImage: "trophy")
            }
            NavigationLink(value: FriendsRoute.privacy) {
                Label("Privacy & blocked", systemImage: "hand.raised")
            }
        }
    }

    /// Care Together, with who you share your slime with.
    private var careLink: some View {
        NavigationLink(value: FriendsRoute.care) {
            LabeledContent {
                if let link = store.state.care {
                    Text(link.partnerName)
                } else if friends.pendingCare != nil || friends.activeCareCode != nil {
                    Text("Waiting")
                }
            } label: {
                Label("Care Together", systemImage: "person.2.fill")
            }
        }
    }

    // MARK: Offline

    private var goOnlineSection: some View {
        let slime = store.state.slime
        return Section {
            VStack(spacing: 14) {
                FriendPortrait(skin: slime.skin, stage: slime.stage, mood: .idle, accessories: slime.equipped)
                    .frame(width: 104, height: 86)
                    .accessibilityHidden(true)
                Text("Play with friends")
                    .font(.title2.bold())
                    .foregroundStyle(Theme.ink)
                Text("Connect with people who have Slimy. Your slimes can hug, visit, send gifts and challenge each other, and you can care for one slime together.")
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                    .multilineTextAlignment(.center)
                if let code = friends.pendingInviteCode {
                    Text("Go online to connect with code \(code).")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Theme.ink)
                        .multilineTextAlignment(.center)
                }
                if let ban = friends.banNotice {
                    Label(ban, systemImage: "exclamationmark.octagon.fill")
                        .font(.subheadline)
                        .foregroundStyle(Theme.danger)
                }
                AppButton(title: "Go Online", systemIcon: "person.2.fill") { showGoOnline = true }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
        } footer: {
            Text("Friends is optional. Your slime and every game work without it, and nothing leaves this iPhone until you go online.")
        }
    }

    @ViewBuilder
    private var offlineFriendsSection: some View {
        if !friends.friends.isEmpty {
            Section {
                ForEach(sortedFriends) { friend in
                    FriendListRow(friend: friend, isPartner: friends.partner?.id == friend.id)
                }
            } header: {
                Text("Friends")
            } footer: {
                Text("This is how things looked last time. Go online to hug, visit and send gifts.")
            }
        }
    }

    @ViewBuilder
    private var offlineMoreSection: some View {
        Section {
            careLink
            if friends.hasServerAccount {
                NavigationLink(value: FriendsRoute.privacy) {
                    Label("Privacy & your data", systemImage: "hand.raised")
                }
            }
        } footer: {
            if friends.hasServerAccount {
                Text("You can delete what slime.hyprnode.de keeps about you at any time.")
            }
        }
    }

    // MARK: Actions

    private func showInIsland(_ friend: Friend) {
        friends.partnerID = friend.id
        actions.say("\(friend.slime.name) now sits next to your slime in the island.")
    }

    private func answer(_ request: FriendRequest, accept: Bool) {
        busyRequests.insert(request.id)
        let name = request.slime?.name ?? request.ownerName
        Task {
            let ok: Bool
            if accept {
                ok = await friends.accept(request)
            } else {
                ok = await friends.decline(request)
            }
            busyRequests.remove(request.id)
            if ok {
                actions.say(accept ? "You and \(name) are friends now." : "Request declined.")
            } else {
                actions.showError()
            }
        }
    }

    private func makeCode() {
        makingCode = true
        Task {
            let ok = await friends.makeCode()
            makingCode = false
            if !ok { actions.showError() }
        }
    }

    private func connect() {
        let code = FriendInvite.sanitize(joinCode)
        guard code.count == FriendInvite.length, !joining else { return }
        codeFocused = false
        joining = true
        Task {
            let line = await friends.join(code: code)
            joining = false
            if let line {
                joinCode = ""
                actions.say(line)
            } else {
                actions.showError()
            }
        }
    }
}
