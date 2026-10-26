import Photos
import SwiftUI
import UIKit

/// The photo booth: a picture of the slime in one of its places, with its
/// name and where to get the app, ready to share or save. The card is drawn
/// at three times its size, so every pixel of the slime stays sharp.
struct PhotoBoothView: View {
    @EnvironmentObject private var store: GameStore
    @Environment(\.openURL) private var openURL

    @State private var pose: BoothPose = .wave
    @State private var place: Habitat?
    @State private var light: BoothLight = .day
    @State private var format: BoothFormat = .portrait
    @State private var caption = ""
    @State private var showDetails = true
    @State private var photo: UIImage?
    @State private var failed = false
    @State private var saving: SaveState = .idle
    @State private var saveProblem: SaveProblem?

    private static let captionLimit = 40

    var body: some View {
        let s = store.state
        let habitat = place.flatMap { s.inventory.habitats.contains($0) ? $0 : nil } ?? s.inventory.habitat
        let options = BoothOptions(pose: pose, habitat: habitat, light: light, format: format,
                                   caption: caption.trimmingCharacters(in: .whitespacesAndNewlines),
                                   showDetails: showDetails)
        NavigationStack {
            Form {
                Section {
                    preview(s.slime, options)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                }

                Section("Format") {
                    Picker("Format", selection: $format) {
                        ForEach(BoothFormat.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }

                Section("Pose") {
                    posePicker(s.slime)
                        .listRowInsets(EdgeInsets(top: 10, leading: 0, bottom: 10, trailing: 0))
                }

                Section {
                    placePicker(s, selected: habitat)
                        .listRowInsets(EdgeInsets(top: 10, leading: 0, bottom: 10, trailing: 0))
                } header: {
                    Text("Place")
                } footer: {
                    if s.inventory.habitats.count < Habitat.allCases.count {
                        Text("More places are waiting in the shop.")
                    }
                }

                Section("Light") {
                    Picker("Light", selection: $light) {
                        ForEach(BoothLight.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }

                Section {
                    TextField("What does \(s.slime.name) say?", text: $caption)
                        .submitLabel(.done)
                    Toggle("Show level and age", isOn: $showDetails)
                } header: {
                    Text("Card")
                } footer: {
                    Text("The speech bubble holds up to \(Self.captionLimit) characters. Leave it empty for no bubble.")
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) { shareBar(s.slime.name) }
            .sheetTitle("Photo Booth", inline: true)
            .task(id: options) { await render(options) }
            .onChange(of: caption) { _, text in
                if text.count > Self.captionLimit { caption = String(text.prefix(Self.captionLimit)) }
            }
            .alert(saveProblem?.title ?? "", isPresented: problemShown, presenting: saveProblem) { problem in
                if problem == .denied {
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                    }
                }
                Button("OK", role: .cancel) {}
            } message: { problem in
                Text(problem.message)
            }
        }
    }

    private var problemShown: Binding<Bool> {
        Binding(get: { saveProblem != nil }, set: { if !$0 { saveProblem = nil } })
    }

    // MARK: Preview

    @ViewBuilder
    private func preview(_ slime: Slime, _ options: BoothOptions) -> some View {
        Group {
            if let photo {
                Image(uiImage: photo)
                    .resizable()
                    .interpolation(.none)
                    .aspectRatio(contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))
                    .accessibilityLabel(photoDescription(slime, options))
                    .accessibilityAddTraits(.isImage)
            } else {
                RoundedRectangle(cornerRadius: Theme.radius, style: .continuous)
                    .fill(Theme.well)
                    .aspectRatio(options.format.aspect, contentMode: .fit)
                    .overlay {
                        if failed {
                            Text("The photo couldn't be made. Try another format.")
                                .font(.subheadline)
                                .foregroundStyle(Theme.inkSoft)
                                .multilineTextAlignment(.center)
                                .padding()
                        } else {
                            ProgressView()
                        }
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(failed ? "The photo couldn't be made" : "Making the photo")
            }
        }
        // A fixed height keeps the controls below still when the format changes.
        .frame(maxWidth: .infinity)
        .frame(height: 400)
        .animation(.easeOut(duration: 0.2), value: options.format)
    }

    private func photoDescription(_ slime: Slime, _ options: BoothOptions) -> String {
        var text = "Photo of \(slime.name) \(options.pose.doing) \(Self.whereAt(options.habitat))"
        text += ", \(options.light.title.lowercased())"
        if !options.caption.isEmpty { text += ", saying \(options.caption)" }
        return text
    }

    private static func whereAt(_ habitat: Habitat) -> String {
        switch habitat {
        case .room: "in its room"
        case .garden: "in the garden"
        case .beach: "on the beach"
        case .space: "in space"
        case .crystal: "in the crystal cave"
        }
    }

    // MARK: Controls

    private func posePicker(_ slime: Slime) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(BoothPose.allCases) { p in
                    let selected = pose == p
                    Button {
                        Feedback.shared.play(.tap)
                        pose = p
                    } label: {
                        VStack(spacing: 6) {
                            BoothSlime(slime: slime, pose: p)
                                .frame(width: 60, height: 50)
                            Text(p.title)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Theme.ink)
                                .lineLimit(1)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .frame(minWidth: 76, minHeight: 44)
                        .background(selected ? Theme.accent.opacity(0.15) : Theme.well,
                                    in: RoundedRectangle(cornerRadius: Theme.smallRadius, style: .continuous))
                        .overlay {
                            if selected {
                                RoundedRectangle(cornerRadius: Theme.smallRadius, style: .continuous)
                                    .strokeBorder(Theme.accent, lineWidth: 2)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(p.title)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
        .contentMargins(.horizontal, 16, for: .scrollContent)
    }

    private func placePicker(_ s: GameState, selected: Habitat) -> some View {
        let owned = Habitat.allCases.filter { s.inventory.habitats.contains($0) }
        let binding = Binding<Habitat>(get: { selected }, set: { place = $0 })
        return ChipTabs(options: owned.map { ($0, $0.title) }, selection: binding)
            .contentMargins(.horizontal, 16, for: .scrollContent)
    }

    // MARK: Sharing

    private func shareBar(_ name: String) -> some View {
        VStack(spacing: 0) {
            Divider()
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) {
                    saveButton
                    shareButton(name)
                }
                VStack(spacing: 10) {
                    shareButton(name)
                    saveButton
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(.bar)
    }

    @ViewBuilder
    private func shareButton(_ name: String) -> some View {
        let label = Label("Share", systemImage: "square.and.arrow.up")
            .font(.body.weight(.semibold))
            .foregroundStyle(Theme.onAccent)
            .frame(maxWidth: .infinity)
        if let photo {
            let image = Image(uiImage: photo)
            ShareLink(item: image,
                      subject: Text("\(name) in Slimy"),
                      message: Text("Meet \(name), my slime in Slimy. https://slime.hyprnode.de"),
                      preview: SharePreview("\(name) in Slimy", image: image)) {
                label
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .tint(Theme.accent)
        } else {
            Button {} label: { label }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .tint(Theme.accent)
                .disabled(true)
        }
    }

    private var saveButton: some View {
        let saved = saving == .saved
        return Button {
            guard let photo else { return }
            Feedback.shared.play(.tap)
            Task { await save(photo) }
        } label: {
            Label(saved ? "Saved" : "Save to Photos",
                  systemImage: saved ? "checkmark.circle.fill" : "square.and.arrow.down")
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .tint(Theme.accent)
        .disabled(photo == nil || saving == .saving)
    }

    /// Adds the picture to the photo library. Slimy only asks to add photos,
    /// never to see the library.
    private func save(_ image: UIImage) async {
        saving = .saving
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            saving = .idle
            Feedback.shared.play(.nope)
            saveProblem = .denied
            return
        }
        do {
            try await Self.addToLibrary(image)
        } catch {
            saving = .idle
            Feedback.shared.play(.nope)
            saveProblem = .failed
            return
        }
        saving = .saved
        Feedback.shared.play(.success)
        UIAccessibility.post(notification: .announcement, argument: "Saved to Photos")
        try? await Task.sleep(for: .seconds(2))
        if saving == .saved { saving = .idle }
    }

    /// Off the main actor: Photos runs the change block on its own queue.
    nonisolated private static func addToLibrary(_ image: UIImage) async throws {
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.creationRequestForAsset(from: image)
        }
    }

    // MARK: Rendering

    @MainActor
    private func render(_ options: BoothOptions) async {
        if photo != nil {
            // Typing in the caption changes the options with every key; wait
            // for a short pause before drawing again.
            try? await Task.sleep(for: .milliseconds(150))
            guard !Task.isCancelled else { return }
        }
        let s = store.state
        let card = BoothCard(slime: s.slime, furniture: s.inventory.furniture, options: options,
                             date: options.light.date(on: .now))
        let renderer = ImageRenderer(content: card)
        renderer.scale = 3
        renderer.proposedSize = ProposedViewSize(options.format.size)
        guard let image = renderer.uiImage, let cgImage = image.cgImage else {
            failed = photo == nil
            return
        }
        // Scale 1 with the full pixel size, so the shared file keeps every pixel.
        photo = UIImage(cgImage: cgImage, scale: 1, orientation: .up)
        failed = false
    }
}

// MARK: - Saving

private enum SaveState {
    case idle, saving, saved
}

private enum SaveProblem {
    case denied, failed

    var title: String {
        switch self {
        case .denied: "Allow Slimy to add photos"
        case .failed: "The photo wasn't saved"
        }
    }

    var message: String {
        switch self {
        case .denied: "To save pictures of your slime, let Slimy add photos in Settings."
        case .failed: "Something went wrong. Try again, or use Share and choose Save Image."
        }
    }
}

// MARK: - Options

private struct BoothOptions: Hashable {
    var pose: BoothPose
    var habitat: Habitat
    var light: BoothLight
    var format: BoothFormat
    var caption: String
    var showDetails: Bool
}

private enum BoothPose: String, CaseIterable, Identifiable, Hashable {
    case wave, idle, love, celebrate, play, blush, curious, eat, stretch, sleep

    var id: String { rawValue }

    var title: String {
        switch self {
        case .wave: "Wave"
        case .idle: "Chill"
        case .love: "Love"
        case .celebrate: "Party"
        case .play: "Jump"
        case .blush: "Shy"
        case .curious: "Curious"
        case .eat: "Snack"
        case .stretch: "Stretch"
        case .sleep: "Sleepy"
        }
    }

    /// For the photo's description: "Photo of Blob waving".
    var doing: String {
        switch self {
        case .wave: "waving"
        case .idle: "relaxing"
        case .love: "in love"
        case .celebrate: "celebrating"
        case .play: "jumping"
        case .blush: "blushing"
        case .curious: "looking curious"
        case .eat: "having a snack"
        case .stretch: "stretching"
        case .sleep: "sleeping"
        }
    }

    var anim: SpriteAnim {
        switch self {
        case .wave: .wave
        case .idle: .idle
        case .love: .love
        case .celebrate: .celebrate
        case .play: .play
        case .blush: .blush
        case .curious: .curious
        case .eat: .eat
        case .stretch: .stretch
        case .sleep: .sleep
        }
    }

    /// The frame of the animation that shows the pose best.
    var frame: Int {
        switch self {
        case .wave, .celebrate, .play, .sleep: 1
        case .blush: 2
        case .love, .curious: 3
        case .idle, .eat, .stretch: 0
        }
    }
}

private enum BoothLight: String, CaseIterable, Identifiable, Hashable {
    case day, evening, night

    var id: String { rawValue }

    var title: String {
        switch self {
        case .day: "Day"
        case .evening: "Evening"
        case .night: "Night"
        }
    }

    /// Today at a time of day that shows this light.
    func date(on day: Date) -> Date {
        let hour: Int
        let minute: Int
        switch self {
        case .day:
            hour = 12
            minute = 0
        case .evening:
            hour = 18
            minute = 0
        case .night:
            hour = 22
            minute = 30
        }
        return Calendar.current.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day
    }
}

private enum BoothFormat: String, CaseIterable, Identifiable, Hashable {
    case portrait, square, story

    var id: String { rawValue }

    var title: String {
        switch self {
        case .portrait: "Portrait"
        case .square: "Square"
        case .story: "Story"
        }
    }

    /// In points. 384 is 96 habitat pixels at 4 points each; the image is
    /// rendered at 3x (1152 pixels wide).
    var size: CGSize {
        switch self {
        case .portrait: CGSize(width: 384, height: 480)
        case .square: CGSize(width: 384, height: 384)
        case .story: CGSize(width: 384, height: 683)
        }
    }

    var aspect: CGFloat { size.width / size.height }

    var slimeHeight: CGFloat {
        switch self {
        case .portrait: 150
        case .square: 118
        case .story: 170
        }
    }

    var plateHeight: CGFloat { self == .square ? 64 : 76 }
}

// MARK: - The card

/// One frame of the slime in a pose, wearing what it wears.
private struct BoothSlime: View {
    let slime: Slime
    let pose: BoothPose

    var body: some View {
        let frames = SlimeSprites.frames(slime.stage, pose.anim)
        let anchors = SlimeSprites.anchors(slime.stage, pose.anim)
        let i = min(pose.frame, max(0, frames.count - 1))
        PixelSlime(frame: frames.isEmpty ? SlimeSprites.blank : frames[i], skin: slime.skin,
                   accessories: slime.equipped, stage: slime.stage, anchor: i < anchors.count ? anchors[i] : nil)
    }
}

/// The picture itself. Fixed sizes and colours: it is an image, so it looks
/// the same in light and dark mode and at every text size.
private struct BoothCard: View {
    let slime: Slime
    let furniture: [FurnitureSlot: String]
    let options: BoothOptions
    let date: Date

    private var paper: Color { .white }
    private var ink: Color { Color(hex: 0x1C1C1E) }
    private var inkSoft: Color { Color(hex: 0x636366) }
    private var green: Color { Color(hex: 0x1E7F37) }

    var body: some View {
        let size = options.format.size
        let scale = size.width / CGFloat(HabitatScene.gridWidth)
        let rows = Int((size.height / scale).rounded(.up))
        let floor = CGFloat(Int(Double(rows) * 0.66)) * scale
        let plate = options.format.plateHeight
        let feet = min(floor + 10 * scale, size.height - 14 - plate - 10)
        let slimeHeight = options.format.slimeHeight
        let aspect = CGFloat(SlimeSprites.width) / CGFloat(SlimeSprites.height)

        ZStack(alignment: .top) {
            // The sky of that hour under the scene, so the card never has a hole.
            Rectangle()
                .fill(DayLight(date).sky)
            HabitatScene(habitat: options.habitat, furniture: furniture, date: date, animate: false)
                .frame(width: size.width, height: size.height)

            VStack(spacing: 6) {
                if !options.caption.isEmpty {
                    bubble(options.caption)
                        .frame(maxWidth: size.width * 0.76)
                }
                ZStack(alignment: .bottom) {
                    Rectangle()
                        .fill(Color.black.opacity(0.18))
                        .frame(width: slimeHeight * 0.8, height: max(3, slimeHeight * 0.04))
                    BoothSlime(slime: slime, pose: options.pose)
                        .frame(width: slimeHeight * aspect, height: slimeHeight)
                    if let effect = slime.equipped.effect {
                        BoothParticles(effect: effect, height: slimeHeight)
                    }
                }
            }
            .frame(width: size.width, height: feet, alignment: .bottom)

            VStack {
                Spacer(minLength: 0)
                nameplate
                    .frame(height: plate)
            }
            .padding(14)
            .frame(width: size.width, height: size.height)
        }
        .frame(width: size.width, height: size.height)
        .clipped()
        .environment(\.colorScheme, .light)
        .environment(\.dynamicTypeSize, .large)
    }

    private var nameplate: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(slime.name)
                    .font(.system(size: 24, weight: .bold, design: .rounded))
                    .foregroundStyle(ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                if options.showDetails {
                    Text("\(slime.stage.title) · Level \(slime.level) · Day \(slime.ageDays() + 1)")
                        .font(.system(size: 13, weight: .medium, design: .rounded))
                        .foregroundStyle(inkSoft)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 0) {
                Text(verbatim: "slimy")
                    .font(.custom("PixelifySans-Bold", fixedSize: 26))
                    .foregroundStyle(green)
                Text(verbatim: "slime.hyprnode.de")
                    .font(.system(size: 11, weight: .semibold, design: .rounded))
                    .foregroundStyle(inkSoft)
            }
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(paper, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func bubble(_ text: String) -> some View {
        Text(text)
            .font(.custom("PixelifySans-Bold", fixedSize: 17))
            .foregroundStyle(ink)
            .multilineTextAlignment(.center)
            .lineLimit(3)
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(paper, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(alignment: .bottom) {
                BoothTail().fill(paper).frame(width: 16, height: 8).offset(y: 7)
            }
            .padding(.bottom, 8)
    }
}

/// A still version of the equipped effect around the slime.
private struct BoothParticles: View {
    let effect: String
    let height: CGFloat

    private static let spots: [(x: CGFloat, y: CGFloat, size: CGFloat)] = [
        (-0.62, -0.86, 1), (0.6, -0.74, 0.8), (-0.5, -0.36, 0.7), (0.68, -0.3, 1), (0.08, -1.1, 0.75),
    ]

    var body: some View {
        ZStack(alignment: .bottom) {
            ForEach(0..<Self.spots.count, id: \.self) { i in
                let spot = Self.spots[i]
                particle
                    .frame(width: height * 0.11 * spot.size, height: height * 0.11 * spot.size)
                    .offset(x: spot.x * height, y: spot.y * height)
            }
        }
        .allowsHitTesting(false)
    }

    @ViewBuilder private var particle: some View {
        switch effect {
        case "effect.hearts": PixelItem(name: "heart")
        case "effect.bubbles": PixelItem(name: "bubbleItem")
        default: IconShape(rows: SlimeSprites.emoteIcons["sparkle_small"] ?? []).fill(Color(hex: 0xFFD166))
        }
    }
}

private struct BoothTail: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.minX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
        p.closeSubpath()
        return p
    }
}
