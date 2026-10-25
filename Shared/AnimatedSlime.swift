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
                    .fixedSize()
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

extension AnimatedSlime {
    init(_ s: SlimeActivityAttributes.ContentState, height: CGFloat) {
        self.init(anim: s.anim, anchor: s.anchor, skin: s.skin, height: height)
    }

    init(partner p: PartnerSnapshot, anchor: Date, height: CGFloat) {
        self.init(anim: p.inLove ? .love : SpriteAnim(p.mood), anchor: anchor,
                  skin: p.skin, height: height, mirrored: true)
    }
}
