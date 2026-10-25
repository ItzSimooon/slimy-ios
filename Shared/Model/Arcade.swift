import Foundation

/// The arcade. Every game has its own rules in App/Arcade.
enum GameID: String, Codable, CaseIterable, Identifiable, Hashable {
    case snackCatcher, slimeJump, bubblePop, memoryGarden, slimeRacing
    case rhythmBounce, gardenPuzzle, treasureDig, basketball, colorMatch

    var id: String { rawValue }

    var title: String {
        switch self {
        case .snackCatcher: "Snack-Fänger"
        case .slimeJump: "Slime-Sprung"
        case .bubblePop: "Blasen-Platzer"
        case .memoryGarden: "Gedächtnisgarten"
        case .slimeRacing: "Slime-Rennen"
        case .rhythmBounce: "Rhythmus-Hüpfer"
        case .gardenPuzzle: "Garten-Rätsel"
        case .treasureDig: "Schatzsuche"
        case .basketball: "Slime-Basketball"
        case .colorMatch: "Farbenspiel"
        }
    }

    var blurb: String {
        switch self {
        case .snackCatcher: "Fang Essen, weich Faulem aus, bau Kombos."
        case .slimeJump: "Spring von Plattform zu Plattform, so hoch es geht."
        case .bubblePop: "Platz Gruppen gleicher Blasen. Große Gruppen zählen mehr."
        case .memoryGarden: "Merk dir, welche Blumen leuchten, und tipp sie nach."
        case .slimeRacing: "Weich Steinen aus und hol den Geist ein."
        case .rhythmBounce: "Tipp genau dann, wenn der Ton ankommt."
        case .gardenPuzzle: "Schieb Steine, sammel Samen, erreich das Beet."
        case .treasureDig: "Grab mit wenig Schaufeln nach Schätzen. Die Zahlen helfen."
        case .basketball: "Wisch zum Werfen. Der Korb bewegt sich."
        case .colorMatch: "Passt Farbe oder Form? Schnell entscheiden."
        }
    }

    /// Short rules for the first-play introduction.
    var tutorial: [String] {
        switch self {
        case .snackCatcher: ["Zieh nach links und rechts, um zu fangen.", "Faules Essen und Kakteen kosten Punkte.", "Mehrere Treffer in Folge geben Kombo-Bonus."]
        case .slimeJump: ["Tipp links oder rechts, um in die Richtung zu springen.", "Landest du auf einer Plattform, springst du weiter.", "Münzen geben Extrapunkte. Nicht runterfallen."]
        case .bubblePop: ["Tipp auf eine Gruppe aus mindestens zwei gleichen Blasen.", "Je größer die Gruppe, desto mehr Punkte.", "Ohne Züge ist die Runde vorbei."]
        case .memoryGarden: ["Schau zu, welche Blumen nacheinander leuchten.", "Tipp sie in der gleichen Reihenfolge.", "Jede Runde kommt eine Blume dazu."]
        case .slimeRacing: ["Wisch nach links oder rechts, um die Spur zu wechseln.", "Steine bremsen, Pfützen machen schneller.", "Sei vor dem Geist im Ziel."]
        case .rhythmBounce: ["Noten fallen auf die Linie zu.", "Tipp genau, wenn sie die Linie treffen.", "Perfekt gibt mehr Punkte als gut."]
        case .gardenPuzzle: ["Wisch, um den Slime zu bewegen.", "Steine lassen sich schieben, Wände nicht.", "Sammel alle Samen und geh dann aufs Beet."]
        case .treasureDig: ["Tipp ein Feld, um zu graben.", "Zahlen zeigen, wie nah der nächste Schatz ist.", "Du hast nur wenige Schaufeln."]
        case .basketball: ["Wisch vom Ball Richtung Korb.", "Länger wischen wirft weiter.", "Treffer in Folge zählen doppelt."]
        case .colorMatch: ["Oben steht eine Regel: Farbe oder Form.", "Tipp Ja, wenn das Teil passt, sonst Nein.", "Sei schnell, die Zeit läuft."]
        }
    }

    /// Icon sprite from SlimeSprites.items.
    var icon: String {
        switch self {
        case .snackCatcher: "apple"
        case .slimeJump: "coin"
        case .bubblePop: "bubbleItem"
        case .memoryGarden: "flower"
        case .slimeRacing: "flag"
        case .rhythmBounce: "noteItem"
        case .gardenPuzzle: "seed"
        case .treasureDig: "chest"
        case .basketball: "ball"
        case .colorMatch: "gem"
        }
    }

    var energyCost: Double { 8 }

    /// Score that counts as a good round, used for rewards and challenges.
    var goodScore: Int {
        switch self {
        case .snackCatcher: 40
        case .slimeJump: 30
        case .bubblePop: 400
        case .memoryGarden: 8
        case .slimeRacing: 60
        case .rhythmBounce: 300
        case .gardenPuzzle: 3
        case .treasureDig: 60
        case .basketball: 12
        case .colorMatch: 25
        }
    }

    /// Unlocked in the arcade from this slime level on.
    var unlockLevel: Int {
        switch self {
        case .snackCatcher, .bubblePop, .memoryGarden: 1
        case .slimeJump, .colorMatch: 2
        case .basketball, .treasureDig: 3
        case .rhythmBounce: 4
        case .slimeRacing: 5
        case .gardenPuzzle: 6
        }
    }
}

/// What a finished round produces. Rewards are worked out here, not by the
/// game views, so they are the same everywhere and can be tested.
struct GameResult: Codable, Hashable {
    var game: GameID
    var score: Int
    /// Unique per round; the reward for a round can only be claimed once.
    var session: UUID
    var finishedAt: Date

    var coins: Int {
        let ratio = Double(max(0, score)) / Double(game.goodScore)
        return min(30, 2 + Int((ratio * 12).rounded()))
    }

    var xp: Int {
        let ratio = Double(max(0, score)) / Double(game.goodScore)
        return min(30, 6 + Int((ratio * 14).rounded()))
    }

    var funGain: Double { 14 + min(16, Double(score) / Double(game.goodScore) * 10) }
}

struct GameRecord: Codable, Hashable {
    var best = 0
    var plays = 0
    var lastPlayed: Date?
    var tutorialSeen = false
}
