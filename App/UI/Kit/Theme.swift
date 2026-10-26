import CoreText
import SwiftUI
import UIKit

/// Slimy's look: a native iOS app around a pixel-art slime. Everything around
/// the slime uses system colours, system materials and SF Rounded with Dynamic
/// Type, so it follows Light/Dark Mode, Increase Contrast and text size.
/// Pixel art is kept for the slime's own world: the slime, its items, homes
/// and the games. Pixelify Sans is the slime's voice, nothing else.
enum Theme {
    // MARK: Surfaces

    /// Behind grouped content.
    static let backdrop = Color(uiColor: .systemGroupedBackground)
    /// Cards and rows on the backdrop.
    static let card = Color(uiColor: .secondarySystemGroupedBackground)
    /// Wells inside a card: inputs, quiet tiles, icon backgrounds.
    static let well = Color(uiColor: .tertiarySystemFill)
    static let separator = Color(uiColor: .separator)

    // MARK: Text

    static let ink = Color.primary
    static let inkSoft = Color.secondary

    // MARK: Colour

    /// Slime green: deep in light mode, bright in dark mode. Same values as
    /// the AccentColor asset, spelled out because `.tint(Color.accentColor)`
    /// at the root would refer to itself and fall back to system blue.
    static let accent = Color.dynamic(light: 0x1E7F37, dark: 0x4CC764)
    /// Text and symbols on an accent fill. White on the deep green, near-black
    /// on the bright green, so both pass 4.5:1.
    static let onAccent = Color.dynamic(light: 0xFFFFFF, dark: 0x0B2912)
    static let gold = Color.dynamic(light: 0xB7791F, dark: 0xFFD166)
    static let love = Color(uiColor: .systemPink)
    static let sky = Color(uiColor: .systemBlue)
    static let danger = Color(uiColor: .systemRed)

    // MARK: Shape

    static let radius: CGFloat = 16
    static let smallRadius: CGFloat = 12

    // MARK: Pixel art (games only)

    /// Size of one chrome pixel inside game boards.
    static let px: CGFloat = 3
    /// Outline colour for pixel art drawn inside games.
    static let border = Color.dynamic(light: 0x3A2B2E, dark: 0x0D0A12)

    // MARK: Type

    /// Bold SF Rounded at the text style closest to `size`, so it scales with
    /// Dynamic Type. Prefer the semantic styles (.headline …) in new code.
    static func title(_ size: CGFloat = 22) -> Font { .system(style(size), design: .rounded, weight: .bold) }
    static func label(_ size: CGFloat = 15) -> Font { .system(style(size), design: .rounded, weight: .semibold) }
    static func text(_ size: CGFloat = 15) -> Font { .system(style(size), design: .rounded) }

    /// The slime speaking: Pixelify Sans, scaled with Dynamic Type.
    static func voice(_ size: CGFloat = 16) -> Font {
        Brand.pixelFont(size, bold: true, scaledLike: .body)
    }

    private static func style(_ size: CGFloat) -> Font.TextStyle {
        switch size {
        case 32...: .largeTitle
        case 26..<32: .title
        case 21..<26: .title2
        case 19..<21: .title3
        case 17..<19: .body
        case 16..<17: .callout
        case 15..<16: .subheadline
        case 13..<15: .footnote
        case 12..<13: .caption
        default: .caption2
        }
    }

    // MARK: Needs

    static func color(for need: Need) -> Color {
        switch need {
        case .hunger: Color(uiColor: .systemOrange)
        case .fun: Color(uiColor: .systemBlue)
        case .energy: Color(uiColor: .systemIndigo)
        case .clean: Color(uiColor: .systemTeal)
        case .love: Color(uiColor: .systemPink)
        }
    }

    static func icon(for need: Need) -> String {
        switch need {
        case .hunger: "apple"
        case .fun: "ball"
        case .energy: "starJelly"
        case .clean: "bubbleItem"
        case .love: "heart"
        }
    }
}

/// The bundled Pixelify Sans files. Info.plist's UIAppFonts should register
/// them; this makes sure, because a missing font silently falls back to SF.
enum AppFonts {
    static let names = ["PixelifySans-Regular", "PixelifySans-Bold"]

    static func register() {
        for name in names where UIFont(name: name, size: 12) == nil {
            guard let url = Bundle.main.url(forResource: name, withExtension: "ttf") else { continue }
            _ = CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }
}

extension Color {
    static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { traits in
            UIColor(Color(hex: traits.userInterfaceStyle == .dark ? dark : light))
        })
    }
}

/// A rectangle with its corners cut by one pixel: the pixel-art "rounded"
/// corner. Only for tiles drawn inside games.
struct NotchedRect: InsettableShape {
    var notch: CGFloat = Theme.px
    var inset: CGFloat = 0

    func path(in rect: CGRect) -> Path {
        let r = rect.insetBy(dx: inset, dy: inset)
        let n = min(notch, r.width / 4, r.height / 4)
        var p = Path()
        p.move(to: CGPoint(x: r.minX + n, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX - n, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX - n, y: r.minY + n))
        p.addLine(to: CGPoint(x: r.maxX, y: r.minY + n))
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY - n))
        p.addLine(to: CGPoint(x: r.maxX - n, y: r.maxY - n))
        p.addLine(to: CGPoint(x: r.maxX - n, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX + n, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX + n, y: r.maxY - n))
        p.addLine(to: CGPoint(x: r.minX, y: r.maxY - n))
        p.addLine(to: CGPoint(x: r.minX, y: r.minY + n))
        p.addLine(to: CGPoint(x: r.minX + n, y: r.minY + n))
        p.closeSubpath()
        return p
    }

    func inset(by amount: CGFloat) -> NotchedRect {
        var s = self
        s.inset += amount
        return s
    }
}
