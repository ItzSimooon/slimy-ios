import SwiftUI

enum HomeSheet: String, Identifiable {
    case kitchen, shop, journal, friends, settings, chat
    var id: String { rawValue }
}

enum HomeCover: String, Identifiable {
    case arcade, bath
    var id: String { rawValue }
}

struct HomeView: View {
    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var friends: FriendsStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var phase

    @State private var sheet: HomeSheet?
    @State private var cover: HomeCover?

    var body: some View {
        let s = store.state
        GeometryReader { geo in
            let scale = max(2, floor(geo.size.width / CGFloat(HabitatScene.gridWidth)))
            let rows = Int(ceil((geo.size.height + geo.safeAreaInsets.top + geo.safeAreaInsets.bottom) / scale))
            let floorY = CGFloat(Int(Double(rows) * 0.66)) * scale - geo.safeAreaInsets.top
            let slimeHeight = min(geo.size.width * 0.42, 190)

            ZStack(alignment: .top) {
                HabitatScene(habitat: s.inventory.habitat, furniture: s.inventory.furniture,
                             date: .now, animate: !reduceMotion && phase == .active)
                    .ignoresSafeArea()

                VStack(spacing: 4) {
                    SpeechBox(text: s.line(at: .now))
                        .frame(maxWidth: geo.size.width * 0.7)
                        .id(s.line(at: .now))
                        .transition(.opacity)
                    LiveSlimeView(height: slimeHeight)
                }
                .position(x: geo.size.width / 2, y: floorY + 20 * scale / 2 - slimeHeight * 0.55)
                .animation(.easeOut(duration: 0.2), value: s.line(at: .now))

                VStack(spacing: 0) {
                    topBar
                    Spacer()
                    bottomPanel
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 6)
            }
        }
        .sheet(item: $sheet) { sheet in
            Group {
                switch sheet {
                case .kitchen: KitchenView()
                case .shop: ShopView()
                case .journal: JournalView()
                case .friends: FriendsView()
                case .settings: SettingsView()
                case .chat: ChatView()
                }
            }
            .presentationBackground(Theme.backdrop)
            .presentationCornerRadius(0)
        }
        .fullScreenCover(item: $cover) { cover in
            switch cover {
            case .arcade: ArcadeView()
            case .bath: BathView()
            }
        }
        .task(id: phase) {
            // Small talk now and then while you watch.
            guard phase == .active else { return }
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(40))
                if store.state.slime.currentLine(at: .now) == nil { _ = store.chatter() }
            }
        }
    }

    // MARK: Top

    private var topBar: some View {
        let s = store.state
        return HStack(alignment: .top, spacing: 8) {
            Button { sheet = .journal } label: {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(s.slime.name).font(Theme.title(18)).foregroundStyle(Theme.ink).lineLimit(1)
                        Text("LV \(s.slime.level)").font(Theme.label(13)).foregroundStyle(Theme.accentInk)
                    }
                    BlockBar(value: s.slime.levelProgress, color: Theme.accent, blocks: 8, height: 5)
                        .frame(width: 110)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
            }
            .buttonStyle(KeyStyle(fill: Theme.paper))
            .accessibilityLabel("\(s.slime.name), Level \(s.slime.level). Tagebuch öffnen")

            Spacer()
            CoinCounter(coins: s.wallet.coins)
            HUDButton(icon: "heart", label: "Freunde", badge: friends.hasNews) { sheet = .friends }
            HUDButton(icon: "speech", label: "Reden") { sheet = .chat }
            HUDButton(icon: "gear", label: "Einstellungen") { sheet = .settings }
        }
        .padding(.top, 4)
    }

    // MARK: Bottom

    private var bottomPanel: some View {
        let s = store.state
        return VStack(spacing: 10) {
            HStack(spacing: 6) {
                ForEach(Need.allCases) { need in
                    NeedChip(need: need, value: s.slime.needs[need], wished: s.slime.wish == need)
                }
            }
            HStack(spacing: 6) {
                ActionKey(title: "Essen", icon: "apple", urgent: s.slime.wish == .hunger) { sheet = .kitchen }
                ActionKey(title: "Spielen", icon: "ball", urgent: s.slime.wish == .fun) { cover = .arcade }
                ActionKey(title: "Baden", icon: "bubbleItem", urgent: s.slime.wish == .clean) { cover = .bath }
                ActionKey(title: s.slime.isAsleep ? "Wecken" : "Schlafen", icon: "starJelly",
                          urgent: s.slime.wish == .energy) { store.toggleSleep() }
                ActionKey(title: "Laden", icon: "coin", urgent: false) { sheet = .shop }
            }
        }
        .padding(10)
        .background(NotchedRect().fill(Theme.paper))
        .overlay(NotchedRect().strokeBorder(Theme.border, lineWidth: Theme.px))
        .background(NotchedRect().fill(Theme.border).offset(y: Theme.px))
    }
}

private struct NeedChip: View {
    let need: Need
    let value: Double
    let wished: Bool

    var body: some View {
        VStack(spacing: 3) {
            PixelItem(name: Theme.icon(for: need)).frame(width: 16, height: 16)
            BlockBar(value: value / 100, color: value < 25 ? Theme.danger : Theme.color(for: need), blocks: 5, height: 6)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 4)
        .background(wished ? Theme.color(for: need).opacity(0.18) : .clear)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(need.title) \(Int(value)) Prozent\(wished ? ", braucht er gerade" : "")")
    }
}

private struct ActionKey: View {
    let title: String
    let icon: String
    let urgent: Bool
    let action: () -> Void

    var body: some View {
        Button {
            Feedback.shared.play(.tap)
            action()
        } label: {
            VStack(spacing: 4) {
                PixelItem(name: icon).frame(width: 22, height: 22)
                Text(title).font(Theme.label(12)).foregroundStyle(Theme.ink).lineLimit(1).minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 60)
        }
        .buttonStyle(KeyStyle(fill: urgent ? Color(hex: 0xFFE38A) : Theme.paperShade))
        .accessibilityHint(urgent ? "Dein Slime braucht das gerade" : "")
    }
}

/// Fanfare for level-ups, achievements, records.
struct CelebrationOverlay: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if let c = store.celebrations.first {
            ZStack {
                Color.black.opacity(0.35).ignoresSafeArea()
                    .onTapGesture { dismiss() }
                if !reduceMotion { Confetti() }
                PixelWindow(padding: 20) {
                    VStack(spacing: 12) {
                        PixelItem(name: c.icon).frame(width: 56, height: 56)
                        Text(c.title).font(Theme.title(26)).foregroundStyle(Theme.ink).multilineTextAlignment(.center)
                        Text(c.detail).font(Theme.text(16)).foregroundStyle(Theme.inkSoft).multilineTextAlignment(.center)
                        PixelButton(title: "Weiter") { dismiss() }
                    }
                    .frame(maxWidth: .infinity)
                }
                .frame(maxWidth: 320)
                .transition(.scale(scale: 0.8).combined(with: .opacity))
            }
            .id(c.id)
            .accessibilityAddTraits(.isModal)
        }
    }

    private func dismiss() {
        withAnimation(.easeOut(duration: 0.15)) {
            if !store.celebrations.isEmpty { store.celebrations.removeFirst() }
        }
    }
}

private struct Confetti: View {
    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 20)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            Canvas { ctx, size in
                var rng = SplitMix(seed: 11)
                for i in 0..<40 {
                    let x = rng.unit() * size.width
                    let speed = 60 + rng.unit() * 90
                    let y = (rng.unit() * size.height + t * speed).truncatingRemainder(dividingBy: size.height)
                    let colors: [Color] = [Theme.love, Theme.gold, Theme.sky, Theme.accent]
                    ctx.fill(Path(CGRect(x: x + sin(t * 2 + Double(i)) * 8, y: y, width: 6, height: 6)),
                             with: .color(colors[i % colors.count]))
                }
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}
