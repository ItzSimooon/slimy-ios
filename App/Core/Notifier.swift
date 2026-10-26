import Foundation
import UserNotifications

/// Local notifications and everything else that interrupts: few and useful.
/// Every category can be switched off, nothing arrives in quiet hours, and
/// at most `dailyLimit` interruptions reach you per day. Island alerts (the
/// Live Activity opening with a sound) ask `mayInterrupt` too, so they share
/// the same rules and the same budget.
enum Notifier {
    enum Category: String, CaseIterable {
        case needs, daily, friends, gifts, world
    }

    private static let center = UNUserNotificationCenter.current()
    private static let scheduledIDs = ["slimy.need", "slimy.daily", "slimy.thinking"]
    static let dailyLimit = 4

    /// Asks once, at a moment where it makes sense (after hatching, from a
    /// button). Returns whether notifications are allowed.
    @discardableResult
    static func requestPermission() async -> Bool {
        let granted = (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        await MainActor.run { GameStore.shared.update { $0.settings.notifications.asked = true } }
        return granted
    }

    static func isAuthorized() async -> Bool {
        let settings = await center.notificationSettings()
        return settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
    }

    /// Replaces every scheduled notification with what fits the state now.
    /// Called after every change, so nothing stale is ever left behind.
    static func reschedule(for state: GameState, now: Date = .now, calendar: Calendar = .current) {
        center.removePendingNotificationRequests(withIdentifiers: scheduledIDs)
        guard state.hatched else { return }
        let prefs = state.settings.notifications

        if prefs.needs, let (need, at) = nextNeed(state, now: now) {
            let line = Dialogue.fill(Dialogue.pick(Dialogue.category(for: need), state: state, at: at).text,
                                     context: .init(game: state.slime.favoriteGame.title), state: state)
            schedule("slimy.need", title: state.slime.name, body: line,
                     at: outsideQuietHours(at, prefs: prefs, calendar: calendar), now: now)
        }

        if prefs.daily, let morning = calendar.nextDate(after: now, matching: DateComponents(hour: max(prefs.quietUntil, 8), minute: 30),
                                                         matchingPolicy: .nextTime) {
            // Mentions the gift only if it will still be unopened by then.
            let giftWaiting = state.streakInfo(at: morning, calendar: calendar).canClaim
            schedule("slimy.daily",
                     title: giftWaiting ? "A gift is waiting" : "A new day in Slimy",
                     body: giftWaiting
                        ? "\(state.slime.name) has today's gift and three new challenges for you."
                        : "New challenges are waiting for you and \(state.slime.name).",
                     at: morning, now: now)
        }

        if prefs.world, let later = calendar.date(byAdding: .day, value: 2, to: now) {
            schedule("slimy.thinking", title: state.slime.name, body: "i thought about you today. just because.",
                     at: outsideQuietHours(later, prefs: prefs, calendar: calendar), now: now)
        }
    }

    /// Something happened right now (a gift arrived, a friend wants to play).
    static func post(_ category: Category, title: String, body: String, state: GameState,
                     now: Date = .now, calendar: Calendar = .current) {
        guard mayInterrupt(category, state: state, now: now, calendar: calendar) else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.threadIdentifier = category.rawValue
        center.add(UNNotificationRequest(identifier: "slimy.\(category.rawValue).\(UUID().uuidString)", content: content, trigger: nil))
    }

    /// Whether something may interrupt right now: its category is switched on
    /// (`nil` for things with a switch of their own, like phone reactions),
    /// it isn't quiet hours, and today's budget isn't used up. A yes uses one
    /// interruption from the budget.
    static func mayInterrupt(_ category: Category?, state: GameState,
                             now: Date = .now, calendar: Calendar = .current) -> Bool {
        let prefs = state.settings.notifications
        if let category, !isOn(category, prefs) { return false }
        guard !prefs.isQuiet(hour: calendar.component(.hour, from: now)) else { return false }
        return takeSlot(now: now, calendar: calendar)
    }

    static func isOn(_ category: Category, _ prefs: NotificationPrefs) -> Bool {
        switch category {
        case .needs: prefs.needs
        case .daily: prefs.daily
        case .friends: prefs.friends
        case .gifts: prefs.gifts
        case .world: prefs.world
        }
    }

    // MARK: Helpers

    /// When the most urgent need will fall below 25, if within 36 hours.
    static func nextNeed(_ state: GameState, now: Date) -> (Need, Date)? {
        guard !state.slime.isAsleep else { return nil }
        var best: (Need, Double)?
        for need in Need.allCases where need != .energy {
            let rate = need.baseDecay(asleep: false) * state.slime.personality.decayFactor(need)
            let hours = (state.slime.needs[need] - 25) / rate
            guard hours > 0.25, hours < 36 else { continue }
            if best == nil || hours < best!.1 { best = (need, hours) }
        }
        guard let best else { return nil }
        return (best.0, now.addingTimeInterval(best.1 * 3600))
    }

    static func outsideQuietHours(_ date: Date, prefs: NotificationPrefs, calendar: Calendar) -> Date {
        guard prefs.isQuiet(hour: calendar.component(.hour, from: date)) else { return date }
        return calendar.nextDate(after: date, matching: DateComponents(hour: prefs.quietUntil, minute: 0),
                                 matchingPolicy: .nextTime) ?? date
    }

    private static func schedule(_ id: String, title: String, body: String, at date: Date, now: Date) {
        let seconds = date.timeIntervalSince(now)
        guard seconds > 60 else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false)
        center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
    }

    // MARK: Daily budget

    private static let budgetDayKey = "slimy.interruptions.day"
    private static let budgetUsedKey = "slimy.interruptions.used"

    /// Counts today's interruptions so a busy day stays calm. Two keys that
    /// are overwritten each day, so nothing piles up in UserDefaults.
    private static func takeSlot(now: Date, calendar: Calendar) -> Bool {
        let defaults = UserDefaults.standard
        let day = dayKey(now, calendar: calendar)
        if defaults.string(forKey: budgetDayKey) != day {
            defaults.set(day, forKey: budgetDayKey)
            defaults.set(0, forKey: budgetUsedKey)
            removeLegacyCounters(defaults)
        }
        let used = defaults.integer(forKey: budgetUsedKey)
        guard used < dailyLimit else { return false }
        defaults.set(used + 1, forKey: budgetUsedKey)
        return true
    }

    private static func dayKey(_ date: Date, calendar: Calendar) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// Earlier versions kept one "slimy.notify.<day>" counter per day.
    private static func removeLegacyCounters(_ defaults: UserDefaults) {
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix("slimy.notify.") {
            defaults.removeObject(forKey: key)
        }
    }
}
