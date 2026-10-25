import Foundation

enum Food: String, Codable, CaseIterable, Identifiable, Hashable {
    case apple, carrot, berry, mushroom, cookie, soup, honey, pudding, cake, starJelly

    var id: String { rawValue }

    /// Foods that can be a slime's secret favourite.
    static let discoverable: [Food] = [.carrot, .berry, .mushroom, .cookie, .soup, .honey, .pudding, .cake]

    var title: String {
        switch self {
        case .apple: "Apple"
        case .carrot: "Carrot"
        case .berry: "Strawberry"
        case .mushroom: "Mushroom"
        case .cookie: "Cookie"
        case .soup: "Soup"
        case .honey: "Honey"
        case .pudding: "Pudding"
        case .cake: "Cake"
        case .starJelly: "Star jelly"
        }
    }

    var sprite: String { rawValue }

    var price: Int {
        switch self {
        case .apple: 0
        case .carrot: 2
        case .berry: 3
        case .mushroom: 4
        case .cookie: 6
        case .soup: 8
        case .honey: 9
        case .pudding: 10
        case .cake: 14
        case .starJelly: 35
        }
    }

    var nutrition: Double {
        switch self {
        case .apple: 16
        case .carrot: 20
        case .berry: 22
        case .mushroom: 24
        case .cookie: 26
        case .soup: 40
        case .honey: 28
        case .pudding: 32
        case .cake: 45
        case .starJelly: 100
        }
    }

    var funBonus: Double {
        switch self {
        case .apple, .carrot, .soup: 0
        case .berry, .mushroom: 3
        case .cookie, .honey: 8
        case .pudding: 10
        case .cake: 12
        case .starJelly: 25
        }
    }

    /// Unlocked in the kitchen from this slime level on.
    var unlockLevel: Int {
        switch self {
        case .apple, .carrot, .berry: 1
        case .cookie, .mushroom: 2
        case .soup: 3
        case .honey, .pudding: 5
        case .cake: 7
        case .starJelly: 10
        }
    }
}
