import SwiftUI

/// The slime, animated by iOS itself, for the island and Lock Screen.
///
/// Each colour layer is a ticking timer drawn in an animation font where every
/// digit is a whole frame and every glyph is one frame wide. Right-aligned and
/// clipped to one glyph, only the seconds digit shows, so the frame changes
/// every second without the app running.
struct AnimatedSlime: View {
    let anim: SpriteAnim
    let anchor: Date
    let skin: Skin
    let height: CGFloat
    var mirrored = false

    var body: some View {
        let width = height * SlimeFont.aspect
        ZStack {
            ForEach(SpriteLayer.allCases, id: \.self) { layer in
                Text(timerInterval: anchor...anchor.addingTimeInterval(SlimeFont.span), countsDown: false)
                    .font(.custom(SlimeFont.name(anim: anim, layer: layer), fixedSize: height))
                    .foregroundStyle(skin.color(for: layer))
                    .lineLimit(1)
                    .multilineTextAlignment(.trailing)
                    // Lay the timer out right-aligned in a box wide enough for
                    // "15:59:59" (tested on device: this keeps the last glyph at
                    // the right edge; .fixedSize() did not), then show only the
                    // last glyph.
                    .frame(width: width * 10, height: height, alignment: .trailing)
                    .frame(width: width, height: height, alignment: .trailing)
                    .clipped()
            }
        }
        .frame(width: width, height: height)
        .scaleEffect(x: mirrored ? -1 : 1, y: 1)
        .accessibilityElement()
        .accessibilityLabel("Slime")
    }
}

/// The slime as the island shows it. Drawn from its pixels by default, which
/// always renders; with the experimental setting on, animated by timer fonts.
struct IslandSlime: View {
    let anim: SpriteAnim
    let anchor: Date
    let skin: Skin
    let height: CGFloat
    var animated = false
    var mirrored = false

    var body: some View {
        if animated {
            AnimatedSlime(anim: anim, anchor: anchor, skin: skin, height: height, mirrored: mirrored)
        } else {
            PixelSlime(frame: anim.posterFrame(at: anchor), skin: skin)
                .frame(width: height * SlimeFont.aspect, height: height)
                .scaleEffect(x: mirrored ? -1 : 1, y: 1)
                .accessibilityLabel("Slime")
        }
    }
}

extension IslandSlime {
    init(_ s: SlimeActivityAttributes.ContentState, height: CGFloat) {
        self.init(anim: s.anim, anchor: s.anchor, skin: s.skin, height: height, animated: s.animated ?? true)
    }

    init(partner p: PartnerSnapshot, anchor: Date, height: CGFloat, animated: Bool?) {
        self.init(anim: p.inLove ? .love : SpriteAnim(p.mood), anchor: anchor,
                  skin: p.skin, height: height, animated: animated ?? true, mirrored: true)
    }
}

extension SpriteAnim {
    /// The single frame that best shows this animation when it can't move.
    /// Idle changes pose with every update so the island doesn't look frozen.
    func posterFrame(at date: Date) -> [String] {
        let frames = self.frames
        switch self {
        case .idle:
            let minute = Calendar.current.component(.minute, from: date)
            return frames[[0, 1, 3, 5][minute % 4]]
        case .play:
            return frames[1]
        default:
            return frames[0]
        }
    }
}
