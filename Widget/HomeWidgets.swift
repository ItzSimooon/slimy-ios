import AppIntents
import SwiftUI
import UIKit
import WidgetKit

// Home Screen, Lock Screen and StandBy widgets. Native chrome (SF Rounded,
// system colours, light and dark) around the pixel slime. The data comes from
// `SlimeProvider` (WidgetData.swift).

/// The slime itself: Home Screen small and medium (with Feed and Pet
/// buttons), Lock Screen circular, rectangular and inline.
struct SlimeHomeWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "de.hyprnode.slime.home", provider: SlimeProvider()) { entry in
            SlimeWidgetView(entry: entry)
                .containerBackground(for: .widget) { WidgetBackground(skin: entry.snapshot?.skin) }
                .widgetURL(URL(string: "slimy://home"))
        }
        .configurationDisplayName("Slime")
        .description("Your slime and how it's doing. The medium size can feed and pet it.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

/// All five needs at a glance.
struct SlimeNeedsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "de.hyprnode.slime.needs", provider: SlimeProvider()) { entry in
            NeedsWidgetView(entry: entry)
                .containerBackground(for: .widget) { WidgetBackground(skin: entry.snapshot?.skin) }
                .widgetURL(URL(string: "slimy://home"))
        }
        .configurationDisplayName("Needs")
        .description("All five needs at a glance.")
        .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryRectangular])
    }
}

/// The system background with a faint wash of the slime's own colour.
private struct WidgetBackground: View {
    let skin: Skin?

    var body: some View {
        ZStack {
            Color(uiColor: .systemBackground)
            if let skin {
                Brand.accent(skin).opacity(0.1)
            }
        }
    }
}

private enum WidgetType {
    static let title = Font.system(.headline, design: .rounded)
    static let status = Font.system(.subheadline, design: .rounded, weight: .semibold)
    static let statusSmall = Font.system(.footnote, design: .rounded, weight: .semibold)
    static let detail = Font.system(.footnote, design: .rounded)
    static let caption = Font.system(.caption, design: .rounded, weight: .semibold)
    static let value = Font.system(.caption2, design: .rounded, weight: .semibold)
    static let button = Font.system(.subheadline, design: .rounded, weight: .semibold)
}

// MARK: - Slime widget

struct SlimeWidgetView: View {
    let entry: SlimeEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryCircular:
            CircularSlime(entry: entry)
        case .accessoryRectangular:
            RectangularSlime(entry: entry)
        case .accessoryInline:
            InlineSlime(entry: entry)
        case .systemMedium:
            MediumSlime(entry: entry)
        default:
            SmallSlime(entry: entry)
        }
    }
}

/// "Hungry", with the need's symbol in its colour.
private struct StatusLabel: View {
    let status: SlimeStatus
    let font: Font

    var body: some View {
        Label {
            Text(status.title)
                .foregroundStyle(.primary)
        } icon: {
            Image(systemName: status.symbol)
                .foregroundStyle(status.tint ?? Color.secondary)
        }
        .font(font)
        .lineLimit(1)
    }
}

/// Name on the left, level on the right.
private struct WidgetHeader: View {
    let entry: SlimeEntry
    var longLevel = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text(entry.name)
                .font(WidgetType.title)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Spacer(minLength: 4)
            if let level = entry.snapshot?.level {
                Text(levelText(level))
                    .font(WidgetType.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .accessibilityLabel("Level \(level)")
            }
        }
    }

    private func levelText(_ level: Int) -> String {
        longLevel ? "Level \(level)" : "Lv \(level)"
    }
}

private struct SmallSlime: View {
    let entry: SlimeEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            WidgetHeader(entry: entry)
            SlimeHero(slime: entry.slime)
                .padding(.vertical, 4)
            StatusLabel(status: entry.status, font: WidgetType.statusSmall)
                .invalidatableContent()
        }
        .dynamicTypeSize(...DynamicTypeSize.xLarge)
        .accessibilityElement(children: .combine)
    }
}

private struct MediumSlime: View {
    let entry: SlimeEntry

    var body: some View {
        let status = entry.status
        HStack(spacing: 14) {
            VStack(spacing: 8) {
                SlimeHero(slime: entry.slime)
                if entry.snapshot != nil {
                    NeedDials(needs: entry.needs, size: 22)
                        .invalidatableContent()
                }
            }
            .frame(width: 128)

            VStack(alignment: .leading, spacing: 2) {
                WidgetHeader(entry: entry, longLevel: true)
                StatusLabel(status: status, font: WidgetType.status)
                    .invalidatableContent()
                detail(status)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .padding(.top, 1)
                // Lowest priority, so the detail gets its second line first.
                Spacer(minLength: 4)
                    .layoutPriority(-1)
                if entry.snapshot != nil {
                    HStack(spacing: 8) {
                        WidgetActionButton(intent: FeedSlimeIntent(), title: "Feed", symbol: "fork.knife",
                                           tint: Brand.tint(for: .hunger))
                        WidgetActionButton(intent: PetSlimeIntent(), title: "Pet", symbol: "hand.raised.fill",
                                           tint: Brand.tint(for: .love))
                    }
                }
            }
        }
        .dynamicTypeSize(...DynamicTypeSize.xLarge)
    }

    /// The slime's own words while they're fresh and nothing is low,
    /// otherwise what would help.
    @ViewBuilder
    private func detail(_ status: SlimeStatus) -> some View {
        if entry.lowNeed == nil, let speech = entry.speech {
            Text(speech)
                .font(Brand.voice(14, relativeTo: .footnote))
                .accessibilityLabel("\(entry.name) says: \(speech)")
        } else {
            Text(status.detail)
                .font(WidgetType.detail)
        }
    }
}

/// A capsule button in an interactive widget. The intent runs in the app's
/// process and the widget reloads when it's done.
private struct WidgetActionButton<Action: AppIntent>: View {
    let intent: Action
    let title: String
    let symbol: String
    let tint: Color

    var body: some View {
        Button(intent: intent) {
            Label {
                Text(title)
                    .foregroundStyle(.primary)
            } icon: {
                Image(systemName: symbol)
                    .foregroundStyle(tint)
            }
            .font(WidgetType.button)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(tint.opacity(0.16), in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// Lock Screen: the slime inside a ring that fills with how well it's doing.
private struct CircularSlime: View {
    let entry: SlimeEntry

    var body: some View {
        Gauge(value: entry.snapshot == nil ? 0 : entry.needs.average, in: 0...100) {
            Text("Wellbeing")
        } currentValueLabel: {
            entry.slime
                .padding(2)
        }
        .gaugeStyle(.accessoryCircularCapacity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(entry.accessibilitySummary)
    }
}

private struct RectangularSlime: View {
    let entry: SlimeEntry

    var body: some View {
        let status = entry.status
        HStack(spacing: 8) {
            entry.slime
                .frame(width: 44, height: 37)
            VStack(alignment: .leading, spacing: 0) {
                Text(entry.name)
                    .font(WidgetType.title)
                    .lineLimit(1)
                    .widgetAccentable()
                Label(status.title, systemImage: status.symbol)
                    .font(.system(.subheadline, design: .rounded, weight: .medium))
                    .lineLimit(1)
                Text(status.detail)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .dynamicTypeSize(...DynamicTypeSize.xLarge)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(entry.accessibilitySummary)
    }
}

private struct InlineSlime: View {
    let entry: SlimeEntry

    var body: some View {
        let status = entry.status
        Label(status.sentence, systemImage: status.symbol)
    }
}

// MARK: - Needs widget

struct NeedsWidgetView: View {
    let entry: SlimeEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryCircular:
            LowestNeedGauge(entry: entry)
        case .accessoryRectangular:
            NeedsRectangular(entry: entry)
        default:
            NeedsSmall(entry: entry)
        }
    }
}

private struct NeedsSmall: View {
    let entry: SlimeEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(entry.name)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .lineLimit(1)
                Spacer(minLength: 4)
                Image(systemName: entry.status.symbol)
                    .font(WidgetType.caption)
                    .foregroundStyle(entry.status.tint ?? Color.secondary)
                    .accessibilityLabel(entry.status.title)
            }
            Spacer(minLength: 6)
            if entry.snapshot == nil {
                Text(entry.status.detail)
                    .font(WidgetType.detail)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
                Spacer(minLength: 0)
            } else {
                // Fits the smallest small widget (148 pt) at the largest
                // allowed text size.
                VStack(spacing: 5) {
                    ForEach(Need.allCases) { need in
                        NeedRow(need: need, value: entry.needs[need])
                    }
                }
                .invalidatableContent()
            }
        }
        .dynamicTypeSize(...DynamicTypeSize.large)
    }
}

/// Pixel icon, meter and percentage for one need.
private struct NeedRow: View {
    let need: Need
    let value: Double

    var body: some View {
        HStack(spacing: 7) {
            PixelItem(name: Brand.icon(for: need))
                .frame(width: 13, height: 13)
            NeedBar(need: need, value: value, height: 6)
            Text("\(Int(value.rounded()))")
                .font(WidgetType.value)
                .monospacedDigit()
                .foregroundStyle(.secondary)
                .frame(minWidth: 22, alignment: .trailing)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(need.title)
        .accessibilityValue("\(Int(value.rounded())) percent")
    }
}

private struct NeedsRectangular: View {
    let entry: SlimeEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(entry.name)
                .font(WidgetType.title)
                .lineLimit(1)
                .widgetAccentable()
            if entry.snapshot == nil {
                Text(entry.status.detail)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            } else {
                NeedDials(needs: entry.needs, size: 26)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .dynamicTypeSize(...DynamicTypeSize.xLarge)
    }
}

/// Lock Screen: the need that is lowest right now, as a ring.
private struct LowestNeedGauge: View {
    let entry: SlimeEntry

    var body: some View {
        if entry.snapshot == nil {
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "drop.fill")
                    .font(.system(.title3, design: .rounded, weight: .semibold))
            }
            .accessibilityLabel(entry.accessibilitySummary)
        } else {
            let lowest = entry.needs.lowest
            Gauge(value: lowest.value, in: 0...100) {
                Text(lowest.need.title)
            } currentValueLabel: {
                VStack(spacing: 0) {
                    Image(systemName: lowest.need.symbol)
                        .font(.system(size: 11, weight: .semibold))
                    Text("\(Int(lowest.value.rounded()))")
                        .font(.system(.title3, design: .rounded, weight: .semibold))
                        .monospacedDigit()
                        .minimumScaleFactor(0.6)
                }
            }
            .gaugeStyle(.accessoryCircularCapacity)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(lowest.need.title), the lowest need")
            .accessibilityValue("\(Int(lowest.value.rounded())) percent")
        }
    }
}
