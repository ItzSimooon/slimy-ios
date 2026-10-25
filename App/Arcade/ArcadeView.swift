import SwiftUI

/// The arcade: pick a game, then the host runs it.
struct ArcadeView: View {
    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var friends: FriendsStore
    @Environment(\.dismiss) private var dismiss
    @State private var playing: GameID?
    @State private var challenge: Challenge?

    var body: some View {
        Group {
            if let playing {
                GameHost(game: playing, challenge: challenge) {
                    self.playing = nil
                    challenge = nil
                }
                .transition(.opacity)
            } else {
                menu
            }
        }
        .onAppear {
            // Opened to play a friend's challenge.
            if let c = friends.playChallenge {
                friends.playChallenge = nil
                challenge = c
                playing = c.game
            }
        }
    }

    private var menu: some View {
        let s = store.state
        return ZStack {
            MenuBackdrop()
            ScrollView {
                VStack(spacing: 14) {
                    MenuHeader(title: "Arcade", subtitle: "Every round costs a little energy and is fun") { dismiss() }

                    HStack(spacing: 10) {
                        PixelItem(name: Theme.icon(for: .energy)).frame(width: 20, height: 20)
                        Text("Energy").font(Theme.label(14)).foregroundStyle(Theme.ink)
                        BlockBar(value: s.slime.needs.energy / 100, color: Theme.color(for: .energy), height: 8)
                    }
                    .padding(.horizontal, 4)

                    TodayCard(playable: s.canPlay) {
                        withAnimation(.easeOut(duration: 0.15)) { playing = .daily }
                    }

                    ForEach(friends.openChallenges) { c in
                        Button {
                            challenge = c
                            withAnimation(.easeOut(duration: 0.15)) { playing = c.game }
                        } label: {
                            HStack(spacing: 10) {
                                PixelItem(name: c.game.icon).frame(width: 26, height: 26)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("\(c.friendName) challenges you").font(Theme.label(15)).foregroundStyle(Theme.ink)
                                    Text("\(c.game.title): beat \(c.theirScore ?? 0) points").font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                                }
                                Spacer()
                            }
                            .padding(12)
                        }
                        .buttonStyle(KeyStyle(fill: Color.dynamic(light: 0xFFF1C9, dark: 0x3A3220)))
                        .disabled(!s.canPlay)
                    }

                    if !s.canPlay {
                        PixelWindow(fill: Theme.paperShade) {
                            Text("\(s.slime.name) is too tired to play. A nap or some star jelly helps.")
                                .font(Theme.text(14)).foregroundStyle(Theme.ink)
                        }
                    }

                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                        ForEach(GameID.classics) { game in
                            GameCard(game: game,
                                     record: s.record(game),
                                     locked: s.slime.level < game.unlockLevel,
                                     favorite: s.slime.favoriteGame == game && s.record(game).plays >= 3,
                                     playable: s.canPlay) {
                                withAnimation(.easeOut(duration: 0.15)) { playing = game }
                            }
                        }
                    }
                }
                .padding(16)
            }
        }
    }
}

/// The day's game, quest and story, on top of the arcade.
struct TodayCard: View {
    @EnvironmentObject private var daily: DailyStore
    @EnvironmentObject private var store: GameStore
    let playable: Bool
    let play: () -> Void

    var body: some View {
        let plan = daily.plan
        let game = plan.game
        let colors = game.theme.colors
        VStack(alignment: .leading, spacing: 0) {
            // A little window into today's world.
            ZStack(alignment: .bottomLeading) {
                GameBackdrop(top: colors.top, bottom: colors.bottom)
                Rectangle().fill(Color(hex: colors.ground)).frame(height: 14)
                HStack(alignment: .bottom, spacing: 10) {
                    ForEach(Array(game.entities.prefix(4).enumerated()), id: \.offset) { i, e in
                        PixelItem(name: e.sprite)
                            .frame(width: 22, height: 22)
                            .offset(y: CGFloat(-10 - (i % 2) * 14))
                    }
                    Spacer()
                    Text(plan.source == "ai" ? "made by AI today" : "new today")
                        .font(Theme.label(11)).foregroundStyle(.white)
                        .padding(.horizontal, 6).padding(.vertical, 3)
                        .background(Color.black.opacity(0.35))
                        .padding(.bottom, 20)
                }
                .padding(.horizontal, 12)
            }
            .frame(height: 84)
            .clipped()

            VStack(alignment: .leading, spacing: 10) {
                Text(game.title).font(Theme.title(22)).foregroundStyle(Theme.ink)
                Text(game.blurb).font(Theme.text(14)).foregroundStyle(Theme.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
                if !plan.story.isEmpty {
                    Text("\u{201C}\(plan.story)\u{201D}").font(Theme.text(13)).italic().foregroundStyle(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
                questRow(plan)
                PixelButton(title: daily.playedToday ? "Play again" : "Play today's game", icon: "starJelly", action: play)
                    .disabled(!playable)
            }
            .padding(14)
        }
        .background(NotchedRect().fill(Theme.paper))
        .overlay(NotchedRect().strokeBorder(Theme.border, lineWidth: Theme.px))
        .background(NotchedRect().fill(Theme.border).offset(x: Theme.px, y: Theme.px).opacity(0.35))
        .clipShape(NotchedRect())
        .accessibilityElement(children: .contain)
    }

    private func questRow(_ plan: DailyPlan) -> some View {
        let p = daily.progress
        return HStack(spacing: 10) {
            PixelItem(name: "flag").frame(width: 20, height: 20)
            VStack(alignment: .leading, spacing: 3) {
                Text(plan.quest.title).font(Theme.label(14)).foregroundStyle(Theme.ink)
                BlockBar(value: Double(p.value) / Double(max(1, p.target)), color: Theme.accent, blocks: 8, height: 5)
            }
            Spacer()
            if daily.questClaimed {
                Text("done").font(Theme.label(13)).foregroundStyle(Theme.accentInk)
            } else if daily.questDone {
                PixelButton(title: "+\(plan.quest.coins)", icon: "coin", compact: true) { daily.claimQuest() }
            } else {
                Text("\(p.value)/\(p.target)").font(Theme.label(13)).foregroundStyle(Theme.inkSoft)
            }
        }
        .padding(10)
        .background(Theme.paperShade)
        .accessibilityElement(children: .combine)
    }
}

private struct GameCard: View {
    let game: GameID
    let record: GameRecord
    let locked: Bool
    let favorite: Bool
    let playable: Bool
    let open: () -> Void

    var body: some View {
        Button {
            Feedback.shared.play(.tap)
            open()
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .top) {
                    PixelItem(name: locked ? "rock" : game.icon).frame(width: 30, height: 30)
                    Spacer()
                    if favorite {
                        PixelItem(name: "heart").frame(width: 16, height: 12)
                            .accessibilityLabel("Favourite game")
                    }
                }
                Text(game.title).font(Theme.label(15)).foregroundStyle(Theme.ink)
                    .lineLimit(2).minimumScaleFactor(0.8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(subtitle).font(Theme.text(12)).foregroundStyle(Theme.inkSoft).lineLimit(1)
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: 112, alignment: .topLeading)
            .opacity(locked ? 0.55 : 1)
        }
        .buttonStyle(KeyStyle(fill: Theme.paper))
        .disabled(locked || !playable)
        .accessibilityLabel("\(game.title). \(subtitle)")
        .accessibilityHint(locked ? "" : game.blurb)
    }

    private var subtitle: String {
        if locked { return "from level \(game.unlockLevel)" }
        if record.plays == 0 { return "new" }
        return "Record \(record.best)"
    }
}

// MARK: - Host

/// Runs one game: introduction, pause, results and the reward. Leaving the
/// app pauses the round; the reward for a round is paid exactly once.
struct GameHost: View {
    let game: GameID
    /// Set when this round answers a friend's challenge: same seed, same board.
    var challenge: Challenge? = nil
    let close: () -> Void

    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var friends: FriendsStore
    @EnvironmentObject private var daily: DailyStore
    @Environment(\.scenePhase) private var phase

    @State private var session: GameSession?
    @State private var showTutorial = false
    @State private var confirmQuit = false
    @State private var summary: RoundSummary?
    @State private var lastSeed: UInt64 = 0
    @State private var choosingFriend = false
    @State private var challengeLine: String?

    struct RoundSummary {
        let result: GameResult
        let paid: Bool
        let record: Bool
        let line: String
    }

    var body: some View {
        ZStack {
            Theme.backdrop.ignoresSafeArea()
            VStack(spacing: 8) {
                topBar
                ZStack {
                    if let session {
                        GameBoard(session: session)
                            .id(session.id)
                            .allowsHitTesting(session.running)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipShape(NotchedRect())
                .overlay(NotchedRect().strokeBorder(Theme.border, lineWidth: Theme.px))
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)

            if showTutorial {
                modal { tutorial }
            } else if let summary {
                modal { results(summary) }
            } else if let session, session.paused {
                modal { pauseMenu(session) }
            }
        }
        .onAppear {
            if tutorialSeen { start() } else { showTutorial = true }
        }
        .onChange(of: phase) { _, phase in
            if phase != .active { session?.paused = true }
        }
        .onChange(of: session?.ended ?? false) { _, ended in
            if ended { finish() }
        }
    }

    // MARK: Pieces

    private var topBar: some View {
        HStack(spacing: 10) {
            Button {
                Feedback.shared.play(.tap)
                if let session, !session.ended { session.paused = true } else { close() }
            } label: {
                Image(systemName: session?.ended == false ? "pause.fill" : "xmark")
                    .font(.system(size: 15, weight: .black))
                    .foregroundStyle(Theme.ink)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(KeyStyle(fill: Theme.paper))
            .accessibilityLabel(session?.ended == false ? "Pause" : "Close")

            VStack(alignment: .leading, spacing: 0) {
                Text(title).font(Theme.label(15)).foregroundStyle(Theme.ink)
                Text(spec == nil ? "Record \(store.state.record(game).best)" : "Good from \(goodScore)")
                    .font(Theme.text(12)).foregroundStyle(Theme.inkSoft)
            }
            Spacer()
            if let session {
                Text(session.status).font(Theme.label(15)).foregroundStyle(Theme.inkSoft).monospacedDigit()
                Text("\(session.score)")
                    .font(Theme.title(24)).foregroundStyle(Theme.ink).monospacedDigit()
                    .contentTransition(.numericText())
                    .accessibilityLabel("\(session.score) points")
            }
        }
        .padding(.top, 4)
    }

    private func modal<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        ZStack {
            Color.black.opacity(0.35).ignoresSafeArea()
            content()
                .padding(24)
                .transition(.scale(scale: 0.96).combined(with: .opacity))
        }
    }

    private var tutorial: some View {
        PixelWindow(title: title) {
            HStack {
                Spacer()
                PixelItem(name: game.icon).frame(width: 44, height: 44)
                Spacer()
            }
            ForEach(Array(rules.enumerated()), id: \.offset) { i, line in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text("\(i + 1)").font(Theme.label(13)).foregroundStyle(.white)
                        .frame(width: 22, height: 22).background(Theme.accent)
                    Text(line).font(Theme.text(15)).foregroundStyle(Theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Text("Anything from \(goodScore) points is good.").font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
            HStack(spacing: 10) {
                PixelButton(title: "Back", kind: .quiet) { close() }
                PixelButton(title: session == nil ? "Let's go" : "Got it") {
                    markTutorialSeen()
                    showTutorial = false
                    // Opened from the pause menu: the round stays paused.
                    if session == nil { start() }
                }
            }
        }
    }

    private func pauseMenu(_ session: GameSession) -> some View {
        PixelWindow(title: "Pause") {
            if confirmQuit {
                Text(session.score > 0
                     ? "The round ends with \(session.score) points and counts."
                     : "No points means no reward, but it costs no energy either.")
                    .font(Theme.text(14)).foregroundStyle(Theme.ink)
                HStack(spacing: 10) {
                    PixelButton(title: "Keep playing", kind: .secondary) { confirmQuit = false }
                    PixelButton(title: "End", kind: .danger) {
                        confirmQuit = false
                        session.end()
                    }
                }
            } else {
                PixelButton(title: "Resume", systemIcon: "play.fill") { session.paused = false }
                PixelButton(title: "How to play", kind: .secondary) { showTutorial = true }
                PixelButton(title: "End round", kind: .quiet) { confirmQuit = true }
            }
        }
    }

    private func results(_ summary: RoundSummary) -> some View {
        PixelWindow(title: summary.record ? "New record!" : "Done") {
            HStack(alignment: .bottom, spacing: 14) {
                GameSlime(anim: summary.record ? .celebrate : .play, height: 64, time: Date.now.timeIntervalSinceReferenceDate)
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(summary.result.score)").font(Theme.title(40)).foregroundStyle(Theme.ink)
                    Text("points").font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                }
            }
            if summary.paid {
                HStack(spacing: 14) {
                    reward("coin", "+\(summary.result.coins)")
                    reward("starJelly", "+\(summary.result.xp) XP")
                }
                Text(summary.line).font(Theme.label(15)).foregroundStyle(Theme.accentInk)
            } else {
                Text(summary.line).font(Theme.text(14)).foregroundStyle(Theme.inkSoft)
            }
            if let challenge, lastSeed == challenge.seedValue {
                Text(summary.result.score > (challenge.theirScore ?? 0)
                     ? "You beat \(challenge.friendName): \(summary.result.score) to \(challenge.theirScore ?? 0)."
                     : "\(challenge.friendName) stays ahead: \(challenge.theirScore ?? 0) to \(summary.result.score).")
                    .font(Theme.label(15)).foregroundStyle(Theme.love)
            } else if summary.result.score > 0, game != .daily, !friends.friends.isEmpty {
                PixelButton(title: "Challenge a friend", icon: "flag", kind: .secondary) { choosingFriend = true }
                    .confirmationDialog("Who do you want to challenge?", isPresented: $choosingFriend) {
                        ForEach(friends.friends) { friend in
                            Button(friend.slime.name) {
                                Task { challengeLine = await friends.challenge(friend, with: summary.result, seed: lastSeed) ?? friends.lastError }
                            }
                        }
                    }
            }
            if let challengeLine {
                Text(challengeLine).font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
            }
            HStack(spacing: 10) {
                PixelButton(title: "Back", kind: .quiet) { close() }
                PixelButton(title: "Again") { start() }
                    .disabled(!store.state.canPlay)
            }
            if !store.state.canPlay {
                Text("\(store.state.slime.name) is too tired for another round.")
                    .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
            }
        }
    }

    private func reward(_ icon: String, _ text: String) -> some View {
        HStack(spacing: 6) {
            PixelItem(name: icon).frame(width: 18, height: 18)
            Text(text).font(Theme.label(17)).foregroundStyle(Theme.ink)
        }
    }

    // MARK: Flow

    // The daily game brings its own name, rules and target.
    private var spec: GameSpec? { game == .daily ? daily.plan.game : nil }
    private var title: String { spec?.title ?? game.title }
    private var rules: [String] { spec?.tutorial ?? game.tutorial }
    private var goodScore: Int { spec?.goodScore ?? game.goodScore }
    private var tutorialSeen: Bool {
        if let spec { return UserDefaults.standard.string(forKey: "slimy.daily.tutorial") == spec.id }
        return store.state.record(game).tutorialSeen
    }

    private func markTutorialSeen() {
        if let spec {
            UserDefaults.standard.set(spec.id, forKey: "slimy.daily.tutorial")
        } else {
            store.update { $0.markTutorialSeen(game) }
        }
    }

    private func start() {
        guard store.state.canPlay else {
            store.toast = Toast(text: "\(store.state.slime.name) is too tired.", good: false)
            close()
            return
        }
        confirmQuit = false
        challengeLine = nil
        // A challenge is played once on its seed; "Again" afterwards is a normal round.
        let seed: UInt64? = (summary == nil && challenge != nil) ? challenge?.seedValue : nil
        summary = nil
        let next = GameSession(game: game, seed: seed, spec: spec)
        lastSeed = next.seed
        session = next
    }

    private func finish() {
        guard let session, summary == nil else { return }
        let result = GameResult(game: game, score: session.score, session: session.id, finishedAt: .now,
                                target: session.spec?.goodScore, day: session.spec?.id)
        guard result.score > 0 else {
            summary = RoundSummary(result: result, paid: false, record: false,
                                   line: "No points this time. It didn't cost any energy.")
            Feedback.shared.play(.whoosh)
            return
        }
        if let challenge, session.seed == challenge.seedValue {
            Task { await friends.answer(challenge, score: result.score) }
        }
        let before = store.state.record(game)
        let outcome = store.finish(result)
        if outcome.ok { friends.contributeCoop(result) }
        let record = result.score > before.best && before.plays > 0
        summary = RoundSummary(result: result, paid: outcome.ok, record: record, line: outcome.line)
        if record { Feedback.shared.play(.levelUp) }
    }
}

/// Picks the view for a game.
private struct GameBoard: View {
    let session: GameSession

    var body: some View {
        switch session.game {
        case .snackCatcher: SnackCatcherView(session: session)
        case .slimeJump: SlimeJumpView(session: session)
        case .bubblePop: BubblePopView(session: session)
        case .memoryGarden: MemoryGardenView(session: session)
        case .slimeRacing: SlimeRacingView(session: session)
        case .rhythmBounce: RhythmBounceView(session: session)
        case .gardenPuzzle: GardenPuzzleView(session: session)
        case .treasureDig: TreasureDigView(session: session)
        case .basketball: BasketballView(session: session)
        case .colorMatch: ColorMatchView(session: session)
        case .daily:
            if let spec = session.spec {
                BlockGameView(session: session, spec: spec)
            } else {
                Color.clear.onAppear { session.end() }
            }
        }
    }
}
