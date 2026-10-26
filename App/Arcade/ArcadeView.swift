import SwiftUI

/// The arcade, shown as a full-screen cover: a native menu with today's
/// game, friends' challenges and every game. Picking one hands over to
/// GameHost, which plays it on top of the menu.
struct ArcadeView: View {
    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var friends: FriendsStore
    @EnvironmentObject private var daily: DailyStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var playing: GameID?
    @State private var challenge: Challenge?
    @State private var showLeaderboard = false

    var body: some View {
        ZStack {
            NavigationStack {
                menu
            }
            .sheet(isPresented: $showLeaderboard) {
                LeaderboardSheet()
            }
            .accessibilityHidden(playing != nil)

            if let playing {
                GameHost(game: playing, challenge: challenge) {
                    withAnimation(.easeOut(duration: 0.2)) {
                        self.playing = nil
                        challenge = nil
                    }
                }
                .transition(.opacity)
                .zIndex(1)
            }
        }
        .onAppear(perform: takeChallenge)
        .task {
            // Opened after midnight without leaving the app: today's game, not yesterday's.
            if daily.plan.day != DailyPlanner.dayKey(.now) { await daily.refresh() }
        }
        .onChange(of: friends.playChallenge?.id) { _, id in
            if id != nil { takeChallenge() }
        }
    }

    // MARK: Menu

    private var menu: some View {
        let s = store.state
        return ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                ArcadeEnergyCard(energy: s.slime.needs.energy, name: s.slime.name, canPlay: s.canPlay)

                TodayCard(playable: s.canPlay, paused: playing != nil) { open(.daily) }

                if !friends.openChallenges.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        ArcadeSectionHeader(title: "Challenges", detail: "Same board as your friend. Beat their score.")
                        VStack(spacing: 0) {
                            ForEach(Array(friends.openChallenges.enumerated()), id: \.element.id) { i, c in
                                if i > 0 { Divider().padding(.leading, 68) }
                                ArcadeChallengeRow(challenge: c) { open(c.game, challenge: c) }
                                    .disabled(!s.canPlay)
                            }
                        }
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))
                        .clipShape(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))
                    }
                }

                VStack(alignment: .leading, spacing: 10) {
                    ArcadeSectionHeader(title: "Games", detail: nil)
                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(GameID.classics) { game in
                            ArcadeGameTile(game: game,
                                     record: s.record(game),
                                     level: s.slime.level,
                                     favorite: s.slime.favoriteGame == game && s.record(game).plays >= 3,
                                     highlightNew: s.progress.stats.plays > 0,
                                     playable: s.canPlay) {
                                open(game)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 32)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Theme.backdrop)
        .navigationTitle("Arcade")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                CoinCounter(coins: s.wallet.coins)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Feedback.shared.play(.tap)
                    showLeaderboard = true
                } label: {
                    Image(systemName: "trophy")
                }
                .accessibilityLabel("Leaderboard")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Feedback.shared.play(.tap)
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Theme.inkSoft)
                        .frame(width: 32, height: 32)
                        .background(Theme.well, in: Circle())
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close")
            }
        }
    }

    private var columns: [GridItem] {
        let count = typeSize.isAccessibilitySize ? 1 : 2
        return Array(repeating: GridItem(.flexible(), spacing: 12), count: count)
    }

    // MARK: Flow

    private func open(_ game: GameID, challenge: Challenge? = nil) {
        guard store.state.canPlay else {
            store.toast = Toast(text: "\(store.state.slime.name) is too tired.", good: false)
            return
        }
        self.challenge = challenge
        withAnimation(.easeOut(duration: 0.2)) { playing = game }
    }

    /// Opened to play a friend's challenge.
    private func takeChallenge() {
        guard playing == nil, let c = friends.playChallenge else { return }
        friends.playChallenge = nil
        open(c.game, challenge: c)
    }
}

// MARK: - Menu pieces

/// A bold section title over cards, like the App Store's.
struct ArcadeSectionHeader: View {
    let title: String
    let detail: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.title3.weight(.bold))
                .foregroundStyle(Theme.ink)
                .accessibilityAddTraits(.isHeader)
            if let detail {
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
            }
        }
        .padding(.horizontal, 4)
    }
}

/// The slime's energy: every round uses some.
private struct ArcadeEnergyCard: View {
    let energy: Double
    let name: String
    let canPlay: Bool

    /// GameState.canPlay needs this much energy before a round.
    private static let minimum = 12.0

    var body: some View {
        Card {
            HStack(spacing: 14) {
                ItemIcon(name: Theme.icon(for: .energy), size: 44)
                VStack(alignment: .leading, spacing: 6) {
                    HStack(alignment: .firstTextBaseline) {
                        Text("Energy")
                            .font(.headline)
                            .foregroundStyle(Theme.ink)
                        Spacer()
                        Text("\(Int(energy.rounded()))%")
                            .font(.subheadline.weight(.semibold))
                            .monospacedDigit()
                            .foregroundStyle(Theme.inkSoft)
                    }
                    Meter(value: energy / 100, color: Theme.color(for: .energy), height: 8)
                    Text(note)
                        .font(.footnote)
                        .foregroundStyle(canPlay ? Theme.inkSoft : Theme.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Energy \(Int(energy.rounded())) percent")
        .accessibilityValue(note)
    }

    private var rounds: Int {
        guard energy >= Self.minimum else { return 0 }
        return Int((energy - Self.minimum) / GameID.daily.energyCost) + 1
    }

    private var note: String {
        if !canPlay { return "\(name) is too tired to play. A nap or some star jelly helps." }
        return rounds <= 1 ? "Enough for one more round." : "Enough for about \(rounds) rounds."
    }
}

/// A friend's open challenge, as a list row.
private struct ArcadeChallengeRow: View {
    let challenge: Challenge
    let open: () -> Void

    var body: some View {
        Button {
            Feedback.shared.play(.tap)
            open()
        } label: {
            HStack(spacing: 12) {
                ItemIcon(name: challenge.game.icon, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(challenge.friendName) challenges you")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Theme.ink)
                    Text("\(challenge.game.title) · Beat \(challenge.theirScore ?? 0)")
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSoft)
                    Text("Ends \(challenge.expiresAt.formatted(.relative(presentation: .named)))")
                        .font(.caption)
                        .foregroundStyle(Theme.inkSoft)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(ArcadeRowStyle())
        .accessibilityHint("Plays \(challenge.game.title) on your friend's board")
    }
}

/// Highlights a list-like row while it is pressed.
private struct ArcadeRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        RowBody(configuration: configuration)
    }

    private struct RowBody: View {
        let configuration: ButtonStyleConfiguration
        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            configuration.label
                .background(configuration.isPressed ? Color(uiColor: .systemFill) : Color.clear)
                .opacity(isEnabled ? 1 : 0.45)
        }
    }
}

/// One game in the grid: its pixel icon, record and lock state.
private struct ArcadeGameTile: View {
    let game: GameID
    let record: GameRecord
    let level: Int
    let favorite: Bool
    /// Marks games you haven't tried yet, once you have played some.
    let highlightNew: Bool
    let playable: Bool
    let open: () -> Void

    private var locked: Bool { level < game.unlockLevel }

    var body: some View {
        Button {
            Feedback.shared.play(.tap)
            open()
        } label: {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top) {
                    ItemIcon(name: game.icon, size: 52)
                        .saturation(locked ? 0 : 1)
                        .overlay(alignment: .bottomTrailing) {
                            if locked {
                                Image(systemName: "lock.fill")
                                    .font(.caption2.weight(.bold))
                                    .foregroundStyle(.white)
                                    .frame(width: 22, height: 22)
                                    .background(Color(uiColor: .systemGray), in: Circle())
                                    .overlay(Circle().stroke(Theme.card, lineWidth: 2))
                                    .offset(x: 5, y: 5)
                            }
                        }
                    Spacer(minLength: 4)
                    if favorite {
                        Image(systemName: "heart.fill")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(Theme.love)
                            .frame(width: 26, height: 26)
                            .background(Theme.love.opacity(0.15), in: Circle())
                    } else if highlightNew && !locked && record.plays == 0 {
                        Tag(text: "NEW")
                    }
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(game.title)
                        .font(.headline)
                        .foregroundStyle(Theme.ink)
                        .lineLimit(2)
                        .minimumScaleFactor(0.85)
                        .multilineTextAlignment(.leading)
                    subtitle
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSoft)
                        .lineLimit(1)
                        .minimumScaleFactor(0.85)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, minHeight: 136, alignment: .topLeading)
        }
        .buttonStyle(TileStyle(fill: Theme.card))
        .disabled(locked || !playable)
        .accessibilityLabel(game.title)
        .accessibilityValue(valueDescription)
        .accessibilityHint(locked ? "" : game.blurb)
    }

    @ViewBuilder private var subtitle: some View {
        if locked {
            Label("Level \(game.unlockLevel)", systemImage: "lock.fill")
        } else if record.plays == 0 {
            Text("Not played yet")
        } else {
            Label("Best \(record.best)", systemImage: "trophy.fill")
        }
    }

    private var valueDescription: String {
        var parts: [String] = []
        if locked {
            parts.append("Unlocks at level \(game.unlockLevel)")
        } else if record.plays == 0 {
            parts.append("Not played yet")
        } else {
            parts.append("Best \(record.best) points")
        }
        if favorite { parts.append("Favourite game") }
        return parts.joined(separator: ", ")
    }
}
