import Foundation
import UserNotifications

/// Local notifications for when the slime is about to need something.
/// These work without a paid developer account.
enum Notifier {
    private static let prefix = "slimy.need."

    static var enabled: Bool {
        get { UserDefaults.standard.object(forKey: "slimy.notifications") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "slimy.notifications") }
    }

    static func requestPermission() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
    }

    static func reschedule(for slime: SlimeState) {
        let center = UNUserNotificationCenter.current()
        let ids = Need.allCases.map { prefix + $0.rawValue } + [prefix + "miss"]
        center.removePendingNotificationRequests(withIdentifiers: ids)
        guard enabled else { return }

        for need in Need.allCases where need != .energy {
            let rate = need.decay(asleep: false)
            let hours = (slime.needs[need] - 22) / rate
            guard hours > 0.2, hours < 36 else { continue }
            schedule(id: prefix + need.rawValue,
                     title: slime.name,
                     body: need.wish,
                     after: hours * 3600)
        }
        schedule(id: prefix + "miss", title: slime.name, body: "hey. ich vermiss dich.", after: 26 * 3600)
    }

    private static func schedule(id: String, title: String, body: String, after seconds: TimeInterval) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false)
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
    }
}
