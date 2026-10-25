import SwiftUI

struct RootView: View {
    @EnvironmentObject private var store: GameStore

    var body: some View {
        ZStack(alignment: .top) {
            if store.state.hatched && store.state.player.tips.contains("onboarded") {
                HomeView()
                    .transition(.opacity)
            } else {
                OnboardingView()
                    .transition(.opacity)
            }

            if let toast = store.toast {
                ToastBanner(toast: toast)
                    .padding(.top, 54)
                    .padding(.horizontal, 16)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .id(toast.id)
                    .task(id: toast.id) {
                        try? await Task.sleep(for: .seconds(2.4))
                        withAnimation(.easeOut(duration: 0.2)) { if store.toast == toast { store.toast = nil } }
                    }
                    .allowsHitTesting(false)
            }

            CelebrationOverlay()
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: store.toast)
        .animation(.easeInOut(duration: 0.4), value: store.state.player.tips.contains("onboarded"))
        .onOpenURL { _ in store.tick() }
    }
}
