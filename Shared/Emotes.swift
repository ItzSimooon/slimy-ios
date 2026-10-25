import SwiftUI

/// A loop of ten pixel icons the slime "says" on the right of the island.
/// Names match the reels in tools/emotes.py and the Emote-<name> fonts.
enum EmoteReel: String, Codable, Hashable, CaseIterable {
    case happyA, happyB, happyC, hunger, fun, sleepy, clean, love
    case battery, zap, wifi, confused, morning, night

    var color: Color {
        switch self {
        case .happyA, .happyB, .happyC, .love: Brand.warm
        case .hunger: Brand.color(for: .hunger)
        case .fun: Brand.color(for: .fun)
        case .sleepy, .night: Brand.cool
        case .clean: Brand.color(for: .clean)
        case .battery: Brand.danger
        case .zap, .morning: Brand.gold
        case .wifi: Brand.slime
        case .confused: Brand.cool
        }
    }

    var fontName: String { "Emote-\(rawValue)" }

    /// The first icon of the reel, for when it can't animate.
    var poster: [String] {
        let first = SlimeSprites.emoteReels[rawValue]?.first ?? "heart"
        return SlimeSprites.emoteIcons[first] ?? []
    }

    /// One of the happy loops, changing with every update so it feels random.
    static func happy(at date: Date) -> EmoteReel {
        [.happyA, .happyB, .happyC][Calendar.current.component(.minute, from: date) % 3]
    }

    init(_ need: Need) {
        switch need {
        case .hunger: self = .hunger
        case .fun: self = .fun
        case .energy: self = .sleepy
        case .clean: self = .clean
        case .love: self = .love
        }
    }

    init?(_ event: PhoneEvent) {
        switch event {
        case .batteryLow, .batteryCritical, .lowPowerOn: self = .battery
        case .batteryFull, .chargerConnected: self = .zap
        case .wifiConnected, .backOnline: self = .wifi
        case .wifiDisconnected, .cellular, .offline, .airplaneOn: self = .confused
        case .goodMorning, .alarm: self = .morning
        case .goodNight: self = .night
        case .chargerDisconnected, .airplaneOff, .lowPowerOff, .newHour: return nil
        }
    }
}

extension SlimeActivityAttributes.ContentState {
    /// What the slime is emoting right now.
    var emote: EmoteReel {
        if let event, let reel = EmoteReel(event) { return reel }
        if mood == .sleep { return .sleepy }
        switch reaction {
        case .love: return .love
        case .bath: return .clean
        default: break
        }
        if let wish { return EmoteReel(wish) }
        return .happy(at: anchor)
    }
}

/// The emote icon. Animated with a timer font like the slime, or the reel's
/// first icon when animation is off.
struct EmoteView: View {
    let reel: EmoteReel
    let anchor: Date
    let size: CGFloat
    var animated = true

    var body: some View {
        Group {
            if animated {
                Text(timerInterval: anchor...anchor.addingTimeInterval(SlimeFont.span), countsDown: false)
                    .font(.custom(reel.fontName, fixedSize: size))
                    .foregroundStyle(reel.color)
                    .lineLimit(1)
                    .multilineTextAlignment(.trailing)
                    .frame(width: size * 10, height: size, alignment: .trailing)
                    .frame(width: size, height: size, alignment: .trailing)
                    .clipped()
            } else {
                IconShape(rows: reel.poster)
                    .fill(reel.color)
                    .frame(width: size, height: size)
            }
        }
        .accessibilityHidden(true)
    }
}

/// A one-colour pixel icon ('#' = filled).
struct IconShape: Shape {
    let rows: [String]

    func path(in rect: CGRect) -> Path {
        let cols = CGFloat(rows.first?.count ?? 1), count = CGFloat(rows.count)
        let px = min(rect.width / cols, rect.height / max(count, 1))
        let ox = rect.minX + (rect.width - px * cols) / 2
        let oy = rect.minY + (rect.height - px * count) / 2
        var path = Path()
        for (y, row) in rows.enumerated() {
            for (x, ch) in row.enumerated() where ch == "#" {
                path.addRect(CGRect(x: ox + CGFloat(x) * px, y: oy + CGFloat(y) * px, width: px + 0.3, height: px + 0.3))
            }
        }
        return path
    }
}
