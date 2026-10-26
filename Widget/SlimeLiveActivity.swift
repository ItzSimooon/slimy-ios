import ActivityKit
import SwiftUI
import WidgetKit

/// The slime in the Dynamic Island and on the Lock Screen. The slime and the
/// emotes are animated by iOS itself (timer fonts, see AnimatedSlime.swift);
/// everything around them is native chrome.
struct SlimeLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SlimeActivityAttributes.self) { context in
            // Once a shared moment's time is up the activity goes stale; draw solo.
            let s = context.isStale ? context.state.solo : context.state
            LockScreenSlime(state: s)
        } dynamicIsland: { context in
            let s = context.isStale ? context.state.solo : context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    IslandPortrait(name: s.name, alignment: .leading) {
                        IslandSlime(s, height: 34)
                    }
                    .padding(.leading, 6)
                    .dynamicTypeSize(...IslandType.largest)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    ExpandedTrailing(state: s)
                        .padding(.trailing, 6)
                        .dynamicTypeSize(...IslandType.largest)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    // The expanded island is at most 160 pt tall; slime, line,
                    // needs and buttons stay inside it on every iPhone.
                    VStack(spacing: 6) {
                        Text(s.line)
                            .font(IslandType.line)
                            .foregroundStyle(IslandInk.primary)
                            .multilineTextAlignment(.center)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .frame(maxWidth: .infinity)
                        NeedActions(state: s, surface: .island)
                    }
                    .padding(.horizontal, 4)
                    .dynamicTypeSize(...IslandType.largest)
                }
            } compactLeading: {
                IslandSlime(s, height: 22, label: "\(s.name): \(s.line)")
            } compactTrailing: {
                CompactBadge(state: s)
            } minimal: {
                IslandSlime(s, height: 18, label: "\(s.name): \(s.line)")
            }
            .keylineTint(Brand.accent(s.skin))
            .widgetURL(URL(string: "slimy://home"))
        }
        .supplementalActivityFamilies([.small, .medium])
    }
}

/// A slime with its name underneath, in the expanded island.
private struct IslandPortrait<Content: View>: View {
    let name: String
    var alignment: HorizontalAlignment
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: alignment, spacing: 4) {
            content
            Text(name)
                .font(IslandType.name)
                .foregroundStyle(IslandInk.primary)
                .lineLimit(1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(name)
    }
}

/// Top right of the expanded island: the friend you're sharing a moment
/// with, your friends, or your slime's level and coins.
private struct ExpandedTrailing: View {
    let state: IslandState

    var body: some View {
        if state.moment != nil, let partner = state.partner {
            // A shared moment: the friend faces you, playing the same animation.
            IslandPortrait(name: partner.name, alignment: .trailing) {
                IslandSlime(friend: partner, state: state, height: 34)
            }
        } else if let friends = state.friends, !friends.isEmpty {
            VStack(alignment: .trailing, spacing: 4) {
                FriendGroup(state: state, height: 28, limit: 2)
                Text(caption)
                    .font(IslandType.small)
                    .foregroundStyle(Brand.warm)
                    .lineLimit(1)
            }
        } else {
            VStack(alignment: .trailing, spacing: 6) {
                Text("Level \(state.level)")
                    .font(IslandType.badge)
                    .foregroundStyle(Brand.bg)
                    .lineLimit(1)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Brand.accent(state.skin), in: Capsule())
                HStack(spacing: 4) {
                    PixelItem(name: "coin")
                        .frame(width: 13, height: 13)
                    Text(state.coins, format: .number)
                        .font(IslandType.badge)
                        .monospacedDigit()
                        .foregroundStyle(Brand.gold)
                        .lineLimit(1)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(state.coins) coins")
            }
        }
    }

    private var caption: String {
        if let p = state.partner { return p.inLove ? "in love" : "\(p.love)% love" }
        let n = state.friends?.count ?? 0
        return n == 1 ? "1 friend" : "\(n) friends"
    }
}

/// Lock Screen, StandBy and the Apple Watch Smart Stack. Uses the system's
/// Live Activity background, so it follows light and dark mode.
struct LockScreenSlime: View {
    let state: IslandState
    @Environment(\.activityFamily) private var family
    @Environment(\.isLuminanceReduced) private var dimmed

    var body: some View {
        switch family {
        case .small:
            watch
        default:
            card
        }
    }

    private var watch: some View {
        HStack(spacing: 8) {
            // The name and line next to it say who this is.
            IslandSlime(state, height: 30)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(state.name)
                    .font(IslandType.title)
                    .lineLimit(1)
                Text(state.line)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(8)
        .dynamicTypeSize(...IslandType.largest)
    }

    private var card: some View {
        VStack(spacing: 10) {
            HStack(alignment: .center, spacing: 12) {
                scene
                VStack(alignment: .leading, spacing: 2) {
                    header
                    Text(state.line)
                        .font(IslandType.voice)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityLabel("\(state.name) says: \(state.line)")
                }
                Spacer(minLength: 0)
            }
            NeedActions(state: state, surface: .card, interactive: !dimmed)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .dynamicTypeSize(...IslandType.largest)
    }

    /// The slime, and the friends with it, standing on a soft shadow.
    private var scene: some View {
        HStack(alignment: .bottom, spacing: 0) {
            // The header next to it names the player's slime; friends speak for themselves.
            IslandSlime(state, height: 50)
                .accessibilityHidden(true)
            if state.moment != nil, let partner = state.partner {
                IslandSlime(friend: partner, state: state, height: 50)
            } else if state.friends?.isEmpty == false {
                FriendGroup(state: state, height: 40, limit: 2)
                    .padding(.leading, 4)
            }
        }
        .padding(.bottom, 3)
        .background(alignment: .bottom) {
            Capsule()
                .fill(Color.primary.opacity(0.1))
                .frame(height: 6)
                .accessibilityHidden(true)
        }
        .fixedSize()
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(state.name)
                .font(IslandType.title)
                .lineLimit(1)
            if state.moment != nil, let partner = state.partner {
                HStack(spacing: 3) {
                    Image(systemName: "heart.fill")
                        .foregroundStyle(.pink)
                    Text("with \(partner.name)")
                        .foregroundStyle(.secondary)
                }
                .font(IslandType.small)
                .lineLimit(1)
            } else {
                Text("Level \(state.level)")
                    .font(IslandType.small)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            if let event = state.event {
                Image(systemName: event.symbol)
                    .font(IslandType.small)
                    .foregroundStyle(.blue)
                    .accessibilityLabel(event.title)
            }
        }
    }
}
