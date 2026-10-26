import SwiftUI

/// Colours shared by app and widgets: the dark palette of the Dynamic Island
/// (which is always black), the slime's pixel-art colours, and the need tints
/// for the native chrome around the slime (rings, buttons) in widgets and on
/// the Lock Screen. No gradients, no glows.
enum Brand {
    static let bg = Color(hex: 0x101411)
    static let panel = Color(hex: 0x171D19)
    static let raised = Color(hex: 0x212A24)
    static let line = Color(hex: 0x2E3A32)
    static let text = Color(hex: 0xE9F1E4)
    static let muted = Color(hex: 0x8D9D91)
    static let slime = Color(hex: 0x7DE67A)
    static let hypr = Color(hex: 0x9B7BFF)       // hyprnode violet, wordmark only
    static let warm = Color(hex: 0xF07A9A)       // hearts, blush
    static let cool = Color(hex: 0x7FD6F0)       // zzz, bubbles, sparks
    static let gold = Color(hex: 0xFFD166)       // coins
    static let danger = Color(hex: 0xE85D5D)

    /// Pixel font (Pixelify Sans, OFL). Bundled in app and widget.
    static func pixel(_ size: CGFloat, bold: Bool = true) -> Font {
        .custom(bold ? "PixelifySans-Bold" : "PixelifySans-Regular", size: size)
    }

    /// Fixed-size pixel font for the island, where Dynamic Type must not grow it.
    static func pixelFixed(_ size: CGFloat, bold: Bool = true) -> Font {
        .custom(bold ? "PixelifySans-Bold" : "PixelifySans-Regular", fixedSize: size)
    }

    /// The slime speaking: Pixelify Sans that scales with Dynamic Type, like
    /// the app's `Theme.voice`.
    static func voice(_ size: CGFloat, relativeTo style: Font.TextStyle) -> Font {
        .custom("PixelifySans-Bold", size: size, relativeTo: style)
    }

    static func body(_ size: CGFloat = 15, _ weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .rounded)
    }

    /// Pixel-art colour of a need (emotes in the island).
    static func color(for need: Need) -> Color {
        switch need {
        case .hunger: Color(hex: 0xF2A65A)
        case .fun: Color(hex: 0x6CC4E8)
        case .energy: Color(hex: 0xE8D55A)
        case .clean: Color(hex: 0x7FD9B0)
        case .love: Color(hex: 0xF07A9A)
        }
    }

    /// Colour of a need in native chrome (rings, bars, button symbols): the
    /// same system colours as the app's need rings, adapting to light and dark.
    static func tint(for need: Need) -> Color {
        switch need {
        case .hunger: .orange
        case .fun: .blue
        case .energy: .indigo
        case .clean: .teal
        case .love: .pink
        }
    }

    /// Ring or bar colour for a need at `value` (0...100): red when it runs low.
    static func tint(for need: Need, value: Double) -> Color {
        value < 25 ? .red : tint(for: need)
    }

    /// The pixel icon of a need, the same one the app's need rings show.
    static func icon(for need: Need) -> String {
        switch need {
        case .hunger: "apple"
        case .fun: "ball"
        case .energy: "starJelly"
        case .clean: "bubbleItem"
        case .love: "heart"
        }
    }

    /// Accent for a slime: its own jelly colour (the Void skin uses violet).
    static func accent(_ skin: Skin) -> Color {
        skin == .void ? hypr : skin.body
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
