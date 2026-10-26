import SwiftUI

/// Names of the fonts built by tools/make_slime.py.
enum SlimeFont {
    /// How long one animation timer runs. Longer than any Live Activity lives.
    static let span: TimeInterval = 16 * 3600

    /// Glyphs are 24 x 20 pixels; the font is sized by height.
    static let aspect: CGFloat = 24.0 / 20.0

    static func name(stage: Stage, anim: SpriteAnim, layer: SpriteLayer) -> String {
        "Slime-\(stage.rawValue.capitalizedFirst)-\(anim.rawValue.capitalizedFirst)-\(layer.rawValue.capitalizedFirst)"
    }

    static func accessory(_ item: String, stage: Stage, anim: SpriteAnim) -> String {
        "Acc-\(item.replacingOccurrences(of: ".", with: ""))-\(stage.rawValue.capitalizedFirst)-\(anim.rawValue.capitalizedFirst)"
    }
}

extension String {
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}

/// A timer that iOS keeps ticking, drawn in an animation font and clipped
/// to its last glyph, so it shows a new frame every second.
struct TickingGlyph: View {
    let font: String
    let anchor: Date
    let height: CGFloat
    let width: CGFloat
    let color: Color

    var body: some View {
        Text(timerInterval: anchor...anchor.addingTimeInterval(SlimeFont.span), countsDown: false)
            .font(.custom(font, fixedSize: height))
            .foregroundStyle(color)
            .lineLimit(1)
            .multilineTextAlignment(.trailing)
            // Right-aligned in a box wide enough for "15:59:59" (tested on
            // device; .fixedSize() pushed the last glyph out of the clip),
            // then only the last glyph is shown.
            .frame(width: width * 10, height: height, alignment: .trailing)
            .frame(width: width, height: height, alignment: .trailing)
            .clipped()
    }
}

/// The slime, animated by iOS itself, for the island and Lock Screen.
struct AnimatedSlime: View {
    let stage: Stage
    let anim: SpriteAnim
    let anchor: Date
    let skin: Skin
    var accessories = Accessories()
    let height: CGFloat
    var mirrored = false
    /// What VoiceOver says for the slime.
    var label = "Slime"

    var body: some View {
        let width = height * SlimeFont.aspect
        ZStack {
            ForEach(SpriteLayer.allCases, id: \.self) { layer in
                TickingGlyph(font: SlimeFont.name(stage: stage, anim: anim, layer: layer), anchor: anchor,
                             height: height, width: width, color: skin.color(for: layer))
            }
            // Only accessories with sprite art have an island font; anything
            // else would show the timer's digits instead of a glyph.
            ForEach(islandAccessories, id: \.name) { item in
                TickingGlyph(font: SlimeFont.accessory(item.name, stage: stage, anim: anim), anchor: anchor,
                             height: height, width: width, color: Color(hex: item.sprite.island))
            }
        }
        .frame(width: width, height: height)
        .scaleEffect(x: mirrored ? -1 : 1, y: 1)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }

    private struct IslandAccessory {
        let name: String
        let sprite: AccessorySprite
    }

    /// Scarf, glasses and hat, in the order the generator layers them.
    private var islandAccessories: [IslandAccessory] {
        var out: [IslandAccessory] = []
        for item in [accessories.scarf, accessories.glasses, accessories.hat].compactMap({ $0 }) {
            guard let sprite = SlimeSprites.data.accessories[item] else { continue }
            out.append(IslandAccessory(name: item, sprite: sprite))
        }
        return out
    }
}

/// The slime as the island shows it: animated by timer fonts, or a single
/// fitting pose when animation is off.
struct IslandSlime: View {
    let stage: Stage
    let anim: SpriteAnim
    let anchor: Date
    let skin: Skin
    var accessories = Accessories()
    let height: CGFloat
    var animated = true
    var mirrored = false
    /// What VoiceOver says for the slime.
    var label = "Slime"

    /// With Reduce Motion on, the slime holds a pose instead of looping.
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if animated && !reduceMotion {
            AnimatedSlime(stage: stage, anim: anim, anchor: anchor, skin: skin, accessories: accessories,
                          height: height, mirrored: mirrored, label: label)
        } else {
            let frames = SlimeSprites.frames(stage, anim)
            let anchors = SlimeSprites.anchors(stage, anim)
            let i = max(0, min(anim.posterIndex(at: anchor), frames.count - 1))
            PixelSlime(frame: frames.isEmpty ? SlimeSprites.blank : frames[i], skin: skin,
                       accessories: accessories, stage: stage, anchor: i < anchors.count ? anchors[i] : nil)
                .frame(width: height * SlimeFont.aspect, height: height)
                .scaleEffect(x: mirrored ? -1 : 1, y: 1)
                // PixelSlime hides itself from VoiceOver; this element speaks for it.
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(label)
        }
    }
}
