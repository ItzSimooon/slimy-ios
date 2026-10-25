import AppIntents
import SwiftUI
import WidgetKit

typealias IslandState = SlimeActivityAttributes.ContentState

/// Text in the island and on the Lock Screen. The rounded system font stays
/// crisp at small sizes; the pixel font is kept for the slime's name on the
/// Lock Screen, where it is big enough to read.
enum IslandType {
    static let line = Font.system(size: 15, weight: .semibold, design: .rounded)
    static let name = Font.system(size: 13, weight: .bold, design: .rounded)
    static let small = Font.system(size: 12, weight: .semibold, design: .rounded)
    static let badge = Font.system(size: 13, weight: .heavy, design: .rounded)
    static let button = Font.system(size: 12, weight: .bold, design: .rounded)
}

/// Five block bars, one per need, each with its pixel icon.
struct NeedsStrip: View {
    let state: IslandState

    var body: some View {
        HStack(spacing: 8) {
            ForEach(Need.allCases) { need in
                HStack(spacing: 4) {
                    PixelItem(name: icon(need)).frame(width: 12, height: 12)
                    PixelBar(value: state.value(need), segments: 5,
                             color: state.value(need) < 25 ? Brand.danger : Brand.color(for: need), height: 6, gap: 1.5)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Need.allCases.map { "\($0.title) \(state.value($0)) percent" }.joined(separator: ", "))
    }

    private func icon(_ need: Need) -> String {
        switch need {
        case .hunger: "apple"
        case .fun: "ball"
        case .energy: "starJelly"
        case .clean: "bubbleItem"
        case .love: "heart"
        }
    }
}

/// A button in the island that runs an intent without opening the app.
struct IslandButton<I: LiveActivityIntent>: View {
    let intent: I
    let symbol: String
    let label: String
    let tint: Color
    var height: CGFloat = 34

    var body: some View {
        Button(intent: intent) {
            HStack(spacing: 5) {
                Image(systemName: symbol).font(.system(size: 12, weight: .bold)).foregroundStyle(tint)
                Text(label).font(IslandType.button).foregroundStyle(Brand.text)
                    .lineLimit(1).minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .background(tint.opacity(0.2))
            .overlay(Rectangle().strokeBorder(tint.opacity(0.55), lineWidth: 1.5))
        }
        .buttonStyle(.plain)
    }
}

struct ActionRow: View {
    let state: IslandState
    var height: CGFloat = 34

    var body: some View {
        HStack(spacing: 6) {
            if state.mood == .sleep {
                IslandButton(intent: SleepSlimeIntent(), symbol: "sun.max.fill", label: "Wake", tint: Brand.color(for: .energy), height: height)
                IslandButton(intent: PetSlimeIntent(), symbol: "hand.raised.fill", label: "Pet", tint: Brand.warm, height: height)
            } else {
                IslandButton(intent: FeedSlimeIntent(), symbol: "fork.knife", label: "Feed", tint: Brand.color(for: .hunger), height: height)
                IslandButton(intent: PlaySlimeIntent(), symbol: "gamecontroller.fill", label: "Play", tint: Brand.color(for: .fun), height: height)
                IslandButton(intent: BathSlimeIntent(), symbol: "drop.fill", label: "Bath", tint: Brand.color(for: .clean), height: height)
                IslandButton(intent: PetSlimeIntent(), symbol: "hand.raised.fill", label: "Pet", tint: Brand.warm, height: height)
            }
        }
    }
}

extension IslandSlime {
    /// The player's slime as the activity state describes it.
    init(_ state: IslandState, height: CGFloat) {
        self.init(stage: state.slimeStage, anim: state.anim, anchor: state.anchor, skin: state.skin,
                  accessories: state.accessories, height: height, animated: state.isAnimated)
    }

    /// A friend's slime, facing the player's. During a shared moment both
    /// play the same animation (in a meetup they slide toward each other).
    init(friend: FriendSlime, state: IslandState, height: CGFloat) {
        self.init(stage: friend.stage, anim: state.moment?.anim ?? SpriteAnim(friend.mood), anchor: state.anchor,
                  skin: friend.skin, accessories: friend.equipped ?? Accessories(), height: height,
                  animated: state.isAnimated, mirrored: true)
    }
}

/// The right side of the compact island: a small animated pixel emote, or
/// the friend you are meeting (or are in love with), facing your slime.
struct CompactBadge: View {
    let state: IslandState

    var body: some View {
        if let p = state.partner, state.moment != nil || p.inLove {
            IslandSlime(friend: p, state: state, height: 22)
        } else {
            EmoteView(reel: state.emote, anchor: state.anchor, size: 18, animated: state.isAnimated)
                .padding(.trailing, 2)
        }
    }
}

/// Friends standing next to each other, the partner first.
struct FriendGroup: View {
    let state: IslandState
    var height: CGFloat
    var limit = 3
    var names = true

    var body: some View {
        let friends = ordered
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(friends, id: \.id) { friend in
                VStack(spacing: 2) {
                    IslandSlime(friend: friend, state: state, height: friend.id == friends.first?.id ? height : height * 0.82)
                    if names {
                        Text(friend.name).font(IslandType.small).foregroundStyle(Brand.text.opacity(0.8)).lineLimit(1)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(friend.name), \(friend.owner)'s slime\(friend.inLove ? ", in love" : "")")
            }
        }
    }

    private var ordered: [FriendSlime] {
        let all = state.friends ?? []
        guard let partner = state.partner else { return Array(all.prefix(limit)) }
        return Array(([partner] + all.filter { $0.id != partner.id }).prefix(limit))
    }
}
