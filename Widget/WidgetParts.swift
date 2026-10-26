import SwiftUI
import WidgetKit

/// A need as a ring around its pixel icon: the widget twin of the app's
/// `NeedRing`. Red when the need runs low. In the tinted Home Screen and on
/// the Lock Screen it is drawn in one colour and the system tints it.
struct NeedDial: View {
    let need: Need
    let value: Double   // 0...100
    var size: CGFloat = 24
    var lineWidth: CGFloat = 3

    @Environment(\.widgetRenderingMode) private var mode

    var body: some View {
        let fraction = CGFloat(min(1, max(0, value / 100)))
        let fullColor = mode == .fullColor
        let color = fullColor ? Brand.tint(for: need, value: value) : Color.white
        ZStack {
            Circle()
                .inset(by: lineWidth / 2)
                .stroke(color.opacity(fullColor ? 0.22 : 0.3), lineWidth: lineWidth)
            Circle()
                .inset(by: lineWidth / 2)
                .trim(from: 0, to: fraction)
                .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .widgetAccentable()
            PixelItem(name: Brand.icon(for: need))
                .frame(width: size * 0.46, height: size * 0.46)
        }
        .frame(width: size, height: size)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(need.title)
        .accessibilityValue("\(Int(value.rounded())) percent")
    }
}

/// A need as a thin capsule meter, like the app's `Meter`.
struct NeedBar: View {
    let need: Need
    let value: Double   // 0...100
    var height: CGFloat = 5

    @Environment(\.widgetRenderingMode) private var mode

    var body: some View {
        let fraction = CGFloat(min(1, max(0, value / 100)))
        let fullColor = mode == .fullColor
        let color = fullColor ? Brand.tint(for: need, value: value) : Color.white
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(color.opacity(fullColor ? 0.2 : 0.3))
                Capsule()
                    .fill(color)
                    .frame(width: max(height, geo.size.width * fraction))
                    .opacity(fraction > 0 ? 1 : 0)
                    .widgetAccentable()
            }
        }
        .frame(height: height)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(need.title)
        .accessibilityValue("\(Int(value.rounded())) percent")
    }
}

/// The five needs as rings in a row.
struct NeedDials: View {
    let needs: Needs
    var size: CGFloat = 22

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Need.allCases) { need in
                NeedDial(need: need, value: needs[need], size: size, lineWidth: size >= 24 ? 3 : 2.5)
                    .frame(maxWidth: .infinity)
            }
        }
    }
}

/// The slime as a still picture: widgets can't run the island's timer
/// animation. Full colour normally; in the tinted Home Screen and on the
/// Lock Screen it is drawn in shades of one colour so its face stays readable.
struct StillSlime: View {
    let stage: Stage
    let anim: SpriteAnim
    let skin: Skin
    var accessories = Accessories()
    let date: Date

    @Environment(\.widgetRenderingMode) private var mode

    var body: some View {
        let frames = SlimeSprites.frames(stage, anim)
        let anchors = SlimeSprites.anchors(stage, anim)
        let index = max(0, min(anim.posterIndex(at: date), frames.count - 1))
        let frame = frames.isEmpty ? SlimeSprites.blank : frames[index]
        let anchor = index < anchors.count ? anchors[index] : nil
        Group {
            if mode == .fullColor {
                PixelSlime(frame: frame, skin: skin, accessories: accessories, stage: stage, anchor: anchor)
            } else {
                MonoSlime(frame: frame, accessories: accessories, anchor: anchor)
                    .widgetAccentable()
            }
        }
        .accessibilityHidden(true)
    }
}

/// One frame of the slime in shades of white, for the system to tint. The
/// outline and eyes stay faint, the shine stays bright, so it reads as a
/// slime and not as a flat blob.
private struct MonoSlime: View {
    let frame: [String]
    var accessories: Accessories
    var anchor: [Int]?

    private struct Layer: Identifiable {
        let id: String
        let rows: [String]
        let code: Character
        let opacity: Double
    }

    var body: some View {
        ZStack {
            ForEach(layers) { layer in
                PixelGridShape(rows: layer.rows, code: layer.code)
                    .fill(Color.white.opacity(layer.opacity))
            }
        }
        .aspectRatio(CGFloat(SlimeSprites.width) / CGFloat(SlimeSprites.height), contentMode: .fit)
    }

    private var layers: [Layer] {
        var out = SpriteLayer.allCases.map {
            Layer(id: $0.rawValue, rows: frame, code: $0.code, opacity: Self.opacity(for: $0))
        }
        guard let anchor, anchor.count >= 4 else { return out }
        for item in [accessories.scarf, accessories.glasses, accessories.hat].compactMap({ $0 }) {
            guard let sprite = SlimeSprites.data.accessories[item] else { continue }
            let grid = AccessoryPlacer.place(sprite, on: frame, anchor: anchor)
            for key in sprite.palette.keys.sorted() {
                guard let code = key.first else { continue }
                out.append(Layer(id: "\(item).\(key)", rows: grid, code: code, opacity: 0.95))
            }
        }
        return out
    }

    private static func opacity(for layer: SpriteLayer) -> Double {
        switch layer {
        case .body: 0.62
        case .shade: 0.42
        case .ink: 0.12
        case .shine: 1
        case .warm, .cool: 0.8
        }
    }
}

/// The slime standing on a soft shadow, as big as the space allows.
struct SlimeHero: View {
    let slime: StillSlime

    var body: some View {
        GeometryReader { geo in
            let height = max(1, min(geo.size.height, geo.size.width / SlimeFont.aspect))
            let width = height * SlimeFont.aspect
            let shadow = max(3, (height * 0.1).rounded())
            ZStack(alignment: .bottom) {
                Ellipse()
                    .fill(Color.primary.opacity(0.1))
                    .frame(width: width * 0.8, height: shadow)
                slime
                    .frame(width: width, height: max(1, height - shadow / 2))
                    .padding(.bottom, shadow / 2)
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .bottom)
        }
        .accessibilityHidden(true)
    }
}
