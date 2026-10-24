import SwiftUI

enum HomeSheet: String, Identifiable {
    case feed, wardrobe, friends, chat, settings
    var id: String { rawValue }
}

enum HomeCover: String, Identifiable {
    case bath, play
    var id: String { rawValue }
}

struct HomeView: View {
    @EnvironmentObject private var store: SlimeStore
    @EnvironmentObject private var island: IslandController
    @EnvironmentObject private var friends: FriendsStore

    @State private var sheet: HomeSheet?
    @State private var cover: HomeCover?

    var body: some View {
        ZStack {
            BrandBackground()
            ScrollView {
                VStack(spacing: 16) {
                    topBar
                    RoomCard(onFriends: { sheet = .friends })
                    NeedsPanel()
                    actionDock
                    IslandCard()
                }
                .padding(.horizontal, 18)
                .padding(.bottom, 28)
            }
            .scrollIndicators(.hidden)
        }
        .sheet(item: $sheet) { sheet in
            Group {
                switch sheet {
                case .feed: FeedSheet()
                case .wardrobe: WardrobeView()
                case .friends: FriendsView()
                case .chat: ChatView()
                case .settings: SettingsView()
                }
            }
            .presentationBackground(Brand.void)
            .presentationCornerRadius(32)
        }
        .fullScreenCover(item: $cover) { cover in
            switch cover {
            case .bath: BathView()
            case .play: SnackCatchView()
            }
        }
    }

    private var topBar: some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 0) {
                Text("slimy")
                    .font(Brand.display(30, .black))
                    .foregroundStyle(Brand.text)
                Text(".hyprnode")
                    .font(Brand.mono(11, .bold))
                    .foregroundStyle(Brand.hyprGlow)
            }
            Spacer()
            CoinChip(coins: store.state.coins)
            IconButton(symbol: "bubble.left.and.text.bubble.right.fill") { sheet = .chat }
            IconButton(symbol: "person.2.fill", badge: friends.partner?.inLove == true) { sheet = .friends }
            IconButton(symbol: "gearshape.fill") { sheet = .settings }
        }
        .padding(.top, 6)
    }

    private var actionDock: some View {
        let s = store.state
        return HStack(spacing: 10) {
            DockButton(title: "Feed", symbol: "fork.knife", tint: Brand.color(for: .hunger),
                       urgent: s.wish == .hunger) { sheet = .feed }
            DockButton(title: "Play", symbol: "gamecontroller.fill", tint: Brand.color(for: .fun),
                       urgent: s.wish == .fun) {
                if s.needs.energy < 12 {
                    store.toast = Toast(text: "too tired to play… 💤", good: false)
                } else {
                    cover = .play
                }
            }
            DockButton(title: "Bath", symbol: "bubbles.and.sparkles.fill", tint: Brand.color(for: .clean),
                       urgent: s.wish == .clean) { cover = .bath }
            DockButton(title: s.isAsleep ? "Wake" : "Bed", symbol: s.isAsleep ? "sun.max.fill" : "moon.fill",
                       tint: Brand.color(for: .energy), urgent: s.wish == .energy) {
                store.act(s.isAsleep ? .wake : .sleep)
            }
            DockButton(title: "Style", symbol: "paintpalette.fill", tint: Brand.hyprGlow, urgent: false) {
                sheet = .wardrobe
            }
        }
    }
}

private struct DockButton: View {
    let title: String
    let symbol: String
    let tint: Color
    let urgent: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 7) {
                Image(systemName: symbol)
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(tint)
                    .frame(width: 54, height: 54)
                    .background(tint.opacity(0.14), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .strokeBorder(tint, lineWidth: urgent ? 2 : 0)
                    )
                    .phaseAnimator([false, true]) { view, phase in
                        view.scaleEffect(urgent && phase ? 1.06 : 1)
                    } animation: { _ in .easeInOut(duration: 0.7) }
                Text(title)
                    .font(Brand.display(12, .bold))
                    .foregroundStyle(urgent ? tint : Brand.muted)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(SquishStyle())
    }
}

/// The slime's room: sky through the window, the slime, its wish.
private struct RoomCard: View {
    @EnvironmentObject private var store: SlimeStore
    @EnvironmentObject private var friends: FriendsStore
    let onFriends: () -> Void

    var body: some View {
        let s = store.state
        ZStack(alignment: .bottom) {
            SkyBackground(date: .now)
            if s.isAsleep {
                Color.black.opacity(0.45)
            }
            // Floor
            LinearGradient(colors: [Brand.raised, Brand.deep], startPoint: .top, endPoint: .bottom)
                .frame(height: 70)

            HStack(alignment: .bottom, spacing: -10) {
                LiveSlimeView(height: 150)
                if let partner = friends.partner, partner.inLove {
                    VisitingPartner(friend: partner)
                        .onTapGesture(perform: onFriends)
                }
            }
            .padding(.bottom, 34)

            VStack {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(s.name)
                            .font(Brand.display(22, .black))
                            .foregroundStyle(Brand.text)
                        Eyebrow(text: "lv \(s.level) · day \(s.ageDays + 1)", color: Brand.hyprGlow)
                        LevelBar(progress: s.levelProgress)
                            .frame(width: 90)
                    }
                    Spacer()
                    SpeechBubble(text: s.line(at: .now), size: 13)
                        .frame(maxWidth: 170, alignment: .trailing)
                }
                Spacer()
            }
            .padding(16)
        }
        .frame(height: 330)
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).strokeBorder(Brand.line, lineWidth: 1))
    }
}

private struct VisitingPartner: View {
    let friend: Friend

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.4)) { context in
            let frames = SpriteAnim.love.frames
            let i = Int(context.date.timeIntervalSinceReferenceDate * 2.5) % frames.count
            PixelSlime(frame: frames[i], skin: friend.slime.skin)
                .frame(width: 96 * SlimeFont.aspect, height: 96)
                .scaleEffect(x: -1, y: 1)
        }
        .accessibilityLabel("\(friend.slime.name) is visiting")
    }
}

struct LevelBar: View {
    let progress: Double

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.14))
                Capsule().fill(Brand.hyprGradient)
                    .frame(width: max(4, geo.size.width * progress))
            }
        }
        .frame(height: 5)
    }
}

private struct NeedsPanel: View {
    @EnvironmentObject private var store: SlimeStore

    var body: some View {
        Panel(padding: 14) {
            HStack(spacing: 0) {
                ForEach(Need.allCases) { need in
                    let v = Int(store.state.needs[need].rounded())
                    VStack(spacing: 6) {
                        NeedRing(need: need, value: v, size: 46)
                            .phaseAnimator([1.0, 1.08]) { view, scale in
                                view.scaleEffect(v < 25 ? scale : 1)
                            } animation: { _ in .easeInOut(duration: 0.6) }
                        Text("\(v)%")
                            .font(Brand.mono(11, .bold))
                            .foregroundStyle(v < 25 ? Brand.danger : Brand.text)
                            .monospacedDigit()
                        Text(need.title)
                            .font(Brand.display(11, .semibold))
                            .foregroundStyle(Brand.muted)
                    }
                    .frame(maxWidth: .infinity)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(need.title) \(v) percent")
                }
            }
        }
    }
}

private struct IslandCard: View {
    @EnvironmentObject private var island: IslandController

    var body: some View {
        Panel {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    Circle()
                        .fill(island.isLive ? Brand.color(for: .clean) : Brand.muted)
                        .frame(width: 8, height: 8)
                        .shadow(color: island.isLive ? Brand.color(for: .clean) : .clear, radius: 4)
                    Eyebrow(text: island.isLive ? "live in your dynamic island" : "not in the island")
                    Spacer()
                }
                Text(island.isLive
                     ? "Hold the island to feed, play, bathe or pet without opening the app."
                     : "Put your slime in the Dynamic Island so it's always with you.")
                    .font(Brand.display(14, .medium))
                    .foregroundStyle(Brand.text)
                if let problem = island.problem {
                    Text(problem)
                        .font(Brand.display(13, .medium))
                        .foregroundStyle(Brand.warm)
                }
                JellyButton(title: island.isLive ? "Refresh island" : "Put in island",
                            symbol: "capsule.fill", tint: Brand.hypr, filled: !island.isLive) {
                    Task { await island.ensureRunning(restart: true) }
                }
            }
        }
    }
}
