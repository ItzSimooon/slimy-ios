import SwiftUI

/// Weather of the day, the same for everyone at the same hour.
enum Weather: String {
    case clear, cloudy, rain, snow

    static func at(_ date: Date, calendar: Calendar = .current) -> Weather {
        let g = Calendar.gregorian(like: calendar)
        let block = g.component(.hour, from: date) / 3
        var rng = SplitMix(seed: SplitMix.seed(Challenges.dayKey(date, calendar: g) + "w\(block)"))
        let roll = rng.unit()
        switch g.component(.month, from: date) {
        case 12:
            // December snows more often.
            if roll < 0.4 { return .clear }
            return roll < 0.55 ? .cloudy : .snow
        case 1, 2:
            if roll < 0.6 { return .clear }
            return roll < 0.8 ? .cloudy : .snow
        default:
            if roll < 0.6 { return .clear }
            return roll < 0.8 ? .cloudy : .rain
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

/// Width of the pixel world, outside the view so drawing code that isn't on
/// the main actor can use it too.
private let habitatGridWidth = 96

/// The slime's world, drawn as pixel art on a 96-pixel-wide grid and scaled
/// by whole points so every pixel stays crisp. Screens that aren't a whole
/// multiple of 96 points wide see a little more of the world at the sides,
/// so the scene always reaches from edge to edge.
struct HabitatScene: View {
    let habitat: Habitat
    let furniture: [FurnitureSlot: String]
    var date: Date
    var animate = true
    /// The slime's birthday: bunting, a cake and confetti.
    var party = false

    static let gridWidth = habitatGridWidth

    /// Points per pixel for a scene this wide.
    nonisolated static func scale(forWidth width: CGFloat) -> CGFloat {
        max(2, floor(width / CGFloat(habitatGridWidth)))
    }

    /// The row where the floor or ground begins, in a scene this many rows tall.
    /// Nonisolated: `WorldInfo` asks for it while drawing.
    nonisolated static func floorRow(rows: Int) -> Int { Int(Double(rows) * 0.66) }

    var body: some View {
        // A scene of "now" follows the clock (daylight, the season); a scene
        // of another moment stays at that moment.
        let live = abs(date.timeIntervalSinceNow) < 60
        GeometryReader { geo in
            let s = Self.scale(forWidth: geo.size.width)
            let rows = max(1, Int(ceil(geo.size.height / s)))
            let ox = (geo.size.width - s * CGFloat(Self.gridWidth)) / 2
            let margin = max(0, Int(ceil(ox / s)))
            if animate {
                TimelineView(.animation(minimumInterval: 1.0 / 10)) { frame in
                    canvas(at: live ? frame.date : date, t: frame.date.timeIntervalSinceReferenceDate,
                           scale: s, ox: ox, rows: rows, margin: margin)
                }
            } else if live {
                // Still, but daylight moves on with the clock.
                TimelineView(.everyMinute) { minute in
                    canvas(at: minute.date, t: 0, scale: s, ox: ox, rows: rows, margin: margin)
                }
            } else {
                // One fixed moment (photos, previews): drawn once, with no
                // timeline, so ImageRenderer always gets the whole scene.
                canvas(at: date, t: 0, scale: s, ox: ox, rows: rows, margin: margin)
            }
        }
        .accessibilityHidden(true)
    }

    /// The scene at one moment. `t` drives the motion; 0 holds it still.
    private func canvas(at now: Date, t: Double, scale s: CGFloat, ox: CGFloat, rows: Int, margin: Int) -> some View {
        Canvas { ctx, size in
            var painter = Painter(ctx: ctx, scale: s, ox: ox, width: Self.gridWidth, height: rows)
            let world = WorldInfo(date: now, t: t, rows: rows, minX: -margin, maxX: Self.gridWidth + margin)
            draw(&painter, world, size: size)
        }
    }

    // MARK: World facts

    struct WorldInfo {
        let date: Date
        let t: Double
        let rows: Int
        /// First column on screen and one past the last; wider than the
        /// 96-pixel world on screens with room to spare.
        let minX: Int
        let maxX: Int
        let light: DayLight
        let weather: Weather
        let season: Season

        init(date: Date, t: Double, rows: Int, minX: Int, maxX: Int) {
            self.date = date
            self.t = t
            self.rows = rows
            self.minX = minX
            self.maxX = maxX
            light = DayLight(date)
            weather = Weather.at(date)
            season = Season.at(date)
        }

        var span: Int { maxX - minX }
        /// Row where the floor or ground begins.
        var floorY: Int { HabitatScene.floorRow(rows: rows) }
    }

    /// A rectangle on the pixel grid.
    struct Box {
        let x: Int
        let y: Int
        let w: Int
        let h: Int
    }

    private func draw(_ p: inout Painter, _ w: WorldInfo, size: CGSize) {
        switch habitat {
        case .room: drawRoom(&p, w)
        case .garden: drawGarden(&p, w)
        case .beach: drawBeach(&p, w)
        case .space: drawSpace(&p, w)
        case .crystal: drawCrystal(&p, w)
        }
        if w.season == .halloween { drawBats(p, w) }
        if party { drawBunting(p, w) }
        // Night falls over everything except things that glow.
        let light = w.light.level
        if light < 1, habitat != .space {
            p.ctx.fill(Path(CGRect(origin: .zero, size: size)),
                       with: .color(Color(hex: 0x14183A).opacity((1 - light) * 0.55)))
        }
        drawGlow(p, w)
        if party { drawConfetti(p, w) }
    }

    // MARK: Room

    private func roomWindow(_ w: WorldInfo) -> Box {
        Box(x: 54, y: max(8, w.floorY / 6), w: 32, h: 26)
    }

    /// Where a piece of furniture stands, and whether it stands on that row.
    private func spot(_ slot: FurnitureSlot, _ w: WorldInfo) -> (x: Int, y: Int, standing: Bool) {
        let window = roomWindow(w)
        switch slot {
        case .ceiling: return (0, 1, false)
        case .wallLeft: return (12, max(10, w.floorY / 3), false)
        case .wallRight: return (60, window.y + window.h + 6, false)
        case .rug: return (36, w.floorY + 14, false)
        case .floorLeft: return (3, w.floorY + 10, true)
        case .floorRight: return (82, w.floorY + 10, true)
        }
    }

    private func drawRoom(_ p: inout Painter, _ w: WorldInfo) {
        let floorY = w.floorY
        // Wall with a soft stripe
        p.rect(w.minX, 0, w.span, floorY, Color(hex: 0xF0D2A8))
        for x in stride(from: Painter.first(3, every: 8, from: w.minX), to: w.maxX, by: 8) {
            p.rect(x, 0, 3, floorY, Color(hex: 0xE8C595))
        }
        // Window with today's sky
        let win = roomWindow(w)
        drawSky(&p, w, Box(x: win.x + 2, y: win.y + 2, w: win.w - 4, h: win.h - 4))
        if w.season == .christmas {
            // Snow piled against the glass.
            for x in (win.x + 2)..<(win.x + win.w - 2) {
                let h = 1 + (x * 7 % 5 == 0 ? 1 : 0)
                p.rect(x, win.y + win.h - 2 - h, 1, h, Color(hex: 0xF4F8FF))
            }
        }
        p.frame(win.x, win.y, win.w, win.h, Color(hex: 0x8A5A36), thickness: 2)
        p.rect(win.x + win.w / 2 - 1, win.y, 2, win.h, Color(hex: 0x8A5A36))
        p.rect(win.x, win.y + win.h / 2 - 1, win.w, 2, Color(hex: 0x8A5A36))
        p.rect(win.x - 2, win.y + win.h, win.w + 4, 2, Color(hex: 0x6B4423))
        // Floor planks
        p.rect(w.minX, floorY, w.span, w.rows - floorY, Color(hex: 0xC48A58))
        var row = 0
        for y in stride(from: floorY + 3, to: w.rows, by: 5) {
            p.rect(w.minX, y, w.span, 1, Color(hex: 0xA8733F))
            let offset = row % 2 == 0 ? 0 : 12
            for x in stride(from: Painter.first(offset, every: 24, from: w.minX), to: w.maxX, by: 24) {
                p.rect(x, y - 4, 1, 4, Color(hex: 0xA8733F))
            }
            row += 1
        }
        p.rect(w.minX, floorY - 2, w.span, 3, Color(hex: 0x8A5A36))

        // Furniture
        for slot in [FurnitureSlot.ceiling, .wallLeft, .wallRight, .rug, .floorLeft, .floorRight] {
            guard let id = furniture[slot] else { continue }
            if slot == .ceiling {
                for x in stride(from: Painter.first(0, every: 32, from: w.minX), to: w.maxX, by: 32) {
                    p.sprite(id, x, 1)
                }
            } else {
                let at = spot(slot, w)
                if at.standing {
                    p.spriteBottom(id, at.x, at.y)
                } else {
                    p.sprite(id, at.x, at.y)
                }
            }
        }

        // Season, and the birthday cake, each in a spot of its own
        let leftFree = furniture[.floorLeft] == nil
        let rightFree = furniture[.floorRight] == nil
        let tree: Int? = w.season == .christmas ? treeX() : nil
        let cake: Int? = party && furniture[.floorRight] != "furn.cake" ? cakeX(tree: tree) : nil
        switch w.season {
        case .halloween:
            drawWeb(p, x: w.minX, y: 0)
            let left = leftFree && cake != 6 ? 7 : (cake == 22 ? 72 : 21)
            p.spriteBottom("hat.pumpkin", left, floorY + 11)
            if rightFree, cake != 84 { p.spriteBottom("hat.pumpkin", 84, floorY + 12) }
        case .christmas:
            drawTree(p, x: treeX(), bottom: floorY + 10)
        case .newYear:
            if rightFree, cake != 84 { p.spriteBottom("hat.party", 85, floorY + 11) }
        case .spring:
            if cake != 22 { p.spriteBottom("flower", 26, floorY + 9) }
        default:
            break
        }
        if let cake { p.spriteBottom("cake", cake, floorY + 12) }
    }

    /// Where the holiday tree stands: in a free corner if there is one.
    private func treeX() -> Int {
        if furniture[.floorLeft] == nil { return 10 }
        if furniture[.floorRight] == nil { return 88 }
        return 22
    }

    /// Where the birthday cake stands: a free corner the tree didn't take,
    /// otherwise beside the slime.
    private func cakeX(tree: Int?) -> Int {
        if furniture[.floorRight] == nil, tree != 88 { return 84 }
        if furniture[.floorLeft] == nil, tree != 10 { return 6 }
        return tree == 22 ? 72 : 22
    }

    private func drawTree(_ p: Painter, x: Int, bottom: Int) {
        let green = Color(hex: 0x3C8F3A)
        let dark = Color(hex: 0x2E7030)
        for i in 0..<5 {
            let width = 3 + i * 3
            p.rect(x - width / 2, bottom - 18 + i * 3, width, 3, green)
            p.rect(x - width / 2, bottom - 16 + i * 3, width, 1, dark)
        }
        p.rect(x - 1, bottom - 3, 3, 3, Color(hex: 0x6B4423))
        p.rect(x, bottom - 21, 1, 3, Color(hex: 0xFFD166))
        p.rect(x - 1, bottom - 20, 3, 1, Color(hex: 0xFFD166))
    }

    private func treeLights(_ p: Painter, x: Int, bottom: Int, t: Double, night: Bool) {
        let lights: [(Int, Int)] = [(-2, -14), (2, -11), (-4, -8), (4, -6), (0, -9), (-5, -5), (5, -12), (1, -4)]
        let colors = [Color(hex: 0xF07A9A), Color(hex: 0xFFD166), Color(hex: 0x7FD6F0)]
        for (i, (dx, dy)) in lights.enumerated() {
            let on = Int(t * 2 + Double(i)) % 2 == 0
            let color = colors[(i + (on ? 0 : 1)) % colors.count]
            if night { p.glow(x + dx, bottom + dy, color) } else { p.rect(x + dx, bottom + dy, 1, 1, color) }
        }
        if night { p.glow(x, bottom - 20, Color(hex: 0xFFD166)) }
    }

    /// A cobweb in the top corner, for October.
    private func drawWeb(_ p: Painter, x: Int, y: Int) {
        let silk = Color(hex: 0xF4F1EA).opacity(0.8)
        for dy in 0..<10 {
            for dx in 0..<10 {
                let d = (Double(dx * dx + dy * dy)).squareRoot()
                guard d < 9.5 else { continue }
                let angle = atan2(Double(dy), Double(dx))
                let spoke = [0.0, Double.pi / 6, Double.pi / 3, Double.pi / 2].contains { abs(angle - $0) * d < 0.6 }
                let ring = abs(d - 3.5) < 0.5 || abs(d - 6.8) < 0.5
                if spoke || ring { p.rect(x + dx, y + dy, 1, 1, silk) }
            }
        }
        // A small spider, hanging by a thread.
        p.rect(x + 7, y + 7, 1, 3, silk)
        p.rect(x + 6, y + 10, 3, 2, Color(hex: 0x2A1E3A))
    }

    // MARK: Garden

    private func drawGarden(_ p: inout Painter, _ w: WorldInfo) {
        let floorY = w.floorY
        let winter = w.season == .christmas
        drawSky(&p, w, Box(x: w.minX, y: 0, w: w.span, h: floorY))
        // Hills, snowed over in December
        let farHill = winter ? Color(hex: 0xDCE6F2) : Color(hex: 0x8ACB6A)
        let nearHill = winter ? Color(hex: 0xC9D7E8) : Color(hex: 0x6BB45A)
        for x in w.minX..<w.maxX {
            let h1 = Int(8 + 5 * sin(Double(x) / 11))
            p.rect(x, floorY - h1 - 6, 1, h1 + 6, farHill)
            let h2 = Int(5 + 3 * sin(Double(x) / 7 + 2))
            p.rect(x, floorY - h2, 1, h2, nearHill)
        }
        // Tree
        p.rect(10, floorY - 22, 4, 22, Color(hex: 0x7A4E2A))
        let leaves: Color = switch w.season {
        case .autumn, .halloween: Color(hex: 0xE0893A)
        case .christmas, .winter, .newYear: Color(hex: 0x3C7A44)
        default: Color(hex: 0x4FA34A)
        }
        for (dx, dy, r) in [(-6, -30, 7), (2, -34, 8), (8, -28, 6)] {
            p.disc(12 + dx, floorY + dy, r, leaves)
            if winter { p.rect(12 + dx - r / 2, floorY + dy - r, r, 1, Color(hex: 0xF4F8FF)) }
        }
        // Fence
        for x in stride(from: 30, to: w.maxX, by: 7) { p.rect(x, floorY - 9, 2, 9, Color(hex: 0xE8D9C0)) }
        p.rect(30, floorY - 7, w.maxX - 30, 1, Color(hex: 0xD9C6A6))
        p.rect(30, floorY - 4, w.maxX - 30, 1, Color(hex: 0xD9C6A6))
        // Grass, or snow in December
        p.rect(w.minX, floorY, w.span, w.rows - floorY, winter ? Color(hex: 0xEEF3FA) : Color(hex: 0x79C05A))
        var rng = SplitMix(seed: 42)
        for _ in 0..<(90 * w.span / 96) {
            let x = rng.int(w.minX...(w.maxX - 1)), y = rng.int(floorY...max(floorY, w.rows - 1))
            p.rect(x, y, winter ? 2 : 1, 1, winter ? Color(hex: 0xD6E1EE) : Color(hex: 0x95D370))
        }
        if winter {
            // A few tufts of grass poke through.
            for _ in 0..<(12 * w.span / 96) {
                let x = rng.int(w.minX...(w.maxX - 1)), y = rng.int((floorY + 3)...max(floorY + 3, w.rows - 2))
                p.rect(x, y, 1, 1, Color(hex: 0x6BB45A))
                p.rect(x + 1, y - 1, 1, 2, Color(hex: 0x6BB45A))
            }
        } else {
            for i in 0..<9 {
                let x = rng.int(2...93), y = rng.int((floorY + 4)...max(floorY + 4, w.rows - 4))
                let c = [Color(hex: 0xF07A9A), Color(hex: 0xFFD166), Color(hex: 0xFFFFFF)][i % 3]
                p.rect(x, y, 1, 1, c)
                p.rect(x - 1, y + 1, 3, 1, c)
                p.rect(x, y + 2, 1, 1, Color(hex: 0x4FA34A))
            }
        }
        switch w.season {
        case .halloween:
            p.spriteBottom("hat.pumpkin", 70, floorY + 8)
            p.spriteBottom("hat.pumpkin", 20, floorY + 12)
        case .christmas:
            drawSnowman(p, x: 86, bottom: floorY + 9)
        default:
            break
        }
        if party { p.spriteBottom("cake", 76, floorY + 12) }
        // A butterfly by day, fireflies at night
        if w.light.isNight {
            if !winter {
                for i in 0..<5 {
                    let x = 20 + Int(36 * (1 + sin(w.t * 0.3 + Double(i) * 1.7)))
                    let y = floorY - 6 - Int(10 * (1 + sin(w.t * 0.5 + Double(i))))
                    if Int(w.t * 3 + Double(i)) % 3 != 0 { p.glow(x, y, Color(hex: 0xEAF77A)) }
                }
            }
        } else if w.weather == .clear, !winter {
            let x = Int(48 + 40 * sin(w.t * 0.13)), y = Int(Double(floorY) - 18 + 6 * sin(w.t * 0.7))
            let flap = Int(w.t * 6) % 2 == 0
            p.rect(x, y, 1, 2, Color(hex: 0x3A2B2E))
            p.rect(x - 2, y - (flap ? 1 : 0), 2, 2, Color(hex: 0xF07A9A))
            p.rect(x + 1, y - (flap ? 1 : 0), 2, 2, Color(hex: 0xF07A9A))
        }
        drawWeather(p, w, Box(x: w.minX, y: 0, w: w.span, h: w.rows))
    }

    private func drawSnowman(_ p: Painter, x: Int, bottom: Int) {
        let snow = Color(hex: 0xFFFFFF)
        let shade = Color(hex: 0xA9BCD3)
        // Outlined, so it stands out against the snow.
        p.disc(x, bottom - 5, 5, shade)
        p.disc(x, bottom - 5, 4, snow)
        p.rect(x + 1, bottom - 3, 3, 1, Color(hex: 0xDCE6F2))
        p.disc(x, bottom - 11, 4, shade)
        p.disc(x, bottom - 11, 3, snow)
        p.rect(x - 1, bottom - 12, 1, 1, Color(hex: 0x2A1E3A))
        p.rect(x + 1, bottom - 12, 1, 1, Color(hex: 0x2A1E3A))
        p.rect(x, bottom - 11, 2, 1, Color(hex: 0xF08A3C))
        p.rect(x - 3, bottom - 8, 7, 1, Color(hex: 0xE85D5D))
        p.rect(x + 2, bottom - 7, 1, 2, Color(hex: 0xE85D5D))
    }

    // MARK: Beach

    private func drawBeach(_ p: inout Painter, _ w: WorldInfo) {
        let floorY = w.floorY
        drawSky(&p, w, Box(x: w.minX, y: 0, w: w.span, h: floorY - 10))
        // Sea with moving waves
        p.rect(w.minX, floorY - 12, w.span, 12, Color(hex: 0x3F8FCB))
        for x in w.minX..<w.maxX {
            let wave = Int(1.5 * sin(Double(x) / 5 + w.t * 1.4))
            p.rect(x, floorY - 12 + wave, 1, 1, Color(hex: 0x7FC6EE))
            if Painter.mod(x + Int(w.t * 4), 13) == 0 { p.rect(x, floorY - 2 + wave, 2, 1, .white) }
        }
        // Sand
        p.rect(w.minX, floorY, w.span, w.rows - floorY, Color(hex: 0xF2D49B))
        var rng = SplitMix(seed: 7)
        for _ in 0..<(60 * w.span / 96) {
            p.rect(rng.int(w.minX...(w.maxX - 1)), rng.int(floorY...max(floorY, w.rows - 1)), 1, 1, Color(hex: 0xE0BD7E))
        }
        // Palm
        for i in 0..<20 { p.rect(84 - i / 4, floorY - i, 3, 1, Color(hex: 0x9A6A3A)) }
        for (dx, dy, len) in [(-10, -1, 10), (0, -2, 10), (-6, 2, 8), (2, 2, 7)] {
            for i in 0..<len { p.rect(79 + dx + i, floorY - 21 + dy + abs(i - len / 2) / 2, 1, 2, Color(hex: 0x4FA34A)) }
        }
        p.sprite("bubbleItem", 14, floorY + 10)
        if w.season == .halloween { p.spriteBottom("hat.pumpkin", 26, floorY + 12) }
        if party { p.spriteBottom("cake", 72, floorY + 12) }
        // A crab walks by now and then
        let travel = Double(w.span + 60)
        let cx = w.minX - 20 + Int((w.t * 4).truncatingRemainder(dividingBy: travel))
        if cx > w.minX - 4, cx < w.maxX {
            p.rect(cx, floorY + 18, 5, 2, Color(hex: 0xE8524A))
            p.rect(cx - 1, floorY + 17, 1, 1, Color(hex: 0xE8524A))
            p.rect(cx + 5, floorY + 17, 1, 1, Color(hex: 0xE8524A))
        }
        drawWeather(p, w, Box(x: w.minX, y: 0, w: w.span, h: w.rows))
    }

    // MARK: Space

    private func drawSpace(_ p: inout Painter, _ w: WorldInfo) {
        let floorY = w.floorY
        p.rect(w.minX, 0, w.span, w.rows, Color(hex: 0x0B0E24))
        var rng = SplitMix(seed: 99)
        for i in 0..<(70 * w.span / 96) {
            let x = rng.int(w.minX...(w.maxX - 1)), y = rng.int(0...max(0, floorY))
            let twinkle = Int(w.t * 2 + Double(i)) % 5 == 0
            p.rect(x, y, 1, 1, Color.white.opacity(twinkle ? 0.3 : 0.85))
        }
        // Ringed planet
        p.disc(72, 18, 9, Color(hex: 0xB59BFF))
        p.disc(70, 16, 3, Color(hex: 0xD4C6FF))
        p.rect(58, 18, 28, 2, Color(hex: 0xF2D49B))
        // Shooting star, every so often
        let cycle = w.t.truncatingRemainder(dividingBy: 12)
        if cycle < 1.2, w.t > 0 {
            let k = Int(cycle * 40)
            for i in 0..<6 { p.rect(10 + k - i, 6 + k / 2 - i / 2, 1, 1, Color.white.opacity(1 - Double(i) / 6)) }
        }
        // Moon ground
        p.rect(w.minX, floorY, w.span, w.rows - floorY, Color(hex: 0x9AA0A8))
        for (x, y, r) in [(14, floorY + 8, 4), (52, floorY + 16, 3), (80, floorY + 6, 2), (30, floorY + 24, 3)] {
            p.disc(x, y, r, Color(hex: 0x7C828A))
        }
        if w.season == .halloween { p.spriteBottom("hat.pumpkin", 82, floorY + 12) }
        if party { p.spriteBottom("cake", 74, floorY + 12) }
    }

    // MARK: Crystal cave

    private func drawCrystal(_ p: inout Painter, _ w: WorldInfo) {
        let floorY = w.floorY
        p.rect(w.minX, 0, w.span, w.rows, Color(hex: 0x2A1E3A))
        var rng = SplitMix(seed: 5)
        for _ in 0..<(120 * w.span / 96) {
            p.rect(rng.int(w.minX...(w.maxX - 1)), rng.int(0...max(0, w.rows - 1)), 2, 1, Color(hex: 0x352848))
        }
        p.rect(w.minX, floorY, w.span, w.rows - floorY, Color(hex: 0x3A2E4A))
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
        if party { p.spriteBottom("cake", 76, floorY + 12) }
    }

    // MARK: Sky, weather, seasons

    /// The open sky of this habitat, for bats and fireworks.
    private func skyBox(_ w: WorldInfo) -> Box? {
        switch habitat {
        case .room:
            let win = roomWindow(w)
            return Box(x: win.x + 2, y: win.y + 2, w: win.w - 4, h: win.h - 4)
        case .garden: return Box(x: w.minX, y: 0, w: w.span, h: max(6, w.floorY - 16))
        case .beach: return Box(x: w.minX, y: 0, w: w.span, h: max(6, w.floorY - 13))
        case .space: return Box(x: w.minX, y: 0, w: w.span, h: max(6, w.floorY - 2))
        case .crystal: return Box(x: w.minX, y: 0, w: w.span, h: max(6, w.floorY / 2))
        }
    }

    private func drawSky(_ p: inout Painter, _ w: WorldInfo, _ box: Box) {
        let light = w.light
        let x = box.x, y = box.y, width = box.w, height = box.h
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
                let gx = x + Painter.mod(cx, width + 20) - 10
                p.rect(gx, y + cy, 10, 3, Color.white.opacity(w.weather == .cloudy ? 0.9 : 0.6))
                p.rect(gx + 2, y + cy - 2, 6, 2, Color.white.opacity(w.weather == .cloudy ? 0.9 : 0.6))
            }
        }
        if habitat == .room {
            var inside = p
            inside.clip = box
            drawWeather(inside, w, box)
        }
    }

    private func drawWeather(_ p: Painter, _ w: WorldInfo, _ box: Box) {
        let x = box.x, y = box.y, width = box.w, height = box.h
        switch w.weather {
        case .rain:
            for i in 0..<(width * height / 50) {
                let px = x + (i * 37) % max(1, width)
                let py = y + Int((Double(i * 53) + w.t * 60).truncatingRemainder(dividingBy: Double(max(1, height))))
                p.rect(px, py, 1, 2, Color(hex: 0x9FD4F2).opacity(0.8))
            }
        case .snow:
            for i in 0..<(width * height / 70) {
                let px = x + Painter.mod(i * 29 + Int(2 * sin(w.t + Double(i))), max(1, width))
                let py = y + Int((Double(i * 41) + w.t * 12).truncatingRemainder(dividingBy: Double(max(1, height))))
                p.rect(px, py, 1, 1, .white)
            }
        default:
            break
        }
    }

    /// Bats flitting across the sky (or the cave) in October.
    private func drawBats(_ p: Painter, _ w: WorldInfo) {
        guard let box = skyBox(w) else { return }
        var sky = p
        sky.clip = box
        let count = box.w < 40 ? 2 : 3
        for i in 0..<count {
            let speed = 3.0 + Double(i) * 1.3
            let lane = Double(box.w + 14)
            let offset = Double(i) * lane / Double(count)
            let x = box.x - 7 + Int((w.t * speed + offset).truncatingRemainder(dividingBy: lane))
            let y = box.y + Int(Double(box.h) * (0.15 + 0.22 * Double(i))) + Int(2 * sin(w.t * 2.2 + Double(i)))
            let up = Int(w.t * 6 + Double(i)) % 2 == 0
            sky.sprite(up ? "bat.up" : "bat.down", x, y)
        }
    }

    // MARK: Party

    /// Bunting across the top of the scene.
    private func drawBunting(_ p: Painter, _ w: WorldInfo) {
        let colors = [Color(hex: 0xF07A9A), Color(hex: 0xFFD166), Color(hex: 0x7FD6F0), Color(hex: 0x7DE67A)]
        func sag(_ x: Int) -> Int {
            1 + Int((3 * sin(Double.pi * Double(x - w.minX) / Double(max(1, w.span)))).rounded())
        }
        for x in w.minX..<w.maxX { p.rect(x, sag(x), 1, 1, Color(hex: 0x6B4423)) }
        for (i, x) in stride(from: Painter.first(1, every: 6, from: w.minX), to: w.maxX, by: 6).enumerated() {
            let top = sag(x) + 1
            let color = colors[i % colors.count]
            p.rect(x, top, 4, 1, color)
            p.rect(x, top + 1, 4, 1, color)
            p.rect(x + 1, top + 2, 2, 1, color)
        }
    }

    private func drawConfetti(_ p: Painter, _ w: WorldInfo) {
        let colors = [Color(hex: 0xF07A9A), Color(hex: 0xFFD166), Color(hex: 0x7FD6F0), Color(hex: 0xB59BFF)]
        var rng = SplitMix(seed: 77)
        for i in 0..<(28 * w.span / 96) {
            let x = rng.int(w.minX...(w.maxX - 1))
            let speed = 4 + rng.unit() * 6
            let start = rng.unit() * Double(w.rows)
            let y = Int((start + w.t * speed).truncatingRemainder(dividingBy: Double(max(1, w.rows))))
            let sway = Int(1.5 * sin(w.t * 2 + Double(i)))
            p.rect(x + sway, y, 1, 1, colors[i % colors.count])
        }
    }

    // MARK: Glow

    /// Things that keep shining at night, drawn over the darkness.
    private func drawGlow(_ p: Painter, _ w: WorldInfo) {
        let night = w.light.isNight
        if habitat == .room {
            if w.season == .christmas { treeLights(p, x: treeX(), bottom: w.floorY + 10, t: w.t, night: night) }
            if night {
                if furniture[.floorRight] == "furn.lamp" {
                    p.glowCone(85, w.floorY - 12, Color(hex: 0xFFE7A0))
                }
                if furniture[.ceiling] == "furn.lights" {
                    let colors = [Color(hex: 0xF07A9A), Color(hex: 0xFFD166), Color(hex: 0x7FD6F0)]
                    let bulbs = stride(from: Painter.first(2, every: 4, from: w.minX), to: w.maxX, by: 4)
                    for x in bulbs where Painter.mod(x / 4 + Int(w.t), 2) == 0 {
                        p.glow(x, 3, colors[Painter.mod(x, 3)])
                    }
                }
                if furniture[.ceiling] == "furn.stars" {
                    for tile in stride(from: Painter.first(0, every: 32, from: w.minX), to: w.maxX, by: 32) {
                        for (i, dx) in [4, 12, 20, 28].enumerated() where Painter.mod(i + Int(w.t), 3) != 0 {
                            p.glow(tile + dx, 6, i % 2 == 0 ? Color(hex: 0xFFD166) : Color(hex: 0xFFF5D6))
                        }
                    }
                }
                for slot in [FurnitureSlot.floorLeft, .floorRight] {
                    guard let id = furniture[slot] else { continue }
                    let at = spot(slot, w)
                    switch id {
                    case "furn.jackolantern":
                        let flicker = 0.85 + 0.15 * sin(w.t * 9)
                        p.layerBottom(id, at.x, at.y, code: "f", color: Color(hex: 0xFFD166).opacity(flicker))
                        p.rect(at.x - 1, at.y - 7, 13, 6, Color(hex: 0xFFB347).opacity(0.15))
                    case "furn.crystal":
                        p.layerBottom(id, at.x, at.y, code: "c", color: Color(hex: 0x7FD6F0))
                        p.layerBottom(id, at.x, at.y, code: "w", color: .white)
                        p.rect(at.x - 1, at.y - 9, 12, 9, Color(hex: 0x7FD6F0).opacity(0.14))
                    default:
                        break
                    }
                }
            }
        }
        if habitat == .garden, w.season == .christmas {
            // Lights along the fence.
            let colors = [Color(hex: 0xF07A9A), Color(hex: 0xFFD166), Color(hex: 0x7FD6F0), Color(hex: 0x7DE67A)]
            for (i, x) in stride(from: 31, to: w.maxX, by: 4).enumerated() {
                let on = Painter.mod(i + Int(w.t * 2), 3) != 0
                let color = colors[i % colors.count].opacity(on ? 1 : 0.45)
                if night { p.glow(x, w.floorY - 10, color) } else { p.rect(x, w.floorY - 10, 1, 1, color) }
            }
        }
        if w.season == .newYear, let box = skyBox(w), habitat != .crystal {
            drawFireworks(p, in: box, t: w.t)
        }
    }

    private func drawFireworks(_ p: Painter, in box: Box, t: Double) {
        var sky = p
        sky.clip = box
        let colors: [UInt32] = [0xFFD166, 0xF07A9A, 0x7FD6F0, 0xB59BFF, 0x7DE67A]
        let reach = max(3, min(10, min(box.w, box.h) / 3))
        let period = 2.6
        for i in 0..<3 {
            let shifted = t / period + Double(i) / 3
            // Without motion, show the bursts open.
            let phase = t == 0 ? 0.55 + Double(i) * 0.1 : shifted.truncatingRemainder(dividingBy: 1)
            let cycle = t == 0 ? i : Int(shifted)
            var rng = SplitMix(seed: SplitMix.seed("firework\(cycle)-\(i)"))
            let cx = box.x + 3 + rng.int(0...max(0, box.w - 7))
            let cy = box.y + 2 + rng.int(0...max(0, box.h / 2))
            let color = Color(hex: colors[rng.int(0...(colors.count - 1))])
            if phase < 0.2 {
                let k = phase / 0.2
                let bottom = box.y + box.h
                let y = bottom - Int(Double(bottom - cy) * k)
                sky.rect(cx, y, 1, 2, color.opacity(0.9))
            } else {
                let k = (phase - 0.2) / 0.8
                let r = 1 + Double(reach) * k
                let fall = Int(k * k * 3)
                for a in 0..<12 {
                    let angle = Double(a) * .pi / 6
                    sky.rect(cx + Int((cos(angle) * r).rounded()), cy + fall + Int((sin(angle) * r).rounded()), 1, 1,
                             color.opacity(1 - k))
                    if a % 2 == 0 {
                        let inner = r * 0.55
                        sky.rect(cx + Int((cos(angle) * inner).rounded()), cy + fall + Int((sin(angle) * inner).rounded()),
                                 1, 1, Color.white.opacity(0.8 * (1 - k)))
                    }
                }
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
    /// Only pixels inside this box are drawn, if set.
    var clip: HabitatScene.Box? = nil

    func rect(_ x: Int, _ y: Int, _ w: Int, _ h: Int, _ color: Color) {
        var x0 = x, y0 = y, x1 = x + w, y1 = y + h
        if let clip {
            x0 = max(x0, clip.x)
            y0 = max(y0, clip.y)
            x1 = min(x1, clip.x + clip.w)
            y1 = min(y1, clip.y + clip.h)
        }
        guard x1 > x0, y1 > y0 else { return }
        ctx.fill(Path(CGRect(x: ox + CGFloat(x0) * scale, y: CGFloat(y0) * scale,
                             width: CGFloat(x1 - x0) * scale + 0.3, height: CGFloat(y1 - y0) * scale + 0.3)),
                 with: .color(color))
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

    /// An item sprite with its top-left at (x, y). Accepts item names,
    /// furniture ids and WorldArt names.
    func sprite(_ name: String, _ x: Int, _ y: Int) {
        guard let s = WorldArt.sprite(name) else { return }
        for (dy, row) in s.rows.enumerated() {
            for (dx, ch) in row.enumerated() where ch != "." {
                if let c = s.color(ch) { rect(x + dx, y + dy, 1, 1, c) }
            }
        }
    }

    /// An item sprite standing on `bottom`: its lowest drawn row sits just
    /// above that row, however much empty space the sprite has below.
    func spriteBottom(_ name: String, _ x: Int, _ bottom: Int) {
        guard let s = WorldArt.sprite(name) else { return }
        sprite(name, x, bottom - Self.usedHeight(s))
    }

    /// One colour of a standing sprite, redrawn in another colour (a lit
    /// face, a glowing crystal).
    func layerBottom(_ name: String, _ x: Int, _ bottom: Int, code: Character, color: Color) {
        guard let s = WorldArt.sprite(name) else { return }
        let top = bottom - Self.usedHeight(s)
        for (dy, row) in s.rows.enumerated() {
            for (dx, ch) in row.enumerated() where ch == code {
                rect(x + dx, top + dy, 1, 1, color)
            }
        }
    }

    private static func usedHeight(_ s: ItemSprite) -> Int {
        let last = s.rows.lastIndex { row in row.contains { $0 != "." } } ?? (s.rows.count - 1)
        return last + 1
    }

    /// The first position of a repeating pattern (start, start ± step …) at
    /// or before `minX`, so the pattern covers a scene that starts left of 0.
    static func first(_ start: Int, every step: Int, from minX: Int) -> Int {
        guard step > 0 else { return start }
        var x = start
        while x > minX { x -= step }
        return x
    }

    /// Remainder that is never negative, for patterns left of 0.
    static func mod(_ a: Int, _ n: Int) -> Int {
        guard n > 0 else { return 0 }
        let r = a % n
        return r < 0 ? r + n : r
    }
}
