import CoreText
import Foundation

/// The animation fonts ship inside the widget extension. The app registers
/// them too, so the diagnostics screen can show exactly what the island draws.
enum IslandFonts {
    private static var done = false

    static func register() {
        guard !done else { return }
        done = true
        guard let dir = Bundle.main.builtInPlugInsURL?.appendingPathComponent("SlimyWidgets.appex"),
              let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
        else { return }
        let fonts = files.filter { $0.pathExtension == "ttf" }
        CTFontManagerRegisterFontURLs(fonts as CFArray, .process, true, nil)
    }
}
