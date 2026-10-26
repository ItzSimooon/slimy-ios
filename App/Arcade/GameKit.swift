import SwiftUI

/// One round of one game. The host owns it; the game view writes its score
/// and calls end(). Its id is the reward key, so a round pays out once.
@MainActor @Observable
final class GameSession {
    let game: GameID
    let id = UUID()
    let seed: UInt64
    /// The day's building-block game, for `.daily`.
    let spec: GameSpec?
    var score = 0
    /// Short text for the top bar, like the time left.
    var status = ""
    var paused = false
    private(set) var ended = false

    init(game: GameID, seed: UInt64? = nil, spec: GameSpec? = nil) {
        self.game = game
        self.seed = seed ?? UInt64.random(in: 1...UInt64.max)
        self.spec = spec
    }

    var running: Bool { !paused && !ended }

    func end() {
        guard !ended else { return }
        ended = true
    }
}

extension ArcadeEvent {
    var sound: Sound {
        switch self {
        case .good: .pop
        case .great: .perfect
        case .bad: .nope
        case .coin: .coin
        case .miss: .miss
        case .jump: .hop
        case .land: .land
        case .tone(let i): .tone(i)
        case .win: .success
        case .lose: .whoosh
        }
    }
}

extension Array where Element == ArcadeEvent {
    @MainActor func play() {
        for event in self { Feedback.shared.play(event.sound) }
    }
}

// MARK: - Loop

private struct GameLoop: ViewModifier {
    let running: Bool
    let step: (Double) -> Void

    func body(content: Content) -> some View {
        content.task(id: running) {
            guard running else { return }
            let clock = ContinuousClock()
            var last = clock.now
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(16))
                let now = clock.now
                // Long gaps (a hitch, the app coming back) count as one short step.
                step(min(0.05, (now - last) / .seconds(1)))
                last = now
            }
        }
    }
}

extension View {
    /// Calls `step` about 60 times a second while `running`.
    func gameLoop(running: Bool, _ step: @escaping (Double) -> Void) -> some View {
        modifier(GameLoop(running: running, step: step))
    }
}

// MARK: - Board geometry

/// Maps board units (100 × 160) into a view.
struct BoardSpace {
    let scale: CGFloat
    let origin: CGPoint

    init(size: CGSize, width: Double = Board.width, height: Double = Board.height) {
        scale = min(size.width / width, size.height / height)
        origin = CGPoint(x: (size.width - width * scale) / 2, y: (size.height - height * scale) / 2)
    }

    func point(_ x: Double, _ y: Double) -> CGPoint {
        CGPoint(x: origin.x + x * scale, y: origin.y + y * scale)
    }

    func rect(_ x: Double, _ y: Double, _ w: Double, _ h: Double) -> CGRect {
        CGRect(x: origin.x + x * scale, y: origin.y + y * scale, width: w * scale, height: h * scale)
    }

    /// A rect of the given size centred on a board point.
    func centered(_ x: Double, _ y: Double, _ w: Double, _ h: Double) -> CGRect {
        rect(x - w / 2, y - h / 2, w, h)
    }

    func boardX(_ viewX: CGFloat) -> Double { Double((viewX - origin.x) / scale) }
    func boardY(_ viewY: CGFloat) -> Double { Double((viewY - origin.y) / scale) }
}

// MARK: - Sprites in a Canvas

/// Item sprites turned into one path per colour, built once.
enum SpritePaths {
    struct Sprite {
        let columns: Int
        let rows: Int
        let layers: [(Color, Path)]
    }

    static let all: [String: Sprite] = {
        var out: [String: Sprite] = [:]
        for (name, sprite) in SlimeSprites.items {
            let cols = sprite.rows.first?.count ?? 1
            var layers: [(Color, Path)] = []
            for key in sprite.palette.keys.sorted() {
                guard let ch = key.first else { continue }
                var path = Path()
                for (y, row) in sprite.rows.enumerated() {
                    for (x, c) in row.enumerated() where c == ch {
                        path.addRect(CGRect(x: x, y: y, width: 1, height: 1).insetBy(dx: -0.02, dy: -0.02))
                    }
                }
                layers.append((Color(hex: sprite.palette[key] ?? 0), path))
            }
            out[name] = Sprite(columns: cols, rows: sprite.rows.count, layers: layers)
        }
        return out
    }()
}

extension GraphicsContext {
    /// Draws an item sprite fitted into `rect`, keeping its pixels square.
    func drawSprite(_ name: String, in rect: CGRect, opacity: Double = 1) {
        guard let sprite = SpritePaths.all[name] else { return }
        let px = min(rect.width / CGFloat(sprite.columns), rect.height / CGFloat(sprite.rows))
        let ox = rect.midX - px * CGFloat(sprite.columns) / 2
        let oy = rect.midY - px * CGFloat(sprite.rows) / 2
        let transform = CGAffineTransform(a: px, b: 0, c: 0, d: px, tx: ox, ty: oy)
        for (color, path) in sprite.layers {
            fill(path.applying(transform), with: .color(color.opacity(opacity)))
        }
    }

    /// A chunky pixel rectangle with a darker bottom edge.
    func block(_ rect: CGRect, _ color: Color, edge: Color? = nil, edgeHeight: CGFloat = 0.22) {
        fill(Path(rect), with: .color(color))
        if let edge {
            let h = rect.height * edgeHeight
            fill(Path(CGRect(x: rect.minX, y: rect.maxY - h, width: rect.width, height: h)), with: .color(edge))
        }
    }

    /// Draws a small pixel pattern (one character per pixel, "." is empty)
    /// fitted into `rect` with square pixels, bottom-aligned.
    func drawPixels(_ rows: [String], palette: [Character: Color], in rect: CGRect, opacity: Double = 1) {
        let columns = rows.map(\.count).max() ?? 0
        guard columns > 0, !rows.isEmpty else { return }
        let px = min(rect.width / CGFloat(columns), rect.height / CGFloat(rows.count))
        let ox = rect.midX - px * CGFloat(columns) / 2
        let oy = rect.maxY - px * CGFloat(rows.count)
        var paths: [Character: Path] = [:]
        for (y, row) in rows.enumerated() {
            for (x, ch) in row.enumerated() where ch != "." {
                paths[ch, default: Path()].addRect(CGRect(x: ox + CGFloat(x) * px, y: oy + CGFloat(y) * px,
                                                          width: px, height: px).insetBy(dx: -0.25, dy: -0.25))
            }
        }
        for ch in paths.keys.sorted() {
            guard let path = paths[ch], let color = palette[ch] else { continue }
            fill(path, with: .color(color.opacity(opacity)))
        }
    }
}

// MARK: - The player's slime inside a game

struct GameSlime: View {
    @EnvironmentObject private var store: GameStore
    var anim: SpriteAnim = .idle
    var height: CGFloat
    var mirrored = false
    /// Seconds since the round started; picks the frame.
    var time: Double

    var body: some View {
        let s = store.state.slime
        let frames = SlimeSprites.frames(s.stage, anim)
        let anchors = SlimeSprites.anchors(s.stage, anim)
        let i = Int(max(0, time) * 3) % max(1, frames.count)
        PixelSlime(frame: frames[i], skin: s.skin, accessories: s.equipped, stage: s.stage,
                   anchor: i < anchors.count ? anchors[i] : nil)
            .frame(width: height * SlimeFont.aspect, height: height)
            .scaleEffect(x: mirrored ? -1 : 1, y: 1)
            .accessibilityHidden(true)
    }
}

/// Banded sky behind the action games, eight flat stripes like an old
/// handheld instead of a smooth gradient.
struct GameBackdrop: View {
    var top: UInt32 = 0x9FD4E8
    var bottom: UInt32 = 0xD8F0E0

    var body: some View {
        Canvas { ctx, size in
            let bands = 8
            let h = size.height / CGFloat(bands)
            for i in 0..<bands {
                let t = Double(i) / Double(bands - 1)
                ctx.fill(Path(CGRect(x: 0, y: CGFloat(i) * h, width: size.width, height: h + 1)),
                         with: .color(Color(hex: Self.mix(top, bottom, t))))
            }
        }
        .accessibilityHidden(true)
    }

    static func mix(_ a: UInt32, _ b: UInt32, _ t: Double) -> UInt32 {
        func channel(_ shift: UInt32) -> UInt32 {
            let x = Double((a >> shift) & 0xFF), y = Double((b >> shift) & 0xFF)
            return UInt32((x + (y - x) * t).rounded()) << shift
        }
        return channel(16) | channel(8) | channel(0)
    }
}

// MARK: - Game feel

/// Particle bursts, floating score popups, screen shake and a hit flash.
/// Purely visual; games add to it from their events and draw it on top.
struct Juice {
    struct Particle {
        var x, y, vx, vy, life, maxLife, size: Double
        var color: Color
    }

    struct Popup {
        var text: String
        var x, y, age: Double
        var color: Color
        var big: Bool
    }

    private(set) var particles: [Particle] = []
    private(set) var popups: [Popup] = []
    private(set) var shake = 0.0
    private(set) var flash = 0.0
    private(set) var squash = 0.0
    var reduceMotion = false

    mutating func burst(x: Double, y: Double, color: Color, count: Int = 10, power: Double = 60) {
        guard !reduceMotion else { return }
        for i in 0..<count {
            let angle = Double(i) / Double(count) * .pi * 2 + Double.random(in: -0.3...0.3)
            let speed = power * Double.random(in: 0.5...1.1)
            let life = Double.random(in: 0.35...0.7)
            particles.append(Particle(x: x, y: y, vx: cos(angle) * speed, vy: sin(angle) * speed - 20,
                                      life: life, maxLife: life, size: Double.random(in: 1.4...2.6), color: color))
        }
        if particles.count > 220 { particles.removeFirst(particles.count - 220) }
    }

    mutating func popup(_ text: String, x: Double, y: Double, color: Color = .white, big: Bool = false) {
        popups.append(Popup(text: text, x: x, y: y, age: 0, color: color, big: big))
        if popups.count > 12 { popups.removeFirst(popups.count - 12) }
    }

    mutating func hit() {
        shake = reduceMotion ? 0 : 0.32
        flash = 0.25
    }

    mutating func bounce() { squash = 0.18 }

    mutating func step(_ dt: Double) {
        for i in particles.indices {
            particles[i].x += particles[i].vx * dt
            particles[i].y += particles[i].vy * dt
            particles[i].vy += 140 * dt
            particles[i].life -= dt
        }
        particles.removeAll { $0.life <= 0 }
        for i in popups.indices {
            popups[i].age += dt
            popups[i].y -= 22 * dt
        }
        popups.removeAll { $0.age > 0.9 }
        shake = max(0, shake - dt)
        flash = max(0, flash - dt)
        squash = max(0, squash - dt)
    }

    /// Screen offset while shaking.
    func shakeOffset(_ time: Double) -> CGSize {
        guard shake > 0 else { return .zero }
        let a = shake / 0.32 * 6
        return CGSize(width: sin(time * 90) * a, height: cos(time * 77) * a * 0.6)
    }

    /// Squash-and-stretch scale for the player, (x, y).
    var squashScale: (CGFloat, CGFloat) {
        let k = sin(squash / 0.18 * .pi) * 0.22
        return (1 + k, 1 - k)
    }

    func draw(in ctx: GraphicsContext, space: BoardSpace) {
        for p in particles {
            let alpha = max(0, p.life / p.maxLife)
            ctx.fill(Path(space.centered(p.x, p.y, p.size, p.size)), with: .color(p.color.opacity(alpha)))
        }
        for p in popups {
            let alpha = max(0, 1 - p.age / 0.9)
            let text = Text(p.text)
                .font(Brand.pixel(p.big ? 22 : 15))
                .foregroundStyle(p.color.opacity(alpha))
            ctx.draw(text, at: space.point(p.x, p.y))
        }
    }
}

extension SlimeSprites {
    /// The main colour of an item sprite, for particles.
    static func mainColor(_ name: String) -> Color {
        guard let sprite = items[name] else { return .white }
        let hex = sprite.palette["a"] ?? sprite.palette.values.sorted().last ?? 0xFFFFFF
        return Color(hex: hex)
    }
}

/// "0:32"
func clock(_ seconds: Double) -> String {
    let s = Int(seconds.rounded(.up))
    return String(format: "%d:%02d", s / 60, s % 60)
}
