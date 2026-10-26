import SwiftUI

/// Best scores of you and your friends, one game at a time, with your own
/// row highlighted. Lives inside a NavigationStack (Friends pushes it);
/// `LeaderboardSheet` wraps it for presenting on its own.
struct LeaderboardView: View {
    @ObservedObject private var friends = FriendsStore.shared
    @State private var selection: GameID?
    @State private var showGoOnline = false
    /// Why the board couldn't load, while there is nothing to show.
    @State private var loadError: String?

    init(game: GameID? = nil) {
        _selection = State(initialValue: game)
    }

    var body: some View {
        content
            .navigationTitle("Leaderboard")
            .task { await load() }
            .onChange(of: friends.isOnline) { _, online in
                if online { Task { await load() } }
            }
            .sheet(isPresented: $showGoOnline) {
                GoOnlineView()
                    .environmentObject(friends)
                    .environmentObject(GameStore.shared)
            }
    }

    @ViewBuilder
    private var content: some View {
        if !friends.isOnline {
            ContentUnavailableView {
                Label("Compare scores with friends", systemImage: "trophy")
            } description: {
                Text("Go online to see your friends' best scores next to yours.")
            } actions: {
                AppButton(title: "Go Online", compact: true) { showGoOnline = true }
            }
        } else if friends.leaderboard.isEmpty {
            if friends.isLoadingLeaderboard {
                ProgressView("Loading scores…")
            } else if let loadError {
                ContentUnavailableView {
                    Label("Couldn't load scores", systemImage: "wifi.exclamationmark")
                } description: {
                    Text(loadError)
                } actions: {
                    Button("Try Again") { Task { await load() } }
                }
            } else {
                ContentUnavailableView {
                    Label("No scores yet", systemImage: "trophy")
                } description: {
                    Text("Play a game in the arcade. Your best scores and your friends' show up here.")
                } actions: {
                    Button("Refresh") { Task { await load() } }
                }
            }
        } else {
            board(friends.leaderboard)
        }
    }

    private func board(_ games: [LeaderboardGame]) -> some View {
        let current = games.first { $0.game == selection } ?? games[0]
        return List {
            if games.count > 1 {
                Section {
                    ChipTabs(options: games.map { ($0.game, $0.game.title) },
                             selection: Binding(get: { current.game }, set: { selection = $0 }))
                        .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                        .listRowBackground(Color.clear)
                }
            }
            Section {
                ForEach(current.entries) { entry in
                    LeaderboardRow(entry: entry, rank: current.rank(of: entry))
                        .listRowBackground(entry.me ? Theme.accent.opacity(0.14) : Theme.card)
                }
            } header: {
                Label(current.game.title, systemImage: "trophy")
            } footer: {
                Text(summary(current))
            }
        }
        .listStyle(.insetGrouped)
        .refreshable { await load() }
    }

    /// Loads the board; a failure with nothing to show becomes `loadError`.
    private func load() async {
        await friends.loadLeaderboard()
        guard friends.isOnline else {
            loadError = nil
            return
        }
        loadError = friends.leaderboard.isEmpty ? friends.lastError : nil
        friends.lastError = nil
    }

    private func summary(_ game: LeaderboardGame) -> String {
        let players = game.entries.count
        guard let mine = game.mine else {
            return "You haven't set a score in \(game.game.title) yet. Only each player's best round counts."
        }
        let rank = game.rank(of: mine)
        if players == 1 { return "Only you so far. Challenge a friend from the arcade." }
        if rank == 1 { return "You're first of \(players). Only each player's best round counts." }
        return "You're number \(rank) of \(players). Only each player's best round counts."
    }
}

/// One place in a game's leaderboard.
private struct LeaderboardRow: View {
    let entry: LeaderboardEntry
    let rank: Int

    var body: some View {
        HStack(spacing: 12) {
            Text(rank, format: .number)
                .font(.headline)
                .monospacedDigit()
                .foregroundStyle(rank == 1 ? Theme.gold : Theme.inkSoft)
                .frame(minWidth: 28)
            FriendAvatar(skin: entry.skin, stage: entry.stage, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(entry.slimeName)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Theme.ink)
                        .lineLimit(1)
                    if entry.me { Tag(text: "You") }
                }
                Text(entry.ownerName)
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(entry.best, format: .number)
                .font(.headline)
                .monospacedDigit()
                .foregroundStyle(Theme.ink)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Place \(rank): \(entry.slimeName), \(entry.ownerName)\(entry.me ? ", you" : "")")
        .accessibilityValue("\(entry.best) points")
    }
}

/// The leaderboard as its own sheet, for screens outside Friends.
struct LeaderboardSheet: View {
    var game: GameID? = nil

    var body: some View {
        NavigationStack {
            LeaderboardView(game: game)
                .sheetTitle("Leaderboard")
        }
    }
}
