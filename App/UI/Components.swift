import SwiftUI

/// Flat background behind every screen.
struct BrandBackground: View {
    var body: some View {
        Brand.bg.ignoresSafeArea()
    }
}

/// A boxed area with a hard 2px border. Used only where a block really is
/// its own object.
struct Panel<Content: View>: View {
    var padding: CGFloat = 14
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Brand.panel)
            .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 2))
    }
}

/// Small pixel-font label above a section.
struct Eyebrow: View {
    let text: String
    var color: Color = Brand.muted

    var body: some View {
        Text(text.uppercased())
            .font(Brand.pixel(12))
            .tracking(1)
            .foregroundStyle(color)
    }
}

struct CoinChip: View {
    let coins: Int

    var body: some View {
        HStack(spacing: 6) {
            PixelItem(name: "coin").frame(width: 14, height: 14)
            Text("\(coins)")
                .font(Brand.pixel(16))
                .foregroundStyle(Brand.gold)
                .contentTransition(.numericText())
                .monospacedDigit()
        }
        .padding(.horizontal, 10)
        .frame(height: 36)
        .background(Brand.raised)
        .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 2))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(coins) Münzen")
    }
}

struct IconButton: View {
    let symbol: String
    let label: String
    var badge = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(Brand.text)
                .frame(width: 36, height: 36)
                .background(Brand.raised)
                .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 2))
                .overlay(alignment: .topTrailing) {
                    if badge {
                        Rectangle().fill(Brand.warm).frame(width: 8, height: 8).offset(x: 2, y: -2)
                    }
                }
        }
        .buttonStyle(PressStyle(depth: 0))
        .accessibilityLabel(label)
    }
}

/// The main button: flat colour, dark 2px border, a hard shadow it sinks into.
struct PixelButton: View {
    let title: String
    var symbol: String?
    var tint: Color = Brand.slime
    var filled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let symbol { Image(systemName: symbol).font(.system(size: 14, weight: .bold)) }
                Text(title).font(Brand.pixel(17))
            }
            .foregroundStyle(filled ? Brand.bg : tint)
            .frame(maxWidth: .infinity)
            .frame(height: 48)
            .background(filled ? tint : Brand.panel)
            .overlay(Rectangle().strokeBorder(filled ? Color.black.opacity(0.55) : tint, lineWidth: 2))
        }
        .buttonStyle(PressStyle())
    }
}

/// Pressing pushes the button down into its shadow, like a real key.
struct PressStyle: ButtonStyle {
    var depth: CGFloat = 4

    func makeBody(configuration: Configuration) -> some View {
        let down = configuration.isPressed
        configuration.label
            .offset(y: down ? depth : 0)
            .background(alignment: .top) {
                if depth > 0 {
                    Rectangle().fill(Color.black).offset(y: depth)
                }
            }
            .opacity(down && depth == 0 ? 0.7 : 1)
            .animation(.easeOut(duration: 0.06), value: down)
    }
}

struct ToastView: View {
    let toast: Toast

    var body: some View {
        Text(toast.text)
            .font(Brand.pixel(16))
            .foregroundStyle(toast.good ? Brand.text : Brand.warm)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Brand.raised)
            .overlay(Rectangle().strokeBorder(toast.good ? Brand.slime : Brand.warm, lineWidth: 2))
            .background(Rectangle().fill(Color.black).offset(y: 4))
    }
}

struct CloseButton: View {
    let action: () -> Void

    var body: some View {
        IconButton(symbol: "xmark", label: "Schließen", action: action)
    }
}

struct SheetHeader: View {
    let eyebrow: String
    let title: String
    let close: () -> Void

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 4) {
                Eyebrow(text: eyebrow, color: Brand.slime)
                Text(title)
                    .font(Brand.pixel(28))
                    .foregroundStyle(Brand.text)
            }
            Spacer()
            CloseButton(action: close)
        }
    }
}

/// One need as a labelled block bar.
struct NeedRow: View {
    let need: Need
    let value: Int

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: need.symbol)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(Brand.color(for: need))
                .frame(width: 18)
            Text(need.title)
                .font(Brand.pixel(14))
                .foregroundStyle(Brand.text)
                .frame(width: 66, alignment: .leading)
            PixelBar(value: value, segments: 10, color: value < 25 ? Brand.danger : Brand.color(for: need), height: 12)
            Text("\(value)")
                .font(Brand.pixel(14))
                .foregroundStyle(value < 25 ? Brand.danger : Brand.muted)
                .monospacedDigit()
                .frame(width: 30, alignment: .trailing)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(need.title) \(value) Prozent")
    }
}
