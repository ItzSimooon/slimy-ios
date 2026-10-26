import Foundation

/// The time of year. Decorations, lines and the seasonal shop follow it.
/// Always worked out on the Gregorian calendar in the player's time zone, so
/// October is October whatever calendar the iPhone is set to.
enum Season: String, CaseIterable {
    case spring, summer, autumn, winter
    /// All of October: pumpkins, bats and spooky lines.
    case halloween
    /// All of December: snow, lights and cozy lines.
    case christmas
    /// New Year's Eve from 6 pm and New Year's Day: fireworks.
    case newYear

    static func at(_ date: Date, calendar: Calendar = .current) -> Season {
        let c = Calendar.gregorian(like: calendar).dateComponents([.month, .day, .hour], from: date)
        let month = c.month ?? 1, day = c.day ?? 1, hour = c.hour ?? 12
        if month == 1 && day == 1 { return .newYear }
        if month == 12 && day == 31 && hour >= 18 { return .newYear }
        switch month {
        case 10: return .halloween
        case 12: return .christmas
        case 3...5: return .spring
        case 6...8: return .summer
        case 9, 11: return .autumn
        default: return .winter
        }
    }

    /// Lines the slime mixes into its small talk during this season.
    var lines: LineCategory? {
        switch self {
        case .halloween: .spooky
        case .christmas: .cozy
        case .newYear: .newYear
        default: nil
        }
    }
}

/// A day with a small gift, once a year.
enum Holiday: String, CaseIterable, Hashable {
    case halloween, holidays, newYear

    static func at(_ date: Date, calendar: Calendar = .current) -> Holiday? {
        let c = Calendar.gregorian(like: calendar).dateComponents([.month, .day], from: date)
        switch (c.month ?? 0, c.day ?? 0) {
        case (10, 31): return .halloween
        case (12, 24...26): return .holidays
        case (1, 1): return .newYear
        default: return nil
        }
    }

    var greeting: String {
        switch self {
        case .halloween: "Happy Halloween"
        case .holidays: "Happy holidays"
        case .newYear: "Happy New Year"
        }
    }

    var coins: Int {
        switch self {
        case .halloween: 31
        case .holidays: 25
        case .newYear: 30
        }
    }

    /// Item sprite for the celebration and the memory book.
    var icon: String {
        switch self {
        case .halloween: "hat.pumpkin"
        case .holidays: "starJelly"
        case .newYear: "hat.party"
        }
    }

    var lines: LineCategory {
        switch self {
        case .halloween: .spooky
        case .holidays: .cozy
        case .newYear: .newYear
        }
    }

    /// Ledger id: paid once per holiday and year.
    func receipt(year: Int) -> String { "holiday:\(rawValue):\(year)" }
}

/// The slime's hatch-day anniversary.
enum Birthday {
    /// How old the slime turns today, or nil if today isn't its birthday.
    /// Slimes hatched on 29 February celebrate on the 28th in other years.
    static func years(born: Date, at now: Date, calendar: Calendar = .current) -> Int? {
        let cal = Calendar.gregorian(like: calendar)
        let b = cal.dateComponents([.year, .month, .day], from: born)
        let n = cal.dateComponents([.year, .month, .day], from: now)
        guard let by = b.year, let bm = b.month, let bd = b.day,
              let ny = n.year, let nm = n.month, let nd = n.day else { return nil }
        let years = ny - by
        guard years >= 1 else { return nil }
        var day = bd
        if bm == 2, bd == 29, !isLeap(ny) { day = 28 }
        return nm == bm && nd == day ? years : nil
    }

    /// Ledger id: paid once per year.
    static func receipt(year: Int) -> String { "birthday:\(year)" }

    static func coins(years: Int) -> Int { min(200, 50 * max(1, years)) }

    private static func isLeap(_ year: Int) -> Bool {
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    }
}

extension Calendar {
    /// A Gregorian calendar in the same time zone, for dates that follow
    /// the Western year (seasons, holidays, birthdays, day keys).
    static func gregorian(like calendar: Calendar) -> Calendar {
        if calendar.identifier == .gregorian { return calendar }
        var g = Calendar(identifier: .gregorian)
        g.timeZone = calendar.timeZone
        g.locale = calendar.locale
        return g
    }
}
