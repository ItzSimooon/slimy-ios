import SwiftUI

/// The sprite sheets from tools/make_slime.py, by animation.
enum SpriteAnim: String, CaseIterable {
    case idle, sad, sleep, eat, play, love, bath, melt, zap, confused, wave

    var frames: [[String]] {
        switch self {
        case .idle: SlimeSprites.idle
        case .sad: SlimeSprites.sad
        case .sleep: SlimeSprites.sleep
        case .eat: SlimeSprites.eat
        case .play: SlimeSprites.play
        case .love: SlimeSprites.love
        case .bath: SlimeSprites.bath
        case .melt: SlimeSprites.melt
        case .zap: SlimeSprites.zap
        case .confused: SlimeSprites.confused
        case .wave: SlimeSprites.wave
        }
    }

    init(_ mood: Mood) {
        self = SpriteAnim(rawValue: mood.rawValue) ?? .idle
    }

    init?(_ reaction: Reaction) {
        guard reaction != .none else { return nil }
        self.init(rawValue: reaction.rawValue)
    }
}

/// Draws one sprite frame crisply at any size.
struct PixelSlime: View {
    let frame: [String]
    let skin: Skin
    var effects = true

    var body: some View {
        // Plain shapes rather than Canvas, so it renders everywhere,
        // including Live Activities.
        ZStack {
            ForEach(SpriteLayer.allCases, id: \.self) { layer in
                if effects || (layer != .warm && layer != .cool) {
                    SpriteLayerShape(frame: frame, code: layer.code)
                        .fill(skin.color(for: layer))
                }
            }
        }
        .aspectRatio(CGFloat(SlimeSprites.width) / CGFloat(SlimeSprites.height), contentMode: .fit)
        .accessibilityHidden(true)
    }
}

/// All pixels of one paint layer of a sprite frame.
struct SpriteLayerShape: Shape {
    let frame: [String]
    let code: Character

    func path(in rect: CGRect) -> Path {
        let cols = CGFloat(SlimeSprites.width), rows = CGFloat(SlimeSprites.height)
        let px = min(rect.width / cols, rect.height / rows)
        let ox = rect.minX + (rect.width - px * cols) / 2
        let oy = rect.maxY - px * rows
        var path = Path()
        for (y, row) in frame.enumerated() {
            var x = 0
            let chars = Array(row)
            while x < chars.count {
                guard chars[x] == code else { x += 1; continue }
                let start = x
                while x < chars.count, chars[x] == code { x += 1 }
                // A hair of overlap hides seams between neighbouring pixels.
                path.addRect(CGRect(x: ox + CGFloat(start) * px, y: oy + CGFloat(y) * px,
                                    width: CGFloat(x - start) * px + 0.4, height: px + 0.4))
            }
        }
        return path
    }
}
