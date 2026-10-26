import Foundation

/// Screenshots for the App Store and CI, from launch arguments:
///
///     -SlimyScreenshot                  go straight to a demo slime's home
///     -SlimySheet <HomeSheet rawValue>  and open that sheet (kitchen, shop …)
///     -SlimyCover <HomeCover rawValue>  or that full-screen cover (arcade, bath)
///
/// HomeView reads these on appear and sets up the demo slime. The rest of
/// the app keeps still while it is active: no What's New, toasts,
/// celebrations, Live Activity or online refreshes. Debug builds only; in
/// release builds every value is off, whatever the arguments say.
enum ScreenshotMode {
    static var isActive: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("-SlimyScreenshot")
        #else
        return false
        #endif
    }

    static var initialSheet: HomeSheet? {
        #if DEBUG
        guard isActive, let raw = value(after: "-SlimySheet") else { return nil }
        return HomeSheet(rawValue: raw)
        #else
        return nil
        #endif
    }

    static var initialCover: HomeCover? {
        #if DEBUG
        guard isActive, let raw = value(after: "-SlimyCover") else { return nil }
        return HomeCover(rawValue: raw)
        #else
        return nil
        #endif
    }

    #if DEBUG
    /// The argument that follows `flag`. Read by hand rather than through
    /// UserDefaults, which would pair the bare `-SlimyScreenshot` flag with
    /// the next argument.
    private static func value(after flag: String) -> String? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: flag), arguments.indices.contains(index + 1) else { return nil }
        return arguments[index + 1]
    }
    #endif
}
