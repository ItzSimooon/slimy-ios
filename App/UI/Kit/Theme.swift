import SwiftUI
import UIKit

/// The look of Slimy's game UI: cream pixel windows with a dark ink border
/// and notched corners, like the menus of a small handheld game. At night
/// (dark mode) the paper turns plum. The island keeps its own dark palette
/// (Brand).
enum Theme {
    static let paper = Color.dynamic(light: 0xFFF7E8, dark: 0x2B2336)
    static let paperShade = Color.dynamic(light: 0xF1E2C4, dark: 0x221C2B)
    static let ink = Color.dynamic(light: 0x3A2B2E, dark: 0xF5ECDC)
    static let inkSoft = Color.dynamic(light: 0x7C6758, dark: 0xB8A898)
    static let border = Color.dynamic(light: 0x3A2B2E, dark: 0x0D0A12)
    static let bevel = Color.dynamic(light: 0xFFFFFF, dark: 0x41374E)
    static let backdrop = Color.dynamic(light: 0xCFE6D2, dark: 0x16121D)
    static let accent = Color(hex: 0x4FB84E)
    static let accentInk = Color(hex: 0x1F4A22)
    static let gold = Color(hex: 0xE8A92A)
    static let love = Color(hex: 0xE0577F)
    static let sky = Color(hex: 0x4FA3D9)
    static let danger = Color(hex: 0xD9483B)

    /// Size of one "pixel" of chrome (borders, notches, shadows).
    static let px: CGFloat = 3

    static func title(_ size: CGFloat = 22) -> Font { Brand.pixel(size) }
    static func label(_ size: CGFloat = 15) -> Font { Brand.pixel(size) }
    /// Running text. Follows Dynamic Type (the pixel font does by itself).
    static func text(_ size: CGFloat = 15) -> Font {
        .system(size: UIFontMetrics(forTextStyle: .body).scaledValue(for: size), weight: .medium, design: .rounded)
    }

    static func color(for need: Need) -> Color {
        switch need {
        case .hunger: Color(hex: 0xE8892A)
        case .fun: Color(hex: 0x3F9FD9)
        case .energy: Color(hex: 0xD9B21F)
        case .clean: Color(hex: 0x3FBF9A)
        case .love: Color(hex: 0xE0577F)
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

extension Color {
    static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { traits in
            UIColor(Color(hex: traits.userInterfaceStyle == .dark ? dark : light))
        })
    }
}

/// A rectangle with its corners cut by one chrome pixel: the pixel-art
/// "rounded" corner.
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
