import SwiftUI

/// First launch: tap the egg until it hatches, name the slime, pick a colour.
struct HatchView: View {
    @EnvironmentObject private var store: SlimeStore
    @EnvironmentObject private var island: IslandController

    @State private var cracks = 0
    @State private var name = ""
    @State private var skin: Skin = .lime
    @State private var wobble = false
    @FocusState private var naming: Bool

    private let tapsToHatch = 5
    private let starters: [Skin] = [.lime, .bubblegum, .ocean]

    var body: some View {
        ZStack {
            BrandBackground()
            VStack(spacing: 24) {
                VStack(spacing: 4) {
                    Text("slimy")
                        .font(Brand.pixel(48))
                        .foregroundStyle(Brand.text)
                    Text("by hyprnode")
                        .font(Brand.pixel(13, bold: false))
                        .foregroundStyle(Brand.hypr)
                }
                .padding(.top, 40)

                Spacer()

                if cracks < tapsToHatch {
                    egg
                    Text(cracks == 0 ? "Da wackelt was. Tipp aufs Ei." : "Weiter. Noch \(tapsToHatch - cracks).")
                        .font(Brand.pixel(17, bold: false))
                        .foregroundStyle(Brand.muted)
                } else {
                    PixelSlime(frame: SpriteAnim.wave.frames[0], skin: skin)
                        .frame(height: 160)
                        .transition(.scale(scale: 0.3).combined(with: .opacity))
                    namePicker
                }

                Spacer()
            }
            .padding(.horizontal, 22)
        }
        .sensoryFeedback(.impact(weight: .heavy), trigger: cracks)
        .animation(.spring(response: 0.45, dampingFraction: 0.65), value: cracks)
    }

    /// A pixel egg that gets more cracked with every tap.
    private var egg: some View {
        Button {
            cracks += 1
            wobble.toggle()
        } label: {
            PixelEgg(cracks: cracks)
                .frame(width: 150, height: 190)
                .rotationEffect(.degrees(wobble ? 5 : -5), anchor: .bottom)
                .animation(.spring(response: 0.15, dampingFraction: 0.25), value: wobble)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Ei. Tippen zum Ausbrüten.")
    }

    private var namePicker: some View {
        VStack(spacing: 14) {
            Text("Ein Slime.")
                .font(Brand.pixel(30))
                .foregroundStyle(Brand.text)
            TextField("Wie soll er heißen?", text: $name)
                .font(Brand.pixel(20))
                .multilineTextAlignment(.center)
                .focused($naming)
                .submitLabel(.done)
                .padding(12)
                .background(Brand.panel)
                .overlay(Rectangle().strokeBorder(naming ? Brand.slime : Brand.line, lineWidth: 2))
            HStack(spacing: 12) {
                ForEach(starters) { option in
                    Button { skin = option } label: {
                        Rectangle()
                            .fill(option.body)
                            .frame(width: 42, height: 42)
                            .overlay(Rectangle().strokeBorder(skin == option ? Brand.text : Color.black.opacity(0.4),
                                                              lineWidth: skin == option ? 3 : 2))
                    }
                    .accessibilityLabel(option.title)
                }
            }
            PixelButton(title: "Hallo, \(name.isEmpty ? "Blob" : name)", symbol: "sparkles", tint: skin.body) {
                store.hatch(name: name.trimmingCharacters(in: .whitespacesAndNewlines), skin: skin)
                Task {
                    await Notifier.requestPermission()
                    await island.ensureRunning(restart: true)
                }
            }
        }
    }
}

/// The egg, drawn as pixels: speckled shell, cracks spreading across.
private struct PixelEgg: View {
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
        GeometryReader { geo in
            let cols = CGFloat(Self.shell[0].count), rows = CGFloat(Self.shell.count)
            let px = min(geo.size.width / cols, geo.size.height / rows)
            ZStack(alignment: .topLeading) {
                ForEach(0..<Self.shell.count, id: \.self) { y in
                    ForEach(Array(Self.shell[y].enumerated()), id: \.offset) { x, ch in
                        if ch != "." {
                            Rectangle()
                                .fill(color(ch, x: x, y: y))
                                .frame(width: px + 0.4, height: px + 0.4)
                                .offset(x: CGFloat(x) * px, y: CGFloat(y) * px)
                        }
                    }
                }
            }
        }
        .aspectRatio(12.0 / 15.0, contentMode: .fit)
    }

    private func color(_ ch: Character, x: Int, y: Int) -> Color {
        let shown = Self.crackPath.prefix(cracks * 2)
        if shown.contains(where: { $0.0 == x && $0.1 == y }) { return Color(hex: 0x2E3A32) }
        if ch == "o" { return Color(hex: 0x7DE67A) }
        return x >= 9 || y >= 13 ? Color(hex: 0xC9D3C2) : Color(hex: 0xE9F1E4)
    }
}
