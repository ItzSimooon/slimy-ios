import SwiftUI
import UIKit

/// Diagnostic strip for the Lock Screen: the same ticking timer drawn five
/// ways, to see how iOS renders timer text with the animation fonts.
struct TimerProbe: View {
    let anchor: Date
    let color: Color

    private var range: ClosedRange<Date> { anchor...anchor.addingTimeInterval(SlimeFont.span) }
    private let font = "Slime-Idle-Body"

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            row("A") {
                Text(timerInterval: range, countsDown: false)
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
            }
            row("B") {
                Text("0123456789")
                    .font(.custom(font, fixedSize: 14))
            }
            row("C") {
                Text(timerInterval: range, countsDown: false)
                    .font(.custom(font, fixedSize: 14))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            row("D") {
                Text(timerInterval: range, countsDown: false)
                    .font(.custom(font, fixedSize: 14))
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .background(Color.white.opacity(0.12))
            }
            row("E") {
                Text(timerInterval: range, countsDown: false)
                    .font(.custom(font, fixedSize: 14))
                    .lineLimit(1)
                    .multilineTextAlignment(.trailing)
                    .fixedSize()
                    .frame(width: 14 * SlimeFont.aspect, height: 14, alignment: .trailing)
                    .clipped()
                    .background(Color.red.opacity(0.35))
            }
            Text("F  Schrift in Erweiterung: \(UIFont(name: font, size: 10) != nil ? "ja" : "nein")")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
        }
        .foregroundStyle(color)
    }

    private func row<Content: View>(_ label: String, @ViewBuilder _ content: () -> Content) -> some View {
        HStack(spacing: 6) {
            Text(label)
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundStyle(.white)
            content()
        }
        .frame(height: 16)
    }
}
