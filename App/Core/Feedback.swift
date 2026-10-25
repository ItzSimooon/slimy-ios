import AVFoundation
import UIKit

/// Sound effects (Resources/Sounds, made by tools/make_sounds.py).
enum Sound: String, CaseIterable {
    case tap, hop, land, eat, pet, splash, sleep, wake, coin, levelUp, success, nope, pop, friend
    case perfect, miss, whoosh, dig, treasure
    case tone1, tone2, tone3, tone4, tone5, tone6, tone7, tone8, tone9

    static func tone(_ i: Int) -> Sound {
        [.tone1, .tone2, .tone3, .tone4, .tone5, .tone6, .tone7, .tone8, .tone9][max(0, min(8, i))]
    }
}

/// Sounds, music and haptics, each switchable in Settings. Uses the ambient
/// audio session: it mixes with other audio and respects the silent switch.
@MainActor
final class Feedback {
    static let shared = Feedback()

    private var players: [Sound: [AVAudioPlayer]] = [:]
    private var music: AVAudioPlayer?
    private var lastPlayed: [Sound: Date] = [:]
    private let light = UIImpactFeedbackGenerator(style: .light)
    private let soft = UIImpactFeedbackGenerator(style: .soft)
    private let notify = UINotificationFeedbackGenerator()

    private var settings: Settings { GameStore.shared.state.settings }

    init() {
        try? AVAudioSession.sharedInstance().setCategory(.ambient, options: [.mixWithOthers])
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            if type == .ended {
                Task { @MainActor in Feedback.shared.updateMusic() }
            }
        }
    }

    func play(_ sound: Sound) {
        haptic(for: sound)
        guard settings.sound else { return }
        // The same sound at most every 60 ms, so fast taps don't turn into noise.
        if let last = lastPlayed[sound], Date.now.timeIntervalSince(last) < 0.06 { return }
        lastPlayed[sound] = .now
        let pool = players[sound] ?? []
        if let free = pool.first(where: { !$0.isPlaying }) {
            free.currentTime = 0
            free.play()
            return
        }
        guard pool.count < 3,
              let url = Bundle.main.url(forResource: sound.rawValue, withExtension: "wav"),
              let player = try? AVAudioPlayer(contentsOf: url) else { return }
        player.volume = 0.7
        player.prepareToPlay()
        player.play()
        players[sound, default: []].append(player)
    }

    func haptic(_ kind: UINotificationFeedbackGenerator.FeedbackType) {
        guard settings.haptics else { return }
        notify.notificationOccurred(kind)
    }

    func tapHaptic() {
        guard settings.haptics else { return }
        light.impactOccurred(intensity: 0.6)
    }

    private func haptic(for sound: Sound) {
        guard settings.haptics else { return }
        switch sound {
        case .levelUp, .success, .treasure: notify.notificationOccurred(.success)
        case .nope, .miss: notify.notificationOccurred(.warning)
        case .pet, .eat, .land: soft.impactOccurred(intensity: 0.7)
        default: break
        }
    }

    /// Starts or stops the music loop to match Settings and app state.
    func updateMusic(active: Bool = true) {
        guard settings.music, active else {
            music?.pause()
            return
        }
        if music == nil, let url = Bundle.main.url(forResource: "music", withExtension: "wav") {
            music = try? AVAudioPlayer(contentsOf: url)
            music?.numberOfLoops = -1
            music?.volume = 0.35
        }
        if music?.isPlaying == false { music?.play() }
    }
}
