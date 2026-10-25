import AppIntents
import SwiftUI
import WidgetKit

typealias IslandState = SlimeActivityAttributes.ContentState

/// Five slim bars, one per need.
struct NeedsStrip: View {
    let state: IslandState
    var compact = false

    var body: some View {
        HStack(spacing: compact ? 6 : 10) {
            ForEach(Need.allCases) { need in
                let v = state.value(need)
                HStack(spacing: 4) {
                    Image(systemName: need.symbol)
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(Brand.color(for: need))
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Color.white.opacity(0.12))
                            Capsule()
                                .fill(Brand.color(for: need))
                                .frame(width: max(3, geo.size.width * CGFloat(v) / 100))
                        }
                    }
                    .frame(height: 5)
                }
                .opacity(v < 25 ? 1 : 0.9)
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
            HStack(spacing: 4) {
                Image(systemName: symbol).font(.system(size: 12, weight: .bold))
                Text(label).font(Brand.display(12, .bold))
            }
            .foregroundStyle(tint)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 7)
            .background(tint.opacity(0.16), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

struct ActionRow: View {
    let state: IslandState

    var body: some View {
        HStack(spacing: 6) {
            if state.mood == .sleep {
                IslandButton(intent: SleepSlimeIntent(), symbol: "sun.max.fill", label: "Wake", tint: Brand.gold)
                IslandButton(intent: PetSlimeIntent(), symbol: "hand.raised.fill", label: "Pet", tint: Brand.warm)
            } else {
                IslandButton(intent: FeedSlimeIntent(), symbol: "fork.knife", label: "Feed", tint: Brand.color(for: .hunger))
                IslandButton(intent: PlaySlimeIntent(), symbol: "gamecontroller.fill", label: "Play", tint: Brand.color(for: .fun))
                IslandButton(intent: BathSlimeIntent(), symbol: "bubbles.and.sparkles.fill", label: "Bath", tint: Brand.color(for: .clean))
                IslandButton(intent: PetSlimeIntent(), symbol: "hand.raised.fill", label: "Pet", tint: Brand.warm)
            }
        }
    }
}

/// What sits on the right of the compact island.
struct CompactBadge: View {
    let state: IslandState

    var body: some View {
        if let p = state.partner, p.inLove {
            ZStack(alignment: .topTrailing) {
                AnimatedSlime(partner: p, anchor: state.anchor, height: 18)
                Image(systemName: "heart.fill")
                    .font(.system(size: 7, weight: .black))
                    .foregroundStyle(Brand.warm)
                    .offset(x: 2, y: -2)
            }
        } else if let event = state.event {
            Image(systemName: event.symbol)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(eventTint(event))
        } else if let wish = state.wish {
            NeedRing(need: wish, value: state.value(wish), size: 22)
        } else {
            Text("LV\(state.level)")
                .font(Brand.mono(11, .bold))
                .foregroundStyle(state.skin == .void ? Brand.hyprGlow : state.skin.body)
        }
    }

    private func eventTint(_ e: PhoneEvent) -> Color {
        switch e.reaction {
        case .melt: Brand.danger
        case .zap: Brand.gold
        case .confused: Brand.cool
        default: Brand.hyprGlow
        }
    }
}
