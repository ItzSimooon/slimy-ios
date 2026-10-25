import SwiftUI

/// The slime in the app: animated, jiggly, pokeable. Tap to poke it,
/// rub it to pet it, pull it to stretch it.
struct LiveSlimeView: View {
    @EnvironmentObject private var store: SlimeStore
    var height: CGFloat = 200
    var interactive = true

    @State private var poke: CGFloat = 0
    @State private var drag: CGSize = .zero
    @State private var rubbed: CGFloat = 0
    @State private var lastDrag: CGSize = .zero
    @State private var taps = 0
    @State private var hearts: [FloatingHeart] = []

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30)) { context in
            let now = context.date
            let t = now.timeIntervalSinceReferenceDate
            let s = store.state
            let frames = animation(for: s, at: now).frames
            let frame = frames[Int(t * 2.5) % frames.count]
            let breathe = CGFloat(sin(t * 2.4)) * (s.isAsleep ? 0.035 : 0.022)
            let pull = max(-0.3, min(0.3, -drag.height / 500))

            ZStack(alignment: .bottom) {
                // Flat pixel shadow under the slime.
                Rectangle()
                    .fill(Color.black.opacity(0.35))
                    .frame(width: height * 0.8, height: height * 0.05)
                    .offset(y: height * 0.025)

                PixelSlime(frame: frame, skin: s.skin)
                    .frame(width: height * SlimeFont.aspect, height: height)
                    .scaleEffect(x: 1 + breathe + poke * 0.14 - pull * 0.45,
                                 y: 1 - breathe - poke * 0.16 + pull,
                                 anchor: .bottom)
                    .offset(x: drag.width * 0.18)

                ForEach(hearts) { heart in
                    let age = now.timeIntervalSince(heart.born)
                    PixelItem(name: "heart")
                        .frame(width: 22, height: 17)
                        .offset(x: heart.x + CGFloat(sin(age * 5)) * 6, y: -height * 0.8 - CGFloat(age) * 60)
                        .opacity(max(0, 1 - age / 1.4))
                }
            }
        }
        .frame(width: height * SlimeFont.aspect * 1.1, height: height * 1.1, alignment: .bottom)
        .contentShape(Rectangle())
        .gesture(dragGesture, isEnabled: interactive)
        .simultaneousGesture(TapGesture().onEnded { pokeIt() }, isEnabled: interactive)
        .sensoryFeedback(.impact(flexibility: .soft, intensity: 0.8), trigger: taps)
        .accessibilityElement()
        .accessibilityLabel("\(store.state.name), dein Slime")
        .accessibilityHint("Tippen zum Anstupsen, reiben zum Kraulen")
        .accessibilityAction(named: "Kraulen") { pet() }
    }

    private func animation(for s: SlimeState, at now: Date) -> SpriteAnim {
        if let reaction = SpriteAnim(s.reaction), now.timeIntervalSince(s.reactionAt) < 5 {
            return reaction
        }
        return SpriteAnim(s.mood)
    }

    private var dragGesture: some Gesture {
        DragGesture(minimumDistance: 3)
            .onChanged { value in
                drag = CGSize(width: max(-120, min(120, value.translation.width)),
                              height: max(-160, min(160, value.translation.height)))
                let dx = value.translation.width - lastDrag.width
                let dy = value.translation.height - lastDrag.height
                rubbed += sqrt(dx * dx + dy * dy)
                lastDrag = value.translation
                if rubbed > 420 {
                    rubbed = 0
                    pet()
                }
            }
            .onEnded { _ in
                lastDrag = .zero
                withAnimation(.interpolatingSpring(stiffness: 220, damping: 7)) { drag = .zero }
            }
    }

    private func pokeIt() {
        taps += 1
        poke = 1
        withAnimation(.interpolatingSpring(stiffness: 320, damping: 6)) { poke = 0 }
        if taps % 5 == 0 { pet() }
    }

    private func pet() {
        store.act(.pet)
        let now = Date.now
        hearts.append(FloatingHeart(x: .random(in: -50...50), born: now))
        hearts.removeAll { now.timeIntervalSince($0.born) > 1.5 }
    }
}

private struct FloatingHeart: Identifiable {
    let id = UUID()
    let x: CGFloat
    let born: Date
}
