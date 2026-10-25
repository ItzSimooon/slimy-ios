import ActivityKit
import Foundation

/// A friend's slime as the island shows it.
struct FriendSlime: Codable, Hashable {
    var id: String
    var name: String
    var owner: String
    var skin: Skin
    var stage: Stage
    var mood: Mood
    var love: Int
    var inLove: Bool
    var equipped: Accessories?
}

/// Something you and a friend are doing together right now.
enum SharedMoment: String, Codable, Hashable {
    case reunion, missing, hug, play, gift, love, achievement, sleep

    var line: String {
        switch self {
        case .reunion: "wiedersehen!"
        case .missing: "ich vermisse dich"
        case .hug: "umarmung"
        case .play: "zusammen spielen"
        case .gift: "ein geschenk"
        case .love: "verliebt"
        case .achievement: "geschafft, zusammen"
        case .sleep: "gute nacht, ihr zwei"
        }
    }

    /// The slimes' animation during the moment.
    var anim: SpriteAnim {
        switch self {
        case .reunion, .achievement: .celebrate
        case .missing: .sad
        case .hug, .love: .love
        case .play: .play
        case .gift: .blush
        case .sleep: .sleep
        }
    }

    var reel: EmoteReel {
        switch self {
        case .hug, .love, .missing: .love
        case .play: .fun
        case .sleep: .sleepy
        case .reunion, .gift, .achievement: .happyB
        }
    }

    /// How long the island stays in this moment before going back to solo.
    var duration: TimeInterval {
        switch self {
        case .love, .sleep: 30 * 60
        default: 10 * 60
        }
    }
}

struct SlimeActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var name: String
        var skin: Skin
        var stage: Stage?
        var mood: Mood
        var reaction: Reaction
        /// Start of the animation timers.
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
        var equipped: Accessories?
        /// Up to three friends, most important first.
        var friends: [FriendSlime]?
        var moment: SharedMoment?
        var momentFriend: String?
        var animated: Bool?
        var probe: Bool?

        func value(_ need: Need) -> Int {
            switch need {
            case .hunger: hunger
            case .fun: fun
            case .energy: energy
            case .clean: clean
            case .love: love
            }
        }

        var slimeStage: Stage { stage ?? .young }
        var accessories: Accessories { equipped ?? Accessories() }
        var isAnimated: Bool { animated ?? true }

        /// What the island plays right now.
        var anim: SpriteAnim {
            if let moment { return moment.anim }
            return SpriteAnim(reaction) ?? SpriteAnim(mood)
        }

        /// The friend sharing the moment, or the one in love.
        var partner: FriendSlime? {
            if let momentFriend, let f = friends?.first(where: { $0.id == momentFriend }) { return f }
            return friends?.first(where: \.inLove)
        }

        /// What the slime is emoting on the right of the compact island.
        var emote: EmoteReel {
            if let moment { return moment.reel }
            if let event, let reel = EmoteReel(event) { return reel }
            if mood == .sleep { return .sleepy }
            switch reaction {
            case .love, .blush: return .love
            case .bath: return .clean
            case .sulk, .confused: return .confused
            default: break
            }
            if let wish { return EmoteReel(wish) }
            return .happy(at: anchor)
        }
    }
}

extension SlimeActivityAttributes.ContentState {
    init(_ game: GameState, at now: Date, friends: [FriendSlime] = [], moment: SharedMoment? = nil,
         momentFriend: String? = nil, animated: Bool = true, probe: Bool = false) {
        let s = game.slime
        let reaction = s.activeReaction(at: now)
        var momentLine: String?
        if let moment {
            if let id = momentFriend, let friend = friends.first(where: { $0.id == id }) {
                momentLine = "\(friend.name): \(moment.line)"
            } else {
                momentLine = moment.line
            }
        }
        self.init(
            name: s.name,
            skin: s.skin,
            stage: s.stage,
            mood: s.mood,
            reaction: reaction,
            anchor: reaction == .none ? now : s.reactionAt,
            hunger: Int(s.needs.hunger.rounded()),
            fun: Int(s.needs.fun.rounded()),
            energy: Int(s.needs.energy.rounded()),
            clean: Int(s.needs.clean.rounded()),
            love: Int(s.needs.love.rounded()),
            wish: s.wish,
            level: s.level,
            coins: game.wallet.coins,
            line: momentLine ?? game.line(at: now),
            event: s.recentEvent(at: now),
            equipped: s.equipped,
            friends: friends.isEmpty ? nil : Array(friends.prefix(3)),
            moment: moment,
            momentFriend: momentFriend,
            animated: animated,
            probe: probe)
    }
}
