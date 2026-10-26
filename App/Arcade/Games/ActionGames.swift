import SwiftUI

// The real-time games. Each view owns its engine, steps it from the game
// loop while the round runs and draws it into a Canvas. VoiceOver users get
// adjustable actions for movement; the games still need quick reactions,
// which the arcade says in each card's hint.

private let grass = Color(hex: 0x6FBF5A)
private let grassEdge = Color(hex: 0x3F8F3A)
private let soil = Color(hex: 0x8A5A3C)

// MARK: - Snack Catcher

struct SnackCatcherView: View {
    let session: GameSession
    @State private var engine: SnackCatcherEngine
    @State private var biteAt = -10.0

    init(session: GameSession) {
        self.session = session
        _engine = State(initialValue: SnackCatcherEngine(seed: session.seed))
    }

    var body: some View {
        GeometryReader { geo in
            let space = BoardSpace(size: geo.size)
            ZStack(alignment: .topLeading) {
                GameBackdrop()
                Canvas { ctx, size in
                    let groundTop = space.point(0, 150).y
                    ctx.block(CGRect(x: 0, y: groundTop, width: size.width, height: size.height - groundTop), soil)
                    ctx.block(CGRect(x: 0, y: groundTop, width: size.width, height: space.scale * 3), grass, edge: grassEdge)
                    for drop in engine.drops {
                        ctx.drawSprite(drop.sprite, in: space.centered(drop.x, drop.y, 10, 10))
                    }
                }
                GameSlime(anim: engine.time - biteAt < 0.4 ? .eat : .idle,
                          height: 16 * space.scale, mirrored: engine.targetX < engine.playerX - 2, time: engine.time)
                    .position(space.point(engine.playerX, 142))
                if engine.combo >= 3 {
                    Text("\(engine.combo)x combo")
                        .font(Theme.label(14)).foregroundStyle(Theme.ink)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Theme.card.opacity(0.85))
                        .position(space.point(50, 12))
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { engine.targetX = space.boardX($0.location.x) })
        }
        .onAppear { session.status = clock(engine.timeLeft) }
        .gameLoop(running: session.running) { dt in
            engine.step(dt)
            let events = engine.drainEvents()
            if events.contains(.good) || events.contains(.great) { biteAt = engine.time }
            events.play()
            session.score = engine.score
            session.status = clock(engine.timeLeft)
            if engine.isOver { session.end() }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Playing field. The slime catches falling food.")
        .accessibilityValue("\(engine.score) points")
        .accessibilityAdjustableAction { direction in
            engine.targetX = max(8, min(92, engine.targetX + (direction == .increment ? 15 : -15)))
        }
    }
}

// MARK: - Slime Jump

struct SlimeJumpView: View {
    let session: GameSession
    @State private var engine: SlimeJumpEngine
    @State private var time = 0.0

    init(session: GameSession) {
        self.session = session
        _engine = State(initialValue: SlimeJumpEngine(seed: session.seed))
    }

    var body: some View {
        GeometryReader { geo in
            let space = BoardSpace(size: geo.size)
            let cam = engine.cameraY
            ZStack(alignment: .topLeading) {
                // The sky darkens as you climb.
                let height = min(1, Double(engine.score) / 80)
                GameBackdrop(top: GameBackdrop.mix(0x8CC8E8, 0x2E2A5A, height),
                             bottom: GameBackdrop.mix(0xDDF1F5, 0x6A5AA0, height))
                Canvas { ctx, _ in
                    for p in engine.platforms {
                        let y = p.y - cam
                        guard y > -10, y < Board.height + 10 else { continue }
                        let rect = space.rect(p.x - p.width / 2, y, p.width, 4)
                        ctx.block(rect, p.drift == 0 ? grass : Color(hex: 0xE8A92A),
                                  edge: p.drift == 0 ? grassEdge : Color(hex: 0xA86F1A), edgeHeight: 0.4)
                        if p.coin { ctx.drawSprite("coin", in: space.centered(p.x, y - 6, 7, 7)) }
                    }
                }
                GameSlime(anim: engine.vy < 0 ? .play : .idle, height: 12 * space.scale,
                          mirrored: engine.steer < 0, time: time)
                    .position(space.point(engine.x, engine.y - cam - 6))
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { engine.steer = $0.location.x < geo.size.width / 2 ? -1 : 1 }
                .onEnded { _ in engine.steer = 0 })
        }
        .onAppear { session.status = "" }
        .gameLoop(running: session.running) { dt in
            time += dt
            engine.step(dt)
            engine.drainEvents().play()
            session.score = engine.score
            if engine.isOver { session.end() }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Playing field. The slime bounces by itself, you steer.")
        .accessibilityValue("\(engine.score) points")
        .accessibilityAdjustableAction { direction in
            engine.steer = direction == .increment ? 1 : -1
            Task {
                try? await Task.sleep(for: .milliseconds(300))
                engine.steer = 0
            }
        }
    }
}

// MARK: - Slime Racing

struct SlimeRacingView: View {
    let session: GameSession
    @State private var engine: SlimeRacingEngine

    private let playerY = 128.0
    private let zoom = 0.9

    init(session: GameSession) {
        self.session = session
        _engine = State(initialValue: SlimeRacingEngine(seed: session.seed))
    }

    private func laneX(_ lane: Int) -> Double { 20 + Double(lane) * 30 }

    var body: some View {
        GeometryReader { geo in
            let space = BoardSpace(size: geo.size)
            ZStack(alignment: .topLeading) {
                Color(hex: 0x7CC46A)
                Canvas { ctx, size in
                    let road = space.rect(5, 0, 90, Board.height)
                    ctx.fill(Path(CGRect(x: road.minX, y: 0, width: road.width, height: size.height)), with: .color(Color(hex: 0xC9A36B)))
                    // Moving lane marks.
                    let shift = (engine.distance * zoom).truncatingRemainder(dividingBy: 16)
                    for lane in 1..<SlimeRacingEngine.lanes {
                        let x = 5 + Double(lane) * 30
                        var y = -16 + shift
                        while y < Board.height {
                            ctx.fill(Path(space.rect(x - 0.8, y, 1.6, 7)), with: .color(Color(hex: 0xEAD2A0)))
                            y += 16
                        }
                    }
                    // Finish line.
                    let finishY = playerY - (SlimeRacingEngine.length - engine.distance) * zoom
                    if finishY > -10 {
                        for i in 0..<18 {
                            for row in 0..<2 {
                                let dark = (i + row) % 2 == 0
                                ctx.fill(Path(space.rect(5 + Double(i) * 5, finishY + Double(row) * 3, 5, 3)),
                                         with: .color(dark ? Color(hex: 0x2B2336) : .white))
                            }
                        }
                    }
                    for o in engine.obstacles where !o.hit || o.kind == .rock {
                        let y = playerY - (o.distance - engine.distance) * zoom
                        guard y > -12, y < Board.height + 12 else { continue }
                        ctx.drawSprite(o.kind == .rock ? "rock" : "puddle",
                                       in: space.centered(laneX(o.lane), y, 14, 14), opacity: o.hit ? 0.5 : 1)
                    }
                    // Progress: you and the ghost.
                    let bar = space.rect(10, 4, 80, 3)
                    ctx.fill(Path(bar), with: .color(Color.black.opacity(0.25)))
                    let ghostX = bar.minX + bar.width * engine.ghostDistance / SlimeRacingEngine.length
                    let meX = bar.minX + bar.width * engine.distance / SlimeRacingEngine.length
                    ctx.fill(Path(CGRect(x: ghostX - 3, y: bar.minY - 3, width: 6, height: bar.height + 6)), with: .color(.white.opacity(0.8)))
                    ctx.fill(Path(CGRect(x: meX - 3, y: bar.minY - 3, width: 6, height: bar.height + 6)), with: .color(Theme.accent))
                }
                // The ghost, when it is on screen.
                let ghostY = playerY - (engine.ghostDistance - engine.distance) * zoom
                if ghostY > 0, ghostY < Board.height {
                    GameSlime(anim: .idle, height: 12 * space.scale, time: engine.time)
                        .opacity(0.35)
                        .position(space.point(laneX(1), ghostY - 6))
                }
                GameSlime(anim: engine.speed < 30 ? .confused : .play, height: 13 * space.scale, time: engine.time)
                    .position(space.point(laneX(engine.lane), playerY - 6))
                    .animation(.easeOut(duration: 0.1), value: engine.lane)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onEnded { value in
                if abs(value.translation.width) > 20 {
                    engine.move(value.translation.width > 0 ? 1 : -1)
                } else {
                    engine.move(value.location.x < geo.size.width / 2 ? -1 : 1)
                }
            })
        }
        .gameLoop(running: session.running) { dt in
            engine.step(dt)
            engine.drainEvents().play()
            session.score = engine.score
            let gap = (engine.distance - engine.ghostDistance) / SlimeRacingEngine.ghostSpeed
            session.status = gap >= 0 ? String(format: "+%.1f s", gap) : String(format: "%.1f s", gap)
            if engine.isOver { session.end() }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Race track with three lanes")
        .accessibilityValue("Lane \(engine.lane + 1) of 3")
        .accessibilityAdjustableAction { direction in engine.move(direction == .increment ? 1 : -1) }
    }
}

// MARK: - Rhythm Bounce

struct RhythmBounceView: View {
    let session: GameSession
    @State private var engine: RhythmBounceEngine
    @State private var touching = false
    @State private var lastLane = 1

    private let lineY = 132.0
    private let laneColors: [UInt32] = [0xE0577F, 0x4FA3D9, 0xE8A92A]

    init(session: GameSession) {
        self.session = session
        _engine = State(initialValue: RhythmBounceEngine(seed: session.seed))
    }

    private func laneX(_ lane: Int) -> Double { 20 + Double(lane) * 30 }

    var body: some View {
        GeometryReader { geo in
            let space = BoardSpace(size: geo.size)
            ZStack(alignment: .topLeading) {
                GameBackdrop(top: 0x2B2336, bottom: 0x4A3A6A)
                Canvas { ctx, _ in
                    for lane in 0..<RhythmBounceEngine.lanes {
                        let color = Color(hex: laneColors[lane])
                        ctx.fill(Path(space.rect(laneX(lane) - 12, 0, 24, Board.height)), with: .color(color.opacity(0.10)))
                        ctx.fill(Path(space.rect(laneX(lane) - 12, lineY - 1.5, 24, 3)), with: .color(color.opacity(0.9)))
                    }
                    for note in engine.notes where note.judged == nil {
                        let ahead = note.time - engine.time
                        guard ahead < RhythmBounceEngine.lead, ahead > -0.3 else { continue }
                        let y = lineY - ahead / RhythmBounceEngine.lead * (lineY - 4)
                        let rect = space.centered(laneX(note.lane), y, 14, 7)
                        ctx.fill(Path(rect), with: .color(Color(hex: laneColors[note.lane])))
                        ctx.fill(Path(rect.insetBy(dx: rect.width * 0.2, dy: rect.height * 0.3)), with: .color(.white.opacity(0.6)))
                    }
                }
                if let last = engine.lastJudgement, engine.time - last.1 < 0.5 {
                    let judgement = last.0
                    Text(judgement == .perfect ? "Perfect" : judgement == .good ? "Good" : "Miss")
                        .font(Theme.title(20))
                        .foregroundStyle(judgement == .miss ? Color.white.opacity(0.6) : Color.white)
                        .position(space.point(50, lineY - 30))
                }
                GameSlime(anim: .play, height: 14 * space.scale, time: engine.time * 2)
                    .position(space.point(laneX(lastLane), lineY + 14))
                    .animation(.easeOut(duration: 0.08), value: lastLane)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { value in
                    guard !touching else { return }
                    touching = true
                    let x = space.boardX(value.location.x)
                    let lane = max(0, min(RhythmBounceEngine.lanes - 1, Int((x - 5) / 30)))
                    lastLane = lane
                    engine.tap(lane: lane)
                    engine.drainEvents().play()
                    session.score = engine.score
                }
                .onEnded { _ in touching = false })
        }
        .gameLoop(running: session.running) { dt in
            engine.step(dt)
            engine.drainEvents().play()
            session.score = engine.score
            session.status = engine.combo >= 4 ? "x\(engine.combo)" : ""
            if engine.isOver { session.end() }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Three lanes. Tap a lane when a note hits the line.")
    }
}

// MARK: - Slime Basketball

struct BasketballView: View {
    let session: GameSession
    @State private var engine: BasketballEngine
    @State private var aim: CGSize?

    init(session: GameSession) {
        self.session = session
        _engine = State(initialValue: BasketballEngine(seed: session.seed))
    }

    var body: some View {
        GeometryReader { geo in
            let space = BoardSpace(size: geo.size)
            ZStack(alignment: .topLeading) {
                GameBackdrop(top: 0xF2C48D, bottom: 0xF7E3C4)
                Canvas { ctx, size in
                    let floorTop = space.point(0, 150).y
                    ctx.block(CGRect(x: 0, y: floorTop, width: size.width, height: size.height - floorTop),
                              Color(hex: 0xC98A4B), edge: Color(hex: 0x9A6533), edgeHeight: 0.15)
                    // Backboard, rim and net.
                    let hx = engine.hoopX
                    ctx.block(space.rect(hx - 12, BasketballEngine.hoopY - 20, 24, 16), .white, edge: Color(hex: 0xD0C8C0), edgeHeight: 0.12)
                    ctx.stroke(Path(space.rect(hx - 6, BasketballEngine.hoopY - 15, 12, 9)), with: .color(Theme.danger), lineWidth: space.scale)
                    ctx.drawSprite("hoop", in: space.centered(hx, BasketballEngine.hoopY + 3, 2 * BasketballEngine.rim + 2, 12))
                    // Where a throw would go while you aim.
                    if let aim, !engine.flying {
                        var x = BasketballEngine.start.x, y = BasketballEngine.start.y
                        var vx = Double(aim.width / space.scale) * 2.5, vy = Double(aim.height / space.scale) * 2.5
                        let length = (vx * vx + vy * vy).squareRoot()
                        if length > 330 { vx *= 330 / length; vy *= 330 / length }
                        for i in 0..<14 {
                            for _ in 0..<3 {
                                vy += BasketballEngine.gravity * 0.02
                                x += vx * 0.02
                                y += vy * 0.02
                            }
                            ctx.fill(Path(ellipseIn: space.centered(x, y, 1.6, 1.6)), with: .color(Color.black.opacity(0.35 - Double(i) * 0.02)))
                        }
                    }
                    ctx.drawSprite("ball", in: space.centered(engine.ball.x, engine.ball.y, 10, 10))
                }
                GameSlime(anim: engine.streak >= 2 ? .celebrate : .idle, height: 12 * space.scale, time: engine.time)
                    .position(space.point(50, 152))
                if engine.streak >= 2 {
                    // The court is always light, so the text stays dark in Dark Mode too.
                    Text("\(engine.streak) in a row")
                        .font(Theme.label(14)).foregroundStyle(Color(hex: 0x3A2B2E))
                        .position(space.point(50, 70))
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 4)
                .onChanged { aim = $0.translation }
                .onEnded { value in
                    aim = nil
                    engine.throwBall(dx: Double(value.translation.width / space.scale),
                                     dy: Double(value.translation.height / space.scale))
                    engine.drainEvents().play()
                })
        }
        .onAppear { session.status = clock(engine.timeLeft) }
        .gameLoop(running: session.running) { dt in
            engine.step(dt)
            engine.drainEvents().play()
            session.score = engine.score
            session.status = clock(engine.timeLeft)
            if engine.isOver { session.end() }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Basketball court. Swipe up to throw.")
        .accessibilityValue("\(engine.score) points, \(engine.hits) hits")
    }
}
