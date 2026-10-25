import ActivityKit
import SwiftUI
import UIKit

/// Everything the Dynamic Island and the save depend on, in one place, so a
/// problem can be found without a Mac attached.
struct DiagnosticsView: View {
    @EnvironmentObject private var island: IslandController
    @EnvironmentObject private var store: GameStore
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    @State private var report: [(String, String, Bool)] = []
    @State private var previewStart = Date.now

    var body: some View {
        ZStack {
            MenuBackdrop()
            ScrollView {
                VStack(spacing: 14) {
                    MenuHeader(title: "Diagnose") { dismiss() }

                    PixelWindow(title: "Insel-Vorschau") {
                        HStack(spacing: 18) {
                            let s = store.state.slime
                            AnimatedSlime(stage: s.stage, anim: .idle, anchor: previewStart, skin: s.skin,
                                          accessories: s.equipped, height: 56)
                            AnimatedSlime(stage: s.stage, anim: .play, anchor: previewStart, skin: s.skin,
                                          accessories: s.equipped, height: 56)
                            EmoteView(reel: .happyA, anchor: previewStart, size: 30)
                        }
                        .padding(8)
                        .background(Color.black)
                        Text("So zeichnet die Insel den Slime. Bewegt er sich hier jede Sekunde, klappt die Animation.")
                            .font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                        Toggle(isOn: Binding(get: { island.probe }, set: { island.probe = $0 })) {
                            Text("Test-Streifen auf dem Sperrbildschirm").font(Theme.label(15)).foregroundStyle(Theme.ink)
                        }
                    }

                    PixelWindow(title: "Zustand") {
                        ForEach(report, id: \.0) { label, value, ok in
                            HStack(alignment: .firstTextBaseline) {
                                Image(systemName: ok ? "checkmark.square.fill" : "xmark.square.fill")
                                    .foregroundStyle(ok ? Theme.accent : Theme.danger)
                                Text(label).font(Theme.label(14)).foregroundStyle(Theme.ink)
                                Spacer()
                                Text(value).font(Theme.text(13)).foregroundStyle(Theme.inkSoft).multilineTextAlignment(.trailing)
                            }
                        }
                    }

                    HStack(spacing: 10) {
                        PixelButton(title: "Insel neu starten") {
                            Task {
                                await island.ensureRunning(restart: true)
                                refresh()
                            }
                        }
                        PixelButton(title: copied ? "Kopiert" : "Bericht kopieren", kind: .secondary) {
                            UIPasteboard.general.string = text
                            copied = true
                        }
                    }

                    PixelWindow(title: "Verlauf") {
                        if island.log.isEmpty {
                            Text("Noch nichts passiert.").font(Theme.text(13)).foregroundStyle(Theme.inkSoft)
                        }
                        ForEach(Array(island.log.enumerated()), id: \.offset) { _, line in
                            Text(line).font(.system(size: 11, design: .monospaced)).foregroundStyle(Theme.ink)
                                .textSelection(.enabled)
                        }
                    }
                }
                .padding(16)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .onAppear(perform: refresh)
        .onChange(of: island.log.count) { refresh() }
    }

    private var text: String {
        (report.map { "\($0.2 ? "OK " : "XX ") \($0.0): \($0.1)" } + ["--"] + island.log).joined(separator: "\n")
    }

    private func refresh() {
        let auth = ActivityAuthorizationInfo()
        let activities = Activity<SlimeActivityAttributes>.activities
        let appex = Bundle.main.builtInPlugInsURL?.appendingPathComponent("SlimyWidgets.appex")
        let appexExists = appex.map { FileManager.default.fileExists(atPath: $0.path) } ?? false
        let fonts = appex.flatMap { try? FileManager.default.contentsOfDirectory(atPath: $0.path) }?
            .filter { $0.hasSuffix(".ttf") }.count ?? 0
        let model = Self.modelIdentifier
        let hasIsland = Self.hasDynamicIsland(model)
        let fontOK = UIFont(name: "Slime-Young-Idle-Body", size: 10) != nil
        let sprites = !SlimeSprites.data.frames.isEmpty

        report = [
            ("iPhone", "\(model) · iOS \(UIDevice.current.systemVersion)", true),
            ("Dynamic Island", hasIsland == nil ? "unbekannt" : (hasIsland! ? "ja" : "nein"), hasIsland != false),
            ("Live-Aktivitäten erlaubt", auth.areActivitiesEnabled ? "ja" : "nein", auth.areActivitiesEnabled),
            ("Insel-Erweiterung", appexExists ? "installiert" : "fehlt", appexExists),
            ("Animations-Schriften", "\(fonts)", fonts > 800),
            ("Schrift lädt", fontOK ? "ja" : "nein", fontOK),
            ("Sprites", sprites ? "geladen" : "fehlen", sprites),
            ("Aktivitäten", activities.isEmpty ? "keine" : activities.map { "\($0.activityState)" }.joined(separator: ", "),
             activities.contains { $0.activityState == .active }),
            ("Spielstand", "\(store.loadSource.rawValue), Version \(store.state.version)", store.saveError == nil),
            ("App Group (Widgets)", GameStorage.sharedDirectory == nil ? "nicht verfügbar" : "aktiv", GameStorage.sharedDirectory != nil),
        ]
        if let problem = island.problem { report.append(("Letzter Fehler", problem, false)) }
        if let error = store.saveError { report.append(("Speichern", error, false)) }
    }

    static var modelIdentifier: String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    /// iPhone 14 Pro and later Pro models, and every iPhone 15 and newer
    /// except the 16e, have the island.
    static func hasDynamicIsland(_ model: String) -> Bool? {
        let parts = model.replacingOccurrences(of: "iPhone", with: "").split(separator: ",")
        guard model.hasPrefix("iPhone"), parts.count == 2,
              let major = Int(parts[0]), let minor = Int(parts[1]) else { return nil }
        if major == 17, minor == 5 { return false }
        if major >= 16 { return true }
        if major == 15 { return minor >= 2 }
        return false
    }
}
