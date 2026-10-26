import StoreKit
import SwiftUI
import UIKit

enum HomeSheet: String, Identifiable {
    case kitchen, shop, journal, friends, settings, chat, gift, crates, care
    var id: String { rawValue }
}

enum HomeCover: String, Identifiable {
    case arcade, bath
    var id: String { rawValue }
}

/// The home screen's coordinate space, for measuring the dock.
private let homeSpace = "home"

/// The slime's home: its habitat, the slime and what it's saying, and around
/// them the HUD (name, level and mood, coins, friends, chat, settings), who
/// it's cared for with, today's pills, crates to open and the needs dock.
/// While you watch, the slime talks to itself every few seconds.
struct HomeView: View {
    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var friends: FriendsStore
    @EnvironmentObject private var daily: DailyStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.scenePhase) private var phase

    @State private var sheet: HomeSheet?
    @State private var cover: HomeCover?
    @State private var screenshotStaged = false
    /// An invite link arrived while a game or the bath was open; Friends
    /// opens once that closes.
    @State private var inviteWaiting = false
    /// The invite code Friends was last opened for, so coming back from a
    /// game doesn't open it again for the same code.
    @State private var inviteShown: String?
    /// Where the dock begins, so the slime never stands behind it.
    @State private var dockTop = CGFloat.infinity

    var body: some View {
        let s = store.state
        let now = Date.now
        let line = s.line(at: now)
        GeometryReader { geo in
            // The same grid HabitatScene draws on, so the slime stands on its floor.
            let scale = HabitatScene.scale(forWidth: geo.size.width)
            let rows = Int(ceil((geo.size.height + geo.safeAreaInsets.top + geo.safeAreaInsets.bottom) / scale))
            let floorRow = HabitatScene.floorRow(rows: rows)
            // On the floor, a little way in; nearer the back if the dock
            // (large text, small screens) would cover its feet.
            let onFloor = CGFloat(floorRow + 12) * scale - geo.safeAreaInsets.top
            let backOfFloor = CGFloat(floorRow + 3) * scale - geo.safeAreaInsets.top
            let feet = max(backOfFloor, min(onFloor, dockTop - 6))
            let slimeHeight = min(geo.size.width * 0.42, 190)
            // LiveSlimeView is 10% taller than the slime, standing on its bottom edge.
            let slimeFrame = slimeHeight * 1.1

            ZStack(alignment: .top) {
                HabitatScene(habitat: s.inventory.habitat, furniture: s.inventory.furniture, date: now,
                             animate: !reduceMotion && phase == .active, party: s.isBirthday(at: now))
                    .ignoresSafeArea()
                StatusBarBackdrop(height: geo.safeAreaInsets.top)

                // The bubble above already shows what it says; no toast on top.
                LiveSlimeView(height: slimeHeight, showsToast: false)
                    .overlay(alignment: .top) {
                        // The bubble sits on top of the slime, whatever its length.
                        SpeechBox(text: line)
                            .frame(width: min(geo.size.width * 0.72, 320))
                            .fixedSize(horizontal: false, vertical: true)
                            .alignmentGuide(.top) { d in d[.bottom] }
                            // With VoiceOver the bubble stays the same element
                            // and only its words change, so focus stays on it.
                            .id(voiceOver ? "speech" : line)
                            .transition(.opacity)
                    }
                    // A soft cross-fade from one thought to the next; with
                    // Reduce Motion the words just change.
                    .animation(reduceMotion ? nil : Animation.easeInOut(duration: 0.35), value: line)
                    .position(x: geo.size.width / 2, y: feet - slimeFrame / 2)

                if let visit = friends.visitor, visit.until > now {
                    visitor(visit.friend, slimeHeight: slimeHeight)
                        .position(x: geo.size.width * 0.82, y: feet - (slimeHeight * 0.55 + 26) / 2)
                }

                VStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 0) {
                        topBar
                        partnerChip
                    }
                    pills
                    Spacer(minLength: 0)
                    dock
                        .onGeometryChange(for: CGFloat.self) { proxy in
                            proxy.frame(in: .named(homeSpace)).minY
                        } action: { top in
                            dockTop = top
                        }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
                .dynamicTypeSize(...DynamicTypeSize.xxLarge)
            }
            .coordinateSpace(.named(homeSpace))
        }
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case .kitchen: KitchenView()
            case .shop: ShopView()
            case .journal: JournalView()
            case .friends: FriendsView()
            case .settings: SettingsView()
            case .chat: ChatView()
            case .gift: StreakView()
            case .crates: CrateShelfView()
            case .care:
                // Care Together lives in its caller's stack (Friends and
                // Settings push it); here it gets a stack and a Done button.
                NavigationStack {
                    CareTogetherView()
                        .sheetTitle("Care Together", inline: true)
                }
            }
        }
        .fullScreenCover(item: $cover) { cover in
            switch cover {
            case .arcade: ArcadeView()
            case .bath: BathView()
            }
        }
        .onAppear {
            stageScreenshot()
            // An invite link that came in before home was on screen.
            if let code = friends.pendingInviteCode { openFriends(forInvite: code) }
        }
        .onChange(of: friends.playChallenge?.id) { _, id in
            guard id != nil else { return }
            sheet = nil
            Task {
                try? await Task.sleep(for: .milliseconds(450))
                cover = .arcade
            }
        }
        .onChange(of: friends.pendingInviteCode) { _, code in
            if let code { openFriends(forInvite: code) }
        }
        .onChange(of: cover) { _, current in
            guard current == nil, inviteWaiting else { return }
            inviteWaiting = false
            guard let code = friends.pendingInviteCode else { return }
            Task {
                // Let the cover slide away before the sheet comes up.
                try? await Task.sleep(for: .milliseconds(450))
                openFriends(forInvite: code)
            }
        }
        .task(id: chatterOn) { await chatterLoop() }
    }

    // MARK: Small talk

    /// The slime talks to itself only while home is in front with nothing
    /// over it, and only if it's allowed to (Settings, Talks on its own).
    private var chatterOn: Bool {
        phase == .active && sheet == nil && cover == nil
            && store.state.settings.chatty && !ScreenshotMode.isActive
    }

    /// Every 8 to 16 seconds it most likely says something. Restarts whenever
    /// a sheet opens or closes or the app leaves the front, so a line never
    /// lands right after coming back.
    private func chatterLoop() async {
        guard chatterOn else { return }
        while !Task.isCancelled {
            // Cancelled (the app left the front, a sheet opened): no last line on the way out.
            do { try await Task.sleep(for: .seconds(Double.random(in: 8...16))) } catch { return }
            guard Double.random(in: 0..<1) < 0.75, mayChatter(at: .now) else { continue }
            _ = store.chatter()
        }
    }

    private func mayChatter(at now: Date) -> Bool {
        // A celebration card or a sheet from elsewhere (What's New) is on top.
        guard store.celebrations.isEmpty, !somethingPresented else { return false }
        // A line it just said (thanks for food, a pet) stays up long enough to read.
        if let said = store.state.slime.say?.at, now.timeIntervalSince(said) < 6 { return false }
        return true
    }

    /// Whether anything is presented over the app, whoever presented it.
    private var somethingPresented: Bool {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
        return windows.first(where: { $0.isKeyWindow })?.rootViewController?.presentedViewController != nil
    }

    /// An invite link (slime.hyprnode.de/f/CODE) opens Friends, which fills
    /// in the code. A game or the bath in progress isn't interrupted.
    private func openFriends(forInvite code: String) {
        guard !ScreenshotMode.isActive, code != inviteShown else { return }
        guard cover == nil else {
            inviteWaiting = true
            return
        }
        inviteShown = code
        if sheet != .friends { sheet = .friends }
    }

    /// Screenshot runs: a demo slime with a few crates to open, then the
    /// sheet or cover asked for (`-SlimySheet crates`, `-SlimySheet care` …).
    private func stageScreenshot() {
        guard ScreenshotMode.isActive, !screenshotStaged else { return }
        screenshotStaged = true
        store.prepareScreenshotDemo()
        // Once per demo save, so relaunches for the next screenshot match.
        if !store.state.wallet.hasClaimed("screenshot:crates") {
            store.update { s in
                s.wallet.claim("screenshot:crates", coins: 0, at: .now)
                s.crates[.wooden, default: 0] += 2
                s.crates[.golden, default: 0] += 1
            }
        }
        let firstSheet = ScreenshotMode.initialSheet
        let firstCover = ScreenshotMode.initialCover
        guard firstSheet != nil || firstCover != nil else { return }
        Task {
            // Let the home settle in before something slides over it.
            try? await Task.sleep(for: .milliseconds(500))
            if let firstSheet {
                sheet = firstSheet
            } else if let firstCover {
                cover = firstCover
            }
        }
    }

    // MARK: Top

    private var topBar: some View {
        let s = store.state
        let mood = SlimeMoodWord.word(for: s.slime)
        return HStack(spacing: 8) {
            Button {
                Feedback.shared.play(.tap)
                sheet = .journal
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(s.slime.name)
                        .font(.headline)
                        .foregroundStyle(Theme.ink)
                        .lineLimit(1)
                    // The mood goes first when room runs short, then the bar.
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 6) {
                            levelText(s.slime.level, mood: mood)
                            levelMeter(s.slime.levelProgress)
                        }
                        levelText(s.slime.level, mood: mood)
                        HStack(spacing: 6) {
                            levelText(s.slime.level, mood: nil)
                            levelMeter(s.slime.levelProgress)
                        }
                        levelText(s.slime.level, mood: nil)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .frame(minHeight: 44)
                .background(.regularMaterial, in: Capsule())
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(s.slime.name), level \(s.slime.level), \(SlimeMoodWord.phrase(for: s.slime))")
            .accessibilityValue("\(Int((s.slime.levelProgress * 100).rounded())) percent to the next level")
            .accessibilityHint("Opens the journal")
            .accessibilityAddTraits(.isButton)

            Spacer(minLength: 0)
            // Coins and buttons keep their size; the name gives way.
            CoinCounter(coins: s.wallet.coins, floating: true)
                .fixedSize()
            HUDButton(systemImage: "person.2.fill", label: "Friends", badge: friends.hasNews) { sheet = .friends }
            HUDButton(systemImage: "bubble.left.fill", label: "Talk") { sheet = .chat }
            HUDButton(systemImage: "gearshape.fill", label: "Settings") { sheet = .settings }
        }
        .padding(.top, 4)
    }

    /// "Level 3 · cheerful", or just "Level 3".
    private func levelText(_ level: Int, mood: String?) -> some View {
        let text: String = mood.map { "Level \(level) · \($0)" } ?? "Level \(level)"
        return Text(text)
            .font(.caption.weight(.semibold))
            .foregroundStyle(Theme.inkSoft)
            .lineLimit(1)
            .fixedSize()
    }

    private func levelMeter(_ progress: Double) -> some View {
        Meter(value: progress, height: 4)
            .frame(width: 56)
    }

    /// Who this slime is cared for with, under its name while it's shared.
    /// Opens Care Together.
    @ViewBuilder private var partnerChip: some View {
        if store.isSharing {
            let partner = (store.state.care?.partnerName ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            Button {
                Feedback.shared.play(.tap)
                sheet = .care
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "person.2.fill")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(Theme.accent)
                    Text(partner.isEmpty ? "Cared for together" : "with \(partner)")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Theme.ink)
                        .lineLimit(1)
                }
                .padding(.horizontal, 12)
                .frame(minHeight: 30)
                .background(.regularMaterial, in: Capsule())
                // Transparent margin: 44 pt to tap, 30 pt to see.
                .padding(.vertical, 7)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(partner.isEmpty ? "Cared for together" : "Cared for together with \(partner)")
            .accessibilityHint("Opens Care Together")
        }
    }

    private enum Pill: Hashable {
        case gift, today, crates
    }

    /// Today's gift, today's game and crates waiting to be opened, side by
    /// side when they fit. Looked at again every minute, so a new day's gift
    /// shows up on its own.
    private var pills: some View {
        TimelineView(.everyMinute) { _ in
            let shown = shownPills
            if !shown.isEmpty {
                // All in a row; two in a row and the third below; or stacked.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        pillRow(shown)
                    }
                    VStack(spacing: 8) {
                        HStack(spacing: 8) {
                            pillRow(Array(shown.prefix(2)))
                        }
                        pillRow(Array(shown.dropFirst(2)))
                    }
                    VStack(spacing: 8) {
                        pillRow(shown)
                    }
                }
                .transition(.opacity)
            }
        }
    }

    private var shownPills: [Pill] {
        var shown: [Pill] = []
        if store.streak.canClaim { shown.append(.gift) }
        if !daily.playedToday { shown.append(.today) }
        if crateCount > 0 { shown.append(.crates) }
        return shown
    }

    private func pillRow(_ shown: [Pill]) -> some View {
        ForEach(shown, id: \.self) { kind in
            pill(kind)
        }
    }

    @ViewBuilder private func pill(_ kind: Pill) -> some View {
        switch kind {
        case .gift: giftPill
        case .today: todayPill
        case .crates: cratesPill
        }
    }

    /// Crates of every kind waiting to be opened.
    private var crateCount: Int {
        store.state.crates.values.reduce(0) { $0 + max(0, $1) }
    }

    private var giftPill: some View {
        Button {
            Feedback.shared.play(.tap)
            sheet = .gift
        } label: {
            pillLabel(text: "Daily Gift") {
                ArtView(name: "gift.closed").frame(width: 18, height: 18)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Daily gift ready")
        .accessibilityHint("Opens today's gift")
    }

    /// Points at today's new game until it's been played.
    private var todayPill: some View {
        Button {
            Feedback.shared.play(.tap)
            cover = .arcade
        } label: {
            pillLabel(text: "New today: \(daily.plan.game.title)") {
                PixelItem(name: "starJelly").frame(width: 16, height: 16)
            }
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens the arcade")
    }

    /// Crates waiting to be opened, with the best of them as the icon.
    private var cratesPill: some View {
        let count = crateCount
        let best = CrateKind.allCases.last { (store.state.crates[$0] ?? 0) > 0 } ?? .wooden
        return Button {
            Feedback.shared.play(.tap)
            sheet = .crates
        } label: {
            pillLabel(text: count == 1 ? "1 crate to open" : "\(count) crates to open") {
                CrateSprite(kind: best).frame(width: 18, height: 18)
            }
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens your crates")
    }

    private func pillLabel<Icon: View>(text: String, @ViewBuilder icon: () -> Icon) -> some View {
        HStack(spacing: 8) {
            icon()
            Text(text)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Theme.ink)
                .lineLimit(1)
            Image(systemName: "chevron.right")
                .font(.caption.weight(.bold))
                .foregroundStyle(Theme.inkSoft)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 44)
        .background(.regularMaterial, in: Capsule())
        .contentShape(Capsule())
    }

    // MARK: Visitor

    /// A friend's slime that came over for a while.
    private func visitor(_ friend: FriendSlime, slimeHeight: CGFloat) -> some View {
        VStack(spacing: 4) {
            Text(friend.name)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Theme.ink)
                .lineLimit(1)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(.regularMaterial, in: Capsule())
            FriendPortrait(skin: friend.skin, stage: friend.stage, mood: .idle,
                           accessories: friend.equipped ?? Accessories())
                .frame(width: slimeHeight * 0.55 * SlimeFont.aspect, height: slimeHeight * 0.55)
                .scaleEffect(x: -1, y: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(friend.name) is visiting")
    }

    // MARK: Bottom

    private var dock: some View {
        let s = store.state
        return VStack(spacing: 14) {
            HStack(spacing: 0) {
                ForEach(Need.allCases) { need in
                    VStack(spacing: 4) {
                        NeedRing(need: need, value: s.slime.needs[need])
                        Text(need.title)
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(s.slime.wish == need ? Theme.ink : Theme.inkSoft)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .frame(maxWidth: .infinity)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(need.title)
                    .accessibilityValue("\(Int(s.slime.needs[need])) percent\(s.slime.wish == need ? ", needed right now" : "")")
                }
            }
            HStack(spacing: 8) {
                ActionKey(title: "Feed", icon: "apple", need: .hunger, urgent: s.slime.wish == .hunger) { sheet = .kitchen }
                ActionKey(title: "Play", icon: "ball", need: .fun, urgent: s.slime.wish == .fun) { cover = .arcade }
                ActionKey(title: "Bath", icon: "bubbleItem", need: .clean, urgent: s.slime.wish == .clean) { cover = .bath }
                ActionKey(title: s.slime.isAsleep ? "Wake" : "Sleep", icon: "starJelly", need: .energy,
                          urgent: s.slime.wish == .energy) { store.toggleSleep(toast: false) }
                ActionKey(title: "Shop", icon: "coin", need: nil, urgent: false) { sheet = .shop }
            }
        }
        .padding(14)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
    }
}

/// Bar material behind the status bar, so the clock and battery stay
/// readable over the pixel world in light and dark mode. Goes at the top of
/// a view that respects the safe area; it draws into the inset above.
struct StatusBarBackdrop: View {
    let height: CGFloat

    var body: some View {
        Rectangle()
            .fill(.bar)
            .frame(maxWidth: .infinity)
            .frame(height: max(0, height))
            .offset(y: -max(0, height))
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

private struct ActionKey: View {
    let title: String
    let icon: String
    let need: Need?
    let urgent: Bool
    let action: () -> Void

    var body: some View {
        let tint = need.map { Theme.color(for: $0) } ?? Theme.accent
        Button {
            Feedback.shared.play(.tap)
            action()
        } label: {
            VStack(spacing: 5) {
                PixelItem(name: icon).frame(width: 24, height: 24)
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity, minHeight: 62)
            .overlay(alignment: .topTrailing) {
                if urgent {
                    Circle().fill(tint).frame(width: 8, height: 8).padding(7)
                }
            }
        }
        .buttonStyle(TileStyle(fill: urgent ? tint.opacity(0.22) : Theme.well, radius: Theme.smallRadius))
        .accessibilityHint(urgent ? "Your slime needs this right now" : "")
    }
}

/// Fanfare for level-ups, achievements, records, birthdays. After a happy
/// one (a level-up from level 3 on) it may ask for an App Store rating,
/// once per app version, when the last card is closed.
struct CelebrationOverlay: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.requestReview) private var requestReview
    @State private var reviewDue = false

    var body: some View {
        ZStack {
            if let c = store.celebrations.first {
                ZStack {
                    Color.black.opacity(0.4).ignoresSafeArea()
                        .onTapGesture { dismiss() }
                        .accessibilityHidden(true)
                    if !reduceMotion { Confetti() }
                    VStack(spacing: 14) {
                        celebrationIcon(c.icon).frame(width: 64, height: 64)
                            .padding(.top, 6)
                        Text(c.title)
                            .font(.title2.weight(.bold))
                            .foregroundStyle(Theme.ink)
                            .multilineTextAlignment(.center)
                            .accessibilityAddTraits(.isHeader)
                        Text(c.detail)
                            .font(.body)
                            .foregroundStyle(Theme.inkSoft)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                        AppButton(title: "Continue") { dismiss() }
                            .padding(.top, 4)
                    }
                    .padding(24)
                    .frame(maxWidth: 340)
                    .background(Theme.card, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                    .padding(24)
                    .transition(reduceMotion ? .opacity : .scale(scale: 0.9).combined(with: .opacity))
                }
                .id(c.id)
                .accessibilityAddTraits(.isModal)
            }
        }
        .onChange(of: store.celebrations.isEmpty) { _, empty in
            guard empty, reviewDue else { return }
            reviewDue = false
            askForReview()
        }
    }

    /// A crate leaves out the empty rows above its lid, so it sits as centred
    /// as every other icon.
    @ViewBuilder private func celebrationIcon(_ name: String) -> some View {
        if let kind = CrateKind.allCases.first(where: { $0.sprite == name }) {
            CrateSprite(kind: kind)
        } else {
            ArtView(name: name)
        }
    }

    private func dismiss() {
        if let c = store.celebrations.first, c.asksForReview, store.mayAskForReview {
            reviewDue = true
        }
        withAnimation(.easeOut(duration: 0.15)) {
            if !store.celebrations.isEmpty { store.celebrations.removeFirst() }
        }
    }

    private func askForReview() {
        Task {
            // A beat after the card is gone, so the two don't collide.
            try? await Task.sleep(for: .milliseconds(700))
            guard store.mayAskForReview, store.celebrations.isEmpty else { return }
            store.noteReviewAsked()
            requestReview()
        }
    }
}

private struct Confetti: View {
    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 20)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            Canvas { ctx, size in
                var rng = SplitMix(seed: 11)
                let colors: [Color] = [Theme.love, Theme.gold, Theme.sky, Theme.accent]
                for i in 0..<40 {
                    let x = rng.unit() * size.width
                    let speed = 60 + rng.unit() * 90
                    let y = (rng.unit() * size.height + t * speed).truncatingRemainder(dividingBy: max(1, size.height))
                    ctx.fill(Path(CGRect(x: x + sin(t * 2 + Double(i)) * 8, y: y, width: 6, height: 6)),
                             with: .color(colors[i % colors.count]))
                }
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
