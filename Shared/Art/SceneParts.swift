import SwiftUI

/// A bar made of blocks, like an old handheld's battery meter.
struct PixelBar: View {
    let value: Int          // 0...100
    var segments = 10
    var color: Color
    var height: CGFloat = 8
    var gap: CGFloat = 2

    var body: some View {
        let filled = Int((Double(value) / 100 * Double(segments)).rounded(.up))
        HStack(spacing: gap) {
            ForEach(0..<segments, id: \.self) { i in
                Rectangle()
                    .fill(i < filled ? color : color.opacity(0.18))
            }
        }
        .frame(height: height)
        .accessibilityElement()
        .accessibilityValue("\(value) percent")
    }
}

/// What the slime says. Flat box with a hard border, no bubble tail.
struct SpeechBubble: View {
    let text: String
    var size: CGFloat = 14

    var body: some View {
        Text(text)
            .font(Brand.body(size, .medium))
            .foregroundStyle(Brand.text)
            .lineLimit(2)
            .minimumScaleFactor(0.85)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(Brand.raised)
            .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 2))
    }
}

/// The view through the slime's window: one flat colour per time of day,
/// pixel stars at night.
struct SkyBackground: View {
    let date: Date

    private static let stars: [(x: CGFloat, y: CGFloat)] = {
        var rng = SeededRandom(seed: 7)
        return (0..<22).map { _ in (rng.next(), rng.next() * 0.6) }
    }()

    var body: some View {
        let hour = Calendar.current.component(.hour, from: date)
        let color: Color = switch hour {
        case 5..<8: Color(hex: 0x3A2E3F)
        case 8..<17: Color(hex: 0x223A4A)
        case 17..<20: Color(hex: 0x4A3328)
        default: Color(hex: 0x0E1316)
        }
        ZStack(alignment: .topLeading) {
            color
            if hour >= 20 || hour < 5 {
                GeometryReader { geo in
                    ForEach(0..<Self.stars.count, id: \.self) { i in
                        Rectangle()
                            .fill(Color.white.opacity(0.55))
                            .frame(width: 2, height: 2)
                            .position(x: Self.stars[i].x * geo.size.width, y: Self.stars[i].y * geo.size.height)
                    }
                }
            }
        }
    }
}

struct SeededRandom {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> CGFloat {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return CGFloat(state >> 33) / CGFloat(UInt32.max >> 1)
    }
}
