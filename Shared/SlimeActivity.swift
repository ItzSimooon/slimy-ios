import ActivityKit
import Foundation

/// A friend's slime, shown next to yours when the two are close.
struct PartnerSnapshot: Codable, Hashable {
    var ownerName: String
    var slimeName: String
    var skin: Skin
    var mood: Mood
    var love: Int        // 0...100 between the two slimes
    var inLove: Bool
}

struct SlimeActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var name: String
        var skin: Skin
        var mood: Mood
        var reaction: Reaction
        /// Start of the animation timer. The first minute after it plays `reaction`.
        var anchor: Date
        var hunger: Int
        var fun: Int
        var energy: Int
        var clean: Int
        var love: Int
        var wish: Need?
        var level: Int
        var coins: Int
        var line: String
        var event: PhoneEvent?
        var partner: PartnerSnapshot?

        func value(_ need: Need) -> Int {
            switch need {
            case .hunger: hunger
            case .fun: fun
            case .energy: energy
            case .clean: clean
            case .love: love
            }
        }
    }
}

extension SlimeState {
    func activityState(at now: Date = .now, partner: PartnerSnapshot? = nil) -> SlimeActivityAttributes.ContentState {
        let reaction = activeReaction(at: now)
        return .init(
            name: name,
            skin: skin,
            mood: mood,
            reaction: reaction,
            anchor: reaction == .none ? now : reactionAt,
            hunger: Int(needs.hunger.rounded()),
            fun: Int(needs.fun.rounded()),
            energy: Int(needs.energy.rounded()),
            clean: Int(needs.clean.rounded()),
            love: Int(needs.love.rounded()),
            wish: wish,
            level: level,
            coins: coins,
            line: line(at: now),
            event: recentEvent(at: now),
            partner: partner)
    }
}

/// Names of the animation fonts built by tools/make_slime.py.
enum SlimeFont {
    /// How long one animation timer runs. Longer than any Live Activity lives.
    static let span: TimeInterval = 16 * 3600

    static func name(anim: SpriteAnim, layer: SpriteLayer) -> String {
        "Slime-\(anim.rawValue.capitalized)-\(layer.fontKey)"
    }

    /// Glyphs are 24 x 20 pixels; the font is sized by height.
    static let aspect: CGFloat = 24.0 / 20.0
}

extension SlimeActivityAttributes.ContentState {
    /// What the island plays right now: the reaction if there is one, else the mood.
    var anim: SpriteAnim { SpriteAnim(reaction) ?? SpriteAnim(mood) }
}
