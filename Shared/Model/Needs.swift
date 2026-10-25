import Foundation

enum Need: String, Codable, CaseIterable, Identifiable, Hashable {
    case hunger, fun, energy, clean, love

    var id: String { rawValue }

    var title: String {
        switch self {
        case .hunger: "Essen"
        case .fun: "Spaß"
        case .energy: "Energie"
        case .clean: "Sauber"
        case .love: "Zuneigung"
        }
    }

    var symbol: String {
        switch self {
        case .hunger: "fork.knife"
        case .fun: "gamecontroller.fill"
        case .energy: "bolt.fill"
        case .clean: "bubbles.and.sparkles.fill"
        case .love: "heart.fill"
        }
    }

    /// Base points lost per hour, before personality.
    func baseDecay(asleep: Bool) -> Double {
        switch (self, asleep) {
        case (.hunger, false): 6
        case (.hunger, true): 2.5
        case (.fun, false): 5
        case (.fun, true): 1
        case (.energy, false): 4
        case (.energy, true): -15
        case (.clean, false): 2.5
        case (.clean, true): 1
        case (.love, false): 4
        case (.love, true): 1.5
        }
    }

    /// What it means when this need is low. Shown in the needs panel.
    var lowHint: String {
        switch self {
        case .hunger: "Hat Hunger. Ohne Essen wird er schlapp."
        case .fun: "Langweilt sich. Ein Spiel hilft."
        case .energy: "Ist müde. Ab ins Bett."
        case .clean: "Ist klebrig. Zeit für ein Bad."
        case .love: "Möchte gekrault werden."
        }
    }
}

struct Needs: Codable, Hashable {
    var hunger = 80.0
    var fun = 70.0
    var energy = 90.0
    var clean = 85.0
    var love = 75.0

    subscript(need: Need) -> Double {
        get {
            switch need {
            case .hunger: hunger
            case .fun: fun
            case .energy: energy
            case .clean: clean
            case .love: love
            }
        }
        set {
            let v = min(100, max(0, newValue.isFinite ? newValue : 50))
            switch need {
            case .hunger: hunger = v
            case .fun: fun = v
            case .energy: energy = v
            case .clean: clean = v
            case .love: love = v
            }
        }
    }

    mutating func add(_ need: Need, _ amount: Double) { self[need] += amount }

    var lowest: (need: Need, value: Double) {
        var best = (Need.hunger, hunger)
        for need in Need.allCases where self[need] < best.1 { best = (need, self[need]) }
        return best
    }

    var average: Double { Need.allCases.map { self[$0] }.reduce(0, +) / Double(Need.allCases.count) }

    /// Repairs values from a damaged save.
    mutating func sanitize() {
        for need in Need.allCases { self[need] = self[need] }
    }
}
