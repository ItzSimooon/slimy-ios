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
                VStack(spacing: 6) {
                    Text("slimy")
                        .font(Brand.display(44, .black))
                        .foregroundStyle(Brand.text)
                    Text("by hyprnode")
                        .font(Brand.mono(12, .bold))
                        .foregroundStyle(Brand.hyprGlow)
                }
                .padding(.top, 40)

                Spacer()

                if cracks < tapsToHatch {
                    egg
                    Text(cracks == 0 ? "Something is wiggling in there… tap the egg." : "Keep going! \(tapsToHatch - cracks) more")
                        .font(Brand.display(16, .semibold))
                        .foregroundStyle(Brand.muted)
                        .contentTransition(.numericText())
                } else {
                    PixelSlime(frame: SpriteAnim.wave.frames[0], skin: skin)
                        .frame(height: 170)
                        .transition(.scale(scale: 0.3).combined(with: .opacity))
                    namePicker
                }

                Spacer()
            }
            .padding(.horizontal, 24)
        }
        .sensoryFeedback(.impact(weight: .heavy), trigger: cracks)
        .animation(.spring(response: 0.5, dampingFraction: 0.6), value: cracks)
    }

    private var egg: some View {
        Button {
            cracks += 1
            wobble.toggle()
        } label: {
            ZStack {
                Ellipse()
                    .fill(LinearGradient(colors: [Color(hex: 0xF4EEFF), Color(hex: 0xC8B8F0)], startPoint: .top, endPoint: .bottom))
                    .frame(width: 150, height: 190)
                    .shadow(color: Brand.hypr.opacity(0.5), radius: 30)
                // spots
                ForEach(0..<3) { i in
                    Circle()
                        .fill(Brand.hypr.opacity(0.35))
                        .frame(width: [26, 18, 22][i], height: [26, 18, 22][i])
                        .offset(x: [-30, 34, 8][i], y: [-40, -10, 50][i])
                }
                // cracks grow with every tap
                Path { p in
                    let points: [CGPoint] = [.init(x: -60, y: -6), .init(x: -38, y: 8), .init(x: -18, y: -10),
                                             .init(x: 4, y: 10), .init(x: 26, y: -8), .init(x: 46, y: 8), .init(x: 64, y: -4)]
                    let shown = Array(points.prefix(cracks + 1))
                    guard let first = shown.first else { return }
                    p.move(to: first)
                    for pt in shown.dropFirst() { p.addLine(to: pt) }
                }
                .stroke(Color(hex: 0x3A2C66), style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
                .offset(x: 75, y: 95)
                .frame(width: 150, height: 190)
            }
            .rotationEffect(.degrees(wobble ? 6 : -6), anchor: .bottom)
            .animation(.spring(response: 0.18, dampingFraction: 0.2), value: wobble)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Egg. Tap to hatch.")
    }

    private var namePicker: some View {
        VStack(spacing: 16) {
            Text("It's a slime!")
                .font(Brand.display(28, .black))
                .foregroundStyle(Brand.text)
            TextField("Name your slime", text: $name)
                .font(Brand.display(20, .bold))
                .multilineTextAlignment(.center)
                .focused($naming)
                .submitLabel(.done)
                .padding(14)
                .background(Brand.deep, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Brand.line))
            HStack(spacing: 14) {
                ForEach(starters) { option in
                    Button { withAnimation(.spring) { skin = option } } label: {
                        Circle()
                            .fill(option.body)
                            .frame(width: 44, height: 44)
                            .overlay(Circle().strokeBorder(Brand.text, lineWidth: skin == option ? 3 : 0))
                    }
                    .accessibilityLabel(option.title)
                }
            }
            JellyButton(title: "Hello, \(name.isEmpty ? "Blob" : name)!", symbol: "sparkles", tint: Brand.hypr) {
                store.hatch(name: name.trimmingCharacters(in: .whitespacesAndNewlines), skin: skin)
                Task {
                    await Notifier.requestPermission()
                    await island.ensureRunning(restart: true)
                }
            }
        }
    }
}
