import ActivityKit
import SwiftUI
import WidgetKit

struct SlimeLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SlimeActivityAttributes.self) { context in
            // Once a shared moment's time is up the activity goes stale; draw solo.
            let s = context.isStale ? context.state.solo : context.state
            LockScreenSlime(state: s)
                .activityBackgroundTint(Brand.bg)
                .activitySystemActionForegroundColor(Brand.text)
        } dynamicIsland: { context in
            let s = context.isStale ? context.state.solo : context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Portrait(name: s.name, alignment: .leading) {
                        IslandSlime(s, height: 44)
                    }
                    .padding(.leading, 6)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    ExpandedTrailing(state: s)
                        .padding(.trailing, 6)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 9) {
                        Text(s.line)
                            .font(IslandType.line)
                            .foregroundStyle(Brand.text)
                            .multilineTextAlignment(.center)
                            .lineLimit(2)
                            .minimumScaleFactor(0.9)
                            .frame(maxWidth: .infinity)
                        NeedsStrip(state: s)
                        ActionRow(state: s)
                    }
                    .padding(.horizontal, 6)
                }
            } compactLeading: {
                IslandSlime(s, height: 22)
            } compactTrailing: {
                CompactBadge(state: s)
            } minimal: {
                IslandSlime(s, height: 18)
            }
            .keylineTint(Brand.accent(s.skin))
            .widgetURL(URL(string: "slimy://home"))
        }
        .supplementalActivityFamilies([.small, .medium])
    }
}

/// A slime with its name underneath.
private struct Portrait<Slime: View>: View {
    let name: String
    var alignment: HorizontalAlignment
    @ViewBuilder var slime: Slime

    var body: some View {
        VStack(alignment: alignment, spacing: 4) {
            slime
            Text(name)
                .font(IslandType.name)
                .foregroundStyle(Brand.text)
                .lineLimit(1)
        }
    }
}

private struct ExpandedTrailing: View {
    let state: IslandState

    var body: some View {
        if state.moment != nil, let partner = state.partner {
            // A shared moment: the friend faces you, playing the same animation.
            Portrait(name: partner.name, alignment: .trailing) {
                IslandSlime(friend: partner, state: state, height: 44)
            }
        } else if let friends = state.friends, !friends.isEmpty {
            VStack(alignment: .trailing, spacing: 4) {
                FriendGroup(state: state, height: 32, limit: 2, names: false)
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
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Brand.accent(state.skin))
                HStack(spacing: 4) {
                    PixelItem(name: "coin").frame(width: 12, height: 12)
                    Text("\(state.coins)")
                        .font(IslandType.badge)
                        .foregroundStyle(Brand.gold)
                }
            }
        }
    }

    private var caption: String {
        if let p = state.partner { return p.inLove ? "in love" : "\(p.love)% love" }
        let n = state.friends?.count ?? 0
        return n == 1 ? "1 friend" : "\(n) friends"
    }
}

/// Lock Screen, StandBy and the Apple Watch Smart Stack.
struct LockScreenSlime: View {
    let state: IslandState
    @Environment(\.activityFamily) private var family
    @Environment(\.isLuminanceReduced) private var dimmed

    var body: some View {
        switch family {
        case .small:
            watch
        default:
            full
        }
    }

    private var watch: some View {
        HStack(spacing: 8) {
            IslandSlime(state, height: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(state.name).font(IslandType.name).foregroundStyle(Brand.text)
                Text(state.line).font(IslandType.small).foregroundStyle(Brand.text.opacity(0.85)).lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(8)
    }

    @ViewBuilder
    private var full: some View {
        if state.probe == true {
            TimerProbe(anchor: state.anchor, color: state.skin.body)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            card
        }
    }

    private var card: some View {
        VStack(spacing: 12) {
            HStack(alignment: .center, spacing: 14) {
                // The slime (and friends) on a little patch of ground.
                VStack(spacing: 0) {
                    HStack(alignment: .bottom, spacing: 0) {
                        IslandSlime(state, height: 54)
                        if state.moment != nil, let partner = state.partner {
                            IslandSlime(friend: partner, state: state, height: 54)
                        } else if state.friends?.isEmpty == false {
                            FriendGroup(state: state, height: 44, limit: 2, names: false)
                                .padding(.leading, 4)
                        }
                    }
                    Rectangle().fill(Brand.line).frame(height: 3)
                }
                .fixedSize()

                VStack(alignment: .leading, spacing: 5) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(state.name)
                            .font(Brand.pixelFixed(18))
                            .foregroundStyle(Brand.text)
                            .lineLimit(1)
                        Text("Lv \(state.level)")
                            .font(IslandType.small)
                            .foregroundStyle(Brand.accent(state.skin))
                        if let event = state.event {
                            Image(systemName: event.symbol)
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(Brand.cool)
                        }
                    }
                    Text(state.line)
                        .font(IslandType.line)
                        .foregroundStyle(Brand.text)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                    if state.moment != nil, let partner = state.partner {
                        Text("with \(partner.name)")
                            .font(IslandType.small)
                            .foregroundStyle(Brand.warm)
                    }
                }
                Spacer(minLength: 0)
            }
            NeedsStrip(state: state)
            if !dimmed {
                ActionRow(state: state)
            }
        }
        .padding(14)
    }
}
