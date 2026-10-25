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
    @EnvironmentObject private var friends: FriendsStore

    @State private var sheet: HomeSheet?
    @State private var cover: HomeCover?

    var body: some View {
        ZStack {
            BrandBackground()
            ScrollView {
                VStack(spacing: 14) {
                    topBar
                    RoomCard(onFriends: { sheet = .friends })
                    NeedsPanel()
                    actionDock
                    IslandCard()
                }
                .padding(.horizontal, 16)
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
            .presentationBackground(Brand.bg)
            .presentationCornerRadius(0)
        }
        .fullScreenCover(item: $cover) { cover in
            switch cover {
            case .bath: BathView()
            case .play: SnackCatchView()
            }
        }
    }

    private var topBar: some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 0) {
                Text("slimy")
                    .font(Brand.pixel(30))
                    .foregroundStyle(Brand.text)
                Text("by hyprnode")
                    .font(Brand.pixel(11, bold: false))
                    .foregroundStyle(Brand.hypr)
            }
            Spacer()
            CoinChip(coins: store.state.coins)
            IconButton(symbol: "text.bubble.fill", label: "Reden") { sheet = .chat }
            IconButton(symbol: "person.2.fill", label: "Freunde", badge: friends.partner?.inLove == true) { sheet = .friends }
            IconButton(symbol: "gearshape.fill", label: "Einstellungen") { sheet = .settings }
        }
        .padding(.top, 6)
    }

    private var actionDock: some View {
        let s = store.state
        return HStack(spacing: 8) {
            DockButton(title: "Essen", symbol: "fork.knife", tint: Brand.color(for: .hunger),
                       urgent: s.wish == .hunger) { sheet = .feed }
            DockButton(title: "Spielen", symbol: "gamecontroller.fill", tint: Brand.color(for: .fun),
                       urgent: s.wish == .fun) {
                if s.needs.energy < 12 {
                    store.toast = Toast(text: "zu müde zum spielen", good: false)
                } else {
                    cover = .play
                }
            }
            DockButton(title: "Baden", symbol: "bubbles.and.sparkles.fill", tint: Brand.color(for: .clean),
                       urgent: s.wish == .clean) { cover = .bath }
            DockButton(title: s.isAsleep ? "Wecken" : "Bett", symbol: s.isAsleep ? "sun.max.fill" : "moon.fill",
                       tint: Brand.color(for: .energy), urgent: s.wish == .energy) {
                store.act(s.isAsleep ? .wake : .sleep)
            }
            DockButton(title: "Farbe", symbol: "paintpalette.fill", tint: Brand.accent(s.skin), urgent: false) {
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
            VStack(spacing: 6) {
                Image(systemName: symbol)
                    .font(.system(size: 18, weight: .bold))
                Text(title)
                    .font(Brand.pixel(13))
            }
            .foregroundStyle(urgent ? Brand.bg : tint)
            .frame(maxWidth: .infinity)
            .frame(height: 66)
            .background(urgent ? tint : Brand.panel)
            .overlay(Rectangle().strokeBorder(urgent ? Color.black.opacity(0.55) : tint.opacity(0.6), lineWidth: 2))
        }
        .buttonStyle(PressStyle())
        .accessibilityHint(urgent ? "Dein Slime braucht das gerade" : "")
    }
}

/// The slime's room: a window with the sky, the floor, the slime and what it says.
private struct RoomCard: View {
    @EnvironmentObject private var store: SlimeStore
    @EnvironmentObject private var friends: FriendsStore
    let onFriends: () -> Void

    var body: some View {
        let s = store.state
        ZStack(alignment: .bottom) {
            Brand.panel

            // Window
            SkyBackground(date: .now)
                .frame(width: 110, height: 80)
                .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 4))
                .overlay(Rectangle().fill(Brand.line).frame(width: 4))
                .overlay(Rectangle().fill(Brand.line).frame(height: 4))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                .padding(.top, 16)
                .padding(.trailing, 16)

            if s.isAsleep {
                Color.black.opacity(0.45)
            }

            // Floor
            Rectangle().fill(Brand.raised).frame(height: 64)
                .overlay(alignment: .top) { Rectangle().fill(Brand.line).frame(height: 3) }

            HStack(alignment: .bottom, spacing: -6) {
                LiveSlimeView(height: 140)
                if let partner = friends.partner, partner.inLove {
                    VisitingPartner(friend: partner)
                        .onTapGesture(perform: onFriends)
                }
            }
            .padding(.bottom, 40)

            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(s.name)
                        .font(Brand.pixel(24))
                        .foregroundStyle(Brand.text)
                    Text("LV \(s.level)")
                        .font(Brand.pixel(14))
                        .foregroundStyle(Brand.accent(s.skin))
                }
                PixelBar(value: Int(s.levelProgress * 100), segments: 12, color: Brand.accent(s.skin), height: 6, gap: 2)
                    .frame(width: 110)
                Text("tag \(s.ageDays + 1)")
                    .font(Brand.pixel(12, bold: false))
                    .foregroundStyle(Brand.muted)
                SpeechBubble(text: s.line(at: .now), size: 14)
                    .frame(maxWidth: 200, alignment: .leading)
                    .padding(.top, 4)
                Spacer()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
        }
        .frame(height: 340)
        .clipped()
        .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 2))
    }
}

private struct VisitingPartner: View {
    let friend: Friend

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.4)) { context in
            let frames = SpriteAnim.love.frames
            let i = Int(context.date.timeIntervalSinceReferenceDate * 2.5) % frames.count
            PixelSlime(frame: frames[i], skin: friend.slime.skin)
                .frame(width: 92 * SlimeFont.aspect, height: 92)
                .scaleEffect(x: -1, y: 1)
        }
        .accessibilityLabel("\(friend.slime.name) ist zu Besuch")
    }
}

private struct NeedsPanel: View {
    @EnvironmentObject private var store: SlimeStore

    var body: some View {
        Panel {
            VStack(spacing: 10) {
                ForEach(Need.allCases) { need in
                    NeedRow(need: need, value: Int(store.state.needs[need].rounded()))
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
                    Rectangle()
                        .fill(island.isLive ? Brand.slime : Brand.muted)
                        .frame(width: 8, height: 8)
                    Eyebrow(text: island.isLive ? "in der dynamic island" : "nicht in der insel",
                            color: island.isLive ? Brand.slime : Brand.muted)
                }
                Text(island.isLive
                     ? "Halt die Insel gedrückt, um zu füttern, zu spielen, zu baden oder zu kraulen."
                     : "Hol deinen Slime in die Dynamic Island, dann ist er immer dabei.")
                    .font(Brand.body(15))
                    .foregroundStyle(Brand.text)
                if let problem = island.problem {
                    Text(problem)
                        .font(Brand.body(14, .medium))
                        .foregroundStyle(Brand.warm)
                }
                PixelButton(title: island.isLive ? "Insel neu laden" : "In die Insel holen",
                            symbol: "capsule.fill", filled: !island.isLive) {
                    Task { await island.ensureRunning(restart: true) }
                }
            }
        }
    }
}
