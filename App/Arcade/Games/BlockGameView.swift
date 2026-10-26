import SwiftUI

/// Draws and runs the day's building-block game.
struct BlockGameView: View {
    let session: GameSession
    @State private var engine: BlockEngine
    @State private var juice = Juice()
    @State private var touching = false
    @State private var facingLeft = false
    @State private var mood: (anim: SpriteAnim, until: Double) = (.play, 0)
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(session: GameSession, spec: GameSpec) {
        self.session = session
        _engine = State(initialValue: BlockEngine(spec: spec, seed: session.seed))
    }

    private var spec: GameSpec { engine.spec }

    var body: some View {
        GeometryReader { geo in
            let space = BoardSpace(size: geo.size)
            let colors = spec.theme.colors
            ZStack(alignment: .topLeading) {
                GameBackdrop(top: colors.top, bottom: colors.bottom)
                Canvas { ctx, size in
                    drawDecor(ctx, size: size, space: space)
                    drawGround(ctx, size: size, space: space)
                    drawThings(ctx, space: space)
                    juice.draw(in: ctx, space: space)
                }
                player(space: space)
                hud(space: space)
                    .padding(8)
            }
            .offset(juice.shakeOffset(engine.time))
            .overlay(Color.red.opacity(juice.flash * 1.2).allowsHitTesting(false))
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { value in
                    let bx = space.boardX(value.location.x), by = space.boardY(value.location.y)
                    switch spec.control {
                    case .drag, .steer:
                        facingLeft = bx < engine.x
                        engine.target = (bx, by)
                    case .jump, .flap:
                        if !touching { engine.tap() }
                    case .lanes:
                        break
                    }
                    touching = true
                }
                .onEnded { value in
                    touching = false
                    guard spec.control == .lanes else { return }
                    if abs(value.translation.width) > 20 {
                        engine.moveLane(value.translation.width > 0 ? 1 : -1)
                    } else {
                        engine.moveLane(value.location.x < geo.size.width / 2 ? -1 : 1)
                    }
                })
        }
        .onAppear {
            juice.reduceMotion = reduceMotion
            report()
        }
        .gameLoop(running: session.running) { dt in
            engine.step(dt)
            for event in engine.drainEvents() { react(to: event) }
            juice.step(dt)
            report()
            if engine.isOver { session.end() }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(spec.title). \(spec.tutorial.joined(separator: " "))")
        .accessibilityValue("\(engine.score) points\(spec.lives > 0 ? ", \(engine.lives) lives" : "")")
        .accessibilityAdjustableAction { direction in
            let step = direction == .increment ? 1.0 : -1.0
            switch spec.control {
            case .lanes: engine.moveLane(Int(step))
            case .drag, .steer: engine.target = (max(8, min(92, engine.x + step * 15)), engine.y)
            case .jump, .flap: engine.tap()
            }
        }
        .accessibilityActions {
            // Only games that jump or flap get the action; elsewhere it would do nothing.
            if spec.control == .jump || spec.control == .flap {
                Button(spec.control == .jump ? "Jump" : "Flap") { engine.tap() }
            }
        }
    }

    // MARK: Feedback

    private func react(to event: BlockEngine.Event) {
        switch event {
        case .collect(let x, let y, let points, let sprite):
            juice.burst(x: x, y: y, color: SlimeSprites.mainColor(sprite), count: 8)
            juice.popup("+\(points)", x: x, y: y - 6, color: .white)
            juice.bounce()
            mood = (.eat, engine.time + 0.35)
            Feedback.shared.play(engine.combo >= 5 ? .perfect : .pop)
        case .hit(let x, let y):
            juice.hit()
            juice.burst(x: x, y: y, color: Theme.danger, count: 14, power: 80)
            mood = (.confused, engine.time + 0.8)
            Feedback.shared.play(.nope)
        case .blocked(let x, let y):
            juice.burst(x: x, y: y, color: Brand.cool, count: 12)
            juice.popup("blocked", x: x, y: y - 8, color: Brand.cool)
            Feedback.shared.play(.land)
        case .power(let kind, let x, let y):
            juice.burst(x: x, y: y, color: Theme.gold, count: 18, power: 90)
            juice.popup(kind.title, x: 50, y: 60, color: Theme.gold, big: true)
            mood = (.celebrate, engine.time + 0.8)
            Feedback.shared.play(.success)
        case .combo(let n):
            juice.popup("\(n) combo!", x: 50, y: 48, color: .white, big: true)
            Feedback.shared.play(.levelUp)
        case .miss:
            Feedback.shared.play(.miss)
        case .jump:
            Feedback.shared.play(.hop)
        }
    }

    private func report() {
        session.score = engine.score
        if let left = engine.timeLeft {
            session.status = clock(left)
        } else {
            session.status = clock(engine.time)
        }
    }

    // MARK: Player

    private func player(space: BoardSpace) -> some View {
        let shrink = engine.powers[.shrink] != nil
        let height = 16 * space.scale * (shrink ? 0.65 : 1)
        let anim: SpriteAnim = engine.time < mood.until ? mood.anim
            : (spec.control == .jump || spec.control == .flap) && engine.vy < 0 ? .celebrate : .play
        let blink = engine.invulnerable > 0 && Int(engine.time * 12) % 2 == 0
        let (sx, sy) = juice.squashScale
        return ZStack {
            GameSlime(anim: anim, height: height, mirrored: facingLeft, time: engine.time)
                .scaleEffect(x: sx, y: sy, anchor: .bottom)
            if engine.powers[.shield] != nil {
                Circle()
                    .strokeBorder(Brand.cool.opacity(0.8), lineWidth: 2.5)
                    .frame(width: height * 1.45, height: height * 1.45)
            }
            if engine.powers[.magnet] != nil {
                Circle()
                    .strokeBorder(Theme.gold.opacity(0.35), style: StrokeStyle(lineWidth: 1.5, dash: [3, 4]))
                    .frame(width: 42 * space.scale * 2, height: 42 * space.scale * 2)
            }
        }
        .opacity(blink ? 0.35 : 1)
        .position(space.point(engine.x, engine.y - 8))
        .accessibilityHidden(true)
    }

    // MARK: HUD

    private func hud(space: BoardSpace) -> some View {
        HStack(alignment: .top, spacing: 8) {
            if spec.lives > 0 {
                HStack(spacing: 2) {
                    ForEach(0..<max(engine.lives, 0), id: \.self) { _ in
                        PixelItem(name: "heart").frame(width: 14, height: 11)
                    }
                }
                .padding(5)
                .background(Color.black.opacity(0.25))
            }
            Spacer()
            ForEach(Array(engine.powers.keys).sorted { $0.rawValue < $1.rawValue }, id: \.self) { p in
                VStack(spacing: 2) {
                    Text(p.title).font(Brand.pixel(10)).foregroundStyle(.white)
                    Meter(value: (engine.powers[p] ?? 0) / max(1, p.seconds), color: Theme.gold, height: 4)
                        .frame(width: 44)
                }
                .padding(4)
                .background(Color.black.opacity(0.3))
            }
            if engine.combo >= 3 {
                Text(String(format: "x%.1f", engine.multiplier))
                    .font(Brand.pixel(16)).foregroundStyle(.white)
                    .padding(.horizontal, 6).padding(.vertical, 3)
                    .background(Theme.love)
            }
        }
        .allowsHitTesting(false)
    }

    // MARK: Drawing

    private func drawGround(_ ctx: GraphicsContext, size: CGSize, space: BoardSpace) {
        let ground = Color(hex: spec.theme.colors.ground)
        switch spec.control {
        case .steer:
            return
        case .flap:
            let top = space.point(0, 152).y
            ctx.fill(Path(CGRect(x: 0, y: top, width: size.width, height: size.height - top)), with: .color(ground))
        case .lanes:
            let top = space.point(0, 152).y
            ctx.fill(Path(CGRect(x: 0, y: top, width: size.width, height: size.height - top)), with: .color(ground))
            for lx in [35.0, 65.0] {
                var y = (engine.time * 60).truncatingRemainder(dividingBy: 16) - 16
                while y < 160 {
                    ctx.fill(Path(space.rect(lx - 0.6, y, 1.2, 7)), with: .color(.white.opacity(0.25)))
                    y += 16
                }
            }
        case .drag, .jump:
            let top = space.point(0, 152).y
            ctx.fill(Path(CGRect(x: 0, y: top, width: size.width, height: size.height - top)), with: .color(ground))
            ctx.fill(Path(CGRect(x: 0, y: top, width: size.width, height: space.scale * 2)), with: .color(.white.opacity(0.25)))
            if spec.control == .jump {
                // Ground marks scroll by, so running feels like running.
                var x = 100 - (engine.time * 48 * engine.intensity).truncatingRemainder(dividingBy: 20)
                while x > -20 {
                    ctx.fill(Path(space.rect(x, 155, 6, 1.5)), with: .color(.black.opacity(0.15)))
                    x -= 20
                }
            }
        }
    }

    private func drawThings(_ ctx: GraphicsContext, space: BoardSpace) {
        for t in engine.things {
            let e = spec.entities[t.kind]
            let size = 10 * e.size
            if e.role == .power {
                // Power-ups glow and pulse so they read at a glance.
                let pulse = 1 + sin(engine.time * 6) * 0.12
                let r = space.centered(t.x, t.y, size * 1.7 * pulse, size * 1.7 * pulse)
                ctx.fill(Path(ellipseIn: r), with: .color(Theme.gold.opacity(0.35)))
                ctx.stroke(Path(ellipseIn: r), with: .color(Theme.gold), lineWidth: 1.5)
            } else if e.role == .avoid {
                ctx.fill(Path(ellipseIn: space.centered(t.x, t.y + size * 0.55, size * 0.9, size * 0.25)),
                         with: .color(.black.opacity(0.15)))
            }
            ctx.drawSprite(e.sprite, in: space.centered(t.x, t.y, size, size))
        }
    }

    private func drawDecor(_ ctx: GraphicsContext, size: CGSize, space: BoardSpace) {
        let t = engine.time
        switch spec.theme.decor {
        case .clouds:
            for (i, c) in Decor.points.prefix(5).enumerated() {
                let x = (c.0 * 140 + t * (3 + Double(i))).truncatingRemainder(dividingBy: 140) - 20
                let y = 10 + c.1 * 50
                ctx.fill(Path(space.rect(x, y, 22, 5)), with: .color(.white.opacity(0.6)))
                ctx.fill(Path(space.rect(x + 5, y - 3, 11, 3)), with: .color(.white.opacity(0.6)))
            }
        case .stars:
            for (i, c) in Decor.points.enumerated() {
                let a = 0.35 + 0.35 * sin(t * 2 + Double(i))
                ctx.fill(Path(space.rect(c.0 * 100, c.1 * 140, 1.2, 1.2)), with: .color(.white.opacity(a)))
            }
        case .bubbles:
            for (i, c) in Decor.points.prefix(14).enumerated() {
                let y = 160 - (c.1 * 160 + t * (8 + Double(i % 4) * 3)).truncatingRemainder(dividingBy: 170)
                let x = c.0 * 100 + sin(t + Double(i)) * 3
                ctx.stroke(Path(ellipseIn: space.centered(x, y, 3, 3)), with: .color(.white.opacity(0.4)), lineWidth: 1)
            }
        case .leaves, .snowflakes:
            let color: Color = spec.theme.decor == .leaves ? Color(hex: 0xE8A92A) : .white
            for (i, c) in Decor.points.prefix(16).enumerated() {
                let y = (c.1 * 160 + t * (10 + Double(i % 3) * 4)).truncatingRemainder(dividingBy: 170) - 10
                let x = c.0 * 100 + sin(t * 1.5 + Double(i)) * 5
                ctx.fill(Path(space.rect(x, y, 1.8, 1.8)), with: .color(color.opacity(0.7)))
            }
        case .sparkles:
            for (i, c) in Decor.points.prefix(14).enumerated() {
                let a = max(0, sin(t * 2.5 + Double(i) * 1.3))
                let x = c.0 * 100, y = c.1 * 140
                ctx.fill(Path(space.rect(x - 1.5, y, 3, 0.8)), with: .color(.white.opacity(a * 0.7)))
                ctx.fill(Path(space.rect(x - 0.4, y - 1.1, 0.8, 3)), with: .color(.white.opacity(a * 0.7)))
            }
        case .embers:
            for (i, c) in Decor.points.prefix(16).enumerated() {
                let y = 160 - (c.1 * 160 + t * (14 + Double(i % 4) * 5)).truncatingRemainder(dividingBy: 170)
                let x = c.0 * 100 + sin(t * 2 + Double(i)) * 4
                ctx.fill(Path(space.rect(x, y, 1.4, 1.4)), with: .color(Color(hex: 0xFF9A3C).opacity(0.75)))
            }
        }
    }
}

/// Fixed scatter of points (0…1) for background decoration.
private enum Decor {
    static let points: [(Double, Double)] = {
        var rng = SplitMix(seed: 42)
        return (0..<30).map { _ in (rng.unit(), rng.unit()) }
    }()
}
