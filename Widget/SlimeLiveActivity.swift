import ActivityKit
import SwiftUI
import WidgetKit

struct SlimeLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SlimeActivityAttributes.self) { context in
            LockScreenSlime(state: context.state)
                .activityBackgroundTint(Brand.bg)
                .activitySystemActionForegroundColor(Brand.text)
        } dynamicIsland: { context in
            let s = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    VStack(alignment: .leading, spacing: 3) {
                        IslandSlime(s, height: 44)
                        Text(s.name)
                            .font(Brand.pixelFixed(13))
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
                        NeedsStrip(state: s)
                        ActionRow(state: s)
                    }
                    .padding(.horizontal, 4)
                    .padding(.top, 2)
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

private struct ExpandedTrailing: View {
    let state: IslandState

    var body: some View {
        if let p = state.partner {
            VStack(alignment: .trailing, spacing: 3) {
                HStack(spacing: 2) {
                    PixelItem(name: "heart")
                        .frame(width: 10, height: 8)
                        .opacity(p.inLove ? 1 : 0.4)
                    IslandSlime(partner: p, anchor: state.anchor, height: 34, animated: state.animated)
                }
                Text(p.inLove ? "verliebt" : "\(p.love) % liebe")
                    .font(Brand.pixelFixed(11))
                    .foregroundStyle(Brand.warm)
            }
        } else {
            VStack(alignment: .trailing, spacing: 6) {
                Text("LV \(state.level)")
                    .font(Brand.pixelFixed(13))
                    .foregroundStyle(Brand.bg)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Brand.accent(state.skin))
                HStack(spacing: 4) {
                    PixelItem(name: "coin").frame(width: 10, height: 10)
                    Text("\(state.coins)")
                        .font(Brand.pixelFixed(13))
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
            IslandSlime(state, height: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(state.name).font(Brand.pixelFixed(13)).foregroundStyle(Brand.text)
                Text(state.line).font(Brand.body(11, .medium)).foregroundStyle(Brand.muted).lineLimit(2)
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
        VStack(spacing: 10) {
            HStack(alignment: .bottom, spacing: 12) {
                // The slime on its little patch of ground.
                VStack(spacing: 0) {
                    HStack(alignment: .bottom, spacing: 2) {
                        IslandSlime(state, height: 56)
                        if let p = state.partner {
                            PixelItem(name: "heart")
                                .frame(width: 12, height: 9)
                                .opacity(p.inLove ? 1 : 0.4)
                                .padding(.bottom, 30)
                            IslandSlime(partner: p, anchor: state.anchor, height: 48, animated: state.animated)
                        }
                    }
                    Rectangle().fill(Brand.line).frame(height: 3)
                }
                .fixedSize()

                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Text(state.name)
                            .font(Brand.pixelFixed(18))
                            .foregroundStyle(Brand.text)
                        Text("LV \(state.level)")
                            .font(Brand.pixelFixed(12))
                            .foregroundStyle(Brand.accent(state.skin))
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
