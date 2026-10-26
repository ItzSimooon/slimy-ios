import AppIntents
import SwiftUI
import WidgetKit

typealias IslandState = SlimeActivityAttributes.ContentState

/// Text in the island and on the Lock Screen card: SF Rounded text styles.
/// Both have a fixed maximum height, so their views cap Dynamic Type at
/// `IslandType.largest`.
enum IslandType {
    static let largest = DynamicTypeSize.large

    static let title = Font.system(.headline, design: .rounded)
    static let line = Font.system(.subheadline, design: .rounded, weight: .semibold)
    static let name = Font.system(.caption, design: .rounded, weight: .semibold)
    static let small = Font.system(.caption, design: .rounded, weight: .semibold)
    static let badge = Font.system(.caption, design: .rounded, weight: .bold)
    static let button = Font.system(.caption2, design: .rounded, weight: .semibold)
    /// The slime speaking, on the Lock Screen card where there is room for it.
    static let voice = Brand.voice(15, relativeTo: .subheadline)
}

/// Colours inside the Dynamic Island, which is black in light and dark mode.
enum IslandInk {
    static let primary = Color.white
    static let tile = Color.white.opacity(0.12)
}

/// The five needs as rings, each one a button that looks after it without
/// opening the app: Feed, Play, Sleep (Wake while asleep), Bath, Pet. On the
/// dimmed Lock Screen (Always On, StandBy at night) only the rings remain.
struct NeedActions: View {
    enum Surface { case island, card }

    let state: IslandState
    var surface: Surface = .island
    var interactive = true

    var body: some View {
        HStack(spacing: 6) {
            ForEach(Need.allCases) { need in
                tile(need)
            }
        }
    }

    @ViewBuilder
    private func tile(_ need: Need) -> some View {
        let face = NeedTile(need: need, value: Double(state.value(need)), title: title(need),
                            surface: surface, raised: interactive)
        if interactive {
            switch need {
            case .hunger: Button(intent: FeedSlimeIntent()) { face }.buttonStyle(.plain)
            case .fun: Button(intent: PlaySlimeIntent()) { face }.buttonStyle(.plain)
            case .energy: Button(intent: SleepSlimeIntent()) { face }.buttonStyle(.plain)
            case .clean: Button(intent: BathSlimeIntent()) { face }.buttonStyle(.plain)
            case .love: Button(intent: PetSlimeIntent()) { face }.buttonStyle(.plain)
            }
        } else {
            face
        }
    }

    /// The action on a button, the need's name when it's only a ring.
    private func title(_ need: Need) -> String {
        guard interactive else { return need.title }
        switch need {
        case .hunger: return "Feed"
        case .fun: return "Play"
        case .energy: return state.mood == .sleep ? "Wake" : "Sleep"
        case .clean: return "Bath"
        case .love: return "Pet"
        }
    }
}

/// One need: its ring above a short label, on a rounded tile when tappable.
private struct NeedTile: View {
    let need: Need
    let value: Double
    let title: String
    let surface: NeedActions.Surface
    let raised: Bool

    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: 14, style: .continuous) }

    var body: some View {
        VStack(spacing: 3) {
            NeedDial(need: need, value: value, size: 24, lineWidth: 3)
            Text(title)
                .font(IslandType.button)
                .foregroundStyle(surface == .island ? IslandInk.primary : Color.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, minHeight: 44)
        .background {
            if raised {
                shape.fill(surface == .island ? IslandInk.tile : Color.primary.opacity(0.08))
            }
        }
        .contentShape(shape)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(spokenValue)
    }

    /// "Food 42 percent" on a button, "42 percent" when the label is the need.
    private var spokenValue: String {
        raised ? "\(need.title) \(Int(value)) percent" : "\(Int(value)) percent"
    }
}

extension IslandSlime {
    /// The player's slime as the activity state describes it. VoiceOver says
    /// its name unless `label` says more.
    init(_ state: IslandState, height: CGFloat, label: String? = nil) {
        self.init(stage: state.slimeStage, anim: state.anim, anchor: state.anchor, skin: state.skin,
                  accessories: state.accessories, height: height, animated: state.isAnimated,
                  label: label ?? state.name)
    }

    /// A friend's slime, facing the player's. During a shared moment both
    /// play the same animation (in a meetup they slide toward each other).
    init(friend: FriendSlime, state: IslandState, height: CGFloat) {
        self.init(stage: friend.stage, anim: state.moment?.anim ?? SpriteAnim(friend.mood), anchor: state.anchor,
                  skin: friend.skin, accessories: friend.equipped ?? Accessories(), height: height,
                  animated: state.isAnimated, mirrored: true,
                  label: "\(friend.name), \(friend.owner)'s slime\(friend.inLove ? ", in love" : "")")
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

    var body: some View {
        let friends = ordered
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(friends, id: \.id) { friend in
                IslandSlime(friend: friend, state: state, height: friend.id == friends.first?.id ? height : height * 0.82)
            }
        }
    }

    private var ordered: [FriendSlime] {
        let all = state.friends ?? []
        guard let partner = state.partner else { return Array(all.prefix(limit)) }
        return Array(([partner] + all.filter { $0.id != partner.id }).prefix(limit))
    }
}
