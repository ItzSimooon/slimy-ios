import SwiftUI
import UserNotifications

/// First launch: an egg, a name, a hatching, meeting the slime, a first
/// meal, and its place outside the app (the Dynamic Island, or the Lock
/// Screen on iPhones without one). Notifications are asked for last, after
/// a screen that says what they are for.
struct OnboardingView: View {
    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var island: IslandController
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    enum Step: Int { case egg, name, meet, feed, island, done }

    @State private var step: Step = .egg
    @State private var cracks = 0
    @State private var wobble = false
    @State private var name = ""
    @State private var skin: Skin = .lime
    @FocusState private var naming: Bool

    private let tapsToHatch = 5
    private static let nameLimit = 16
    private static let suggestions = ["Mochi", "Blub", "Pudding", "Gloop", "Jelly", "Bean", "Pip", "Squish",
                                      "Wobble", "Kiwi", "Noodle", "Dumpling", "Gummy", "Tofu", "Pebble", "Boba"]

    var body: some View {
        ZStack {
            HabitatScene(habitat: .room, furniture: [:], date: .now, animate: !reduceMotion)
                .ignoresSafeArea()
            GeometryReader { geo in
                ZStack(alignment: .top) {
                    ScrollView {
                        VStack(spacing: 20) {
                            content
                        }
                        .padding(20)
                        .frame(maxWidth: 520)
                        .frame(maxWidth: .infinity, minHeight: geo.size.height)
                    }
                    .scrollBounceBehavior(.basedOnSize)
                    .scrollDismissesKeyboard(.interactively)
                    StatusBarBackdrop(height: geo.safeAreaInsets.top)
                }
            }
        }
        .onAppear {
            if ScreenshotMode.isActive {
                store.prepareScreenshotDemo()
            } else if store.state.hatched {
                step = .meet
            }
        }
    }

    @ViewBuilder private var content: some View {
        switch step {
        case .egg:
            egg
            panel {
                Text(cracks == 0 ? "Something is wobbling. Tap the egg." : "Almost! \(tapsToHatch - cracks) more.")
                    .font(.headline)
                    .foregroundStyle(Theme.ink)
                    .frame(maxWidth: .infinity)
                    .multilineTextAlignment(.center)
            }
        case .name:
            PixelEgg(cracks: tapsToHatch)
                .frame(width: naming ? 70 : 110, height: naming ? 90 : 140)
                .accessibilityHidden(true)
            panel {
                Text("What should it be called?")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
                nameField
                Text("And which colour?")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.inkSoft)
                HStack(spacing: 12) {
                    ForEach(Skin.hatchChoices, id: \.self) { option in
                        skinButton(option)
                    }
                }
                AppButton(title: "Hatch") { hatch() }
            }
        case .meet:
            let s = store.state.slime
            LiveSlimeView(height: 150)
            panel {
                Text("Hi, \(s.name)!")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
                Text("\(s.name) is \(s.personality.top.map(\.title).joined(separator: " and ")).")
                    .font(.headline)
                    .foregroundStyle(Theme.ink)
                Text(s.personality.dominant.blurb)
                    .font(.body)
                    .foregroundStyle(Theme.inkSoft)
                Text("Tap it or rub it. It likes that.")
                    .font(.body)
                    .foregroundStyle(Theme.ink)
                AppButton(title: "Next") { go(to: .feed) }
            }
        case .feed:
            LiveSlimeView(height: 150)
            panel {
                Text("It's hungry")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
                Text("Slimes have five needs: food, fun, energy, cleanliness and love. Start with an apple.")
                    .font(.body)
                    .foregroundStyle(Theme.inkSoft)
                AppButton(title: "Give an Apple", icon: "apple") {
                    store.feed(.apple)
                    if island.presence == .unavailable {
                        // No Live Activities here (iPad, Mac): nothing to set up.
                        Task { await afterIsland() }
                    } else {
                        go(to: .island)
                    }
                }
            }
        case .island:
            let presence = island.presence
            LiveSlimeView(height: 130)
            panel {
                Text("Always with you")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
                Text(islandText(presence))
                    .font(.body)
                    .foregroundStyle(Theme.inkSoft)
                AppButton(title: presence == .lockScreen ? "Put It on the Lock Screen" : "Bring It to the Island",
                          icon: "starJelly") {
                    Task {
                        await island.ensureRunning(restart: true)
                        await afterIsland()
                    }
                }
                AppButton(title: "Later", kind: .quiet) {
                    // Don't bring it back on its own; Settings can turn it on.
                    island.keepAlive = false
                    Task { await afterIsland() }
                }
            }
        case .done:
            LiveSlimeView(height: 130)
            panel {
                Text("One last thing")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Theme.ink)
                    .accessibilityAddTraits(.isHeader)
                Text("Next, Slimy asks whether it may tell you when \(store.state.slime.name) needs something. A few times a day at most, never at night. You can change this any time in Settings.")
                    .font(.body)
                    .foregroundStyle(Theme.inkSoft)
                // Always leads to the system prompt, which has its own
                // "Don't Allow".
                AppButton(title: "Continue") {
                    Task {
                        await Notifier.requestPermission()
                        finish()
                    }
                }
            }
        }
    }

    /// Where the slime lives outside the app on this iPhone.
    private func islandText(_ presence: IslandController.Presence) -> String {
        let name = store.state.slime.name
        switch presence {
        case .dynamicIsland:
            return "\(name) can live in the Dynamic Island. It wobbles there, shows what it needs, and you can feed it without opening the app."
        case .lockScreen:
            return "\(name) can live on your Lock Screen. It shows what it needs there, and you can feed it without opening the app."
        case .unavailable:
            return presence.explanation
        }
    }

    // MARK: Pieces

    private func panel<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            content()
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .transition(reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.97)))
    }

    private var egg: some View {
        Button {
            cracks += 1
            wobble.toggle()
            Feedback.shared.play(cracks >= tapsToHatch ? .pop : .tap)
            if cracks >= tapsToHatch { go(to: .name) }
        } label: {
            PixelEgg(cracks: cracks)
                .frame(width: 150, height: 190)
                .rotationEffect(.degrees(reduceMotion ? 0 : (wobble ? 5 : -5)), anchor: .bottom)
                .animation(reduceMotion ? nil : Animation.spring(response: 0.15, dampingFraction: 0.25), value: wobble)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Egg")
        .accessibilityValue(cracks == 0 ? "" : "\(cracks) of \(tapsToHatch) taps")
        .accessibilityHint("Tap to help it hatch")
    }

    private var nameField: some View {
        HStack(spacing: 4) {
            TextField("Name", text: $name)
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .focused($naming)
                .submitLabel(.done)
                .onSubmit { naming = false }
                .padding(.vertical, 12)
                .padding(.leading, 44)
                .onChange(of: name) { _, new in
                    if new.count > Self.nameLimit { name = String(new.prefix(Self.nameLimit)) }
                }
            Button {
                Feedback.shared.play(.tap)
                name = Self.suggestion(other: name)
            } label: {
                Image(systemName: "dice.fill")
                    .font(.title3)
                    .foregroundStyle(Theme.accent)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Suggest a name")
        }
        .background(Theme.well, in: RoundedRectangle(cornerRadius: Theme.smallRadius, style: .continuous))
        .animation(reduceMotion ? nil : Animation.easeOut(duration: 0.2), value: naming)
    }

    private func skinButton(_ option: Skin) -> some View {
        let selected = skin == option
        return Button {
            Feedback.shared.play(.tap)
            skin = option
        } label: {
            PixelSlime(frame: SlimeSprites.frames(.baby, .idle)[0], skin: option)
                .frame(width: 60, height: 50)
                .padding(10)
                .frame(maxWidth: .infinity)
                .overlay(
                    RoundedRectangle(cornerRadius: Theme.smallRadius, style: .continuous)
                        .strokeBorder(selected ? Theme.accent : .clear, lineWidth: 2.5)
                )
        }
        .buttonStyle(TileStyle(fill: Theme.well, radius: Theme.smallRadius))
        .accessibilityLabel(option.title)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // MARK: Steps

    private func go(to next: Step) {
        withAnimation(reduceMotion ? nil : Animation.spring(response: 0.5, dampingFraction: 0.8)) { step = next }
    }

    private func hatch() {
        naming = false
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        store.hatch(name: trimmed.isEmpty ? Self.suggestion(other: "") : trimmed, skin: skin)
        go(to: .meet)
    }

    /// The notification step, unless iOS already has an answer (a slime
    /// hatched again after the first one was released).
    private func afterIsland() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        if settings.authorizationStatus == .notDetermined {
            go(to: .done)
        } else {
            store.update { $0.settings.notifications.asked = true }
            finish()
        }
    }

    private func finish() {
        store.markTip("onboarded")
        Notifier.reschedule(for: store.state)
    }

    /// A name idea, never the one already in the field.
    private static func suggestion(other current: String) -> String {
        let options = suggestions.filter { $0 != current }
        return options.randomElement() ?? "Mochi"
    }
}

/// The egg, drawn as pixels: speckled shell, cracks spreading across.
struct PixelEgg: View {
    let cracks: Int

    private static let shell = [
        "....xxxx....",
        "...xxxxxx...",
        "..xxxxxxxx..",
        "..xxoxxxxx..",
        ".xxxxxxxxxx.",
        ".xxxxxxoxxx.",
        "xxxxxxxxxxxx",
        "xxxoxxxxxxxx",
        "xxxxxxxxxoxx",
        "xxxxxxxxxxxx",
        "xxxxxoxxxxxx",
        ".xxxxxxxxxx.",
        ".xxxxxxxxxx.",
        "..xxxxxxxx..",
        "...xxxxxx...",
    ]
    private static let crackPath: [(Int, Int)] = [(1, 7), (2, 6), (3, 7), (4, 6), (5, 7), (6, 6), (7, 7), (8, 6), (9, 7), (10, 6)]

    var body: some View {
        let shown = Set(Self.crackPath.prefix(cracks * 2).map { "\($0.0),\($0.1)" })
        let grid = Self.shell.enumerated().map { y, row in
            String(row.enumerated().map { x, ch -> Character in
                if ch == "." { return "." }
                if shown.contains("\(x),\(y)") { return "c" }
                if ch == "o" { return "o" }
                return x >= 9 || y >= 13 ? "s" : "x"
            })
        }
        ZStack {
            PixelGridShape(rows: grid, code: "x", cols: 12, count: 15, bottom: false).fill(Color(hex: 0xF7F0E2))
            PixelGridShape(rows: grid, code: "s", cols: 12, count: 15, bottom: false).fill(Color(hex: 0xDCCFB8))
            PixelGridShape(rows: grid, code: "o", cols: 12, count: 15, bottom: false).fill(Color(hex: 0x7DE67A))
            PixelGridShape(rows: grid, code: "c", cols: 12, count: 15, bottom: false).fill(Color(hex: 0x3A2B2E))
        }
        .aspectRatio(12.0 / 15.0, contentMode: .fit)
    }
}
