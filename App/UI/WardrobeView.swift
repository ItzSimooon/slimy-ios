import SwiftUI

struct WardrobeView: View {
    @EnvironmentObject private var store: SlimeStore
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @FocusState private var naming: Bool

    var body: some View {
        let s = store.state
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                SheetHeader(eyebrow: "wardrobe", title: "Style \(s.name)") { dismiss() }

                VStack(alignment: .leading, spacing: 8) {
                    Eyebrow(text: "name")
                    HStack {
                        TextField("Name", text: $name)
                            .font(Brand.display(18, .bold))
                            .foregroundStyle(Brand.text)
                            .focused($naming)
                            .submitLabel(.done)
                            .onSubmit { store.rename(name) }
                            .textInputAutocapitalization(.words)
                            .id("name-field")
                        if name != s.name, !name.isEmpty {
                            Button("Save") { store.rename(name); naming = false }
                                .font(Brand.display(15, .bold))
                        }
                    }
                    .padding(14)
                    .background(Brand.deep, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }

                VStack(alignment: .leading, spacing: 10) {
                    Eyebrow(text: "jelly colors · new ones unlock as you level up")
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3), spacing: 12) {
                        ForEach(Skin.allCases) { skin in
                            SkinTile(skin: skin, selected: s.skin == skin, unlocked: s.isUnlocked(skin)) {
                                withAnimation(.spring) { store.wear(skin) }
                            }
                        }
                    }
                }
            }
            .padding(20)
        }
        .onAppear { name = s.name }
        .presentationDetents([.large])
    }
}

private struct SkinTile: View {
    let skin: Skin
    let selected: Bool
    let unlocked: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                ZStack {
                    PixelSlime(frame: SpriteAnim.idle.frames[0], skin: skin)
                        .frame(height: 56)
                        .saturation(unlocked ? 1 : 0)
                        .opacity(unlocked ? 1 : 0.35)
                    if !unlocked {
                        Image(systemName: "lock.fill")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(Brand.text)
                    }
                }
                Text(skin.title)
                    .font(Brand.display(13, .bold))
                    .foregroundStyle(Brand.text)
                Text(unlocked ? (selected ? "wearing" : "tap to wear") : "level \(skin.unlockLevel)")
                    .font(Brand.mono(10, .semibold))
                    .foregroundStyle(selected ? Brand.hyprGlow : Brand.muted)
            }
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity)
            .background(Brand.deep, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(selected ? Brand.hypr : Brand.line, lineWidth: selected ? 2 : 1))
        }
        .buttonStyle(SquishStyle())
        .disabled(!unlocked)
    }
}
