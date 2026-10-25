import SwiftUI

/// Weather of the day, the same for everyone at the same hour.
enum Weather: String {
    case clear, cloudy, rain, snow

    static func at(_ date: Date, calendar: Calendar = .current) -> Weather {
        let block = calendar.component(.hour, from: date) / 3
        var rng = SplitMix(seed: SplitMix.seed(Challenges.dayKey(date, calendar: calendar) + "w\(block)"))
        let month = calendar.component(.month, from: date)
        let winter = month == 12 || month <= 2
        let roll = rng.unit()
        switch roll {
        case ..<0.6: return .clear
        case ..<0.8: return .cloudy
        default: return winter ? .snow : .rain
        }
    }
}

enum Season {
    case spring, summer, autumn, winter, halloween, christmas

    static func at(_ date: Date, calendar: Calendar = .current) -> Season {
        let month = calendar.component(.month, from: date)
        let day = calendar.component(.day, from: date)
        if month == 10, day >= 15 { return .halloween }
        if month == 12, day <= 26 { return .christmas }
        switch month {
        case 3...5: return .spring
        case 6...8: return .summer
        case 9...11: return .autumn
        default: return .winter
        }
    }
}

/// Light over the course of a day: 1 at noon, ~0.25 at night.
struct DayLight {
    let hour: Double

    init(_ date: Date, calendar: Calendar = .current) {
        let c = calendar.dateComponents([.hour, .minute], from: date)
        hour = Double(c.hour ?? 12) + Double(c.minute ?? 0) / 60
    }

    var isNight: Bool { hour < 6 || hour >= 20.5 }

    var level: Double {
        switch hour {
        case 7..<18: 1
        case 5..<7: 0.25 + (hour - 5) / 2 * 0.75
        case 18..<21: 1 - (hour - 18) / 3 * 0.75
        default: 0.25
        }
    }

    var sky: Color {
        switch hour {
        case 5..<7: Color(hex: 0xF2A98A)
        case 7..<17: Color(hex: 0x8FD3F2)
        case 17..<19: Color(hex: 0xF2B37A)
        case 19..<21: Color(hex: 0x7A5A9A)
        default: Color(hex: 0x1C2240)
        }
    }

    var skyLow: Color {
        switch hour {
        case 5..<7: Color(hex: 0xF7D2A6)
        case 7..<17: Color(hex: 0xC6EBF7)
        case 17..<19: Color(hex: 0xF7D7A0)
        case 19..<21: Color(hex: 0xC27A8A)
        default: Color(hex: 0x2B3260)
        }
    }
}

/// The slime's world, drawn as pixel art on a fixed 96-pixel-wide grid and
/// scaled by whole points so every pixel stays crisp.
struct HabitatScene: View {
    let habitat: Habitat
    let furniture: [FurnitureSlot: String]
    var date: Date
    var animate = true

    static let gridWidth = 96

    var body: some View {
        GeometryReader { geo in
            let s = max(2, floor(geo.size.width / CGFloat(Self.gridWidth)))
            let rows = Int(ceil(geo.size.height / s))
            let ox = (geo.size.width - s * CGFloat(Self.gridWidth)) / 2
            TimelineView(.animation(minimumInterval: 1.0 / 10, paused: !animate)) { context in
                let t = animate ? context.date.timeIntervalSinceReferenceDate : 0
                Canvas { ctx, size in
                    var painter = Painter(ctx: ctx, scale: s, ox: ox, width: Self.gridWidth, height: rows)
                    let world = WorldInfo(date: date, t: t, rows: rows)
                    switch habitat {
                    case .room: drawRoom(&painter, world)
                    case .garden: drawGarden(&painter, world)
                    case .beach: drawBeach(&painter, world)
                    case .space: drawSpace(&painter, world)
                    case .crystal: drawCrystal(&painter, world)
                    }
                    // Night falls over everything except things that glow.
                    let light = DayLight(date).level
                    if light < 1, habitat != .space {
                        painter.ctx.fill(Path(CGRect(origin: .zero, size: size)),
                                         with: .color(Color(hex: 0x14183A).opacity((1 - light) * 0.55)))
                    }
                    drawGlow(&painter, world)
                }
            }
        }
        .accessibilityHidden(true)
    }

    // MARK: World facts

    struct WorldInfo {
        let date: Date
        let t: Double
        let rows: Int
        var light: DayLight { DayLight(date) }
        var weather: Weather { Weather.at(date) }
        var season: Season { Season.at(date) }
        /// Row where the floor or ground begins.
        var floorY: Int { Int(Double(rows) * 0.66) }
    }

    // MARK: Room

    private func drawRoom(_ p: inout Painter, _ w: WorldInfo) {
        let floorY = w.floorY
        // Wall with a soft stripe
        p.rect(0, 0, 96, floorY, Color(hex: 0xF0D2A8))
        for x in stride(from: 3, to: 96, by: 8) { p.rect(x, 0, 3, floorY, Color(hex: 0xE8C595)) }
        // Window with today's sky
        let wx = 54, wy = max(8, floorY / 6), ww = 32, wh = 26
        drawSky(&p, w, x: wx + 2, y: wy + 2, width: ww - 4, height: wh - 4)
        p.frame(wx, wy, ww, wh, Color(hex: 0x8A5A36), thickness: 2)
        p.rect(wx + ww / 2 - 1, wy, 2, wh, Color(hex: 0x8A5A36))
        p.rect(wx, wy + wh / 2 - 1, ww, 2, Color(hex: 0x8A5A36))
        p.rect(wx - 2, wy + wh, ww + 4, 2, Color(hex: 0x6B4423))
        // Floor planks
        p.rect(0, floorY, 96, w.rows - floorY, Color(hex: 0xC48A58))
        var row = 0
        for y in stride(from: floorY + 3, to: w.rows, by: 5) {
            p.rect(0, y, 96, 1, Color(hex: 0xA8733F))
            let offset = row % 2 == 0 ? 0 : 12
            for x in stride(from: offset, to: 96, by: 24) { p.rect(x, y - 4, 1, 4, Color(hex: 0xA8733F)) }
            row += 1
        }
        p.rect(0, floorY - 2, 96, 3, Color(hex: 0x8A5A36))

        // Furniture
        if let id = furniture[.ceiling] { p.sprite(id, 0, 1); p.sprite(id, 32, 1); p.sprite(id, 64, 1) }
        if let id = furniture[.wallLeft] { p.sprite(id, 12, max(10, floorY / 3)) }
        if let id = furniture[.wallRight] { p.sprite(id, 60, wy + wh + 6) }
        if let id = furniture[.rug] { p.sprite(id, 36, floorY + 14) }
        if let id = furniture[.floorLeft] { p.spriteBottom(id, 3, floorY + 10) }
        if let id = furniture[.floorRight] { p.spriteBottom(id, 82, floorY + 10) }

        // Season
        switch w.season {
        case .halloween: p.spriteBottom("hat.pumpkin", furniture[.floorLeft] == nil ? 8 : 24, floorY + 12)
        case .christmas: drawTree(&p, x: 22, bottom: floorY + 10, t: w.t)
        case .spring: p.spriteBottom("flower", 26, floorY + 9)
        default: break
        }
    }

    private func drawTree(_ p: inout Painter, x: Int, bottom: Int, t: Double) {
        let green = Color(hex: 0x3C8F3A)
        for i in 0..<5 {
            let width = 3 + i * 3
            p.rect(x - width / 2, bottom - 18 + i * 3, width, 3, green)
        }
        p.rect(x - 1, bottom - 3, 3, 3, Color(hex: 0x6B4423))
        p.rect(x, bottom - 20, 1, 2, Color(hex: 0xFFD166))
        let lights: [(Int, Int)] = [(-2, -14), (2, -11), (-4, -8), (4, -6), (0, -9)]
        for (i, (dx, dy)) in lights.enumerated() {
            let on = Int(t * 2 + Double(i)) % 2 == 0
            p.rect(x + dx, bottom + dy, 1, 1, on ? Color(hex: 0xF07A9A) : Color(hex: 0xFFD166))
        }
    }

    // MARK: Garden

    private func drawGarden(_ p: inout Painter, _ w: WorldInfo) {
        let floorY = w.floorY
        drawSky(&p, w, x: 0, y: 0, width: 96, height: floorY)
        // Hills
        for x in 0..<96 {
            let h1 = Int(8 + 5 * sin(Double(x) / 11))
            p.rect(x, floorY - h1 - 6, 1, h1 + 6, Color(hex: 0x8ACB6A))
            let h2 = Int(5 + 3 * sin(Double(x) / 7 + 2))
            p.rect(x, floorY - h2, 1, h2, Color(hex: 0x6BB45A))
        }
        // Tree
        p.rect(10, floorY - 22, 4, 22, Color(hex: 0x7A4E2A))
        for (dx, dy, r) in [(-6, -30, 7), (2, -34, 8), (8, -28, 6)] {
            p.disc(12 + dx, floorY + dy, r, w.season == .autumn || w.season == .halloween ? Color(hex: 0xE0893A) : Color(hex: 0x4FA34A))
        }
        // Fence
        for x in stride(from: 30, to: 96, by: 7) { p.rect(x, floorY - 9, 2, 9, Color(hex: 0xE8D9C0)) }
        p.rect(30, floorY - 7, 66, 1, Color(hex: 0xD9C6A6))
        p.rect(30, floorY - 4, 66, 1, Color(hex: 0xD9C6A6))
        // Grass
        p.rect(0, floorY, 96, w.rows - floorY, Color(hex: 0x79C05A))
        var rng = SplitMix(seed: 42)
        for _ in 0..<90 {
            let x = rng.int(0...95), y = rng.int(floorY...max(floorY, w.rows - 1))
            p.rect(x, y, 1, 1, Color(hex: 0x95D370))
        }
        for i in 0..<9 {
            let x = rng.int(2...93), y = rng.int((floorY + 4)...max(floorY + 4, w.rows - 4))
            let c = [Color(hex: 0xF07A9A), Color(hex: 0xFFD166), Color(hex: 0xFFFFFF)][i % 3]
            p.rect(x, y, 1, 1, c)
            p.rect(x - 1, y + 1, 3, 1, c)
            p.rect(x, y + 2, 1, 1, Color(hex: 0x4FA34A))
        }
        if w.season == .halloween { p.spriteBottom("hat.pumpkin", 70, floorY + 8) }
        // A butterfly by day, fireflies at night
        if w.light.isNight {
            for i in 0..<5 {
                let x = 20 + Int(36 * (1 + sin(w.t * 0.3 + Double(i) * 1.7)))
                let y = floorY - 6 - Int(10 * (1 + sin(w.t * 0.5 + Double(i))))
                if Int(w.t * 3 + Double(i)) % 3 != 0 { p.glow(x, y, Color(hex: 0xEAF77A)) }
            }
        } else if w.weather == .clear {
            let x = Int(48 + 40 * sin(w.t * 0.13)), y = Int(Double(floorY) - 18 + 6 * sin(w.t * 0.7))
            let flap = Int(w.t * 6) % 2 == 0
            p.rect(x, y, 1, 2, Color(hex: 0x3A2B2E))
            p.rect(x - 2, y - (flap ? 1 : 0), 2, 2, Color(hex: 0xF07A9A))
            p.rect(x + 1, y - (flap ? 1 : 0), 2, 2, Color(hex: 0xF07A9A))
        }
        drawWeather(&p, w, x: 0, y: 0, width: 96, height: w.rows)
    }

    // MARK: Beach

    private func drawBeach(_ p: inout Painter, _ w: WorldInfo) {
        let floorY = w.floorY
        drawSky(&p, w, x: 0, y: 0, width: 96, height: floorY - 10)
        // Sea with moving waves
        p.rect(0, floorY - 12, 96, 12, Color(hex: 0x3F8FCB))
        for x in 0..<96 {
            let wave = Int(1.5 * sin(Double(x) / 5 + w.t * 1.4))
            p.rect(x, floorY - 12 + wave, 1, 1, Color(hex: 0x7FC6EE))
            if (x + Int(w.t * 4)) % 13 == 0 { p.rect(x, floorY - 2 + wave, 2, 1, .white) }
        }
        // Sand
        p.rect(0, floorY, 96, w.rows - floorY, Color(hex: 0xF2D49B))
        var rng = SplitMix(seed: 7)
        for _ in 0..<60 { p.rect(rng.int(0...95), rng.int(floorY...max(floorY, w.rows - 1)), 1, 1, Color(hex: 0xE0BD7E)) }
        // Palm
        for i in 0..<20 { p.rect(84 - i / 4, floorY - i, 3, 1, Color(hex: 0x9A6A3A)) }
        for (dx, dy, len) in [(-10, -1, 10), (0, -2, 10), (-6, 2, 8), (2, 2, 7)] {
            for i in 0..<len { p.rect(79 + dx + i, floorY - 21 + dy + abs(i - len / 2) / 2, 1, 2, Color(hex: 0x4FA34A)) }
        }
        p.sprite("bubbleItem", 14, floorY + 10)
        // A crab walks by now and then
        let cx = Int((w.t * 4).truncatingRemainder(dividingBy: 160)) - 20
        if cx > -4, cx < 100 {
            p.rect(cx, floorY + 18, 5, 2, Color(hex: 0xE8524A))
            p.rect(cx - 1, floorY + 17, 1, 1, Color(hex: 0xE8524A))
            p.rect(cx + 5, floorY + 17, 1, 1, Color(hex: 0xE8524A))
        }
        drawWeather(&p, w, x: 0, y: 0, width: 96, height: w.rows)
    }

    // MARK: Space

    private func drawSpace(_ p: inout Painter, _ w: WorldInfo) {
        let floorY = w.floorY
        p.rect(0, 0, 96, w.rows, Color(hex: 0x0B0E24))
        var rng = SplitMix(seed: 99)
        for i in 0..<70 {
            let x = rng.int(0...95), y = rng.int(0...max(0, floorY))
            let twinkle = Int(w.t * 2 + Double(i)) % 5 == 0
            p.rect(x, y, 1, 1, Color.white.opacity(twinkle ? 0.3 : 0.85))
        }
        // Ringed planet
        p.disc(72, 18, 9, Color(hex: 0xB59BFF))
        p.disc(70, 16, 3, Color(hex: 0xD4C6FF))
        p.rect(58, 18, 28, 2, Color(hex: 0xF2D49B))
        // Shooting star, every so often
        let cycle = w.t.truncatingRemainder(dividingBy: 12)
        if cycle < 1.2 {
            let k = Int(cycle * 40)
            for i in 0..<6 { p.rect(10 + k - i, 6 + k / 2 - i / 2, 1, 1, Color.white.opacity(1 - Double(i) / 6)) }
        }
        // Moon ground
        p.rect(0, floorY, 96, w.rows - floorY, Color(hex: 0x9AA0A8))
        for (x, y, r) in [(14, floorY + 8, 4), (52, floorY + 16, 3), (80, floorY + 6, 2), (30, floorY + 24, 3)] {
            p.disc(x, y, r, Color(hex: 0x7C828A))
        }
    }

    // MARK: Crystal cave

    private func drawCrystal(_ p: inout Painter, _ w: WorldInfo) {
        let floorY = w.floorY
        p.rect(0, 0, 96, w.rows, Color(hex: 0x2A1E3A))
        var rng = SplitMix(seed: 5)
        for _ in 0..<120 {
            p.rect(rng.int(0...95), rng.int(0...max(0, w.rows - 1)), 2, 1, Color(hex: 0x352848))
        }
        p.rect(0, floorY, 96, w.rows - floorY, Color(hex: 0x3A2E4A))
        let pulse = 0.6 + 0.4 * sin(w.t * 1.5)
        for (i, (x, h)) in [(8, 14), (20, 9), (70, 16), (86, 10), (44, 7)].enumerated() {
            let c = i % 2 == 0 ? Color(hex: 0xB59BFF) : Color(hex: 0x7FD6F0)
            for j in 0..<h {
                let width = max(1, 4 - j / 4)
                p.rect(x - width / 2, floorY - j, width, 1, c.opacity(0.7 + 0.3 * pulse))
            }
        }
        for x in [30, 60] {
            p.rect(x, floorY - 3, 1, 3, Color(hex: 0xE9F1E4))
            p.rect(x - 2, floorY - 5, 5, 2, Color(hex: 0x7FD6F0).opacity(pulse))
        }
    }

    // MARK: Sky, weather, glow

    private func drawSky(_ p: inout Painter, _ w: WorldInfo, x: Int, y: Int, width: Int, height: Int) {
        let light = w.light
        p.rect(x, y, width, height, light.sky)
        p.rect(x, y + height * 2 / 3, width, height - height * 2 / 3, light.skyLow)
        if light.isNight {
            var rng = SplitMix(seed: 3)
            for _ in 0..<max(4, width * height / 60) {
                p.rect(x + rng.int(0...max(0, width - 1)), y + rng.int(0...max(0, height * 2 / 3)), 1, 1, Color.white.opacity(0.8))
            }
            p.disc(x + width - 8, y + 6, 3, Color(hex: 0xF4EBD9))
            p.disc(x + width - 7, y + 5, 3, light.sky)
        } else if w.weather == .clear || w.weather == .cloudy {
            p.disc(x + 7, y + 7, 3, Color(hex: 0xFFE38A))
        }
        if w.weather != .clear {
            let drift = Int((w.t * 1.5).truncatingRemainder(dividingBy: Double(width + 30))) - 15
            for (cx, cy) in [(drift, 5), (drift + width / 2, 9)] {
                let gx = x + ((cx % (width + 20)) + width + 20) % (width + 20) - 10
                p.rect(gx, y + cy, 10, 3, Color.white.opacity(w.weather == .cloudy ? 0.9 : 0.6))
                p.rect(gx + 2, y + cy - 2, 6, 2, Color.white.opacity(w.weather == .cloudy ? 0.9 : 0.6))
            }
        }
        if habitat == .room { drawWeather(&p, w, x: x, y: y, width: width, height: height) }
    }

    private func drawWeather(_ p: inout Painter, _ w: WorldInfo, x: Int, y: Int, width: Int, height: Int) {
        switch w.weather {
        case .rain:
            for i in 0..<(width * height / 50) {
                let px = x + (i * 37) % max(1, width)
                let py = y + Int((Double(i * 53) + w.t * 60).truncatingRemainder(dividingBy: Double(max(1, height))))
                p.rect(px, py, 1, 2, Color(hex: 0x9FD4F2).opacity(0.8))
            }
        case .snow:
            for i in 0..<(width * height / 70) {
                let px = x + (i * 29 + Int(2 * sin(w.t + Double(i)))) % max(1, width)
                let py = y + Int((Double(i * 41) + w.t * 12).truncatingRemainder(dividingBy: Double(max(1, height))))
                p.rect(px, py, 1, 1, .white)
            }
        default:
            break
        }
    }

    /// Lamps and lights keep glowing at night.
    private func drawGlow(_ p: inout Painter, _ w: WorldInfo) {
        guard habitat == .room, w.light.isNight else { return }
        if furniture[.floorRight] == "furn.lamp" {
            p.glowCone(85, w.floorY - 12, Color(hex: 0xFFE7A0))
        }
        if furniture[.ceiling] == "furn.lights" {
            for x in stride(from: 2, to: 96, by: 4) where (x / 4 + Int(w.t)) % 2 == 0 {
                p.glow(x, 3, [Color(hex: 0xF07A9A), Color(hex: 0xFFD166), Color(hex: 0x7FD6F0)][x % 3])
            }
        }
    }
}

/// Draws on the pixel grid.
struct Painter {
    var ctx: GraphicsContext
    let scale: CGFloat
    let ox: CGFloat
    let width: Int
    let height: Int

    func rect(_ x: Int, _ y: Int, _ w: Int, _ h: Int, _ color: Color) {
        guard w > 0, h > 0 else { return }
        ctx.fill(Path(CGRect(x: ox + CGFloat(x) * scale, y: CGFloat(y) * scale,
                             width: CGFloat(w) * scale + 0.3, height: CGFloat(h) * scale + 0.3)), with: .color(color))
    }

    func frame(_ x: Int, _ y: Int, _ w: Int, _ h: Int, _ color: Color, thickness: Int = 1) {
        rect(x, y, w, thickness, color)
        rect(x, y + h - thickness, w, thickness, color)
        rect(x, y, thickness, h, color)
        rect(x + w - thickness, y, thickness, h, color)
    }

    func disc(_ cx: Int, _ cy: Int, _ r: Int, _ color: Color) {
        for dy in -r...r {
            let half = Int((Double(r * r - dy * dy)).squareRoot().rounded())
            rect(cx - half, cy + dy, half * 2 + 1, 1, color)
        }
    }

    func glow(_ x: Int, _ y: Int, _ color: Color) {
        rect(x - 1, y - 1, 3, 3, color.opacity(0.25))
        rect(x, y, 1, 1, color)
    }

    func glowCone(_ x: Int, _ y: Int, _ color: Color) {
        for i in 0..<14 {
            rect(x - i / 2 - 1, y + i, i + 2, 1, color.opacity(0.18 - Double(i) * 0.01))
        }
    }

    /// An item sprite with its top-left at (x, y).
    func sprite(_ name: String, _ x: Int, _ y: Int) {
        guard let s = SlimeSprites.items[name] else { return }
        for (dy, row) in s.rows.enumerated() {
            for (dx, ch) in row.enumerated() where ch != "." {
                if let c = s.color(ch) { rect(x + dx, y + dy, 1, 1, c) }
            }
        }
    }

    /// An item sprite standing on `bottom`.
    func spriteBottom(_ name: String, _ x: Int, _ bottom: Int) {
        guard let s = SlimeSprites.items[name] else { return }
        sprite(name, x, bottom - s.rows.count)
    }
}
