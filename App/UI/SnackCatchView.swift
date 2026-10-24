import Combine
import SwiftUI

/// Mini-game: drag the slime left and right to catch falling snacks.
/// 30 seconds. Snacks give points, spiky things take them away.
struct SnackCatchView: View {
    @EnvironmentObject private var store: SlimeStore
    @Environment(\.dismiss) private var dismiss
    @StateObject private var game = SnackGame()

    private let ticker = Timer.publish(every: 1 / 60, on: .main, in: .common).autoconnect()

    var body: some View {
        GeometryReader { geo in
            ZStack {
                SkyBackground(date: .now)
                    .ignoresSafeArea()

                ForEach(game.items) { item in
                    Text(item.kind.emoji)
                        .font(.system(size: 34))
                        .rotationEffect(.degrees(item.spin))
                        .position(x: item.x * geo.size.width, y: item.y * geo.size.height)
                }

                ForEach(game.pops) { pop in
                    Text(pop.text)
                        .font(Brand.display(20, .black))
                        .foregroundStyle(pop.good ? Brand.gold : Brand.danger)
                        .position(x: pop.x * geo.size.width, y: geo.size.height * (SnackGame.playerY - 0.12) - CGFloat(pop.age) * 60)
                        .opacity(1 - pop.age / 0.8)
                }

                player(size: geo.size)

                hud
                if game.phase == .ready { readyCard }
                if game.phase == .done { resultCard }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                game.playerX = min(0.92, max(0.08, value.location.x / geo.size.width))
            })
        }
        .onReceive(ticker) { now in game.step(now: now) }
        .sensoryFeedback(.impact(weight: .light), trigger: game.catches)
        .sensoryFeedback(.error, trigger: game.ouches)
        .onChange(of: game.phase) { _, phase in
            if phase == .done { game.result = store.act(.play(score: game.score)) }
        }
        .statusBarHidden()
    }

    private func player(size: CGSize) -> some View {
        let frames = (game.recentlyAte ? SpriteAnim.eat : game.recentlyHurt ? SpriteAnim.sad : SpriteAnim.play).frames
        let t = Date.now.timeIntervalSinceReferenceDate
        return PixelSlime(frame: frames[Int(t * 3) % frames.count], skin: store.state.skin)
            .frame(width: SnackGame.playerWidth * size.width, height: SnackGame.playerWidth * size.width / SlimeFont.aspect)
            .position(x: game.playerX * size.width, y: SnackGame.playerY * size.height)
            .offset(x: game.recentlyHurt ? CGFloat(sin(t * 60)) * 5 : 0)
    }

    private var hud: some View {
        VStack {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Eyebrow(text: "snack catch", color: Brand.hyprGlow)
                    Text("\(game.score)")
                        .font(Brand.display(40, .black))
                        .foregroundStyle(Brand.text)
                        .contentTransition(.numericText())
                        .monospacedDigit()
                }
                Spacer()
                Text("\(Int(ceil(game.timeLeft)))s")
                    .font(Brand.mono(20, .bold))
                    .foregroundStyle(game.timeLeft < 6 ? Brand.danger : Brand.text)
                    .monospacedDigit()
                CloseButton { dismiss() }
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            Spacer()
        }
    }

    private var readyCard: some View {
        Panel(padding: 22) {
            VStack(alignment: .leading, spacing: 14) {
                Text("Catch the snacks!")
                    .font(Brand.display(26, .black))
                    .foregroundStyle(Brand.text)
                Text("Drag anywhere to move \(store.state.name). 🍎 🍓 🍪 give points, 🌟 gives a lot, and 🌵 hurts. You have 30 seconds.")
                    .font(Brand.display(15, .medium))
                    .foregroundStyle(Brand.muted)
                JellyButton(title: "Start", symbol: "play.fill", tint: Brand.color(for: .fun)) { game.start() }
            }
        }
        .padding(24)
    }

    private var resultCard: some View {
        Panel(padding: 22) {
            VStack(alignment: .leading, spacing: 14) {
                Eyebrow(text: game.score >= store.state.counters.bestScore ? "new best!" : "time's up", color: Brand.gold)
                Text("\(game.score) points")
                    .font(Brand.display(32, .black))
                    .foregroundStyle(Brand.text)
                Text(game.result?.line ?? "")
                    .font(Brand.display(15, .medium))
                    .foregroundStyle(Brand.muted)
                HStack(spacing: 10) {
                    CoinChip(coins: store.state.coins)
                    Text("+\(2 + game.score / 5) coins")
                        .font(Brand.mono(12, .bold))
                        .foregroundStyle(Brand.gold)
                }
                HStack(spacing: 10) {
                    JellyButton(title: "Done", tint: Brand.hypr, filled: false) { dismiss() }
                    if store.state.needs.energy >= 12 {
                        JellyButton(title: "Again", symbol: "arrow.clockwise", tint: Brand.color(for: .fun)) { game.start() }
                    }
                }
            }
        }
        .padding(24)
    }
}

@MainActor
final class SnackGame: ObservableObject {
    enum Phase { case ready, playing, done }

    enum Kind: CaseIterable {
        case apple, berry, cookie, star, cactus

        var emoji: String {
            switch self {
            case .apple: "🍎"
            case .berry: "🍓"
            case .cookie: "🍪"
            case .star: "🌟"
            case .cactus: "🌵"
            }
        }

        var points: Int {
            switch self {
            case .apple: 1
            case .berry: 2
            case .cookie: 3
            case .star: 8
            case .cactus: -4
            }
        }
    }

    struct Item: Identifiable {
        let id = UUID()
        var x: CGFloat
        var y: CGFloat
        var speed: CGFloat
        var spin: Double
        let kind: Kind
    }

    struct Pop: Identifiable {
        let id = UUID()
        let x: CGFloat
        let text: String
        let good: Bool
        let born: Date
        var age: Double { Date.now.timeIntervalSince(born) }
    }

    static let playerY: CGFloat = 0.82
    static let playerWidth: CGFloat = 0.26
    static let length: Double = 30

    @Published var phase: Phase = .ready
    @Published var items: [Item] = []
    @Published var pops: [Pop] = []
    @Published var playerX: CGFloat = 0.5
    @Published var score = 0
    @Published var timeLeft = SnackGame.length
    @Published var catches = 0
    @Published var ouches = 0
    var result: ActionOutcome?

    private var last: Date?
    private var spawnTimer: Double = 0
    private var lastCatch = Date.distantPast
    private var lastHurt = Date.distantPast

    var recentlyAte: Bool { Date.now.timeIntervalSince(lastCatch) < 0.5 }
    var recentlyHurt: Bool { Date.now.timeIntervalSince(lastHurt) < 0.5 }

    func start() {
        items = []
        pops = []
        score = 0
        timeLeft = Self.length
        result = nil
        last = nil
        phase = .playing
    }

    func step(now: Date) {
        defer { last = now }
        pops.removeAll { $0.age > 0.8 }
        guard phase == .playing, let last else { return }
        let dt = min(0.05, now.timeIntervalSince(last))
        timeLeft -= dt
        if timeLeft <= 0 {
            timeLeft = 0
            phase = .done
            return
        }

        // Faster and busier as time runs out.
        let progress = 1 - timeLeft / Self.length
        spawnTimer -= dt
        if spawnTimer <= 0 {
            spawnTimer = 0.65 - progress * 0.35
            let roll = Double.random(in: 0..<1)
            let kind: Kind = roll < 0.06 ? .star : roll < 0.28 ? .cactus : [.apple, .berry, .cookie].randomElement()!
            items.append(Item(x: .random(in: 0.08...0.92), y: -0.05,
                              speed: .random(in: 0.28...0.4) + CGFloat(progress) * 0.25,
                              spin: .random(in: -20...20), kind: kind))
        }

        var kept: [Item] = []
        for var item in items {
            item.y += item.speed * dt
            item.spin += 90 * dt
            let caught = abs(item.y - (Self.playerY - 0.04)) < 0.035
                && abs(item.x - playerX) < Self.playerWidth / 2
            if caught {
                score = max(0, score + item.kind.points)
                pops.append(Pop(x: item.x, text: item.kind.points > 0 ? "+\(item.kind.points)" : "\(item.kind.points)",
                                good: item.kind.points > 0, born: now))
                if item.kind.points > 0 {
                    catches += 1
                    lastCatch = now
                } else {
                    ouches += 1
                    lastHurt = now
                }
            } else if item.y < 1.05 {
                kept.append(item)
            }
        }
        items = kept
    }
}
