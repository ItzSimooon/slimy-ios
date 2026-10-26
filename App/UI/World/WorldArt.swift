import SwiftUI

/// Pixel art that only lives in the slime's home and on the gift screen:
/// seasonal pieces, the gift box, and scene versions of some furniture.
/// Same format as the sprite data: "." is empty, every other character
/// is a colour from the palette.
enum WorldArt {
    static let sprites: [String: ItemSprite] = [
        // A carved pumpkin. "f" is the face: dark by day, lit at night.
        "furn.jackolantern": ItemSprite(palette: ["o": 0xF08A3C, "d": 0xB85A1E, "g": 0x6CC65A, "f": 0x3A1E0A], rows: [
            ".....gg....",
            "...oogooo..",
            ".ooooodood.",
            "ooffodoffod",
            "ooffodoffod",
            "ooooofooood",
            "offofffoffd",
            ".offfffffd.",
            "..ooooood..",
            "...ddddd...",
        ]),
        // Stars on a string, one 32-pixel length; repeats across the ceiling.
        "furn.stars": ItemSprite(palette: ["k": 0x2E3A32, "y": 0xFFD166, "w": 0xFFF5D6, "o": 0xE8A23A], rows: [
            "kk..kk..kk..kk..kk..kk..kk..kk..",
            "..kk..kk..kk..kk..kk..kk..kk..kk",
            "...k.......k.......k.......k....",
            "...y.......w.......y.......w....",
            "..yyy.....www.....yyy.....www...",
            "...o.......y.......o.......y....",
        ]),
        "gift.closed": ItemSprite(palette: giftPalette, rows: [
            "..rrr..rrr..",
            ".r...rr...r.",
            "..rrrrrrrr..",
            "llllleelllll",
            "lwlllrrllllm",
            "mmmmmrrmmmmm",
            ".bbbbrrbbbb.",
            ".bwbbrrbbbs.",
            ".bbbbrrbbbs.",
            ".bbbbrrbbbs.",
            ".bbbbrrbbbs.",
            ".sssseessss.",
        ]),
        "gift.open": ItemSprite(palette: giftPalette, rows: [
            "..y......y..",
            "y....y......",
            "..llllrrllll",
            "..mmmmrrmmmm",
            ".....y......",
            "...y....y...",
            ".skkkkkkkks.",
            ".bwbbrrbbbs.",
            ".bbbbrrbbbs.",
            ".bbbbrrbbbs.",
            ".bbbbrrbbbs.",
            ".sssseessss.",
        ]),
        "bat.up": ItemSprite(palette: ["k": 0x2A1E3A], rows: [
            "k.....k",
            "kk.k.kk",
            ".kkkkk.",
            "...k...",
        ]),
        "bat.down": ItemSprite(palette: ["k": 0x2A1E3A], rows: [
            ".......",
            "..kkk..",
            "kkkkkkk",
            "k.....k",
        ]),
    ]

    private static let giftPalette: [String: UInt32] = [
        "r": 0xF07A9A, "e": 0xC2547A,
        "l": 0x5FB0E8, "m": 0x3A86D9,
        "b": 0x7CC8FF, "s": 0x3A86D9, "w": 0xEFF8FF,
        "k": 0x24507E, "y": 0xFFD166,
    ]

    /// Scene art first, then the shared sprites; furniture ids fall back
    /// to the sprite their catalog entry borrows.
    static func sprite(_ name: String) -> ItemSprite? {
        if let own = sprites[name] { return own }
        if let shared = SlimeSprites.items[name] { return shared }
        if let borrowed = Catalog.item(name)?.sprite { return SlimeSprites.items[borrowed] }
        return nil
    }
}

/// Draws a sprite from WorldArt or the shared sprite data, crisp at any size.
struct ArtView: View {
    let name: String

    var body: some View {
        if let sprite = WorldArt.sprite(name) {
            let cols = sprite.rows.first?.count ?? 1
            ZStack {
                ForEach(sprite.palette.keys.sorted(), id: \.self) { key in
                    if let ch = key.first {
                        PixelGridShape(rows: sprite.rows, code: ch, cols: cols, count: sprite.rows.count, bottom: false)
                            .fill(Color(hex: sprite.palette[key] ?? 0))
                    }
                }
            }
            .aspectRatio(CGFloat(cols) / CGFloat(max(1, sprite.rows.count)), contentMode: .fit)
            .accessibilityHidden(true)
        }
    }
}
