import SwiftUI

/// One friend: how their slime is doing, what's left today, the shared
/// garden and memories, with the same actions as the list.
struct FriendDetailView: View {
    let friendID: String

    @EnvironmentObject private var friends: FriendsStore
    @Environment(\.dismiss) private var dismiss
    @StateObject private var actions = FriendActionCenter()
    @State private var loadError: String?

    var body: some View {
        Group {
            if let friend = friends.friend(friendID) {
                content(friend)
            } else {
                ContentUnavailableView("Not friends anymore", systemImage: "person.crop.circle.badge.xmark",
                                       description: Text("This slime is no longer on your friends list."))
            }
        }
        .navigationTitle(friends.friend(friendID)?.slime.name ?? "Friend")
        .navigationBarTitleDisplayMode(.inline)
        .friendActions(actions)
        .task { await load() }
        .onChange(of: actions.gone) { _, id in
            if id == friendID { dismiss() }
        }
    }

    private func content(_ friend: Friend) -> some View {
        List {
            header(friend)
            together(friend)
            if let detail = friends.details[friendID] {
                today(detail, friend: friend)
                garden(detail.coop, friend: friend)
                memories(detail.memories)
            } else if let loadError {
                Section {
                    Label(loadError, systemImage: "wifi.exclamationmark")
                        .foregroundStyle(Theme.inkSoft)
                    Button("Try Again") { Task { await load() } }
                }
            } else if friends.isOnline {
                Section {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Loading memories and garden…")
                            .foregroundStyle(Theme.inkSoft)
                    }
                }
            }
            manage(friend)
        }
        .listStyle(.insetGrouped)
        .refreshable { await load() }
    }

    // MARK: Sections

    private func header(_ friend: Friend) -> some View {
        Section {
            VStack(spacing: 12) {
                FriendPortrait(skin: friend.slime.skin, stage: friend.stage, mood: friend.slime.mood,
                               accessories: friend.slime.equipped ?? Accessories())
                    .frame(width: 128, height: 104)
                    .accessibilityHidden(true)
                VStack(spacing: 4) {
                    Text(friend.slime.name)
                        .font(.title2.bold())
                        .foregroundStyle(Theme.ink)
                    Text("\(friend.ownerName)'s \(friend.stage.title.lowercased()) · Level \(friend.slime.level)")
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSoft)
                        .multilineTextAlignment(.center)
                }
                .accessibilityElement(children: .combine)
                if friends.partner?.id == friend.id {
                    Tag(text: "In your island", color: Theme.accent)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)

            LabeledContent {
                Text(moodDetail(friend))
                    .foregroundStyle(Theme.inkSoft)
            } label: {
                if friend.slime.happiness == nil {
                    Label("Mood", systemImage: "eye.slash")
                } else {
                    Label(friend.slime.mood.friendTitle, systemImage: friend.slime.mood.friendSymbol)
                }
            }
        } footer: {
            Text("Updated \(friend.slime.updatedAt.formatted(.relative(presentation: .named))).")
        }
    }

    private func together(_ friend: Friend) -> some View {
        let isPartner = friends.partner?.id == friend.id
        return Section {
            FriendQuickActions(friend: friend, busy: actions.busy.contains(friend.id)) { item in
                actions.quick(item, on: friend)
            }
            .padding(.vertical, 4)
            .disabled(!friends.isOnline)
            Button {
                friends.partnerID = friend.id
                actions.say("\(friend.slime.name) now sits next to your slime in the island.")
            } label: {
                Label(isPartner ? "In your island" : "Show in my island",
                      systemImage: isPartner ? "checkmark.circle.fill" : "sparkles")
            }
            .disabled(isPartner)
        } header: {
            Text("Together")
        } footer: {
            Text("The friend you choose sits next to your slime in the Dynamic Island.")
        }
    }

    private func today(_ detail: FriendDetail, friend: Friend) -> some View {
        Section {
            ForEach(InteractionKind.allCases, id: \.self) { kind in
                LabeledContent {
                    Text(leftText(kind, detail: detail, friend: friend))
                        .monospacedDigit()
                } label: {
                    Label(kind.title, systemImage: kind.symbol)
                }
            }
        } header: {
            Text("Left today")
        } footer: {
            Text("Hugs, visits and gifts reach each friend a few times a day. The counts start over tomorrow.")
        }
    }

    private func garden(_ coop: CoopStatus, friend: Friend) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(coop.done ? "Full this week" : "\(coop.total) of \(coop.goal)")
                        .font(.headline)
                        .monospacedDigit()
                    Spacer()
                    if coop.done {
                        Image(systemName: "checkmark.seal.fill")
                            .foregroundStyle(Theme.accent)
                            .accessibilityHidden(true)
                    }
                }
                Meter(value: Double(coop.total) / Double(max(1, coop.goal)), color: Theme.accent, height: 8)
                Text("You \(coop.mine) · \(friend.slime.name) \(coop.theirs)")
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                    .monospacedDigit()
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)
        } header: {
            Text("Shared garden")
        } footer: {
            Text("Every round either of you plays in the arcade makes the garden grow. Fill it this week and both slimes are happy.")
        }
    }

    private func memories(_ all: [FriendMemory]) -> some View {
        let memories = all.filter(\.isShown)
        return Section {
            if memories.isEmpty {
                Text("No memories yet. A hug is a good start.")
                    .foregroundStyle(Theme.inkSoft)
            }
            ForEach(Array(memories.enumerated()), id: \.offset) { _, memory in
                LabeledContent {
                    Text(memory.at.formatted(date: .abbreviated, time: .omitted))
                } label: {
                    Label(memory.title, systemImage: memory.symbol)
                }
            }
        } header: {
            Text("Memories")
        }
    }

    private func manage(_ friend: Friend) -> some View {
        Section {
            Button {
                actions.reportTarget = ReportTarget(friend: friend)
            } label: {
                Label("Report \(friend.ownerName)…", systemImage: "exclamationmark.bubble")
            }
            Button(role: .destructive) {
                actions.removeFor = friend
            } label: {
                Label("Remove Friend", systemImage: "person.badge.minus")
            }
            Button(role: .destructive) {
                actions.blockTarget = ReportTarget(friend: friend)
            } label: {
                Label("Block \(friend.ownerName)", systemImage: "hand.raised")
            }
        } footer: {
            Text("Friends since \(friend.since.formatted(date: .long, time: .omitted)).")
        }
        .disabled(!friends.isOnline)
    }

    // MARK: Helpers

    private func moodDetail(_ friend: Friend) -> String {
        guard let happiness = friend.slime.happiness else { return "Hidden by \(friend.ownerName)" }
        return "Happiness \(happiness)%"
    }

    private func leftText(_ kind: InteractionKind, detail: FriendDetail, friend: Friend) -> String {
        let left = max(0, detail.today[kind.rawValue] ?? 0)
        return left == 0 ? "Done for today" : "\(left) left"
    }

    private func load() async {
        guard friends.isOnline, let friend = friends.friend(friendID) else { return }
        loadError = nil
        await friends.loadDetail(friend)
        if friends.details[friendID] == nil {
            loadError = friends.lastError ?? "Couldn't load this friend. Pull down to try again."
            friends.lastError = nil
        }
    }
}
