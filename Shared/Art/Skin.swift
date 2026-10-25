import SwiftUI

/// Jelly colours. New ones unlock as the slime levels up.
enum Skin: String, Codable, CaseIterable, Identifiable, Hashable {
    case lime, bubblegum, ocean, grape, lava, gold, ghost, void

    var id: String { rawValue }

    var title: String {
        switch self {
        case .lime: "Lime"
        case .bubblegum: "Bubblegum"
        case .ocean: "Ocean"
        case .grape: "Grape"
        case .lava: "Lava"
        case .gold: "Gold"
        case .ghost: "Ghost"
        case .void: "Hypr Void"
        }
    }

    var unlockLevel: Int {
        switch self {
        case .lime: 1
        case .bubblegum: 2
        case .ocean: 3
        case .grape: 4
        case .lava: 6
        case .gold: 8
        case .ghost: 10
        case .void: 15
        }
    }

    private var hexes: (body: UInt32, shade: UInt32, ink: UInt32, shine: UInt32) {
        switch self {
        case .lime: (0x7DE67A, 0x3DAA5A, 0x143028, 0xF0FFF0)
        case .bubblegum: (0xFF9FCB, 0xE0609A, 0x3A1228, 0xFFF0F7)
        case .ocean: (0x7CC8FF, 0x3A86D9, 0x0F2240, 0xEFF8FF)
        case .grape: (0xB59BFF, 0x7A5BE0, 0x22164A, 0xF4F0FF)
        case .lava: (0xFF9A5C, 0xE0532F, 0x3A140A, 0xFFF3E8)
        case .gold: (0xFFD86B, 0xE0A42F, 0x3A2A08, 0xFFFBEA)
        case .ghost: (0xE8F0FF, 0xA9B6D8, 0x2A3048, 0xFFFFFF)
        case .void: (0x2A2540, 0x1A1630, 0x9B7BFF, 0xD9CCFF)
        }
    }

    var body: Color { Color(hex: hexes.body) }
    var shade: Color { Color(hex: hexes.shade) }
    var ink: Color { Color(hex: hexes.ink) }
    var shine: Color { Color(hex: hexes.shine) }

    /// Colour for one paint layer of the sprite.
    func color(for layer: SpriteLayer) -> Color {
        switch layer {
        case .body: body
        case .shade: shade
        case .ink: ink
        case .shine: shine
        case .warm: Brand.warm
        case .cool: Brand.cool
        }
    }
}

/// Paint layers, in the same order the font generator draws them.
enum SpriteLayer: String, CaseIterable, Hashable {
    case body, shade, ink, shine, warm, cool

    /// Character used for this layer in `SlimeSprites`.
    var code: Character {
        switch self {
        case .body: "b"
        case .shade: "s"
        case .ink: "k"
        case .shine: "w"
        case .warm: "r"
        case .cool: "c"
        }
    }

    var fontKey: String { rawValue.capitalized }
}
