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
        Canvas { ctx, size in
            let cols = CGFloat(SlimeSprites.width), rows = CGFloat(SlimeSprites.height)
            let px = min(size.width / cols, size.height / rows)
            let ox = (size.width - px * cols) / 2
            let oy = size.height - px * rows
            for layer in SpriteLayer.allCases {
                if !effects, layer == .warm || layer == .cool { continue }
                var path = Path()
                for (y, row) in frame.enumerated() {
                    for (x, ch) in row.enumerated() where ch == layer.code {
                        // A hair of overlap hides seams between neighbouring pixels.
                        path.addRect(CGRect(x: ox + CGFloat(x) * px, y: oy + CGFloat(y) * px,
                                            width: px + 0.4, height: px + 0.4))
                    }
                }
                ctx.fill(path, with: .color(skin.color(for: layer)))
            }
        }
        .aspectRatio(CGFloat(SlimeSprites.width) / CGFloat(SlimeSprites.height), contentMode: .fit)
        .accessibilityHidden(true)
    }
}
