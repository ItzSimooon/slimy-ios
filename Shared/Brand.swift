import SwiftUI

/// The slime.hyprnode look: a deep violet night, glassy panels, jelly colours.
enum Brand {
    static let void = Color(hex: 0x0C0B14)        // app background
    static let deep = Color(hex: 0x15131F)        // panels
    static let raised = Color(hex: 0x1F1C2E)      // buttons, chips
    static let line = Color(hex: 0x2E2A44)        // hairlines
    static let text = Color(hex: 0xF3F0FF)
    static let muted = Color(hex: 0x9C96B8)
    static let hypr = Color(hex: 0x9B7BFF)        // hyprnode violet
    static let hyprGlow = Color(hex: 0xC6B4FF)
    static let warm = Color(hex: 0xFF6E9A)        // hearts, blush, food
    static let cool = Color(hex: 0x7FE0FF)        // zzz, bubbles, sparks
    static let gold = Color(hex: 0xFFD166)        // coins
    static let danger = Color(hex: 0xFF5D6C)

    static let hyprGradient = LinearGradient(
        colors: [Color(hex: 0x9B7BFF), Color(hex: 0x6B8CFF)],
        startPoint: .topLeading, endPoint: .bottomTrailing)

    static let wordmark = "slime.hyprnode"

    static func display(_ size: CGFloat, _ weight: Font.Weight = .heavy) -> Font {
        .system(size: size, weight: weight, design: .rounded)
    }

    static func mono(_ size: CGFloat, _ weight: Font.Weight = .semibold) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }

    static func color(for need: Need) -> Color {
        switch need {
        case .hunger: Color(hex: 0xFFB45C)
        case .fun: Color(hex: 0x7FE0FF)
        case .energy: Color(hex: 0xFFE066)
        case .clean: Color(hex: 0x8CF0C8)
        case .love: Color(hex: 0xFF6E9A)
        }
    }
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: opacity)
    }
}
