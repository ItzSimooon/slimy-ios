import ActivityKit
import SwiftUI
import WidgetKit

struct SlimeLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SlimeActivityAttributes.self) { context in
            LockScreenSlime(state: context.state)
                .activityBackgroundTint(Brand.void.opacity(0.92))
                .activitySystemActionForegroundColor(Brand.hyprGlow)
        } dynamicIsland: { context in
            let s = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    VStack(alignment: .leading, spacing: 2) {
                        AnimatedSlime(s, height: 44)
                        Text(s.name)
                            .font(Brand.display(12, .bold))
                            .foregroundStyle(Brand.text)
                            .lineLimit(1)
                    }
                    .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    ExpandedTrailing(state: s)
                        .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    SpeechBubble(text: s.line, size: 12)
                        .padding(.top, 4)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 8) {
                        NeedsStrip(state: s, compact: true)
                        ActionRow(state: s)
                    }
                    .padding(.horizontal, 4)
                    .padding(.top, 2)
                }
            } compactLeading: {
                AnimatedSlime(s, height: 22)
            } compactTrailing: {
                CompactBadge(state: s)
            } minimal: {
                AnimatedSlime(s, height: 18)
            }
            .keylineTint(s.skin == .void ? Brand.hypr : s.skin.body)
            .widgetURL(URL(string: "slimy://home"))
        }
        .supplementalActivityFamilies([.small, .medium])
    }
}

private struct ExpandedTrailing: View {
    let state: IslandState

    var body: some View {
        if let p = state.partner {
            VStack(alignment: .trailing, spacing: 2) {
                HStack(spacing: 2) {
                    Image(systemName: p.inLove ? "heart.fill" : "heart")
                        .font(.system(size: 10, weight: .black))
                        .foregroundStyle(Brand.warm)
                    AnimatedSlime(partner: p, anchor: state.anchor, height: 34)
                }
                Text(p.inLove ? "in love" : "\(p.love)% love")
                    .font(Brand.mono(10, .bold))
                    .foregroundStyle(Brand.warm)
            }
        } else {
            VStack(alignment: .trailing, spacing: 4) {
                Text("LV \(state.level)")
                    .font(Brand.mono(11, .bold))
                    .foregroundStyle(Brand.void)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Brand.hyprGradient, in: Capsule())
                HStack(spacing: 3) {
                    Circle().fill(Brand.gold).frame(width: 8, height: 8)
                    Text("\(state.coins)")
                        .font(Brand.mono(11, .bold))
                        .foregroundStyle(Brand.gold)
                }
            }
        }
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
        HStack(spacing: 6) {
            AnimatedSlime(state, height: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(state.name).font(Brand.display(13, .bold)).foregroundStyle(Brand.text)
                Text(state.line).font(Brand.display(11, .medium)).foregroundStyle(Brand.muted).lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(8)
    }

    private var full: some View {
        ZStack {
            SkyBackground(date: state.anchor)
                .opacity(dimmed ? 0.35 : 1)
            VStack(spacing: 10) {
                HStack(alignment: .bottom, spacing: 10) {
                    HStack(alignment: .bottom, spacing: 0) {
                        AnimatedSlime(state, height: 60)
                        if let p = state.partner {
                            Image(systemName: "heart.fill")
                                .font(.system(size: 12, weight: .black))
                                .foregroundStyle(Brand.warm)
                                .opacity(p.inLove ? 1 : 0.45)
                                .padding(.bottom, 30)
                            AnimatedSlime(partner: p, anchor: state.anchor, height: 52)
                        }
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 6) {
                            Text(state.name)
                                .font(Brand.display(17))
                                .foregroundStyle(Brand.text)
                            Text("LV \(state.level)")
                                .font(Brand.mono(10, .bold))
                                .foregroundStyle(Brand.hyprGlow)
                            if let event = state.event {
                                Image(systemName: event.symbol)
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundStyle(Brand.cool)
                            }
                        }
                        SpeechBubble(text: state.line, size: 13)
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
}
