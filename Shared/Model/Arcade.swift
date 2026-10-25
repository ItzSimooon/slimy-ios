import Foundation

/// The arcade. Every game has its own rules in App/Arcade.
enum GameID: String, Codable, CaseIterable, Identifiable, Hashable {
    case snackCatcher, slimeJump, bubblePop, memoryGarden, slimeRacing
    case rhythmBounce, gardenPuzzle, treasureDig, basketball, colorMatch
    /// The day's game, built from blocks (DailyPlan).
    case daily

    var id: String { rawValue }

    /// The ten fixed games, without the daily one.
    static var classics: [GameID] { allCases.filter { $0 != .daily } }

    var title: String {
        switch self {
        case .snackCatcher: "Snack Catcher"
        case .slimeJump: "Slime Jump"
        case .bubblePop: "Bubble Pop"
        case .memoryGarden: "Memory Garden"
        case .slimeRacing: "Slime Racing"
        case .rhythmBounce: "Rhythm Bounce"
        case .gardenPuzzle: "Garden Puzzle"
        case .treasureDig: "Treasure Dig"
        case .basketball: "Slime Basketball"
        case .colorMatch: "Color Match"
        case .daily: "Today's game"
        }
    }

    var blurb: String {
        switch self {
        case .snackCatcher: "Catch food, dodge the rotten stuff, build combos."
        case .slimeJump: "Bounce from platform to platform, as high as you can."
        case .bubblePop: "Pop groups of matching bubbles. Big groups score more."
        case .memoryGarden: "Remember which flowers light up and tap them back."
        case .slimeRacing: "Dodge rocks and beat the ghost to the finish."
        case .rhythmBounce: "Tap right when the note arrives."
        case .gardenPuzzle: "Push stones, collect seeds, reach the flower bed."
        case .treasureDig: "Dig for treasure with few shovels. The numbers help."
        case .basketball: "Swipe to throw. The hoop moves."
        case .colorMatch: "Does the color or the shape match? Decide fast."
        case .daily: "A new game every day."
        }
    }

    /// Short rules for the first-play introduction.
    var tutorial: [String] {
        switch self {
        case .snackCatcher: ["Drag left and right to catch.", "Rotten food and cacti cost points.", "Several catches in a row give a combo bonus."]
        case .slimeJump: ["Hold the left or right side to steer.", "Land on a platform and you bounce again.", "Coins give extra points. Don't fall."]
        case .bubblePop: ["Tap a group of at least two matching bubbles.", "The bigger the group, the more points.", "The round ends when no moves are left."]
        case .memoryGarden: ["Watch which flowers light up, one after another.", "Tap them in the same order.", "Every round adds one more flower."]
        case .slimeRacing: ["Swipe or tap left and right to change lanes.", "Rocks slow you down, puddles speed you up.", "Finish before the ghost."]
        case .rhythmBounce: ["Notes fall toward the line.", "Tap the lane exactly when a note hits the line.", "Perfect scores more than good."]
        case .gardenPuzzle: ["Swipe or use the arrows to move.", "Stones can be pushed, hedges can't.", "Collect every seed, then step onto the bed."]
        case .treasureDig: ["Tap a square to dig.", "Numbers show how far the nearest treasure is.", "You only have a few shovels."]
        case .basketball: ["Swipe up from the ball toward the hoop.", "A longer swipe throws farther.", "Hits in a row count double."]
        case .colorMatch: ["The rule at the top says color or shape.", "Tap Yes if the piece matches the sample, otherwise No.", "Be quick, the clock is running."]
        case .daily: ["Every day brings a different game."]
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
        case .daily: "starJelly"
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
        case .daily: 100
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
        case .daily: 1
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
    /// For the daily game: the good score of that day's game and its day.
    var target: Int? = nil
    var day: String? = nil

    /// A good round's score for this game.
    var goodScore: Int { max(1, target ?? game.goodScore) }

    var coins: Int {
        let ratio = Double(max(0, score)) / Double(goodScore)
        return min(30, 2 + Int((ratio * 12).rounded()))
    }

    var xp: Int {
        let ratio = Double(max(0, score)) / Double(goodScore)
        return min(30, 6 + Int((ratio * 14).rounded()))
    }

    var funGain: Double { 14 + min(16, Double(score) / Double(goodScore) * 10) }
}

struct GameRecord: Codable, Hashable {
    var best = 0
    var plays = 0
    var lastPlayed: Date?
    var tutorialSeen = false
}
