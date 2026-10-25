import SwiftUI

struct WardrobeView: View {
    @EnvironmentObject private var store: SlimeStore
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @FocusState private var naming: Bool

    var body: some View {
        let s = store.state
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                SheetHeader(eyebrow: "aussehen", title: s.name) { dismiss() }

                VStack(alignment: .leading, spacing: 8) {
                    Eyebrow(text: "name")
                    HStack(spacing: 10) {
                        TextField("Name", text: $name)
                            .font(Brand.pixel(20))
                            .foregroundStyle(Brand.text)
                            .focused($naming)
                            .submitLabel(.done)
                            .onSubmit { store.rename(name) }
                            .textInputAutocapitalization(.words)
                            .padding(12)
                            .background(Brand.panel)
                            .overlay(Rectangle().strokeBorder(naming ? Brand.slime : Brand.line, lineWidth: 2))
                        if name != s.name, !name.isEmpty {
                            Button("Speichern") { store.rename(name); naming = false }
                                .font(Brand.pixel(15))
                                .foregroundStyle(Brand.slime)
                        }
                    }
                }

                VStack(alignment: .leading, spacing: 10) {
                    Eyebrow(text: "farben · neue gibt es mit jedem level")
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 2), spacing: 10) {
                        ForEach(Skin.allCases) { skin in
                            SkinTile(skin: skin, selected: s.skin == skin, unlocked: s.isUnlocked(skin)) {
                                store.wear(skin)
                            }
                        }
                    }
                }
            }
            .padding(18)
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
            HStack(spacing: 10) {
                PixelSlime(frame: SpriteAnim.idle.frames[0], skin: skin)
                    .frame(height: 40)
                    .saturation(unlocked ? 1 : 0)
                    .opacity(unlocked ? 1 : 0.35)
                VStack(alignment: .leading, spacing: 2) {
                    Text(skin.title)
                        .font(Brand.pixel(15))
                        .foregroundStyle(Brand.text)
                    Text(unlocked ? (selected ? "trägt er" : "anziehen") : "ab level \(skin.unlockLevel)")
                        .font(Brand.pixel(11, bold: false))
                        .foregroundStyle(selected ? Brand.slime : Brand.muted)
                }
                Spacer(minLength: 0)
            }
            .padding(10)
            .background(Brand.panel)
            .overlay(Rectangle().strokeBorder(selected ? Brand.slime : Brand.line, lineWidth: 2))
        }
        .buttonStyle(PressStyle())
        .disabled(!unlocked)
        .accessibilityLabel("\(skin.title), \(unlocked ? (selected ? "ausgewählt" : "verfügbar") : "gesperrt bis Level \(skin.unlockLevel)")")
    }
}
