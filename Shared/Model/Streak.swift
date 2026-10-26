import Foundation

/// The daily gift and the visit streak, as saved.
///
/// Days are counted as calendar dates (Gregorian, local time), never as
/// 24-hour spans, so daylight saving and travelling don't break a streak.
/// The calendar of seven gifts moves on with every gift opened: missing a
/// day never takes a gift away, only the streak count starts again.
struct StreakState: Codable, Hashable {
    /// Day key (yyyy-MM-dd) of the last gift opened.
    var lastDay: String?
    /// Days in a row with a gift opened, ending on `lastDay`.
    var count = 0
    var best = 0
    /// Gifts opened in total. Moves the seven-day calendar along.
    var opened = 0
}

/// One gift in the seven-day calendar.
struct DailyGift: Hashable, Identifiable {
    static let days = 7

    /// 1...7: its place in this week's calendar.
    let day: Int
    let coins: Int
    /// A catalog item, if the gift holds one.
    let item: String?
    /// A crate. The seventh day brings a mystery crate.
    var crate: CrateKind? = nil

    var id: Int { day }
    var isSpecial: Bool { day == Self.days }

    /// "20 coins", the crate or the item's name.
    var title: String {
        if let crate { return crate.title }
        if let item, let entry = Catalog.item(item) { return entry.title }
        return "\(coins) coins"
    }

    /// Everything in it: "20 coins", or "Mystery crate and 60 coins".
    var summary: String {
        if let crate { return "\(crate.title) and \(coins) coins" }
        if let item, let entry = Catalog.item(item) { return "\(entry.title) and \(coins) coins" }
        return "\(coins) coins"
    }

    /// Item sprite for tiles and celebrations.
    var icon: String {
        if let crate { return crate.sprite }
        if let item, let entry = Catalog.item(item) { return entry.sprite }
        return "coin"
    }
}

/// The gift calendar as it stands right now, for the UI.
struct StreakInfo: Equatable {
    /// Days in a row. Still counts while the last gift was today or yesterday.
    let current: Int
    let best: Int
    /// Gifts opened in total.
    let opened: Int
    /// Today's gift is waiting.
    let canClaim: Bool
    let claimedToday: Bool
    /// 1...7: today's place in the calendar, opened or not.
    let today: Int
    /// This week's seven gifts.
    let week: [DailyGift]
    /// When the next gift can be opened, if it can't be opened now.
    let nextGift: Date?

    var todayGift: DailyGift { week[today - 1] }

    func isOpened(_ gift: DailyGift) -> Bool {
        gift.day < today || (gift.day == today && claimedToday)
    }
}

enum Streak {
    /// Coins for days 1 to 7. The seventh day also brings a crate.
    static let coins = [10, 15, 20, 25, 30, 40, 60]
    /// The crate on every seventh day.
    static let weekCrate: CrateKind = .mystery
    /// Streak lengths that go into the memory book.
    static let milestones: Set<Int> = [7, 30, 100, 365]
    static let xp = 8

    static func dayKey(_ date: Date, calendar: Calendar = .current) -> String {
        Challenges.dayKey(date, calendar: .gregorian(like: calendar))
    }

    /// Ledger id of a day's gift: paid once per date.
    static func receipt(_ dayKey: String) -> String { "gift:\(dayKey)" }

    /// Whole calendar days from one day key to another, negative if `to` is
    /// earlier. Compares dates only, so time zones and daylight saving can't
    /// add or lose a day. Nil if a key is damaged.
    static func days(from: String, to: String) -> Int? {
        guard let a = noon(of: from), let b = noon(of: to) else { return nil }
        return utc.dateComponents([.day], from: a, to: b).day
    }

    private static let utc: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(secondsFromGMT: 0) ?? .current
        return c
    }()

    private static func noon(of key: String) -> Date? {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, (1...12).contains(parts[1]), (1...31).contains(parts[2]) else { return nil }
        return utc.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: 12))
    }
}

extension GameState {
    /// The gift calendar at `now`, without changing anything.
    func streakInfo(at now: Date, calendar: Calendar = .current) -> StreakInfo {
        let today = Streak.dayKey(now, calendar: calendar)
        let gap = streak.lastDay.flatMap { Streak.days(from: $0, to: today) }
        let claimedToday = gap == 0
        // One day behind the last gift (the clock went back, or a flight
        // west): wait for tomorrow. Further behind means the clock was set
        // ahead earlier; start again from here, like the needs do.
        let waiting = gap == -1
        let canClaim = hatched && !claimedToday && !waiting && !wallet.hasClaimed(Streak.receipt(today))
        let alive = gap.map { (-1...1).contains($0) } ?? false
        let done = max(0, claimedToday ? streak.opened - 1 : streak.opened)
        let index = done % DailyGift.days
        let week = (0..<DailyGift.days).map { i -> DailyGift in
            let last = i == DailyGift.days - 1
            return DailyGift(day: i + 1, coins: Streak.coins[i], item: nil, crate: last ? Streak.weekCrate : nil)
        }
        var next: Date?
        if hatched, !canClaim {
            let g = Calendar.gregorian(like: calendar)
            let ahead = waiting ? 2 : 1
            next = g.date(byAdding: .day, value: ahead, to: g.startOfDay(for: now))
        }
        return StreakInfo(current: alive ? streak.count : 0, best: streak.best, opened: streak.opened,
                          canClaim: canClaim, claimedToday: claimedToday, today: index + 1,
                          week: week, nextGift: next)
    }

    /// Opens today's gift: pays its coins (and on day 7 its crate), extends
    /// the streak and checks achievements. Nil if there's nothing to open.
    mutating func claimDailyGift(now: Date, calendar: Calendar = .current) -> (gift: DailyGift, outcome: Outcome)? {
        tick(now: now, calendar: calendar)
        let info = streakInfo(at: now, calendar: calendar)
        guard info.canClaim else { return nil }
        let today = Streak.dayKey(now, calendar: calendar)
        let gift = info.todayGift
        guard wallet.claim(Streak.receipt(today), coins: gift.coins, at: now) else { return nil }

        let gap = streak.lastDay.flatMap { Streak.days(from: $0, to: today) }
        let startedOver = gap != 1 && streak.count >= 2
        streak.count = gap == 1 ? streak.count + 1 : 1
        streak.best = max(streak.best, streak.count)
        streak.lastDay = today
        streak.opened += 1
        if let item = gift.item { inventory.owned.insert(item) }
        if let crate = gift.crate { addCrate(crate) }
        if Streak.milestones.contains(streak.count) {
            memory.add(MemoryEntry(kind: .streak, at: now, detail: "\(streak.count)", keepsake: true))
        }

        slime.isAsleep = false
        slime.needs.add(.love, 5)
        slime.needs.add(.fun, 4)
        slime.bond = min(100, slime.bond + 0.5)
        slime.feel(.joy, 0.2)
        slime.feel(.excitement, gift.isSpecial ? 0.35 : 0.2)
        slime.react(.celebrate, at: now)
        noteCare(.gift, now: now)

        let category: LineCategory
        if gift.isSpecial {
            category = .giftWeek
        } else if startedOver {
            category = .streakFresh
        } else if streak.count >= 3 {
            category = .streak
        } else {
            category = .dailyGift
        }
        let context = LineContext(item: gift.title.lowercased(), streak: streak.count)
        var line = Dialogue.say(category, in: &self, context: context, at: now, calendar: calendar)
        var events = gainXP(Streak.xp, now: now, line: &line)
        events += checkAchievements(now: now)
        slime.say = Utterance(text: line, at: now)
        return (gift, Outcome(ok: true, line: line, events: events))
    }
}

extension StreakState {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = StreakState()
        lastDay = c.value(.lastDay, d.lastDay)
        count = max(0, c.value(.count, d.count))
        best = max(0, c.value(.best, d.best))
        opened = max(0, c.value(.opened, d.opened))
    }
}
