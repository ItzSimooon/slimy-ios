import SwiftUI

/// A small ring that fills with how happy one need is.
struct NeedRing: View {
    let need: Need
    let value: Int
    var size: CGFloat = 22

    var body: some View {
        ZStack {
            Circle().stroke(Brand.color(for: need).opacity(0.25), lineWidth: 2.5)
            Circle()
                .trim(from: 0, to: CGFloat(value) / 100)
                .stroke(Brand.color(for: need), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Image(systemName: need.symbol)
                .font(.system(size: size * 0.42, weight: .bold))
                .foregroundStyle(Brand.color(for: need))
        }
        .frame(width: size, height: size)
    }
}

struct SpeechBubble: View {
    let text: String
    var size: CGFloat = 13

    var body: some View {
        Text(text)
            .font(Brand.display(size, .semibold))
            .foregroundStyle(Brand.text)
            .lineLimit(2)
            .minimumScaleFactor(0.8)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// Sky behind the Lock Screen card, following the time of day.
struct SkyBackground: View {
    let date: Date

    var body: some View {
        let hour = Calendar.current.component(.hour, from: date)
        let colors: [Color] = switch hour {
        case 5..<8: [Color(hex: 0x2B1E4A), Color(hex: 0xC2668A)]
        case 8..<17: [Color(hex: 0x1B2450), Color(hex: 0x3F5FB0)]
        case 17..<20: [Color(hex: 0x2A1640), Color(hex: 0xD9774F)]
        default: [Color(hex: 0x07060F), Color(hex: 0x1A1533)]
        }
        ZStack(alignment: .bottom) {
            LinearGradient(colors: colors, startPoint: .top, endPoint: .bottom)
            if hour >= 20 || hour < 5 {
                Canvas { ctx, size in
                    var rng = SeededRandom(seed: 7)
                    for _ in 0..<26 {
                        let r = CGRect(x: rng.next() * size.width, y: rng.next() * size.height * 0.6,
                                       width: 1.5, height: 1.5)
                        ctx.fill(Path(ellipseIn: r), with: .color(.white.opacity(0.4 + rng.next() * 0.5)))
                    }
                }
            }
            Rectangle().fill(Color.black.opacity(0.35)).frame(height: 10)
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
