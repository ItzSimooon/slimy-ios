import Foundation
import Network
import UIKit

/// Watches battery, power and network while the app is running and turns
/// changes into `PhoneEvent`s. When the app is closed, Shortcuts automations
/// send the same events through `SlimeReactsIntent`.
@MainActor
final class PhoneMonitor {
    static let shared = PhoneMonitor()

    var enabled: Bool { GameStore.shared.state.settings.liveEvents && GameStore.shared.state.hatched }

    private let pathMonitor = NWPathMonitor()
    private var started = false
    private var lastNet: (online: Bool, wifi: Bool, cellular: Bool)?
    private var lastBattery: Float = -1
    private var lastBatteryState: UIDevice.BatteryState = .unknown
    private var lastLowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
    private var lastFired: [PhoneEvent: Date] = [:]
    private var lastHour = Calendar.current.component(.hour, from: .now)

    func start() {
        guard !started else { return }
        started = true

        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        lastBattery = device.batteryLevel
        lastBatteryState = device.batteryState

        let center = NotificationCenter.default
        center.addObserver(forName: UIDevice.batteryLevelDidChangeNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in self.batteryChanged() }
        }
        center.addObserver(forName: UIDevice.batteryStateDidChangeNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in self.batteryChanged() }
        }
        center.addObserver(forName: .NSProcessInfoPowerStateDidChange, object: nil, queue: .main) { _ in
            Task { @MainActor in self.powerModeChanged() }
        }
        center.addObserver(forName: UIApplication.significantTimeChangeNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in self.clockChanged() }
        }
        Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { _ in
            Task { @MainActor in self.clockChanged() }
        }

        pathMonitor.pathUpdateHandler = { path in
            let net = (online: path.status == .satisfied,
                       wifi: path.usesInterfaceType(.wifi),
                       cellular: path.usesInterfaceType(.cellular))
            Task { @MainActor in self.networkChanged(net) }
        }
        pathMonitor.start(queue: .main)
    }

    private func batteryChanged() {
        let device = UIDevice.current
        let level = device.batteryLevel
        let state = device.batteryState
        defer { lastBattery = level; lastBatteryState = state }
        guard level >= 0, lastBattery >= 0 else { return }

        if state != lastBatteryState {
            switch (lastBatteryState, state) {
            case (.unplugged, .charging), (.unplugged, .full):
                fire(.chargerConnected)
            case (.charging, .unplugged), (.full, .unplugged):
                fire(.chargerDisconnected)
            case (.charging, .full):
                fire(.batteryFull)
            default:
                break
            }
        }
        if lastBattery >= 0.05, level < 0.05 {
            fire(.batteryCritical)
        } else if lastBattery >= 0.20, level < 0.20 {
            fire(.batteryLow)
        }
    }

    private func powerModeChanged() {
        let on = ProcessInfo.processInfo.isLowPowerModeEnabled
        guard on != lastLowPower else { return }
        lastLowPower = on
        fire(on ? .lowPowerOn : .lowPowerOff)
    }

    private func networkChanged(_ net: (online: Bool, wifi: Bool, cellular: Bool)) {
        defer { lastNet = net }
        guard let old = lastNet else { return }
        if old.online, !net.online {
            fire(.offline)
        } else if !old.online, net.online {
            fire(net.wifi ? .wifiConnected : .backOnline)
        } else if !old.wifi, net.wifi {
            fire(.wifiConnected)
        } else if old.wifi, !net.wifi, net.cellular {
            fire(.cellular)
        }
    }

    private func clockChanged() {
        let hour = Calendar.current.component(.hour, from: .now)
        guard hour != lastHour else { return }
        lastHour = hour
        switch hour {
        case 7: fire(.goodMorning)
        case 22: fire(.goodNight)
        default: fire(.newHour)
        }
    }

    private func fire(_ event: PhoneEvent) {
        guard enabled else { return }
        // Don't let a flapping connection or a charger wobble spam the slime.
        if let last = lastFired[event], Date.now.timeIntervalSince(last) < 10 * 60 { return }
        lastFired[event] = .now
        let said = GameStore.shared.handle(event)
        let name = GameStore.shared.slime.name
        Task {
            await IslandController.shared.sync(alert: event.alerts
                ? .init(title: "\(name)", body: "\(said)", sound: .default) : nil)
            await IslandController.shared.settle()
        }
    }
}
