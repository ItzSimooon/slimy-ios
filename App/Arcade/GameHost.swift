import SwiftUI

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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var session: GameSession?
    @State private var showTutorial = false
    @State private var confirmQuit = false
    @State private var summary: RoundSummary?
    @State private var lastSeed: UInt64 = 0
    @State private var choosingFriend = false
    @State private var challengeLine: String?
    @ScaledMetric(relativeTo: .largeTitle) private var bigScore: CGFloat = 56

    // Spelled out because ArcadeView, in another file, creates the host and
    // the private state above must not decide this initializer's access.
    init(game: GameID, challenge: Challenge? = nil, close: @escaping () -> Void) {
        self.game = game
        self.challenge = challenge
        self.close = close
    }

    struct RoundSummary {
        let result: GameResult
        let paid: Bool
        let record: Bool
        /// Best score before this round.
        let previousBest: Int
        let line: String
    }

    private var modalShown: Bool {
        showTutorial || summary != nil || session?.paused == true
    }

    var body: some View {
        ZStack {
            Theme.backdrop.ignoresSafeArea()
            VStack(spacing: 10) {
                topBar
                ZStack {
                    if let session {
                        GameBoard(session: session)
                            .id(session.id)
                            .allowsHitTesting(session.running)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.card)
                .clipShape(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
            .frame(maxWidth: 720)
            .accessibilityHidden(modalShown)

            if showTutorial {
                modal { tutorial }
            } else if let summary {
                modal { results(summary) }
            } else if let session, session.paused {
                modal { pauseMenu(session) }
            }
        }
        .animation(reduceMotion ? nil : .snappy(duration: 0.25), value: modalShown)
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

    // MARK: Top bar

    private var topBar: some View {
        let running = session.map { !$0.ended } ?? false
        return HStack(spacing: 12) {
            HUDButton(systemImage: running ? "pause.fill" : "xmark", label: running ? "Pause" : "Close") {
                if let session, !session.ended { session.paused = true } else { close() }
            }

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(Theme.ink)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                    .lineLimit(1)
            }
            .accessibilityElement(children: .combine)

            Spacer(minLength: 8)

            if let session {
                if !session.status.isEmpty {
                    Text(session.status)
                        .font(.subheadline.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(Theme.inkSoft)
                        .lineLimit(1)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Theme.well, in: Capsule())
                }
                Text("\(session.score)")
                    .font(.title.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(Theme.ink)
                    .contentTransition(.numericText(value: Double(session.score)))
                    .animation(reduceMotion ? nil : .snappy(duration: 0.2), value: session.score)
                    .accessibilityLabel("\(session.score) points")
            }
        }
        .padding(.top, 4)
    }

    private var subtitle: String {
        if spec != nil { return "Good from \(goodScore) points" }
        if let challenge, lastSeed == challenge.seedValue, summary == nil {
            return "Beat \(challenge.friendName): \(challenge.theirScore ?? 0)"
        }
        let best = store.state.record(game).best
        return best > 0 ? "Best \(best)" : "Good from \(goodScore) points"
    }

    // MARK: Cards

    private func modal<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        let card = VStack(spacing: 16) { content() }
            .padding(24)
            .frame(maxWidth: 420)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
            .padding(20)
        return ZStack {
            Color.black.opacity(0.4)
                .ignoresSafeArea()
                .accessibilityHidden(true)
            GeometryReader { geo in
                ScrollView {
                    card.frame(maxWidth: .infinity, minHeight: geo.size.height)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
        }
        .transition(.opacity.combined(with: .scale(scale: 0.96)))
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
    }

    private var tutorial: some View {
        Group {
            ItemIcon(name: game.icon, size: 72)
                .accessibilityHidden(true)
            VStack(spacing: 4) {
                Text(title)
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
                Text(blurb)
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)

            VStack(alignment: .leading, spacing: 12) {
                ForEach(Array(rules.enumerated()), id: \.offset) { i, line in
                    HStack(alignment: .top, spacing: 12) {
                        Text("\(i + 1)")
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(Theme.onAccent)
                            .frame(width: 26, height: 26)
                            .background(Theme.accent, in: Circle())
                            .accessibilityHidden(true)
                        Text(line)
                            .font(.body)
                            .foregroundStyle(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 2)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Label("Anything from \(goodScore) points is a good round.", systemImage: "star.fill")
                .font(.footnote)
                .foregroundStyle(Theme.inkSoft)
                .frame(maxWidth: .infinity, alignment: .leading)

            if session == nil {
                HStack(spacing: 12) {
                    AppButton(title: "Back", kind: .quiet) { close() }
                    AppButton(title: "Let's Go", systemIcon: "play.fill") {
                        markTutorialSeen()
                        showTutorial = false
                        start()
                    }
                }
            } else {
                // Opened from the pause menu: the round stays paused.
                AppButton(title: "Got It") { showTutorial = false }
            }
        }
    }

    private func pauseMenu(_ session: GameSession) -> some View {
        Group {
            Image(systemName: "pause.circle.fill")
                .font(.largeTitle)
                .foregroundStyle(Theme.accent)
                .accessibilityHidden(true)
            VStack(spacing: 4) {
                Text("Paused")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
                Text(session.score == 1 ? "1 point so far" : "\(session.score) points so far")
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
            }
            if confirmQuit {
                Text(session.score > 0
                     ? "The round ends with \(session.score) points and counts."
                     : "No points means no reward, but it costs no energy either.")
                    .font(.body)
                    .foregroundStyle(Theme.ink)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 12) {
                    AppButton(title: "Keep Playing", kind: .secondary) { confirmQuit = false }
                    AppButton(title: "End Round", kind: .danger) {
                        confirmQuit = false
                        session.paused = false
                        session.end()
                    }
                }
            } else {
                VStack(spacing: 10) {
                    AppButton(title: "Resume", systemIcon: "play.fill") { session.paused = false }
                    AppButton(title: "How to Play", systemIcon: "questionmark.circle", kind: .secondary) { showTutorial = true }
                    AppButton(title: "End Round", systemIcon: "flag.checkered", kind: .quiet) { confirmQuit = true }
                }
            }
        }
    }

    private func results(_ summary: RoundSummary) -> some View {
        Group {
            TimelineView(.animation(minimumInterval: 1.0 / 12, paused: reduceMotion)) { context in
                GameSlime(anim: summary.record ? .celebrate : (summary.paid ? .play : .sad), height: 72,
                          time: context.date.timeIntervalSinceReferenceDate)
            }
            .frame(height: 72)

            VStack(spacing: 2) {
                Text(headline(summary))
                    .font(.headline)
                    .foregroundStyle(summary.record ? Theme.gold : Theme.inkSoft)
                    .accessibilityAddTraits(.isHeader)
                Text("\(summary.result.score)")
                    .font(.system(size: bigScore, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(Theme.ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                Text(summary.result.score == 1 ? "point" : "points")
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
            }
            .accessibilityElement(children: .combine)

            if summary.paid {
                HStack(spacing: 10) {
                    reward("coin", "+\(summary.result.coins)", label: "\(summary.result.coins) coins")
                    reward("starJelly", "+\(summary.result.xp) XP", label: "\(summary.result.xp) experience")
                }
                Text(summary.line)
                    .font(Theme.voice(16))
                    .foregroundStyle(Theme.ink)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityLabel("\(store.state.slime.name) says: \(summary.line)")
            } else {
                Text(summary.line)
                    .font(.subheadline)
                    .foregroundStyle(Theme.inkSoft)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if game != .daily, summary.paid {
                Text(summary.record ? "Previous best: \(summary.previousBest)" : "Best: \(store.state.record(game).best)")
                    .font(.footnote)
                    .foregroundStyle(Theme.inkSoft)
            }

            if let challenge, lastSeed == challenge.seedValue {
                let theirs = challenge.theirScore ?? 0
                Label(summary.result.score > theirs
                      ? "You beat \(challenge.friendName): \(summary.result.score) to \(theirs)."
                      : "\(challenge.friendName) stays ahead: \(theirs) to \(summary.result.score).",
                      systemImage: summary.result.score > theirs ? "trophy.fill" : "flag.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.love)
                    .multilineTextAlignment(.center)
            } else if summary.result.score > 0, game != .daily, friends.isOnline, !friends.friends.isEmpty {
                AppButton(title: "Challenge a Friend", icon: "flag", kind: .secondary) { choosingFriend = true }
                    .confirmationDialog("Who do you want to challenge?", isPresented: $choosingFriend, titleVisibility: .visible) {
                        ForEach(friends.friends) { friend in
                            Button(friend.slime.name) {
                                Task {
                                    challengeLine = await friends.challenge(friend, with: summary.result, seed: lastSeed)
                                        ?? friends.lastError
                                }
                            }
                        }
                        Button("Cancel", role: .cancel) {}
                    } message: {
                        Text("They play the same board and try to beat \(summary.result.score).")
                    }
            }
            if let challengeLine {
                Text(challengeLine)
                    .font(.footnote)
                    .foregroundStyle(Theme.inkSoft)
                    .multilineTextAlignment(.center)
            }

            HStack(spacing: 12) {
                AppButton(title: "Back", kind: .quiet) { close() }
                AppButton(title: "Again", systemIcon: "arrow.clockwise") { start() }
                    .disabled(!store.state.canPlay)
            }
            if !store.state.canPlay {
                Text("\(store.state.slime.name) is too tired for another round.")
                    .font(.footnote)
                    .foregroundStyle(Theme.inkSoft)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private func headline(_ summary: RoundSummary) -> String {
        if summary.record { return "New record!" }
        if !summary.paid { return "Round over" }
        return summary.result.score >= summary.result.goodScore ? "Great round" : "Round over"
    }

    private func reward(_ icon: String, _ text: String, label: String) -> some View {
        HStack(spacing: 6) {
            PixelItem(name: icon).frame(width: 18, height: 18)
            Text(text)
                .font(.headline)
                .monospacedDigit()
                .foregroundStyle(Theme.ink)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Theme.well, in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }

    // MARK: Flow

    // The daily game brings its own name, rules and target.
    private var spec: GameSpec? { game == .daily ? daily.plan.game : nil }
    private var title: String { spec?.title ?? game.title }
    private var blurb: String { spec?.blurb ?? game.blurb }
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
            summary = RoundSummary(result: result, paid: false, record: false, previousBest: store.state.record(game).best,
                                   line: "No points this time. It didn't cost any energy.")
            Feedback.shared.play(.whoosh)
            return
        }
        if let challenge, session.seed == challenge.seedValue {
            Task { await friends.answer(challenge, score: result.score) }
        }
        let before = store.state.record(game)
        // The results card shows the slime's line, so it isn't repeated in a toast.
        let outcome = store.apply({ $0.finish(result) }, sound: .coin, toast: false)
        if outcome.ok {
            friends.contributeCoop(result)
            // Friends see your best scores on their leaderboard.
            if game != .daily, friends.isOnline {
                let best = store.state.record(game).best
                Task { await friends.uploadRecords([game: best]) }
            }
        }
        // Every day's game is different, so its scores are never records.
        let record = game != .daily && outcome.ok && result.score > before.best && before.plays > 0
        summary = RoundSummary(result: result, paid: outcome.ok, record: record, previousBest: before.best, line: outcome.line)
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
        case .slimeStack: SlimeStackView(session: session)
        case .jellyDash: JellyDashView(session: session)
        case .daily:
            if let spec = session.spec {
                BlockGameView(session: session, spec: spec)
            } else {
                Color.clear.onAppear { session.end() }
            }
        }
    }
}
