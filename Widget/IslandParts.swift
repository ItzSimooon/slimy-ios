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

/// What sits on the right of the compact island.
struct CompactBadge: View {
    let state: IslandState

    var body: some View {
        if let p = state.partner, p.inLove {
            HStack(spacing: 1) {
                PixelItem(name: "heart").frame(width: 8, height: 7)
                IslandSlime(partner: p, anchor: state.anchor, height: 18, animated: state.animated)
            }
        } else if let event = state.event {
            Image(systemName: event.symbol)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(eventTint(event))
        } else if let wish = state.wish {
            HStack(spacing: 3) {
                Image(systemName: wish.symbol)
                    .font(.system(size: 11, weight: .bold))
                Text("\(state.value(wish))")
                    .font(Brand.pixelFixed(13))
            }
            .foregroundStyle(state.value(wish) < 25 ? Brand.danger : Brand.color(for: wish))
        } else {
            Text("LV\(state.level)")
                .font(Brand.pixelFixed(13))
                .foregroundStyle(Brand.accent(state.skin))
        }
    }

    private func eventTint(_ e: PhoneEvent) -> Color {
        switch e.reaction {
        case .melt: Brand.danger
        case .zap: Brand.gold
        case .confused: Brand.cool
        default: Brand.slime
        }
    }
}
