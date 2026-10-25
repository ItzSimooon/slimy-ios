import AppIntents
import SwiftUI
import WidgetKit

// Home Screen, Lock Screen and StandBy widgets. They read the snapshot the
// app writes into the shared App Group container and project the needs
// forward, so the bars keep moving between app launches. Without the App
// Group (some sideloading setups) they show a short note instead.

struct SlimeEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot?
    let needs: Needs
}

struct SlimeProvider: TimelineProvider {
    func placeholder(in context: Context) -> SlimeEntry {
        SlimeEntry(date: .now, snapshot: nil, needs: Needs())
    }

    func getSnapshot(in context: Context, completion: @escaping (SlimeEntry) -> Void) {
        completion(entry(at: .now))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SlimeEntry>) -> Void) {
        let now = Date.now
        // Every 30 minutes for six hours; WidgetKit decides the real pace.
        let entries = (0..<12).map { entry(at: now.addingTimeInterval(Double($0) * 1800)) }
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(6 * 3600))))
    }

    private func entry(at date: Date) -> SlimeEntry {
        guard let snapshot = GameStorage.readSnapshot() else {
            return SlimeEntry(date: date, snapshot: nil, needs: Needs())
        }
        return SlimeEntry(date: date, snapshot: snapshot, needs: snapshot.projectedNeeds(at: date))
    }
}

extension SlimeEntry {
    /// The need to mention, if one is low.
    var wish: Need? {
        let lowest = needs.lowest
        return lowest.value < 35 ? lowest.need : nil
    }

    var anim: SpriteAnim {
        guard let snapshot else { return .idle }
        if snapshot.isAsleep { return .sleep }
        return wish == nil ? .idle : .sad
    }
}

/// The slime, drawn still (widgets don't animate).
private struct WidgetSlime: View {
    let entry: SlimeEntry
    var mirrored = false

    var body: some View {
        let stage = entry.snapshot?.stage ?? .young
        let frames = SlimeSprites.frames(stage, entry.anim)
        let anchors = SlimeSprites.anchors(stage, entry.anim)
        let i = min(entry.anim.posterIndex(at: entry.date), frames.count - 1)
        PixelSlime(frame: frames[max(0, i)], skin: entry.snapshot?.skin ?? .lime,
                   accessories: entry.snapshot?.equipped ?? Accessories(), stage: stage,
                   anchor: i < anchors.count ? anchors[max(0, i)] : nil)
            .scaleEffect(x: mirrored ? -1 : 1, y: 1)
            .accessibilityHidden(true)
    }
}

private struct NeedRow: View {
    let needs: Needs
    var compact = false

    var body: some View {
        HStack(spacing: compact ? 4 : 6) {
            ForEach(Need.allCases) { need in
                VStack(spacing: 2) {
                    PixelItem(name: iconName(need)).frame(width: 11, height: 11)
                    PixelBar(value: Int(needs[need].rounded()), segments: 4, color: Brand.color(for: need), height: 4, gap: 1)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Need.allCases.map { "\($0.title) \(Int(needs[$0].rounded())) percent" }.joined(separator: ", "))
    }

    private func iconName(_ need: Need) -> String {
        switch need {
        case .hunger: "apple"
        case .fun: "ball"
        case .energy: "starJelly"
        case .clean: "bubbleItem"
        case .love: "heart"
        }
    }
}

private func statusLine(_ entry: SlimeEntry) -> String {
    guard let s = entry.snapshot else { return "Open Slimy once." }
    if s.isAsleep { return "sleeping" }
    if let wish = entry.wish { return wish.lowHint }
    return entry.date.timeIntervalSince(s.writtenAt) < 1800 ? s.line : "how are you?"
}

struct SlimeWidgetView: View {
    let entry: SlimeEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                WidgetSlime(entry: entry).padding(6)
            }
            .accessibilityLabel("\(entry.snapshot?.name ?? "Slime"): \(statusLine(entry))")
        case .accessoryInline:
            Text("\(entry.snapshot?.name ?? "Slime"): \(statusLine(entry))")
        case .accessoryRectangular:
            HStack(spacing: 6) {
                WidgetSlime(entry: entry).frame(width: 36, height: 30)
                VStack(alignment: .leading, spacing: 1) {
                    Text(entry.snapshot?.name ?? "Slime").font(Brand.pixelFixed(13))
                    Text(statusLine(entry)).font(.system(size: 12, weight: .medium, design: .rounded)).lineLimit(2)
                }
                Spacer(minLength: 0)
            }
        case .systemMedium:
            HStack(spacing: 12) {
                VStack(spacing: 0) {
                    WidgetSlime(entry: entry).frame(width: 96, height: 80)
                    Rectangle().fill(Brand.line).frame(width: 100, height: 3)
                }
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(entry.snapshot?.name ?? "Slimy").font(Brand.pixelFixed(17)).foregroundStyle(Brand.text)
                        Spacer()
                        if let s = entry.snapshot {
                            Text("LV \(s.level)").font(Brand.pixelFixed(12)).foregroundStyle(Brand.accent(s.skin))
                        }
                    }
                    Text(statusLine(entry)).font(Brand.body(13, .medium)).foregroundStyle(Brand.muted).lineLimit(2)
                    if entry.snapshot != nil { NeedRow(needs: entry.needs) }
                    HStack(spacing: 6) {
                        Button(intent: FeedSlimeIntent()) { chip("Feed", "apple") }.buttonStyle(.plain)
                        Button(intent: PetSlimeIntent()) { chip("Pet", "heart") }.buttonStyle(.plain)
                    }
                }
            }
        default:
            VStack(spacing: 6) {
                HStack {
                    Text(entry.snapshot?.name ?? "Slimy").font(Brand.pixelFixed(14)).foregroundStyle(Brand.text).lineLimit(1)
                    Spacer()
                }
                WidgetSlime(entry: entry).frame(maxWidth: .infinity, maxHeight: .infinity)
                Text(statusLine(entry)).font(Brand.body(11, .medium)).foregroundStyle(Brand.muted).lineLimit(1)
                if entry.snapshot != nil { NeedRow(needs: entry.needs, compact: true) }
            }
        }
    }

    private func chip(_ title: String, _ icon: String) -> some View {
        HStack(spacing: 4) {
            PixelItem(name: icon).frame(width: 12, height: 12)
            Text(title).font(Brand.pixelFixed(11)).foregroundStyle(Brand.text)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(Brand.raised)
        .overlay(Rectangle().strokeBorder(Brand.line, lineWidth: 1.5))
    }
}

struct SlimeHomeWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "de.hyprnode.slime.home", provider: SlimeProvider()) { entry in
            SlimeWidgetView(entry: entry)
                .containerBackground(Brand.bg, for: .widget)
                .widgetURL(URL(string: "slimy://home"))
        }
        .configurationDisplayName("Slime")
        .description("Your slime and what it needs right now.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}
