import Foundation

enum ItemKind: String, Codable, CaseIterable, Hashable {
    case hat, glasses, scarf, effect, furniture

    var title: String {
        switch self {
        case .hat: "Hats"
        case .glasses: "Glasses"
        case .scarf: "Scarves"
        case .effect: "Effects"
        case .furniture: "Furniture"
        }
    }
}

/// Where a piece of furniture stands in a habitat.
enum FurnitureSlot: String, Codable, CaseIterable, Hashable {
    case floorLeft, floorRight, wallLeft, wallRight, rug, ceiling

    var title: String {
        switch self {
        case .floorLeft: "Floor left"
        case .floorRight: "Floor right"
        case .wallLeft: "Wall left"
        case .wallRight: "Wall right"
        case .rug: "Rug"
        case .ceiling: "Ceiling"
        }
    }
}

/// How an item is unlocked besides buying it.
enum Unlock: Codable, Hashable {
    case level(Int)
    case achievement(String)
    case bond(Int)
    case season(month: Int)
}

struct CatalogItem: Identifiable, Hashable {
    let id: String
    let kind: ItemKind
    let title: String
    let price: Int
    let unlock: Unlock
    var slot: FurnitureSlot? = nil
    var rare = false
    /// Shared sprite to show for it, when it has none of its own.
    var art: String? = nil

    /// Sprite name in SlimeSprites (accessories and furniture share the id).
    var sprite: String { art ?? id }
}

extension Unlock {
    /// How a locked item is unlocked, for the shop.
    var hint: String {
        switch self {
        case .level(let n): return "From level \(n)"
        case .achievement(let id): return "Achievement: \(Achievements.achievement(id)?.title ?? id)"
        case .bond(let n): return "At bond \(n)"
        case .season(let month): return "\(Self.monthNames[max(1, min(12, month)) - 1]) only"
        }
    }

    private static let monthNames = ["January", "February", "March", "April", "May", "June", "July",
                                     "August", "September", "October", "November", "December"]
}

enum Catalog {
    static let items: [CatalogItem] = [
        // Hats
        .init(id: "hat.party", kind: .hat, title: "Party hat", price: 25, unlock: .level(1)),
        .init(id: "hat.beanie", kind: .hat, title: "Beanie", price: 40, unlock: .level(2)),
        .init(id: "hat.flower", kind: .hat, title: "Blossom", price: 50, unlock: .level(3)),
        .init(id: "hat.pumpkin", kind: .hat, title: "Pumpkin", price: 60, unlock: .season(month: 10), rare: true),
        .init(id: "hat.crown", kind: .hat, title: "Crown", price: 150, unlock: .level(8), rare: true),
        .init(id: "hat.wizard", kind: .hat, title: "Wizard hat", price: 220, unlock: .level(12), rare: true),
        // Glasses
        .init(id: "glasses.round", kind: .glasses, title: "Round glasses", price: 35, unlock: .level(2)),
        .init(id: "glasses.shades", kind: .glasses, title: "Sunglasses", price: 60, unlock: .level(4)),
        .init(id: "glasses.star", kind: .glasses, title: "Star glasses", price: 120, unlock: .level(9), rare: true),
        // Scarves
        .init(id: "scarf.red", kind: .scarf, title: "Red scarf", price: 40, unlock: .level(3)),
        .init(id: "scarf.striped", kind: .scarf, title: "Striped scarf", price: 70, unlock: .level(6)),
        // Effects
        .init(id: "effect.sparkle", kind: .effect, title: "Sparkle", price: 100, unlock: .level(5)),
        .init(id: "effect.hearts", kind: .effect, title: "Little hearts", price: 0, unlock: .bond(50), rare: true),
        .init(id: "effect.bubbles", kind: .effect, title: "Soap bubbles", price: 0, unlock: .achievement("bather"), rare: true),
        // Furniture
        .init(id: "furn.bed", kind: .furniture, title: "Little bed", price: 30, unlock: .level(1), slot: .floorLeft),
        .init(id: "furn.plant", kind: .furniture, title: "Potted plant", price: 25, unlock: .level(1), slot: .floorRight),
        .init(id: "furn.rug", kind: .furniture, title: "Rug", price: 35, unlock: .level(2), slot: .rug),
        .init(id: "furn.poster", kind: .furniture, title: "Poster", price: 20, unlock: .level(2), slot: .wallLeft),
        .init(id: "furn.lamp", kind: .furniture, title: "Floor lamp", price: 45, unlock: .level(3), slot: .floorRight),
        .init(id: "furn.shelf", kind: .furniture, title: "Shelf", price: 55, unlock: .level(4), slot: .wallRight),
        .init(id: "furn.lights", kind: .furniture, title: "Fairy lights", price: 60, unlock: .level(5), slot: .ceiling),
        .init(id: "furn.aquarium", kind: .furniture, title: "Aquarium", price: 120, unlock: .level(8), slot: .floorLeft, rare: true),
        .init(id: "furn.trophy", kind: .furniture, title: "Trophy", price: 0, unlock: .achievement("arcadeStar"), slot: .wallRight, rare: true),
        // Seasonal furniture
        .init(id: "furn.jackolantern", kind: .furniture, title: "Jack-o'-lantern", price: 45, unlock: .season(month: 10),
              slot: .floorRight, rare: true, art: "hat.pumpkin"),
        .init(id: "furn.stars", kind: .furniture, title: "Star garland", price: 60, unlock: .season(month: 12),
              slot: .ceiling, rare: true, art: "starJelly"),
        // Gifts: the seventh day of the gift calendar, and the first birthday
        .init(id: "furn.chest", kind: .furniture, title: "Treasure chest", price: 0, unlock: .achievement("gifts7"),
              slot: .floorRight, rare: true, art: "chest"),
        .init(id: "furn.crystal", kind: .furniture, title: "Glow crystal", price: 0, unlock: .achievement("gifts14"),
              slot: .floorLeft, rare: true, art: "gem"),
        .init(id: "furn.flag", kind: .furniture, title: "Finish flag", price: 0, unlock: .achievement("gifts21"),
              slot: .floorLeft, rare: true, art: "flag"),
        .init(id: "furn.cake", kind: .furniture, title: "Birthday cake", price: 0, unlock: .achievement("birthday"),
              slot: .floorRight, rare: true, art: "cake"),
    ]

    static func item(_ id: String) -> CatalogItem? { items.first { $0.id == id } }

    static func items(_ kind: ItemKind) -> [CatalogItem] { items.filter { $0.kind == kind } }
}

enum Habitat: String, Codable, CaseIterable, Identifiable, Hashable {
    case room, garden, beach, space, crystal

    var id: String { rawValue }

    var title: String {
        switch self {
        case .room: "Room"
        case .garden: "Garden"
        case .beach: "Beach"
        case .space: "Space"
        case .crystal: "Crystal cave"
        }
    }

    var price: Int {
        switch self {
        case .room: 0
        case .garden: 150
        case .beach: 250
        case .space: 400
        case .crystal: 0
        }
    }

    var unlock: Unlock {
        switch self {
        case .room: .level(1)
        case .garden: .level(5)
        case .beach: .level(9)
        case .space: .level(14)
        case .crystal: .achievement("collector")
        }
    }

    /// Outdoor habitats get weather.
    var hasWeather: Bool { self == .garden || self == .beach }
}
