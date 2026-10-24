import SwiftUI

struct RootView: View {
    @EnvironmentObject private var store: SlimeStore

    var body: some View {
        ZStack(alignment: .top) {
            if store.hasHatched {
                HomeView()
                    .transition(.opacity)
            } else {
                HatchView()
                    .transition(.opacity)
            }

            if let toast = store.toast {
                ToastView(toast: toast)
                    .padding(.top, 8)
                    .padding(.horizontal, 16)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .id(toast.id)
                    .task(id: toast.id) {
                        try? await Task.sleep(for: .seconds(2.4))
                        withAnimation(.spring) { if store.toast == toast { store.toast = nil } }
                    }
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: store.toast)
        .animation(.easeInOut(duration: 0.5), value: store.hasHatched)
        .onOpenURL { _ in store.tick() }
    }
}
