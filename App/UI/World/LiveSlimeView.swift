import SwiftUI

/// The slime in the app: animated, jiggly, pokeable. Tap to poke it, rub it
/// to pet it, pull it to stretch it. With Reduce Motion it holds still in
/// the pose that fits, checked once a second so reactions still end.
struct LiveSlimeView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var height: CGFloat = 200
    var interactive = true
    /// Shows a specific slime instead of the player's (friends, previews).
    var override: Slime? = nil
    /// Whether petting shows its line as a toast. Off where the slime's
    /// speech bubble is already on screen.
    var showsToast = true

    @State private var poke: CGFloat = 0
    @State private var drag: CGSize = .zero
    @State private var rubbed: CGFloat = 0
    @State private var lastDrag: CGSize = .zero
    @State private var taps = 0
    @State private var hearts: [FloatingHeart] = []

    var body: some View {
        Group {
            if reduceMotion {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    slime(at: context.date, still: true)
                }
            } else {
                TimelineView(.animation(minimumInterval: 1 / 30)) { context in
                    slime(at: context.date, still: false)
                }
            }
        }
        .frame(width: height * SlimeFont.aspect * 1.1, height: height * 1.1, alignment: .bottom)
        .contentShape(Rectangle())
        .gesture(dragGesture, isEnabled: interactive)
        .simultaneousGesture(TapGesture().onEnded { pokeIt() }, isEnabled: interactive)
        .accessibilityElement()
        .accessibilityLabel("\((override ?? store.state.slime).name), your slime")
        .accessibilityHint(interactive ? "Tap to poke, rub to pet" : "")
        .accessibilityActions {
            // Only where it can be touched; elsewhere it's a picture.
            if interactive {
                Button("Pet") { pet() }
            }
        }
    }

    @ViewBuilder
    private func slime(at now: Date, still: Bool) -> some View {
        let t = now.timeIntervalSinceReferenceDate
        let s = override ?? store.state.slime
        let anim = animation(for: s, at: now)
        let frames = SlimeSprites.frames(s.stage, anim)
        let anchors = SlimeSprites.anchors(s.stage, anim)
        let i = (still ? anim.posterIndex(at: now) : Int(t * 2.5)) % max(1, frames.count)
        let breathe = still ? 0 : CGFloat(sin(t * 2.4)) * (s.isAsleep ? 0.035 : 0.022)
        let pull = max(-0.3, min(0.3, -drag.height / 500))
        let worn = outfit(for: s, at: now)

        ZStack(alignment: .bottom) {
            Rectangle()
                .fill(Color.black.opacity(0.18))
                .frame(width: height * 0.8, height: max(3, height * 0.04))

            PixelSlime(frame: frames[min(i, frames.count - 1)], skin: s.skin, accessories: worn,
                       stage: s.stage, anchor: i < anchors.count ? anchors[i] : nil)
                .frame(width: height * SlimeFont.aspect, height: height)
                .scaleEffect(x: 1 + breathe + poke * 0.14 - pull * 0.45,
                             y: 1 - breathe - poke * 0.16 + pull,
                             anchor: .bottom)
                .offset(x: drag.width * 0.18)

            if let effect = s.equipped.effect, !still {
                EffectParticles(effect: effect, t: t, height: height)
            }

            ForEach(hearts) { heart in
                let age = now.timeIntervalSince(heart.born)
                PixelItem(name: "heart")
                    .frame(width: 22, height: 17)
                    .offset(x: heart.x, y: -height * 0.8 - CGFloat(age) * 60)
                    .opacity(max(0, 1 - age / 1.4))
            }
        }
    }

    /// What it wears: its own things, plus a party hat on its birthday if
    /// its head is free.
    private func outfit(for s: Slime, at now: Date) -> Accessories {
        var worn = s.equipped
        if override == nil, worn.hat == nil, store.state.isBirthday(at: now) {
            worn.hat = "hat.party"
        }
        return worn
    }

    private func animation(for s: Slime, at now: Date) -> SpriteAnim {
        if let reaction = SpriteAnim(s.reaction), now.timeIntervalSince(s.reactionAt) < 5 {
            return reaction
        }
        if s.mood == .idle, !s.isAsleep {
            // Idle variety: stretch in the morning, blush when close, curious now and then.
            let cycle = Int(now.timeIntervalSinceReferenceDate / 9) % 9
            let hour = Calendar.current.component(.hour, from: now)
            if cycle == 3, (6..<10).contains(hour) { return .stretch }
            if cycle == 5, s.bond >= 50 { return .blush }
            if cycle == 7, s.personality.curious > 0.5 { return .curious }
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
                let settle = reduceMotion ? Animation.easeOut(duration: 0.15)
                                          : Animation.interpolatingSpring(stiffness: 220, damping: 7)
                withAnimation(settle) { drag = .zero }
            }
    }

    private func pokeIt() {
        taps += 1
        Feedback.shared.play(.pop)
        if !reduceMotion {
            poke = 1
            withAnimation(.interpolatingSpring(stiffness: 320, damping: 6)) { poke = 0 }
        }
        if taps % 5 == 0 { pet() }
    }

    private func pet() {
        guard override == nil else { return }
        store.pet(toast: showsToast)
        // Floating hearts are motion; with Reduce Motion the pet reaction says it.
        guard !reduceMotion else { return }
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

/// Particles for the equipped effect (sparkle, hearts, bubbles).
struct EffectParticles: View {
    let effect: String
    let t: Double
    let height: CGFloat

    var body: some View {
        ZStack {
            ForEach(0..<6, id: \.self) { i in
                let phase = (t * 0.6 + Double(i) / 6).truncatingRemainder(dividingBy: 1)
                let x = CGFloat(sin(Double(i) * 2.1 + t * 0.8)) * height * 0.55
                let y = -height * (0.2 + CGFloat(phase) * 0.9)
                particle
                    .frame(width: height * 0.09, height: height * 0.09)
                    .offset(x: x, y: y)
                    .opacity(1 - phase)
            }
        }
        .allowsHitTesting(false)
    }

    @ViewBuilder private var particle: some View {
        switch effect {
        case "effect.hearts": PixelItem(name: "heart")
        case "effect.bubbles": PixelItem(name: "bubbleItem")
        default: IconShape(rows: SlimeSprites.emoteIcons["sparkle_small"] ?? []).fill(Color(hex: 0xFFD166))
        }
    }
}
