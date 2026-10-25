import AppIntents
import SwiftUI
import WidgetKit

typealias IslandState = SlimeActivityAttributes.ContentState

/// Five block bars, one per need, with the need's icon.
struct NeedsStrip: View {
    let state: IslandState
    var segments = 5

    var body: some View {
        HStack(spacing: 10) {
            ForEach(Need.allCases) { need in
                HStack(spacing: 4) {
                    Image(systemName: need.symbol)
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(Brand.color(for: need))
                    PixelBar(value: state.value(need), segments: segments,
                             color: Brand.color(for: need), height: 6, gap: 1.5)
                }
            }
        }
    }
}

/// A button in the island that runs an intent without opening the app.
struct IslandButton<I: LiveActivityIntent>: View {
    let intent: I
    let symbol: String
    let label: String
    let tint: Color

    var body: some View {
        Button(intent: intent) {
            VStack(spacing: 3) {
                Image(systemName: symbol).font(.system(size: 13, weight: .bold))
                Text(label).font(Brand.pixelFixed(11))
            }
            .foregroundStyle(tint)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .background(tint.opacity(0.14))
            .overlay(Rectangle().strokeBorder(tint.opacity(0.5), lineWidth: 1.5))
        }
        .buttonStyle(.plain)
    }
}

struct ActionRow: View {
    let state: IslandState

    var body: some View {
        HStack(spacing: 6) {
            if state.mood == .sleep {
                IslandButton(intent: SleepSlimeIntent(), symbol: "sun.max.fill", label: "Wecken", tint: Brand.color(for: .energy))
                IslandButton(intent: PetSlimeIntent(), symbol: "hand.raised.fill", label: "Kraulen", tint: Brand.warm)
            } else {
                IslandButton(intent: FeedSlimeIntent(), symbol: "fork.knife", label: "Essen", tint: Brand.color(for: .hunger))
                IslandButton(intent: PlaySlimeIntent(), symbol: "gamecontroller.fill", label: "Spielen", tint: Brand.color(for: .fun))
                IslandButton(intent: BathSlimeIntent(), symbol: "bubbles.and.sparkles.fill", label: "Baden", tint: Brand.color(for: .clean))
                IslandButton(intent: PetSlimeIntent(), symbol: "hand.raised.fill", label: "Kraulen", tint: Brand.warm)
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
    /// play the same animation.
    init(friend: FriendSlime, state: IslandState, height: CGFloat) {
        self.init(stage: friend.stage, anim: state.moment?.anim ?? SpriteAnim(friend.mood), anchor: state.anchor,
                  skin: friend.skin, accessories: friend.equipped ?? Accessories(), height: height,
                  animated: state.isAnimated, mirrored: true)
    }
}

/// What sits on the right of the compact island: always a small animated
/// pixel emote, or the friend you share a moment with (or are in love with).
struct CompactBadge: View {
    let state: IslandState

    var body: some View {
        if let p = state.partner {
            HStack(spacing: 1) {
                PixelItem(name: "heart").frame(width: 8, height: 7)
                    .opacity(p.inLove || state.moment != nil ? 1 : 0.4)
                IslandSlime(friend: p, state: state, height: 18)
            }
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
        HStack(alignment: .bottom, spacing: 4) {
            ForEach(friends, id: \.id) { friend in
                VStack(spacing: 2) {
                    IslandSlime(friend: friend, state: state, height: friend.id == friends.first?.id ? height : height * 0.82)
                    if names {
                        Text(friend.name).font(Brand.pixelFixed(10)).foregroundStyle(Brand.muted).lineLimit(1)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(friend.name) von \(friend.owner)\(friend.inLove ? ", verliebt" : "")")
            }
        }
    }

    private var ordered: [FriendSlime] {
        let all = state.friends ?? []
        guard let partner = state.partner else { return Array(all.prefix(limit)) }
        return Array(([partner] + all.filter { $0.id != partner.id }).prefix(limit))
    }
}
