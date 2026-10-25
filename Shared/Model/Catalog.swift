import Foundation

enum ItemKind: String, Codable, CaseIterable, Hashable {
    case hat, glasses, scarf, effect, furniture

    var title: String {
        switch self {
        case .hat: "Hüte"
        case .glasses: "Brillen"
        case .scarf: "Schals"
        case .effect: "Effekte"
        case .furniture: "Möbel"
        }
    }
}

/// Where a piece of furniture stands in a habitat.
enum FurnitureSlot: String, Codable, CaseIterable, Hashable {
    case floorLeft, floorRight, wallLeft, wallRight, rug, ceiling

    var title: String {
        switch self {
        case .floorLeft: "Boden links"
        case .floorRight: "Boden rechts"
        case .wallLeft: "Wand links"
        case .wallRight: "Wand rechts"
        case .rug: "Teppich"
        case .ceiling: "Decke"
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

    /// Sprite name in SlimeSprites (accessories and furniture share the id).
    var sprite: String { id }
}

enum Catalog {
    static let items: [CatalogItem] = [
        // Hats
        .init(id: "hat.party", kind: .hat, title: "Partyhut", price: 25, unlock: .level(1)),
        .init(id: "hat.beanie", kind: .hat, title: "Mütze", price: 40, unlock: .level(2)),
        .init(id: "hat.flower", kind: .hat, title: "Blüte", price: 50, unlock: .level(3)),
        .init(id: "hat.pumpkin", kind: .hat, title: "Kürbis", price: 60, unlock: .season(month: 10), rare: true),
        .init(id: "hat.crown", kind: .hat, title: "Krone", price: 150, unlock: .level(8), rare: true),
        .init(id: "hat.wizard", kind: .hat, title: "Zauberhut", price: 220, unlock: .level(12), rare: true),
        // Glasses
        .init(id: "glasses.round", kind: .glasses, title: "Runde Brille", price: 35, unlock: .level(2)),
        .init(id: "glasses.shades", kind: .glasses, title: "Sonnenbrille", price: 60, unlock: .level(4)),
        .init(id: "glasses.star", kind: .glasses, title: "Sternbrille", price: 120, unlock: .level(9), rare: true),
        // Scarves
        .init(id: "scarf.red", kind: .scarf, title: "Roter Schal", price: 40, unlock: .level(3)),
        .init(id: "scarf.striped", kind: .scarf, title: "Ringelschal", price: 70, unlock: .level(6)),
        // Effects
        .init(id: "effect.sparkle", kind: .effect, title: "Funkeln", price: 100, unlock: .level(5)),
        .init(id: "effect.hearts", kind: .effect, title: "Herzchen", price: 0, unlock: .bond(50), rare: true),
        .init(id: "effect.bubbles", kind: .effect, title: "Seifenblasen", price: 0, unlock: .achievement("bather"), rare: true),
        // Furniture
        .init(id: "furn.bed", kind: .furniture, title: "Bettchen", price: 30, unlock: .level(1), slot: .floorLeft),
        .init(id: "furn.plant", kind: .furniture, title: "Topfpflanze", price: 25, unlock: .level(1), slot: .floorRight),
        .init(id: "furn.rug", kind: .furniture, title: "Teppich", price: 35, unlock: .level(2), slot: .rug),
        .init(id: "furn.poster", kind: .furniture, title: "Poster", price: 20, unlock: .level(2), slot: .wallLeft),
        .init(id: "furn.lamp", kind: .furniture, title: "Stehlampe", price: 45, unlock: .level(3), slot: .floorRight),
        .init(id: "furn.shelf", kind: .furniture, title: "Regal", price: 55, unlock: .level(4), slot: .wallRight),
        .init(id: "furn.lights", kind: .furniture, title: "Lichterkette", price: 60, unlock: .level(5), slot: .ceiling),
        .init(id: "furn.aquarium", kind: .furniture, title: "Aquarium", price: 120, unlock: .level(8), slot: .floorLeft, rare: true),
        .init(id: "furn.trophy", kind: .furniture, title: "Pokal", price: 0, unlock: .achievement("arcadeStar"), slot: .wallRight, rare: true),
    ]

    static func item(_ id: String) -> CatalogItem? { items.first { $0.id == id } }

    static func items(_ kind: ItemKind) -> [CatalogItem] { items.filter { $0.kind == kind } }
}

enum Habitat: String, Codable, CaseIterable, Identifiable, Hashable {
    case room, garden, beach, space, crystal

    var id: String { rawValue }

    var title: String {
        switch self {
        case .room: "Zimmer"
        case .garden: "Garten"
        case .beach: "Strand"
        case .space: "Weltraum"
        case .crystal: "Kristallhöhle"
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
