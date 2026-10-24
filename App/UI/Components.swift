import SwiftUI

/// Deep violet night with a soft glow, behind every screen.
struct BrandBackground: View {
    var body: some View {
        ZStack {
            Brand.void
            RadialGradient(colors: [Brand.hypr.opacity(0.22), .clear],
                           center: .init(x: 0.5, y: -0.05), startRadius: 10, endRadius: 520)
            RadialGradient(colors: [Brand.warm.opacity(0.07), .clear],
                           center: .init(x: 1.1, y: 0.9), startRadius: 10, endRadius: 380)
        }
        .ignoresSafeArea()
    }
}

/// A panel. Only used where a block really is its own object.
struct Panel<Content: View>: View {
    var padding: CGFloat = 16
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Brand.deep, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Brand.line, lineWidth: 1))
    }
}

/// Small uppercase mono label: the hyprnode "terminal" voice.
struct Eyebrow: View {
    let text: String
    var color: Color = Brand.muted

    var body: some View {
        Text(text.uppercased())
            .font(Brand.mono(10, .bold))
            .tracking(1.4)
            .foregroundStyle(color)
    }
}

struct CoinChip: View {
    let coins: Int

    var body: some View {
        HStack(spacing: 5) {
            ZStack {
                Circle().fill(Brand.gold)
                Circle().strokeBorder(Color.black.opacity(0.25), lineWidth: 1.5).padding(2)
            }
            .frame(width: 14, height: 14)
            Text("\(coins)")
                .font(Brand.mono(13, .bold))
                .foregroundStyle(Brand.gold)
                .contentTransition(.numericText())
                .monospacedDigit()
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Brand.raised, in: Capsule())
    }
}

struct IconButton: View {
    let symbol: String
    var badge = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(Brand.text)
                .frame(width: 40, height: 40)
                .background(Brand.raised, in: Circle())
                .overlay(alignment: .topTrailing) {
                    if badge {
                        Circle().fill(Brand.warm).frame(width: 9, height: 9).offset(x: -2, y: 2)
                    }
                }
        }
        .buttonStyle(SquishStyle())
    }
}

/// Big chunky jelly button.
struct JellyButton: View {
    let title: String
    var symbol: String?
    var tint: Color = Brand.hypr
    var filled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let symbol { Image(systemName: symbol).font(.system(size: 15, weight: .bold)) }
                Text(title).font(Brand.display(16, .bold))
            }
            .foregroundStyle(filled ? Brand.void : tint)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background {
                if filled {
                    Capsule().fill(tint)
                        .shadow(color: tint.opacity(0.45), radius: 14, y: 4)
                } else {
                    Capsule().fill(tint.opacity(0.14))
                }
            }
        }
        .buttonStyle(SquishStyle())
    }
}

/// Buttons squash like jelly when pressed.
struct SquishStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(x: configuration.isPressed ? 1.06 : 1, y: configuration.isPressed ? 0.9 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.45), value: configuration.isPressed)
    }
}

struct ToastView: View {
    let toast: Toast

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: toast.good ? "sparkles" : "exclamationmark.bubble.fill")
                .foregroundStyle(toast.good ? Brand.hyprGlow : Brand.warm)
            Text(toast.text)
                .font(Brand.display(14, .semibold))
                .foregroundStyle(Brand.text)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().strokeBorder(Brand.line, lineWidth: 1))
        .shadow(color: .black.opacity(0.4), radius: 16, y: 6)
    }
}

/// Close button for full-screen modes.
struct CloseButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "xmark")
                .font(.system(size: 14, weight: .black))
                .foregroundStyle(Brand.text)
                .frame(width: 36, height: 36)
                .background(Brand.raised.opacity(0.9), in: Circle())
        }
        .accessibilityLabel("Close")
    }
}
