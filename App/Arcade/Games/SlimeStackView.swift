import SwiftUI

/// Slime Stack: the slime rides the sliding jelly block; a tap drops it on
/// the tower.
struct SlimeStackView: View {
    let session: GameSession
    @State private var engine: SlimeStackEngine
    @State private var juice = Juice()
    @State private var touching = false
    @State private var mood: (anim: SpriteAnim, until: Double) = (.idle, 0)
    @State private var endIn: Double?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Jelly colours: fill and darker edge.
    private static let jelly: [(fill: UInt32, edge: UInt32)] = [
        (0x7DD86B, 0x4E9E45), (0xF58AB0, 0xC25782), (0x6FC3F0, 0x3E8CC0),
        (0xF7D35C, 0xC9A12E), (0xB08CF0, 0x7E5CC0), (0xF5A05A, 0xC2702E),
    ]
    private static let gold: (fill: UInt32, edge: UInt32) = (0xFFD166, 0xC9962E)

    init(session: GameSession) {
        self.session = session
        _engine = State(initialValue: SlimeStackEngine(seed: session.seed))
    }

    var body: some View {
        GeometryReader { geo in
            let space = BoardSpace(size: geo.size)
            // The sky gets darker the higher the tower grows.
            let height = min(1, Double(engine.height) / 40)
            ZStack(alignment: .topLeading) {
                GameBackdrop(top: GameBackdrop.mix(0x9FD4E8, 0x1B1F4A, height),
                             bottom: GameBackdrop.mix(0xF6E7C8, 0x4A3A7A, height))
                Canvas { ctx, size in
                    drawStars(ctx, space: space, alpha: max(0, height * 2 - 0.6))
                    drawGround(ctx, size: size, space: space)
                    drawMarks(ctx, space: space)
                    for block in engine.tower where visible(block.level) {
                        drawBlock(ctx, block, space: space)
                    }
                    if !engine.isOver {
                        drawBlock(ctx, engine.current, space: space)
                    }
                    for piece in engine.pieces {
                        drawPiece(ctx, piece, space: space)
                    }
                    juice.draw(in: ctx, space: space)
                }
                rider(space: space)
                hud(space: space)
            }
            .offset(juice.shakeOffset(engine.time))
            .overlay(Color.red.opacity(juice.flash * 1.2).allowsHitTesting(false))
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    guard !touching else { return }
                    touching = true
                    drop()
                }
                .onEnded { _ in touching = false })
        }
        .onAppear {
            juice.reduceMotion = reduceMotion
            report()
        }
        .onChange(of: session.running) { _, running in
            if !running { touching = false }
        }
        .gameLoop(running: session.running) { dt in
            engine.step(dt)
            for event in engine.drainEvents() { Feedback.shared.play(event.sound) }
            juice.step(dt)
            report()
            if let left = endIn {
                endIn = left - dt
                if left - dt <= 0 { session.end() }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Slime Stack. A jelly block slides above the tower. Drop it when it is right above the block below.")
        .accessibilityValue("Tower of \(engine.height) blocks, \(engine.score) points")
        .accessibilityAddTraits(.allowsDirectInteraction)
        .accessibilityAction { drop() }
        .accessibilityAction(named: "Drop block") { drop() }
    }

    // MARK: Input

    private func drop() {
        guard session.running, !engine.isOver else { return }
        let before = engine.current
        let below = engine.top
        let result = engine.drop()
        let events = engine.drainEvents()
        events.forEach { Feedback.shared.play($0.sound) }
        let y = SlimeStackEngine.y(level: before.level) + engine.scroll
        switch result {
        case .perfect:
            let placed = engine.top
            juice.burst(x: placed.left, y: y + 4, color: .white, count: 6, power: 40)
            juice.burst(x: placed.right, y: y + 4, color: .white, count: 6, power: 40)
            juice.popup("Perfect", x: placed.x, y: y - 10, color: .white, big: engine.combo >= 3)
            juice.bounce()
            mood = (.celebrate, engine.time + 0.6)
        case .cut:
            let edge = before.x > below.x ? below.right : below.left
            juice.burst(x: edge, y: y + 4, color: Color(hex: Self.jelly[before.color % Self.jelly.count].fill), count: 8)
            juice.bounce()
            mood = (.idle, 0)
        case .missed:
            juice.hit()
            mood = (.confused, engine.time + 5)
            endIn = 0.9
        case .ignored:
            break
        }
        if before.gold, result == .perfect || result == .cut {
            juice.burst(x: before.x, y: y, color: Theme.gold, count: 14, power: 70)
            juice.popup("+\(SlimeStackEngine.goldBonus)", x: before.x, y: y - 20, color: Theme.gold, big: true)
        }
        report()
    }

    private func report() {
        session.score = engine.score
        session.status = engine.height == 1 ? "1 block" : "\(engine.height) blocks"
    }

    // MARK: Drawing

    private func screenY(_ worldY: Double) -> Double { worldY + engine.scroll }

    private func visible(_ level: Int) -> Bool {
        let y = screenY(SlimeStackEngine.y(level: level))
        return y > -SlimeStackEngine.blockHeight - 4 && y < Board.height + 40
    }

    private func drawBlock(_ ctx: GraphicsContext, _ block: SlimeStackEngine.Block, space: BoardSpace) {
        let y = screenY(SlimeStackEngine.y(level: block.level))
        let h = SlimeStackEngine.blockHeight
        if block.level == 0 {
            // The base: a grassy plinth.
            ctx.block(space.rect(block.left, y, block.width, h), Color(hex: 0x8A5A3C), edge: Color(hex: 0x6B4429), edgeHeight: 0.3)
            ctx.block(space.rect(block.left, y, block.width, 2.5), Color(hex: 0x6FBF5A), edge: Color(hex: 0x3F8F3A), edgeHeight: 0.4)
            return
        }
        let colors = block.gold ? Self.gold : Self.jelly[block.color % Self.jelly.count]
        let rect = space.rect(block.left, y, block.width, h)
        ctx.block(rect, Color(hex: colors.fill), edge: Color(hex: colors.edge), edgeHeight: 0.25)
        // A light stripe on top and a pixel of shine make it read as jelly.
        ctx.fill(Path(space.rect(block.left + 1, y + 1, max(0, block.width - 2), 1.2)), with: .color(.white.opacity(0.45)))
        if block.width > 6 {
            ctx.fill(Path(space.rect(block.left + 2, y + 3, 1.4, 1.4)), with: .color(.white.opacity(0.7)))
        }
        if block.gold {
            let a = 0.5 + 0.5 * sin(engine.time * 6 + Double(block.id))
            ctx.fill(Path(space.rect(block.x - 0.7, y + 2.5, 1.4, 4)), with: .color(.white.opacity(a)))
            ctx.fill(Path(space.rect(block.x - 2, y + 3.8, 4, 1.4)), with: .color(.white.opacity(a)))
        }
    }

    private func drawPiece(_ ctx: GraphicsContext, _ piece: SlimeStackEngine.Piece, space: BoardSpace) {
        let colors = piece.gold ? Self.gold : Self.jelly[piece.color % Self.jelly.count]
        let h = SlimeStackEngine.blockHeight
        var c = ctx
        c.translateBy(x: space.point(piece.x, 0).x, y: space.point(0, screenY(piece.y) + h / 2).y)
        c.rotate(by: .radians(piece.angle))
        let width: CGFloat = CGFloat(piece.width) * space.scale
        let height: CGFloat = CGFloat(h) * space.scale
        let rect = CGRect(x: -width / 2, y: -height / 2, width: width, height: height)
        c.block(rect, Color(hex: colors.fill), edge: Color(hex: colors.edge), edgeHeight: 0.25)
    }

    private func drawGround(_ ctx: GraphicsContext, size: CGSize, space: BoardSpace) {
        let top = space.point(0, screenY(SlimeStackEngine.ground)).y
        guard top < size.height else { return }
        ctx.block(CGRect(x: 0, y: top, width: size.width, height: size.height - top), Color(hex: 0x8A5A3C))
        ctx.block(CGRect(x: 0, y: top, width: size.width, height: space.scale * 3), Color(hex: 0x6FBF5A),
                  edge: Color(hex: 0x3F8F3A))
    }

    /// Height marks every ten blocks along the left edge.
    private func drawMarks(_ ctx: GraphicsContext, space: BoardSpace) {
        var level = 10
        while level <= engine.height + 12 {
            let y = screenY(SlimeStackEngine.y(level: level - 1))
            if y > -4, y < Board.height {
                ctx.fill(Path(space.rect(0, y, 5, 0.8)), with: .color(.white.opacity(0.6)))
                ctx.draw(Text("\(level)").font(Brand.pixel(11)).fontDesign(nil).foregroundStyle(Color.white.opacity(0.75)),
                         at: space.point(4, y - 3.5), anchor: .leading)
            }
            level += 10
        }
    }

    private func drawStars(_ ctx: GraphicsContext, space: BoardSpace, alpha: Double) {
        guard alpha > 0 else { return }
        for (i, p) in StackStars.points.enumerated() {
            // Stars drift down slowly with the tower so the climb is felt.
            let y = (p.1 * 180 + engine.scroll * 0.2).truncatingRemainder(dividingBy: 180) - 10
            let twinkle = 0.6 + 0.4 * sin(engine.time * 2 + Double(i))
            ctx.fill(Path(space.rect(p.0 * 100, y, 1.2, 1.2)), with: .color(.white.opacity(alpha * twinkle)))
        }
    }

    // MARK: Slime and HUD

    private func rider(space: BoardSpace) -> some View {
        let block = engine.isOver ? engine.top : engine.current
        let y = screenY(SlimeStackEngine.y(level: block.level))
        let anim: SpriteAnim = engine.time < mood.until ? mood.anim : .idle
        let (sx, sy) = juice.squashScale
        return GameSlime(anim: anim, height: 12 * space.scale, mirrored: !engine.isOver && engine.direction < 0,
                         time: engine.time)
            .scaleEffect(x: sx, y: sy, anchor: .bottom)
            .position(space.point(block.x, y - 6))
    }

    private func hud(space: BoardSpace) -> some View {
        HStack {
            Spacer()
            if engine.combo >= 2 {
                Text("Perfect x\(engine.combo)")
                    .font(Brand.pixel(15)).fontDesign(nil)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Theme.love)
                    .transition(.scale.combined(with: .opacity))
            }
        }
        .padding(10)
        .animation(reduceMotion ? nil : .snappy(duration: 0.2), value: engine.combo >= 2)
        .allowsHitTesting(false)
    }
}

/// Fixed star field for the high sky.
private enum StackStars {
    static let points: [(Double, Double)] = {
        var rng = SplitMix(seed: 77)
        return (0..<26).map { _ in (rng.unit(), rng.unit()) }
    }()
}
