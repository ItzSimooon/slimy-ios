import SwiftUI

/// Scrub the mud off with your finger. Bubbles everywhere.
struct BathView: View {
    @EnvironmentObject private var store: SlimeStore
    @Environment(\.dismiss) private var dismiss

    @State private var spots: [MudSpot] = []
    @State private var bubbles: [Bubble] = []
    @State private var finished = false
    @State private var alreadyClean = false
    @State private var scrubs = 0

    private let slimeHeight: CGFloat = 210

    var body: some View {
        GeometryReader { geo in
            let slimeSize = CGSize(width: slimeHeight * SlimeFont.aspect, height: slimeHeight)
            let slimeOrigin = CGPoint(x: (geo.size.width - slimeSize.width) / 2,
                                      y: geo.size.height * 0.62 - slimeSize.height)
            ZStack {
                TileWall()
                TimelineView(.animation) { context in
                    let t = context.date.timeIntervalSinceReferenceDate
                    let frames = (finished ? SpriteAnim.bath : SpriteAnim.idle).frames
                    ZStack(alignment: .topLeading) {
                        PixelSlime(frame: frames[Int(t * 2.5) % frames.count], skin: store.state.skin)
                            .frame(width: slimeSize.width, height: slimeSize.height)
                            .offset(x: slimeOrigin.x, y: slimeOrigin.y)
                        ForEach(spots) { spot in
                            MudBlob(seed: spot.seed)
                                .fill(Color(hex: 0x6B4A2E))
                                .frame(width: spot.size, height: spot.size * 0.8)
                                .opacity(spot.dirt)
                                .position(x: slimeOrigin.x + spot.unit.x * slimeSize.width,
                                          y: slimeOrigin.y + spot.unit.y * slimeSize.height)
                        }
                        Canvas { ctx, _ in
                            let now = context.date
                            for b in bubbles {
                                let age = now.timeIntervalSince(b.born)
                                guard age < 1.6 else { continue }
                                let p = CGPoint(x: b.origin.x + CGFloat(sin(age * 4 + b.wobble)) * 10,
                                                y: b.origin.y - CGFloat(age) * 90)
                                let r = b.radius * (1 + CGFloat(age) * 0.3)
                                let rect = CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2)
                                let alpha = 1 - age / 1.6
                                ctx.stroke(Path(ellipseIn: rect), with: .color(Brand.cool.opacity(alpha)), lineWidth: 1.5)
                                ctx.fill(Path(ellipseIn: rect.insetBy(dx: r * 0.55, dy: r * 0.55).offsetBy(dx: -r * 0.3, dy: -r * 0.3)),
                                         with: .color(.white.opacity(alpha * 0.8)))
                            }
                        }
                        .allowsHitTesting(false)
                    }
                }
                Water()
                    .frame(height: geo.size.height * 0.42)
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .allowsHitTesting(false)

                overlay
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in scrub(at: value.location, origin: slimeOrigin, size: slimeSize) }
            )
        }
        .ignoresSafeArea()
        .sensoryFeedback(.impact(flexibility: .soft, intensity: 0.4), trigger: scrubs / 6)
        .sensoryFeedback(.success, trigger: finished)
        .onAppear(perform: makeMess)
    }

    private var overlay: some View {
        VStack {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Eyebrow(text: "bath time", color: Brand.cool)
                    Text(headline)
                        .font(Brand.display(24, .black))
                        .foregroundStyle(Brand.text)
                }
                Spacer()
                CloseButton { dismiss() }
            }
            .padding(.horizontal, 20)
            .padding(.top, 60)
            Spacer()
            if finished || alreadyClean {
                JellyButton(title: "Done", symbol: "checkmark", tint: Brand.color(for: .clean)) { dismiss() }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 44)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring, value: finished)
    }

    private var headline: String {
        if alreadyClean { return "Already squeaky clean ✨" }
        if finished { return "Squeaky clean!! 🫧" }
        let left = spots.filter { $0.dirt > 0.05 }.count
        return left == 1 ? "1 mud spot left" : "Scrub off \(left) mud spots"
    }

    private func makeMess() {
        let clean = store.state.needs.clean
        guard clean <= 95 else {
            alreadyClean = true
            return
        }
        let count = max(3, Int((100 - clean) / 11))
        spots = (0..<count).map { i in
            MudSpot(unit: CGPoint(x: .random(in: 0.25...0.75), y: .random(in: 0.5...0.9)),
                    size: .random(in: 26...42), seed: i)
        }
    }

    private func scrub(at point: CGPoint, origin: CGPoint, size: CGSize) {
        guard !finished, !alreadyClean else { return }
        scrubs += 1
        if scrubs % 2 == 0 {
            bubbles.append(Bubble(origin: point, radius: .random(in: 5...13), wobble: .random(in: 0...6), born: .now))
            bubbles.removeAll { Date.now.timeIntervalSince($0.born) > 1.6 }
        }
        for i in spots.indices {
            let c = CGPoint(x: origin.x + spots[i].unit.x * size.width, y: origin.y + spots[i].unit.y * size.height)
            if hypot(c.x - point.x, c.y - point.y) < 44 {
                spots[i].dirt = max(0, spots[i].dirt - 0.06)
            }
        }
        if spots.allSatisfy({ $0.dirt <= 0.05 }) {
            finished = true
            store.act(.bath)
        }
    }
}

private struct MudSpot: Identifiable {
    let id = UUID()
    let unit: CGPoint
    let size: CGFloat
    let seed: Int
    var dirt: Double = 1
}

private struct Bubble {
    let origin: CGPoint
    let radius: CGFloat
    let wobble: Double
    let born: Date
}

/// A lumpy blob, different for every seed.
private struct MudBlob: Shape {
    let seed: Int

    func path(in rect: CGRect) -> Path {
        var rng = SeededRandom(seed: UInt64(seed + 11))
        var path = Path()
        let c = CGPoint(x: rect.midX, y: rect.midY)
        let n = 9
        for i in 0...n {
            let a = Double(i) / Double(n) * .pi * 2
            let r = 0.36 + rng.next() * 0.14
            let p = CGPoint(x: c.x + CGFloat(cos(a)) * rect.width * r, y: c.y + CGFloat(sin(a)) * rect.height * r)
            if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        path.closeSubpath()
        return path
    }
}

private struct TileWall: View {
    var body: some View {
        Canvas { ctx, size in
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(hex: 0x141A2C)))
            let tile: CGFloat = 46
            var y: CGFloat = 0
            while y < size.height {
                var x: CGFloat = 0
                while x < size.width {
                    let r = CGRect(x: x + 2, y: y + 2, width: tile - 4, height: tile - 4)
                    ctx.fill(Path(roundedRect: r, cornerRadius: 6), with: .color(Color(hex: 0x1B2340)))
                    x += tile
                }
                y += tile
            }
        }
        .ignoresSafeArea()
    }
}

private struct Water: View {
    var body: some View {
        TimelineView(.animation) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            Canvas { ctx, size in
                for layer in 0..<2 {
                    var path = Path()
                    let amp: CGFloat = layer == 0 ? 7 : 5
                    let phase = t * (layer == 0 ? 1.6 : -1.1)
                    path.move(to: CGPoint(x: 0, y: size.height))
                    for x in stride(from: 0, through: size.width, by: 6) {
                        let y = 14 + CGFloat(layer) * 8 + sin(Double(x) / 38 + phase) * amp
                        path.addLine(to: CGPoint(x: x, y: y))
                    }
                    path.addLine(to: CGPoint(x: size.width, y: size.height))
                    path.closeSubpath()
                    ctx.fill(path, with: .color(Brand.cool.opacity(layer == 0 ? 0.22 : 0.3)))
                }
            }
        }
    }
}
