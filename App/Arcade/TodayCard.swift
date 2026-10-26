import SwiftUI

/// Today's game at the top of the arcade: a window into its pixel world,
/// what kind of game it is, the slime's thought of the day and the quest.
struct TodayCard: View {
    @EnvironmentObject private var daily: DailyStore
    @EnvironmentObject private var store: GameStore
    let playable: Bool
    /// Stops the little scene while a game runs on top of the menu.
    var paused = false
    let play: () -> Void

    var body: some View {
        let plan = daily.plan
        let game = plan.game
        let shape = RoundedRectangle(cornerRadius: Theme.radius, style: .continuous)
        VStack(alignment: .leading, spacing: 0) {
            TodayScene(game: game, paused: paused)
                .frame(height: 140)
                .overlay(alignment: .topLeading) {
                    Text(Date.now, format: .dateTime.weekday(.wide).month(.abbreviated).day())
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Theme.ink)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(.regularMaterial, in: Capsule())
                        .padding(12)
                }
                .clipped()
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Today's game")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Theme.accent)
                    Text(game.title)
                        .font(.title2.weight(.bold))
                        .foregroundStyle(Theme.ink)
                    Text(game.blurb)
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)

                facts(game)

                if !plan.story.isEmpty {
                    thought(plan.story)
                }

                quest(plan)

                AppButton(title: daily.playedToday ? "Play Again" : "Play", systemIcon: "play.fill", action: play)
                    .disabled(!playable)
            }
            .padding(16)
        }
        .background(Theme.card, in: shape)
        .clipShape(shape)
    }

    // MARK: Pieces

    private func facts(_ game: GameSpec) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { chips(game) }
            VStack(alignment: .leading, spacing: 8) { chips(game) }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func chips(_ game: GameSpec) -> some View {
        switch game.goal {
        case .timed: chip("\(Int(game.duration)) seconds", "timer")
        case .survive: chip("Last as long as you can", "hourglass")
        }
        if game.lives > 0 {
            chip(game.lives == 1 ? "1 life" : "\(game.lives) lives", "heart.fill")
        }
        chip(controlName(game.control), controlSymbol(game.control))
    }

    private func chip(_ text: String, _ symbol: String) -> some View {
        Label(text, systemImage: symbol)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Theme.inkSoft)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Theme.well, in: RoundedRectangle(cornerRadius: Theme.smallRadius, style: .continuous))
    }

    private func controlName(_ control: GameControl) -> String {
        switch control {
        case .drag: "Drag to move"
        case .lanes: "Switch lanes"
        case .steer: "Float around"
        case .jump: "Tap to jump"
        case .flap: "Tap to fly"
        }
    }

    private func controlSymbol(_ control: GameControl) -> String {
        switch control {
        case .drag: "hand.draw"
        case .lanes: "arrow.left.arrow.right"
        case .steer: "hand.point.up.left"
        case .jump: "arrow.up"
        case .flap: "wind"
        }
    }

    /// The slime's thought of the day, in its own voice.
    private func thought(_ story: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "quote.opening")
                .font(.footnote.weight(.bold))
                .foregroundStyle(Theme.accent)
            Text(story)
                .font(Theme.voice(15))
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.well, in: RoundedRectangle(cornerRadius: Theme.smallRadius, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(store.state.slime.name) says: \(story)")
    }

    private func quest(_ plan: DailyPlan) -> some View {
        let p = store.state.questProgress(plan)
        let claimed = daily.questClaimed
        let done = p.value >= p.target
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                PixelItem(name: "flag")
                    .frame(width: 24, height: 24)
                    .padding(.top, 2)
                VStack(alignment: .leading, spacing: 6) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(plan.quest.title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Theme.ink)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 8)
                        if claimed {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(Theme.accent)
                        } else {
                            Text("\(min(p.value, p.target))/\(p.target)")
                                .font(.subheadline.weight(.semibold))
                                .monospacedDigit()
                                .foregroundStyle(Theme.inkSoft)
                        }
                    }
                    Meter(value: Double(p.value) / Double(max(1, p.target)), color: Theme.accent, height: 6)
                    Text(claimed ? "Reward collected" : "Reward: \(plan.quest.coins) coins")
                        .font(.caption)
                        .foregroundStyle(Theme.inkSoft)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Quest: \(plan.quest.title)")
            .accessibilityValue(claimed ? "Done, reward collected" : "\(min(p.value, p.target)) of \(p.target)")

            if done && !claimed {
                AppButton(title: "Collect \(plan.quest.coins) Coins", icon: "coin", kind: .secondary) {
                    daily.claimQuest()
                }
            }
        }
        .padding(12)
        .background(Theme.well, in: RoundedRectangle(cornerRadius: Theme.smallRadius, style: .continuous))
    }
}

/// A little window into today's world: its sky, ground, things and the
/// slime, gently bobbing.
private struct TodayScene: View {
    let game: GameSpec
    let paused: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let colors = game.theme.colors
        TimelineView(.animation(minimumInterval: 1.0 / 20, paused: paused || reduceMotion)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            GeometryReader { geo in
                let groundTop: CGFloat = geo.size.height - 22
                ZStack(alignment: .topLeading) {
                    GameBackdrop(top: colors.top, bottom: colors.bottom)
                    Canvas { ctx, size in
                        ctx.fill(Path(CGRect(x: 0, y: groundTop, width: size.width, height: size.height - groundTop)),
                                 with: .color(Color(hex: colors.ground)))
                        ctx.fill(Path(CGRect(x: 0, y: groundTop, width: size.width, height: 3)),
                                 with: .color(.white.opacity(0.25)))
                        let things = Array(game.entities.prefix(5))
                        let ground = Double(groundTop)
                        let width = Double(size.width)
                        for (i, e) in things.enumerated() {
                            let slot = (Double(i) + 1) / Double(things.count + 1)
                            let x: Double = width * (0.3 + 0.66 * slot)
                            let bob: Double = reduceMotion ? 0 : sin(t * 2 + Double(i) * 1.7) * 4
                            let lift: Double = 28 + Double(i % 2) * 30
                            let y: Double = ground - lift + bob
                            let side: Double = 26 * e.size
                            let rect = CGRect(x: x - side / 2, y: y - side / 2, width: side, height: side)
                            if e.role == .power {
                                ctx.fill(Path(ellipseIn: rect.insetBy(dx: -6, dy: -6)), with: .color(Theme.gold.opacity(0.3)))
                            }
                            ctx.drawSprite(e.sprite, in: rect)
                        }
                    }
                    GameSlime(anim: .play, height: 46, time: t)
                        .position(x: geo.size.width * 0.15, y: groundTop - 22)
                }
            }
        }
    }
}
