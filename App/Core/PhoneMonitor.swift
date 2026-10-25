import BackgroundTasks
import Foundation
import Network
import UIKit

/// Notices what the iPhone does (battery, charger, Low Power Mode, Wi-Fi,
/// mobile data, offline, morning and night) and lets the slime react.
///
/// No Shortcuts needed. While the app is open, changes are seen live. While
/// it is closed, the app compares the phone's state with the last one it saw
/// every time iOS lets it run: background refresh, a background task while
/// charging, island and widget buttons, Control Center. Those wake-ups are
/// decided by iOS, so a reaction can come late; things that happened and
/// un-happened in between (a short Wi-Fi drop) can't be seen. Only public
/// APIs are used; airplane mode and alarms are not observable by apps.
@MainActor
final class PhoneMonitor {
    static let shared = PhoneMonitor()

    static let powerTaskID = "de.hyprnode.slimy.power"

    /// What the phone looked like the last time the app checked.
    struct Snapshot: Codable, Equatable {
        var battery: Float          // 0...1, or -1 unknown
        var charging: Bool
        var full: Bool
        var lowPower: Bool
        var online: Bool
        var wifi: Bool
        var cellular: Bool
        /// False when the network state wasn't known yet (just launched, or a
        /// background run that couldn't read it). Never compared against.
        var netKnown: Bool
        var at: Date
    }

    var enabled: Bool { GameStore.shared.state.settings.liveEvents && GameStore.shared.state.hatched }

    private let defaults = UserDefaults.standard
    private let pathMonitor = NWPathMonitor()
    private var currentPath: NWPath?
    private var started = false
    private var timer: Timer?
    /// Right after the app opens, the first readings only set the baseline:
    /// the network state arrives a moment later and would otherwise look
    /// like "Wi-Fi just connected".
    private var settleUntil = Date.distantPast

    private var last: Snapshot? {
        get { defaults.data(forKey: "slimy.phone.snapshot").flatMap { try? JSONDecoder().decode(Snapshot.self, from: $0) } }
        set { defaults.set(newValue.flatMap { try? JSONEncoder().encode($0) }, forKey: "slimy.phone.snapshot") }
    }

    /// When each event last made the slime react, kept across launches.
    private var lastFired: [String: Date] {
        get { (defaults.dictionary(forKey: "slimy.phone.fired") as? [String: Date]) ?? [:] }
        set { defaults.set(newValue, forKey: "slimy.phone.fired") }
    }

    /// Starts live watching (called when the app becomes active).
    func start() {
        settleUntil = .now.addingTimeInterval(5)
        guard !started else { check(); return }
        started = true
        UIDevice.current.isBatteryMonitoringEnabled = true

        let center = NotificationCenter.default
        for name in [UIDevice.batteryLevelDidChangeNotification, UIDevice.batteryStateDidChangeNotification,
                     .NSProcessInfoPowerStateDidChange, UIApplication.significantTimeChangeNotification] {
            center.addObserver(forName: name, object: nil, queue: .main) { _ in
                Task { @MainActor in PhoneMonitor.shared.check() }
            }
        }
        timer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { _ in
            Task { @MainActor in PhoneMonitor.shared.check() }
        }
        pathMonitor.pathUpdateHandler = { path in
            Task { @MainActor in
                PhoneMonitor.shared.currentPath = path
                PhoneMonitor.shared.check()
            }
        }
        pathMonitor.start(queue: .main)
        check()
    }

    /// For background runs: waits briefly for the network state, then checks.
    func checkInBackground() async {
        UIDevice.current.isBatteryMonitoringEnabled = true
        // A path remembered from the last time the app was open may be old;
        // read a fresh one (or none, if it doesn't come in time).
        if UIApplication.shared.applicationState != .active {
            currentPath = await Self.networkPath()
        }
        check()
    }

    /// Compares the phone with the last snapshot and reacts to what changed.
    func check(now: Date = .now) {
        let snapshot = read(now: now)
        let previous = last
        last = snapshot
        guard enabled else { return }

        var events: [PhoneEvent] = []
        let settling = now < settleUntil
        if let old = previous, !settling {
            let age = now.timeIntervalSince(old.at)
            let background = UIApplication.shared.applicationState != .active
            // Battery thresholds are crossings, so they are news whenever seen.
            // Charger and power mode changes count while you watch, or in a
            // background run (that is the reaction to charging); not hours
            // later when you open the app.
            events += powerEvents(from: old, to: snapshot, includeState: background || age < 30 * 60)
            // Network changes only count when seen live, a few minutes apart.
            if old.netKnown, snapshot.netKnown, age < 5 * 60 {
                events += networkEvents(from: old, to: snapshot)
            }
        }
        events += clockEvents(since: previous?.at, now: now)

        // A long gap can turn up several changes at once; react to the two
        // that matter most, in order.
        for event in events.sorted(by: { $0.priority > $1.priority }).prefix(2) {
            fire(event, now: now)
        }
    }

    // MARK: Reading

    private func read(now: Date) -> Snapshot {
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        let path = currentPath
        return Snapshot(
            battery: device.batteryLevel,
            charging: device.batteryState == .charging || device.batteryState == .full,
            full: device.batteryState == .full,
            lowPower: ProcessInfo.processInfo.isLowPowerModeEnabled,
            online: path.map { $0.status == .satisfied } ?? (last?.online ?? false),
            wifi: path?.usesInterfaceType(.wifi) ?? (last?.wifi ?? false),
            cellular: path?.usesInterfaceType(.cellular) ?? (last?.cellular ?? false),
            netKnown: path != nil,
            at: now)
    }

    private static func networkPath() async -> NWPath? {
        final class Once: @unchecked Sendable {
            var done = false
        }
        return await withCheckedContinuation { continuation in
            let monitor = NWPathMonitor()
            let once = Once()
            // Both callbacks run on the main queue, so `once` needs no lock.
            let finish: @Sendable (NWPath?) -> Void = { path in
                guard !once.done else { return }
                once.done = true
                monitor.cancel()
                continuation.resume(returning: path)
            }
            monitor.pathUpdateHandler = { path in finish(path) }
            monitor.start(queue: .main)
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { finish(nil) }
        }
    }

    // MARK: Changes

    private func powerEvents(from old: Snapshot, to new: Snapshot, includeState: Bool) -> [PhoneEvent] {
        var out: [PhoneEvent] = []
        if includeState {
            if !old.charging, new.charging { out.append(.chargerConnected) }
            if old.charging, !new.charging { out.append(.chargerDisconnected) }
            if !old.full, new.full { out.append(.batteryFull) }
            if !old.lowPower, new.lowPower { out.append(.lowPowerOn) }
            if old.lowPower, !new.lowPower { out.append(.lowPowerOff) }
        }
        if old.battery >= 0, new.battery >= 0, !new.charging {
            if old.battery >= 0.05, new.battery < 0.05 {
                out.append(.batteryCritical)
            } else if old.battery >= 0.20, new.battery < 0.20 {
                out.append(.batteryLow)
            }
        }
        return out
    }

    private func networkEvents(from old: Snapshot, to new: Snapshot) -> [PhoneEvent] {
        if old.online, !new.online { return [.offline] }
        if !old.online, new.online { return [new.wifi ? .wifiConnected : .backOnline] }
        if !old.wifi, new.wifi { return [.wifiConnected] }
        if old.wifi, !new.wifi, new.cellular { return [.cellular] }
        return []
    }

    /// Morning and night follow the slime's sleep schedule; a new hour only
    /// counts while you're watching.
    private func clockEvents(since: Date?, now: Date) -> [PhoneEvent] {
        let calendar = Calendar.current
        let schedule = GameStore.shared.state.settings.sleep
        let hour = calendar.component(.hour, from: now)
        var out: [PhoneEvent] = []
        if hour >= schedule.wake, hour < schedule.wake + 3, !firedToday(.goodMorning, now: now) {
            out.append(.goodMorning)
        }
        if hour == schedule.bedtime, !firedToday(.goodNight, now: now) {
            out.append(.goodNight)
        }
        if let since, UIApplication.shared.applicationState == .active,
           calendar.component(.hour, from: since) != hour, now.timeIntervalSince(since) < 3600 {
            out.append(.newHour)
        }
        return out
    }

    private func firedToday(_ event: PhoneEvent, now: Date) -> Bool {
        guard let at = lastFired[event.rawValue] else { return false }
        return Calendar.current.isDate(at, inSameDayAs: now)
    }

    private func fire(_ event: PhoneEvent, now: Date) {
        // A flapping connection or a wobbly charger must not spam the slime.
        if let at = lastFired[event.rawValue], now.timeIntervalSince(at) < 10 * 60 { return }
        var fired = lastFired
        fired[event.rawValue] = now
        lastFired = fired.filter { now.timeIntervalSince($0.value) < 3 * 86400 }

        let said = GameStore.shared.handle(event)
        let name = GameStore.shared.slime.name
        let background = UIApplication.shared.applicationState != .active
        Task {
            await IslandController.shared.sync(alert: event.alerts
                ? .init(title: "\(name)", body: "\(said)", sound: .default) : nil)
            if !background { await IslandController.shared.settle() }
        }
    }

    // MARK: Background task while charging

    /// Asks iOS for a run while the phone charges, which is usually soon
    /// after the cable goes in (and often overnight).
    static func schedulePowerCheck() {
        let request = BGProcessingTaskRequest(identifier: powerTaskID)
        request.requiresExternalPower = true
        request.requiresNetworkConnectivity = false
        request.earliestBeginDate = .now.addingTimeInterval(15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    static func registerPowerTask() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: powerTaskID, using: .main) { task in
            let work = Task { @MainActor in
                await PhoneMonitor.shared.checkInBackground()
                await IslandController.shared.ensureRunning()
                schedulePowerCheck()
                task.setTaskCompleted(success: true)
            }
            task.expirationHandler = { work.cancel() }
        }
    }
}

extension PhoneEvent {
    /// Which reaction wins when several changes turn up in one check.
    var priority: Int {
        switch self {
        case .batteryCritical: 9
        case .batteryLow: 8
        case .offline: 7
        case .chargerConnected, .chargerDisconnected: 6
        case .batteryFull: 5
        case .goodMorning, .goodNight: 4
        case .wifiConnected, .backOnline, .cellular, .wifiDisconnected: 3
        case .lowPowerOn, .lowPowerOff: 2
        case .airplaneOn, .airplaneOff, .alarm, .newHour: 1
        }
    }
}
