import CoreText
import Foundation

/// The island's animation fonts ship inside the widget extension. The app
/// registers them for itself too, so anything in the app that draws the
/// island's slime (`AnimatedSlime`) looks exactly like the island. There are
/// several hundred files, so `registerInBackground()` keeps the work off the
/// launch path; `register()` does it right away and only once.
enum IslandFonts {
    private static let registration: Void = {
        guard let dir = Bundle.main.builtInPlugInsURL?.appendingPathComponent("SlimyWidgets.appex"),
              let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
        else { return }
        let fonts = files.filter { $0.pathExtension == "ttf" }
        guard !fonts.isEmpty else { return }
        CTFontManagerRegisterFontURLs(fonts as CFArray, .process, true, nil)
    }()

    static func register() {
        _ = registration
    }

    static func registerInBackground() {
        Task.detached(priority: .utility) { register() }
    }
}
