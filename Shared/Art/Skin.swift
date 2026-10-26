import SwiftUI

/// Jelly colours. New ones unlock as the slime levels up; the secret ones
/// (`isSecret`) only come out of crates.
enum Skin: String, Codable, CaseIterable, Identifiable, Hashable {
    case lime, bubblegum, ocean, grape, lava, gold, ghost, void, axolotl

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
        case .axolotl: "Axolotl"
        }
    }

    /// The level that unlocks this colour. Secret colours never unlock by
    /// level (`Int.max`); check `isSecret` before showing a level for one.
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
        case .axolotl: Int.max
        }
    }

    /// A colour that is never listed in the shop or among the colours still
    /// to unlock. It only comes from crates and shows once it's owned.
    var isSecret: Bool { self == .axolotl }

    /// Every colour that unlocks with levels, in order: all but the secret ones.
    static var regular: [Skin] { allCases.filter { !$0.isSecret } }

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
        // tools/art_items.py, AXOLOTL_SKIN
        case .axolotl: (0xFFC4D8, 0xF08DB0, 0x4A1736, 0xFFFFFF)
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

    /// Pixels this colour adds on top of every frame (the axolotl's gills and
    /// cheeks), from the sprite data. Nil for plain colours.
    var overlay: SkinOverlaySprite? {
        SlimeSprites.data.skinOverlays?[rawValue]
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
