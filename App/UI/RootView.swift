import Combine
import SwiftUI
import UIKit

/// The app's root: onboarding until the slime has hatched and settled in,
/// then its home. Also hosts the celebration overlay, the toast layer and
/// What's New after an update.
struct RootView: View {
    @EnvironmentObject private var store: GameStore
    @EnvironmentObject private var friends: FriendsStore
    @State private var showWhatsNew = false

    private var onboarded: Bool {
        store.state.hatched && store.state.player.tips.contains("onboarded")
    }

    var body: some View {
        ZStack {
            // Screenshot runs go straight home; HomeView sets up a demo slime.
            if onboarded || ScreenshotMode.isActive {
                HomeView()
                    .transition(.opacity)
            } else {
                OnboardingView()
                    .transition(.opacity)
            }

            if !ScreenshotMode.isActive {
                CelebrationOverlay()
            }
        }
        .animation(.easeInOut(duration: 0.4), value: onboarded)
        .background {
            SceneHook { ToastLayer.install(in: $0) }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .sheet(isPresented: $showWhatsNew, onDismiss: WhatsNew.markSeen) {
            WhatsNewView()
                .environmentObject(store)
        }
        .task { await offerWhatsNew() }
        .onChange(of: onboarded) { _, isOnboarded in
            // Someone who hatched their slime in this version has seen it all.
            if isOnboarded { WhatsNew.markSeen() }
        }
        .onOpenURL { url in
            store.tick()
            // Invite links (slimy://f/CODE, https://slime.hyprnode.de/f/CODE)
            // fill in the friend code; widget taps (slimy://home) just open.
            friends.openInvite(url)
        }
    }

    /// Waits until nothing else is on screen (a sheet, a game, a
    /// celebration), then shows What's New. If it never gets the chance, it
    /// stays due for the next launch.
    private func offerWhatsNew() async {
        guard onboarded, !ScreenshotMode.isActive, WhatsNew.isDue else { return }
        for _ in 0..<600 {
            try? await Task.sleep(for: .seconds(1))
            if Task.isCancelled { return }
            if canPresentWhatsNew {
                showWhatsNew = true
                return
            }
        }
    }

    private var canPresentWhatsNew: Bool {
        guard onboarded, store.celebrations.isEmpty,
              UIApplication.shared.applicationState == .active else { return false }
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
        guard let root = windows.first(where: { $0.isKeyWindow })?.rootViewController else { return false }
        return root.presentedViewController == nil
    }
}

// MARK: - Toasts

/// Toasts float above everything, sheets and full-screen covers included, so
/// buying something in the shop or feeding in the kitchen shows its toast
/// over that sheet. They live in a window of their own above the app's: it
/// never takes a touch, is only shown while a toast is up, and leaves the
/// status bar as the app had it. The toast sits just below the status bar or
/// Dynamic Island, inside the safe area.
@MainActor
private enum ToastLayer {
    private static var window: UIWindow?
    private static var subscription: AnyCancellable?
    private static var hideTask: Task<Void, Never>?

    static func install(in scene: UIWindowScene) {
        guard ToastLayer.window?.windowScene !== scene else { return }
        let host = ToastHostingController(rootView: AnyView(ToastStack().environmentObject(GameStore.shared)))
        host.view.backgroundColor = .clear
        host.view.isUserInteractionEnabled = false
        host.view.accessibilityElementsHidden = true

        let window = PassthroughWindow(windowScene: scene)
        window.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.normal.rawValue + 1)
        window.backgroundColor = .clear
        window.rootViewController = host
        window.isHidden = true
        ToastLayer.window = window

        // Published values arrive on the main thread: GameStore is main-actor bound.
        ToastLayer.subscription = GameStore.shared.$toast
            .map { $0 != nil }
            .removeDuplicates()
            .sink { visible in
                MainActor.assumeIsolated { ToastLayer.setVisible(visible) }
            }
    }

    private static func setVisible(_ visible: Bool) {
        ToastLayer.hideTask?.cancel()
        if visible {
            ToastLayer.window?.isHidden = false
            return
        }
        // Long enough for the toast's exit animation.
        ToastLayer.hideTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(450))
            guard !Task.isCancelled else { return }
            ToastLayer.window?.isHidden = true
        }
    }
}

/// Lets every touch through to the app's window below.
private final class PassthroughWindow: UIWindow {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        nil
    }
}

/// Keeps the status bar exactly as the app's own window has it while a
/// toast is showing.
private final class ToastHostingController: UIHostingController<AnyView> {
    override var preferredStatusBarStyle: UIStatusBarStyle {
        view.window?.windowScene?.statusBarManager?.statusBarStyle ?? .default
    }

    override var prefersStatusBarHidden: Bool {
        view.window?.windowScene?.statusBarManager?.isStatusBarHidden ?? false
    }
}

/// The toast itself, top centre. VoiceOver reads it out instead of moving
/// focus to it.
private struct ToastStack: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 0) {
            if let toast = store.toast, !ScreenshotMode.isActive {
                ToastBanner(toast: toast)
                    .frame(maxWidth: 520)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .transition(transition)
                    .id(toast.id)
                    .task(id: toast.id) { await present(toast) }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .animation(animation, value: store.toast)
        .fontDesign(.rounded)
        .tint(Theme.accent)
        .allowsHitTesting(false)
    }

    private var transition: AnyTransition {
        reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity)
    }

    private var animation: Animation {
        reduceMotion ? .easeOut(duration: 0.2) : .spring(response: 0.35, dampingFraction: 0.85)
    }

    /// Announces the toast, keeps it up long enough to read, then clears it
    /// unless a newer one replaced it.
    private func present(_ toast: Toast) async {
        UIAccessibility.post(notification: .announcement, argument: toast.text)
        let reading = Double(toast.text.count) * 0.06
        let seconds = UIAccessibility.isVoiceOverRunning ? 5 : min(5, max(2.4, reading))
        try? await Task.sleep(for: .seconds(seconds))
        guard !Task.isCancelled else { return }
        withAnimation(.easeOut(duration: 0.2)) {
            if store.toast == toast { store.toast = nil }
        }
    }
}

/// Reports the window scene the root view lives in, once it has one.
private struct SceneHook: UIViewRepresentable {
    let onScene: @MainActor (UIWindowScene) -> Void

    func makeUIView(context: Context) -> HookView {
        let view = HookView()
        view.onScene = onScene
        return view
    }

    func updateUIView(_ uiView: HookView, context: Context) {}

    final class HookView: UIView {
        var onScene: (@MainActor (UIWindowScene) -> Void)?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            isHidden = true
        }

        required init?(coder: NSCoder) {
            super.init(coder: coder)
            isUserInteractionEnabled = false
            isHidden = true
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let scene = window?.windowScene { onScene?(scene) }
        }
    }
}
