import SwiftUI

/// Jelly Dash: the slime runs on its own; tap to jump, hold to jump higher.
struct JellyDashView: View {
    let session: GameSession
    @State private var engine: JellyDashEngine
    @State private var juice = Juice()
    @State private var touching = false
    @State private var mood: (anim: SpriteAnim, until: Double) = (.play, 0)
    @State private var endIn: Double?
    @State private var elapsed = 0.0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static let rock: [String] = [
        "...ggg...",
        "..gwggg..",
        ".gwgggdg.",
        ".ggggggd.",
        "ggggggdgd",
        "gggggdgdd",
        "ggggdgddd",
        "gggdgdddd",
        "ddddddddd",
    ]
    /// Half of a boulder; two of them stack up.
    private static let bigRock: [String] = [
        "...ggggg...",
        "..gwgggggg.",
        ".gwgggggdgg",
        ".ggggggggdg",
        "gggggggdggd",
        "ggggggdgggd",
        "gggggggdgdd",
        "ggggggdgddd",
        "gggggdgdddd",
        "ggggdgddddd",
        "gggdgdddddd",
        "ddddddddddd",
    ]
    private static let puddle: [String] = [
        "..bbbbbbbbbb..",
        ".bbwwbbbbbbbb.",
        "bbbbbbbbbbbddd",
        ".bbbbbbbbbddd.",
    ]
    private static let stone: [Character: Color] = [
        "g": Color(hex: 0x8D9D91), "d": Color(hex: 0x5A6660), "w": Color(hex: 0xC9D2C2),
    ]
    private static let water: [Character: Color] = [
        "b": Color(hex: 0x7FD6F0), "d": Color(hex: 0x4A9AC2), "w": .white,
    ]

    init(session: GameSession) {
        self.session = session
        _engine = State(initialValue: JellyDashEngine(seed: session.seed))
    }

    var body: some View {
        GeometryReader { geo in
            let space = BoardSpace(size: geo.size)
            // Day turns to dusk as the run goes on.
            let dusk = min(1, engine.distance / 6000)
            ZStack(alignment: .topLeading) {
                GameBackdrop(top: GameBackdrop.mix(0x8CC8E8, 0x5B4A8A, dusk),
                             bottom: GameBackdrop.mix(0xDDF1F5, 0xF29A76, dusk))
                Canvas { ctx, size in
                    drawScenery(ctx, size: size, space: space, dusk: dusk)
                    drawGround(ctx, size: size, space: space)
                    drawObstacles(ctx, space: space)
                    drawPickups(ctx, space: space)
                    juice.draw(in: ctx, space: space)
                }
                runner(space: space)
                hud
            }
            .offset(juice.shakeOffset(engine.time))
            .overlay(Color.red.opacity(juice.flash * 1.2).allowsHitTesting(false))
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    guard !touching, session.running else { return }
                    touching = true
                    engine.press()
                    react(to: engine.drainEvents())
                }
                .onEnded { _ in
                    touching = false
                    engine.release()
                })
        }
        .onAppear {
            juice.reduceMotion = reduceMotion
            report()
        }
        .onChange(of: session.running) { _, running in
            // A pause in the middle of a held jump lets go of it.
            if !running {
                touching = false
                engine.release()
            }
        }
        .gameLoop(running: session.running) { dt in
            elapsed += dt
            engine.step(dt)
            react(to: engine.drainEvents())
            juice.step(dt)
            report()
            if engine.isOver {
                if let left = endIn {
                    endIn = left - dt
                    if left - dt <= 0 { session.end() }
                } else {
                    endIn = 0.8
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Jelly Dash. The slime runs by itself. Jump over puddles and rocks, collect coins and berries.")
        .accessibilityValue("\(engine.meters) meters, \(engine.lives) hearts, \(engine.score) points")
        .accessibilityAddTraits(.allowsDirectInteraction)
        .accessibilityAction { jumpForVoiceOver(high: false) }
        .accessibilityAction(named: "Jump") { jumpForVoiceOver(high: false) }
        .accessibilityAction(named: "High jump") { jumpForVoiceOver(high: true) }
    }

    // MARK: Input and feedback

    private func jumpForVoiceOver(high: Bool) {
        guard session.running else { return }
        engine.press()
        react(to: engine.drainEvents())
        Task {
            try? await Task.sleep(for: .milliseconds(high ? 260 : 40))
            engine.release()
        }
    }

    private func react(to events: [ArcadeEvent]) {
        let x = JellyDashEngine.playerX
        let y = engine.y
        for event in events {
            switch event {
            case .jump:
                juice.burst(x: x, y: JellyDashEngine.ground, color: Color(hex: 0xC9A36B), count: 5, power: 26)
                Feedback.shared.play(.hop)
            case .land:
                juice.bounce()
            case .coin:
                juice.burst(x: x + 4, y: y - 8, color: Theme.gold, count: 7, power: 45)
                juice.popup("+\(JellyDashEngine.coinValue)", x: x + 6, y: y - 16, color: .white)
                Feedback.shared.play(.coin)
            case .good:
                juice.burst(x: x + 4, y: y - 8, color: SlimeSprites.mainColor("berry"), count: 10, power: 55)
                juice.popup("+\(JellyDashEngine.berryValue)", x: x + 6, y: y - 18, color: .white, big: true)
                mood = (.eat, engine.time + 0.35)
                Feedback.shared.play(.pop)
            case .great:
                juice.popup("Streak +\(JellyDashEngine.streakBonus)", x: 50, y: 52, color: Theme.gold, big: true)
                Feedback.shared.play(.perfect)
            case .bad:
                juice.hit()
                juice.burst(x: x, y: y - 4, color: Theme.danger, count: 12, power: 70)
                mood = (.confused, engine.time + 0.7)
                Feedback.shared.play(.nope)
            case .lose:
                mood = (.sad, engine.time + 10)
                Feedback.shared.play(.whoosh)
            case .miss, .tone, .win:
                Feedback.shared.play(event.sound)
            }
        }
    }

    private func report() {
        session.score = engine.score
        session.status = "\(engine.meters) m"
    }

    // MARK: Drawing

    private func screenX(_ trackX: Double) -> Double { trackX - engine.distance }

    private func drawGround(_ ctx: GraphicsContext, size: CGSize, space: BoardSpace) {
        let top = space.point(0, JellyDashEngine.ground).y
        ctx.block(CGRect(x: 0, y: top, width: size.width, height: size.height - top), Color(hex: 0x8A5A3C))
        ctx.block(CGRect(x: 0, y: top, width: size.width, height: space.scale * 3), Color(hex: 0x6FBF5A),
                  edge: Color(hex: 0x3F8F3A))
        // Marks in the soil scroll at running speed.
        let shift = engine.distance.truncatingRemainder(dividingBy: 18)
        let left = space.boardX(0) - 18
        var x = left - shift
        while x < space.boardX(size.width) + 18 {
            ctx.fill(Path(space.rect(x, JellyDashEngine.ground + 7, 4, 1.4)), with: .color(.black.opacity(0.15)))
            ctx.fill(Path(space.rect(x + 9, JellyDashEngine.ground + 13, 3, 1.4)), with: .color(.black.opacity(0.12)))
            x += 18
        }
    }

    /// Clouds, far hills and bushes, each layer slower than the track.
    private func drawScenery(_ ctx: GraphicsContext, size: CGSize, space: BoardSpace, dusk: Double) {
        let left = space.boardX(0) - 30
        let right = space.boardX(size.width) + 30
        let span = right - left
        for (i, c) in DashScenery.clouds.enumerated() {
            let x = left + (c.0 * span - engine.distance * 0.08 - Double(i) * 7).positiveRemainder(span)
            let y = 18 + c.1 * 50
            ctx.fill(Path(space.rect(x, y, 20, 4.5)), with: .color(.white.opacity(0.65 - dusk * 0.25)))
            ctx.fill(Path(space.rect(x + 4, y - 3, 10, 3)), with: .color(.white.opacity(0.65 - dusk * 0.25)))
        }
        let hill = Color(hex: GameBackdrop.mix(0x9CCB8A, 0x6E5A8C, dusk))
        for (i, h) in DashScenery.hills.enumerated() {
            let x = left + (Double(i) * 34 - engine.distance * 0.2).positiveRemainder(span)
            let height = 10 + h * 18
            // Stepped pixel hill.
            ctx.fill(Path(space.rect(x, JellyDashEngine.ground - height, 26, height)), with: .color(hill))
            ctx.fill(Path(space.rect(x + 5, JellyDashEngine.ground - height - 4, 16, 4)), with: .color(hill))
            ctx.fill(Path(space.rect(x + 9, JellyDashEngine.ground - height - 7, 8, 3)), with: .color(hill))
        }
        let bush = Color(hex: GameBackdrop.mix(0x4FA34A, 0x3E5A4A, dusk))
        for (i, b) in DashScenery.bushes.enumerated() {
            let x = left + (Double(i) * 23 + b * 9 - engine.distance * 0.5).positiveRemainder(span)
            ctx.fill(Path(space.rect(x, JellyDashEngine.ground - 5, 10, 5)), with: .color(bush))
            ctx.fill(Path(space.rect(x + 2, JellyDashEngine.ground - 8, 6, 3)), with: .color(bush))
        }
    }

    private func drawObstacles(_ ctx: GraphicsContext, space: BoardSpace) {
        let g = JellyDashEngine.ground
        for o in engine.obstacles {
            let x = screenX(o.x)
            guard x > -20, x < Board.width + 30 else { continue }
            switch o.kind {
            case .puddle:
                ctx.drawPixels(Self.puddle, palette: Self.water, in: space.rect(x - 7, g - 2.5, 14, 4))
            case .rock:
                ctx.fill(Path(ellipseIn: space.rect(x - 6, g - 1, 12, 2.4)), with: .color(.black.opacity(0.15)))
                ctx.drawPixels(Self.rock, palette: Self.stone, in: space.rect(x - 4.5, g - 9, 9, 9))
            case .boulder:
                ctx.fill(Path(ellipseIn: space.rect(x - 7, g - 1, 14, 2.4)), with: .color(.black.opacity(0.15)))
                ctx.drawPixels(Self.bigRock, palette: Self.stone, in: space.rect(x - 5.5, g - 12, 11, 12))
                ctx.drawPixels(Self.bigRock, palette: Self.stone, in: space.rect(x - 5, g - 24, 10, 12))
            }
        }
    }

    private func drawPickups(_ ctx: GraphicsContext, space: BoardSpace) {
        for p in engine.pickups {
            let x = screenX(p.x)
            guard x > -10, x < Board.width + 20 else { continue }
            let bob = reduceMotion ? 0 : sin(elapsed * 5 + p.x * 0.3) * 1.2
            switch p.kind {
            case .coin: ctx.drawSprite("coin", in: space.centered(x, p.y + bob, 7, 7))
            case .berry: ctx.drawSprite("berry", in: space.centered(x, p.y + bob, 9, 9))
            }
        }
    }

    // MARK: Slime and HUD

    private func runner(space: BoardSpace) -> some View {
        let anim: SpriteAnim = engine.time < mood.until ? mood.anim
            : engine.isOnGround ? .play : .celebrate
        let blink = engine.invulnerable > 0 && Int(engine.time * 12) % 2 == 0
        let (sx, sy) = juice.squashScale
        // `elapsed` keeps running after the last heart, so the sad slime moves.
        return GameSlime(anim: anim, height: 12 * space.scale, time: elapsed * 1.6)
            .scaleEffect(x: sx, y: sy, anchor: .bottom)
            .opacity(blink ? 0.35 : 1)
            .position(space.point(JellyDashEngine.playerX, engine.y - 6))
    }

    private var hud: some View {
        HStack(alignment: .top) {
            HStack(spacing: 3) {
                ForEach(0..<JellyDashEngine.lives, id: \.self) { i in
                    PixelItem(name: "heart")
                        .frame(width: 15, height: 12)
                        .opacity(i < engine.lives ? 1 : 0.25)
                }
            }
            .padding(6)
            .background(Color.black.opacity(0.25))
            Spacer()
            if engine.streak >= 3 {
                Text("\(engine.streak) in a row")
                    .font(Brand.pixel(14))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 4)
                    .background(Theme.love)
            }
        }
        .padding(10)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Fixed scatter for the scenery layers.
private enum DashScenery {
    static let clouds: [(Double, Double)] = {
        var rng = SplitMix(seed: 501)
        return (0..<5).map { _ in (rng.unit(), rng.unit()) }
    }()
    static let hills: [Double] = {
        var rng = SplitMix(seed: 502)
        return (0..<6).map { _ in rng.unit() }
    }()
    static let bushes: [Double] = {
        var rng = SplitMix(seed: 503)
        return (0..<9).map { _ in rng.unit() }
    }()
}

private extension Double {
    /// Remainder that is never negative, for wrapping scenery around.
    func positiveRemainder(_ divisor: Double) -> Double {
        guard divisor > 0 else { return 0 }
        let r = truncatingRemainder(dividingBy: divisor)
        return r < 0 ? r + divisor : r
    }
}
