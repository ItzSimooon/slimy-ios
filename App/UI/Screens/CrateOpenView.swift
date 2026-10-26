import SwiftUI
import UIKit

/// Opening a crate, full screen. The crate wobbles; every tap knocks its lid
/// a little looser until it cracks, bursts in the colour of what's inside,
/// and the reward rises out. The legendary reward gets a longer moment of
/// its own: the crate rattles, the screen flashes and the axolotl appears.
/// With Reduce Motion nothing shakes, flies or flashes: the reward fades in.
struct CrateOpenView: View {
    let kind: CrateKind
    let onDone: () -> Void

    init(kind: CrateKind, onDone: @escaping () -> Void) {
        self.kind = kind
        self.onDone = onDone
        _current = State(initialValue: kind)
    }

    @EnvironmentObject private var store: GameStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private enum Phase {
        /// Waiting for taps.
        case closed
        /// Cracked; the reveal is on its way.
        case cracking
        /// The legendary build-up.
        case charging
        /// Lid off, the reward rising.
        case open
        /// The reward is shown with its words and buttons.
        case revealed
        /// There was no crate of this kind to open.
        case empty
    }

    /// The kind being opened now; "Open another" can move on to a crate
    /// that came out of this one.
    @State private var current: CrateKind
    @State private var phase: Phase = .closed
    @State private var taps = 0
    @State private var tapsNeeded = Int.random(in: 3...5)
    @State private var knocks = 0
    @State private var shake: Double = 0
    @State private var squash: CGFloat = 1
    @State private var bursts: [SparkBurst] = []
    @State private var flash: Double = 0
    @State private var reward: CrateReward?
    /// Crates that came along with the opening besides the reward itself
    /// and its bonus crate.
    @State private var extras: [CrateKind] = []
    @State private var rise = false

    private static let stageHeight: CGFloat = 330
    private static let crateSize: CGFloat = 168
    /// Where the risen reward sits, from the middle of the stage (the spark
    /// layer's centre).
    private static let rewardCentre = CGSize(width: 0, height: -45)

    var body: some View {
        ZStack {
            Theme.backdrop.ignoresSafeArea()

            GeometryReader { geo in
                ScrollView {
                    VStack(spacing: 20) {
                        stage
                        caption
                            .frame(maxWidth: 520)
                            .frame(minHeight: 150, alignment: .top)
                        controls
                            .frame(maxWidth: 520)
                            .frame(minHeight: 170, alignment: .top)
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 52)
                    .padding(.bottom, 20)
                    .frame(maxWidth: .infinity, minHeight: geo.size.height)
                }
                .scrollBounceBehavior(.basedOnSize)
            }

            // A soft wash of the legendary colour, not a white blast.
            Rarity.legendary.displayColor
                .opacity(flash)
                .ignoresSafeArea()
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .overlay(alignment: .topTrailing) {
            if phase == .closed {
                HUDButton(systemImage: "xmark", label: "Close", action: onDone)
                    .accessibilityHint("Keeps the crate for later")
                    .padding(.trailing, 16)
                    .padding(.top, 8)
            }
        }
        .onAppear {
            if reward == nil, count(current) == 0 { phase = .empty }
        }
    }

    // MARK: Stage

    private var isOpen: Bool { phase == .open || phase == .revealed }

    /// Where the reward and its disc stand. With Reduce Motion they're
    /// already in place and only fade in.
    private var risen: Bool { rise || reduceMotion }

    private var stage: some View {
        ZStack(alignment: .bottom) {
            if let reward, isOpen {
                // A flat disc of the rarity's colour behind the reward.
                Circle()
                    .fill(reward.rarity.displayColor.opacity(reward.rarity == .legendary ? 0.26 : 0.16))
                    .frame(width: 240, height: 240)
                    .scaleEffect(risen ? 1 : 0.3)
                    .opacity(rise ? 1 : 0)
                    .offset(y: -88)
                    .accessibilityHidden(true)
            }

            crate

            if let reward, isOpen {
                CrateRewardArt(reward: reward, slime: store.state.slime,
                               size: reward.rarity == .legendary ? 124 : 112)
                    .scaleEffect(risen ? 1 : 0.25, anchor: .bottom)
                    .offset(y: risen ? -150 : -50)
                    .opacity(rise ? 1 : 0)
                    .accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: Self.stageHeight)
        .overlay {
            // Wider than the stage, so sparks can fly past its edges.
            SparkLayer(bursts: bursts)
                .frame(width: 720, height: 900)
        }
    }

    private var crate: some View {
        let moving = !reduceMotion && (phase == .closed || phase == .charging)
        return Button(action: tapCrate) {
            TimelineView(.animation(minimumInterval: 1 / 30, paused: !moving)) { context in
                let t = context.date.timeIntervalSinceReferenceDate
                CrateSprite(kind: current, cropped: false, lift: lift, tilt: tilt, open: isOpen)
                    .frame(width: Self.crateSize, height: Self.crateSize)
                    .scaleEffect(x: 2 - squash, y: squash, anchor: .bottom)
                    .rotationEffect(.degrees((moving ? wobble(t) : 0) + shake), anchor: .bottom)
                    .offset(x: moving && phase == .charging ? CGFloat(sin(t * 57)) * 2.5 : 0)
                    .scaleEffect(isOpen && !reduceMotion ? 0.8 : 1, anchor: .bottom)
                    .opacity(phase == .empty ? 0.35 : 1)
            }
            .frame(width: Self.crateSize * 1.2, height: Self.crateSize)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .allowsHitTesting(phase == .closed)
        .accessibilityLabel(CrateCopy.name(current))
        .accessibilityValue(taps == 0 ? "" : "\(taps) of \(tapsNeeded) taps")
        .accessibilityHint("Tap to crack it open")
        .accessibilityHidden(phase != .closed)
    }

    /// The top of the closed crate, from the middle of the stage.
    private var crateTop: CGSize {
        CGSize(width: 0, height: Self.stageHeight / 2 - Self.crateSize * (1 - CrateArt.topShare(current)))
    }

    /// The open crate's mouth (it shrinks a little as it opens).
    private var mouth: CGSize {
        CGSize(width: 0, height: Self.stageHeight / 2 - 0.8 * Self.crateSize * (1 - CrateArt.topShare(current)) + 12)
    }

    /// The idle wobble: a little jiggle every couple of seconds, or a
    /// rattle while something legendary is about to come out.
    private func wobble(_ t: Double) -> Double {
        if phase == .charging { return sin(t * 46) * 7 }
        let cycle = t.truncatingRemainder(dividingBy: 2.4)
        guard cycle < 0.7 else { return 0 }
        let p = cycle / 0.7
        return sin(p * .pi * 5) * 4 * (1 - p)
    }

    /// How loose the lid is: more with every tap.
    private var lift: Double {
        switch phase {
        case .closed: Double(taps) / Double(max(1, tapsNeeded))
        case .cracking, .charging: 1
        case .open, .revealed, .empty: 0
        }
    }

    private var tilt: Double {
        guard taps > 0, phase == .closed || phase == .cracking || phase == .charging else { return 0 }
        return (knocks % 2 == 0 ? 1 : -1) * lift * 7
    }

    // MARK: Words

    @ViewBuilder private var caption: some View {
        switch phase {
        case .closed, .cracking:
            VStack(spacing: 6) {
                Text(CrateCopy.name(current))
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
                Text(tapHint)
                    .font(.body)
                    .foregroundStyle(Theme.inkSoft)
                let left = count(current)
                if phase == .closed, left > 1 {
                    Text("\(left) on the shelf")
                        .font(.footnote)
                        .foregroundStyle(Theme.inkSoft)
                        .monospacedDigit()
                }
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
        case .charging:
            VStack(spacing: 6) {
                Text("Something special is inside")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Theme.ink)
                Text("Hold on…")
                    .font(.body)
                    .foregroundStyle(Theme.inkSoft)
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .transition(.opacity)
        case .open:
            Color.clear.frame(height: 1)
        case .revealed:
            if let reward {
                rewardText(reward)
            }
        case .empty:
            VStack(spacing: 6) {
                Text("Nothing left to open")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Theme.ink)
                Text("Crates come with the seventh daily gift, every fifth level, great arcade rounds and some achievements, or for coins in the shop.")
                    .font(.body)
                    .foregroundStyle(Theme.inkSoft)
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var tapHint: String {
        if phase == .cracking { return "Here it comes!" }
        if taps == 0 { return "Tap the crate to crack it open." }
        let left = tapsNeeded - taps
        return left == 1 ? "One more tap!" : "\(left) more taps."
    }

    private func rewardText(_ reward: CrateReward) -> some View {
        VStack(spacing: 10) {
            RarityBadge(rarity: reward.rarity)
            Text(reward.title)
                .font(.title.weight(.bold))
                .foregroundStyle(Theme.ink)
            if let converted = convertedText(reward) {
                Label {
                    Text(converted)
                } icon: {
                    PixelItem(name: "coin").frame(width: 18, height: 18)
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Theme.ink)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Theme.well, in: RoundedRectangle(cornerRadius: Theme.smallRadius, style: .continuous))
            } else if let detail = detail(reward) {
                Text(detail)
                    .font(.body)
                    .foregroundStyle(Theme.inkSoft)
            }
            if let extra = extrasText {
                Label(extra, systemImage: "shippingbox.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.ink)
            }
        }
        .multilineTextAlignment(.center)
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .combine)
        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .bottom)))
    }

    /// "Already yours, so you get 40 coins instead." Nil unless it was converted.
    private func convertedText(_ reward: CrateReward) -> String? {
        guard reward.convertedCoins != nil else { return nil }
        return reward.detail
    }

    /// What the reward means and where to find it, unless it was converted.
    private func detail(_ reward: CrateReward) -> String? {
        guard reward.convertedCoins == nil else { return nil }
        switch reward.kind {
        case .coins:
            return "Added to your coins."
        case .food:
            return "Free servings, waiting in the kitchen."
        case .item(let id):
            if Catalog.item(id)?.kind == .furniture { return "New furniture. Place it in the room from the shop." }
            return "Something new to wear. Try it on in the shop."
        case .skin(let skin):
            return skin.isSecret ? "The secret colour, found only in crates." : "A new colour. Pick it in the shop under Colours."
        case .crate:
            return "Another crate to open."
        }
    }

    private var extrasText: String? {
        guard !extras.isEmpty else { return nil }
        let names: [String] = CrateKind.allCases.compactMap { k in
            let n = extras.filter { $0 == k }.count
            guard n > 0 else { return nil }
            let name = CrateCopy.name(k).lowercased()
            return n == 1 ? "a \(name)" : "\(n) \(name)s"
        }
        return "You also got \(names.joined(separator: " and "))."
    }

    private func spoken(_ reward: CrateReward) -> String {
        var parts = ["\(reward.rarity.title): \(reward.title)."]
        if let converted = convertedText(reward) {
            parts.append(converted)
        } else if let detail = detail(reward) {
            parts.append(detail)
        }
        if let extra = extrasText { parts.append(extra) }
        return parts.joined(separator: " ")
    }

    // MARK: Buttons

    @ViewBuilder private var controls: some View {
        switch phase {
        case .revealed:
            VStack(spacing: 10) {
                if let skin = offeredSkin {
                    if store.state.slime.skin == skin {
                        Label("\(store.state.slime.name) is wearing it", systemImage: "checkmark.circle.fill")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Theme.accent)
                            .frame(minHeight: 44)
                    } else {
                        AppButton(title: "Use this colour", kind: .secondary) { wear(skin) }
                    }
                }
                if let next = nextCrate {
                    AppButton(title: next == current ? "Open another" : "Open the \(CrateCopy.name(next).lowercased())",
                              systemIcon: "shippingbox") { openNext(next) }
                    AppButton(title: "Done", kind: .secondary, action: onDone)
                } else {
                    AppButton(title: "Done", action: onDone)
                }
            }
            .transition(.opacity)
        case .empty:
            AppButton(title: "Done", action: onDone)
        case .closed, .cracking, .charging, .open:
            Color.clear.frame(height: 1)
        }
    }

    /// A colour that came out of the crate and can be worn right away.
    private var offeredSkin: Skin? {
        guard let reward else { return nil }
        let found: Skin?
        if reward.rarity == .legendary {
            found = .axolotl
        } else if case .skin(let won) = reward.kind {
            found = won
        } else {
            found = nil
        }
        guard let skin = found, store.state.slime.isUnlocked(skin) else { return nil }
        return skin
    }

    /// Another of the same kind, or else a crate that just came out.
    private var nextCrate: CrateKind? {
        if count(current) > 0 { return current }
        var candidates = extras
        if let bonus = reward?.bonusCrate { candidates.insert(bonus, at: 0) }
        if let reward, case .crate(let won) = reward.kind { candidates.insert(won, at: 0) }
        return candidates.first { count($0) > 0 }
    }

    // MARK: Opening

    private func count(_ kind: CrateKind) -> Int {
        store.state.crates[kind, default: 0]
    }

    private func tapCrate() {
        guard phase == .closed, count(current) > 0 else { return }
        withAnimation(reduceMotion ? nil : Animation.spring(response: 0.25, dampingFraction: 0.5)) {
            taps += 1
        }
        // Every tap knocks a little harder than the last.
        thump(0.35 + 0.15 * Double(taps))
        if taps >= tapsNeeded {
            crack()
            return
        }
        Feedback.shared.play(.dig)
        knock(strength: 1 + 0.2 * Double(taps))
        addBurst(SparkBurst(colors: CrateArt.colors(current), count: 7, speed: 260, size: 6, life: 0.6,
                            gravity: 900, origin: crateTop, spread: 0.55))
    }

    /// A shake and a squash, like a knock on the lid.
    private func knock(strength: Double) {
        knocks += 1
        guard !reduceMotion else { return }
        shake = (knocks % 2 == 0 ? 1 : -1) * 8 * strength
        squash = 1 - 0.1 * CGFloat(min(strength, 2))
        withAnimation(.interpolatingSpring(stiffness: 280, damping: 8)) {
            shake = 0
            squash = 1
        }
    }

    private func crack() {
        let before = store.state.crates
        // The store plays its "nope" when there was nothing to open.
        guard let got = store.openCrate(current) else {
            withAnimation(.easeOut(duration: 0.25)) { phase = .empty }
            return
        }
        phase = .cracking
        extras = Self.extraCrates(before: before, after: store.state.crates, opened: current, reward: got)
        reward = got
        Task {
            if got.rarity == .legendary {
                await revealLegendary()
            } else {
                await reveal(got.rarity)
            }
        }
    }

    private func reveal(_ rarity: Rarity) async {
        Feedback.shared.play(.treasure)
        if reduceMotion {
            withAnimation(.easeInOut(duration: 0.5)) {
                phase = .open
                rise = true
            }
            try? await Task.sleep(for: .milliseconds(500))
            finish()
            return
        }
        knock(strength: 1.6)
        try? await Task.sleep(for: .milliseconds(120))
        let level = Rarity.allCases.firstIndex(of: rarity) ?? 0
        addBurst(SparkBurst(colors: sparkColors(rarity), count: 22 + 10 * level, speed: 440, size: 7, life: 1.15,
                            origin: mouth, spread: 0.55))
        withAnimation(.spring(response: 0.4, dampingFraction: 0.62)) { phase = .open }
        try? await Task.sleep(for: .milliseconds(160))
        Feedback.shared.play(.whoosh)
        withAnimation(.spring(response: 0.55, dampingFraction: 0.68)) { rise = true }
        try? await Task.sleep(for: .milliseconds(420))
        if rarity >= .epic {
            Feedback.shared.play(.success)
            addBurst(SparkBurst(colors: sparkColors(rarity), count: 20, speed: 300, size: 6, life: 1.0,
                                gravity: 420, origin: Self.rewardCentre, spread: 1))
        }
        finish()
    }

    /// The legendary moment: a long rattle, a flash, a big burst, and the
    /// axolotl rising slowly, with a second burst once it's up.
    private func revealLegendary() async {
        withAnimation(.easeOut(duration: 0.25)) { phase = .charging }
        if reduceMotion {
            try? await Task.sleep(for: .seconds(1.4))
            Feedback.shared.play(.levelUp)
            withAnimation(.easeInOut(duration: 0.9)) {
                phase = .open
                rise = true
            }
            try? await Task.sleep(for: .milliseconds(900))
            finish()
            return
        }
        for beat in 0..<6 {
            Feedback.shared.play(.dig)
            thump(0.45 + Double(beat) * 0.11)
            knock(strength: 1 + Double(beat) * 0.25)
            addBurst(SparkBurst(colors: CrateArt.colors(current), count: 6 + beat, speed: 240 + CGFloat(beat) * 30,
                                size: 6, life: 0.6, gravity: 900, origin: crateTop, spread: 0.6))
            try? await Task.sleep(for: .milliseconds(330 - beat * 30))
        }
        Feedback.shared.play(.levelUp)
        withAnimation(.easeIn(duration: 0.1)) { flash = 0.4 }
        try? await Task.sleep(for: .milliseconds(110))
        // The lid goes while the screen is washed in colour.
        phase = .open
        addBurst(SparkBurst(colors: sparkColors(.legendary), count: 64, speed: 620, size: 8, life: 1.7,
                            gravity: 640, origin: mouth, spread: 0.75))
        withAnimation(.easeOut(duration: 0.9)) { flash = 0 }
        try? await Task.sleep(for: .milliseconds(300))
        Feedback.shared.play(.whoosh)
        withAnimation(.spring(response: 1.1, dampingFraction: 0.62)) { rise = true }
        try? await Task.sleep(for: .milliseconds(850))
        Feedback.shared.play(.treasure)
        addBurst(SparkBurst(colors: sparkColors(.legendary), count: 36, speed: 380, size: 7, life: 1.3,
                            gravity: 380, origin: Self.rewardCentre, spread: 1))
        try? await Task.sleep(for: .milliseconds(450))
        finish()
    }

    private func finish() {
        withAnimation(reduceMotion ? Animation.easeOut(duration: 0.25) : Animation.spring(response: 0.45, dampingFraction: 0.85)) {
            phase = .revealed
        }
        if let reward {
            UIAccessibility.post(notification: .announcement, argument: spoken(reward))
        }
    }

    private func openNext(_ next: CrateKind) {
        withAnimation(reduceMotion ? Animation.easeInOut(duration: 0.3) : Animation.spring(response: 0.45, dampingFraction: 0.85)) {
            current = next
            reward = nil
            extras = []
            rise = false
            taps = 0
            knocks = 0
            tapsNeeded = Int.random(in: 3...5)
            bursts = []
            phase = .closed
        }
    }

    private func wear(_ skin: Skin) {
        store.wear(skin)
        Feedback.shared.play(.success)
        UIAccessibility.post(notification: .announcement, argument: "\(store.state.slime.name) is wearing it now.")
    }

    // MARK: Effects

    private func addBurst(_ burst: SparkBurst) {
        guard !reduceMotion else { return }
        bursts.append(burst)
        let id = burst.id
        Task {
            try? await Task.sleep(for: .seconds(burst.life + 0.1))
            bursts.removeAll { $0.id == id }
        }
    }

    private func sparkColors(_ rarity: Rarity) -> [Color] {
        let tint = rarity.displayColor
        switch rarity {
        case .common, .rare: return [tint, tint.opacity(0.6)]
        case .epic: return [tint, tint.opacity(0.6), Theme.gold]
        case .legendary: return [tint, Skin.axolotl.body, Skin.axolotl.shade, Theme.gold]
        }
    }

    /// A knock felt in the hand, firmer with every tap and through the
    /// legendary rattle. Respects the haptics setting.
    private func thump(_ intensity: Double) {
        guard store.state.settings.haptics else { return }
        CrateHaptics.rigid.impactOccurred(intensity: CGFloat(min(1, intensity)))
    }

    /// Crates that arrived with this opening beyond the reward and its
    /// bonus crate, like one from an achievement it just completed.
    private static func extraCrates(before: [CrateKind: Int], after: [CrateKind: Int], opened: CrateKind,
                                    reward: CrateReward) -> [CrateKind] {
        var out: [CrateKind] = []
        for k in CrateKind.allCases {
            var expected = before[k, default: 0]
            if k == opened { expected -= 1 }
            if case .crate(let won) = reward.kind, won == k { expected += 1 }
            if reward.bonusCrate == k { expected += 1 }
            let extra = after[k, default: 0] - expected
            if extra > 0 { out += Array(repeating: k, count: extra) }
        }
        return out
    }
}

private enum CrateHaptics {
    @MainActor static let rigid = UIImpactFeedbackGenerator(style: .rigid)
}
