import SwiftUI

/// First launch: an egg, a name, a hatching, meeting the slime, a first
/// meal, and the Dynamic Island. Permissions are optional and asked in context.
struct OnboardingView: View {
    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var island: IslandController

    enum Step: Int { case egg, name, meet, feed, island, done }

    @State private var step: Step = .egg
    @State private var cracks = 0
    @State private var wobble = false
    @State private var name = ""
    @State private var skin: Skin = .lime
    @FocusState private var naming: Bool

    private let tapsToHatch = 5

    var body: some View {
        ZStack {
            HabitatScene(habitat: .room, furniture: [:], date: .now, animate: true)
                .ignoresSafeArea()
            VStack(spacing: 18) {
                Spacer()
                content
                Spacer()
            }
            .padding(20)
        }
        .onAppear {
            if store.state.hatched { step = .meet }
        }
    }

    @ViewBuilder private var content: some View {
        switch step {
        case .egg:
            egg
            PixelWindow {
                Text(cracks == 0 ? "Something is wobbling. Tap the egg." : "Almost! \(tapsToHatch - cracks) more.")
                    .font(Theme.label(17)).foregroundStyle(Theme.ink).frame(maxWidth: .infinity)
            }
        case .name:
            PixelEgg(cracks: tapsToHatch).frame(width: 110, height: 140)
            PixelWindow(title: "What should it be called?") {
                TextField("Name", text: $name)
                    .font(Theme.title(22))
                    .foregroundStyle(Theme.ink)
                    .multilineTextAlignment(.center)
                    .focused($naming)
                    .submitLabel(.done)
                    .padding(10)
                    .background(Theme.paperShade)
                    .overlay(Rectangle().strokeBorder(Theme.border, lineWidth: 2))
                Text("And which color?").font(Theme.label(15)).foregroundStyle(Theme.ink)
                HStack(spacing: 12) {
                    ForEach([Skin.lime, .bubblegum, .ocean], id: \.self) { option in
                        Button { skin = option } label: {
                            PixelSlime(frame: SlimeSprites.frames(.baby, .idle)[0], skin: option)
                                .frame(width: 60, height: 50)
                                .padding(4)
                        }
                        .buttonStyle(KeyStyle(fill: skin == option ? Color(hex: 0xE6F6DC) : Theme.paper))
                        .accessibilityLabel(option.title)
                        .accessibilityAddTraits(skin == option ? .isSelected : [])
                    }
                }
                .frame(maxWidth: .infinity)
                PixelButton(title: "Hatch") {
                    store.hatch(name: name, skin: skin)
                    withAnimation(.spring(response: 0.5, dampingFraction: 0.6)) { step = .meet }
                }
            }
        case .meet:
            let s = store.state.slime
            LiveSlimeView(height: 150)
            PixelWindow(title: "Hi, \(s.name)!") {
                Text("\(s.name) is \(s.personality.top.map(\.title).joined(separator: " and ")).")
                    .font(Theme.label(17)).foregroundStyle(Theme.ink)
                Text(s.personality.dominant.blurb).font(Theme.text(15)).foregroundStyle(Theme.inkSoft)
                Text("Tap it or rub it. It likes that.").font(Theme.text(15)).foregroundStyle(Theme.ink)
                PixelButton(title: "Next") { step = .feed }
            }
        case .feed:
            LiveSlimeView(height: 150)
            PixelWindow(title: "It's hungry") {
                Text("Slimes have five needs: food, fun, energy, cleanliness and love. Start with an apple.")
                    .font(Theme.text(15)).foregroundStyle(Theme.ink)
                PixelButton(title: "Give an apple", icon: "apple") {
                    store.feed(.apple)
                    step = .island
                }
            }
        case .island:
            LiveSlimeView(height: 130)
            PixelWindow(title: "Always with you") {
                Text("\(store.state.slime.name) can live up in the Dynamic Island. It wobbles there, tells you what it needs, and you can feed it without opening the app.")
                    .font(Theme.text(15)).foregroundStyle(Theme.ink)
                PixelButton(title: "Bring it to the island", icon: "starJelly") {
                    Task {
                        await island.ensureRunning(restart: true)
                        step = .done
                    }
                }
                PixelButton(title: "Later", kind: .quiet) { step = .done }
            }
        case .done:
            LiveSlimeView(height: 130)
            PixelWindow(title: "Almost done") {
                Text("Should \(store.state.slime.name) let you know when it needs something? A few times a day at most, never at night.")
                    .font(Theme.text(15)).foregroundStyle(Theme.ink)
                PixelButton(title: "Yes, please") {
                    Task {
                        await Notifier.requestPermission()
                        finish()
                    }
                }
                PixelButton(title: "No thanks", kind: .quiet) { finish() }
            }
        }
    }

    private var egg: some View {
        Button {
            cracks += 1
            wobble.toggle()
            Feedback.shared.play(cracks >= tapsToHatch ? .pop : .tap)
            if cracks >= tapsToHatch {
                withAnimation(.spring) { step = .name }
            }
        } label: {
            PixelEgg(cracks: cracks)
                .frame(width: 150, height: 190)
                .rotationEffect(.degrees(wobble ? 5 : -5), anchor: .bottom)
                .animation(.spring(response: 0.15, dampingFraction: 0.25), value: wobble)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Egg. Tap to hatch.")
    }

    private func finish() {
        store.markTip("onboarded")
        Notifier.reschedule(for: store.state)
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
