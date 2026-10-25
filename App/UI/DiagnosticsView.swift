import ActivityKit
import SwiftUI
import UIKit

/// Everything the Dynamic Island depends on, in one place, so a problem can
/// be found without a Mac attached.
struct DiagnosticsView: View {
    @EnvironmentObject private var island: IslandController
    @EnvironmentObject private var store: SlimeStore
    @Environment(\.dismiss) private var dismiss
    @State private var previewStart = Date.now
    @State private var copied = false
    @State private var report: [(String, String, Bool)] = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Button { dismiss() } label: {
                        Label("Zurück", systemImage: "chevron.left").font(Brand.display(15, .bold))
                    }
                    Spacer()
                }
                Text("Diagnose")
                    .font(Brand.display(26, .black))
                    .foregroundStyle(Brand.text)

                Panel {
                    VStack(alignment: .leading, spacing: 12) {
                        Eyebrow(text: "insel-vorschau")
                        HStack(spacing: 18) {
                            AnimatedSlime(anim: .idle, anchor: previewStart, skin: store.state.skin, height: 56)
                            AnimatedSlime(anim: .play, anchor: previewStart, skin: store.state.skin, height: 56)
                            AnimatedSlime(anim: .eat, anchor: previewStart, skin: store.state.skin, height: 56)
                        }
                        Text("Das ist die Animation über die Uhr. Bewegt er sich hier jede Sekunde, klappt der Trick zumindest in der App.")
                            .font(Brand.display(13, .medium))
                            .foregroundStyle(Brand.muted)
                        Toggle(isOn: Binding(get: { island.animated }, set: { island.animated = $0 })) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Animation in der Insel (experimentell)")
                                    .font(Brand.display(15, .bold))
                                    .foregroundStyle(Brand.text)
                                Text("Aus: Der Slime wird aus Pixeln gezeichnet und ist sicher sichtbar. An: Er bewegt sich jede Sekunde, falls iOS mitspielt. Ist er danach unsichtbar, schalte es wieder aus.")
                                    .font(Brand.display(12, .medium))
                                    .foregroundStyle(Brand.muted)
                            }
                        }
                        .tint(Brand.hypr)
                    }
                }

                Panel {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(report, id: \.0) { label, value, ok in
                            HStack(alignment: .firstTextBaseline) {
                                Image(systemName: ok ? "checkmark.circle.fill" : "xmark.circle.fill")
                                    .foregroundStyle(ok ? Brand.color(for: .clean) : Brand.danger)
                                Text(label)
                                    .font(Brand.display(14, .semibold))
                                    .foregroundStyle(Brand.text)
                                Spacer()
                                Text(value)
                                    .font(Brand.mono(12, .semibold))
                                    .foregroundStyle(Brand.muted)
                                    .multilineTextAlignment(.trailing)
                            }
                        }
                    }
                }

                HStack(spacing: 10) {
                    JellyButton(title: "Insel neu starten", symbol: "arrow.clockwise", tint: Brand.hypr) {
                        Task {
                            await island.ensureRunning(restart: true)
                            refresh()
                        }
                    }
                    JellyButton(title: copied ? "Kopiert" : "Bericht kopieren", symbol: "doc.on.doc",
                                tint: Brand.hypr, filled: false) {
                        UIPasteboard.general.string = text
                        copied = true
                    }
                }

                Eyebrow(text: "verlauf")
                VStack(alignment: .leading, spacing: 4) {
                    if island.log.isEmpty {
                        Text("Noch nichts passiert. Tipp auf „Insel neu starten“.")
                            .font(Brand.display(13, .medium))
                            .foregroundStyle(Brand.muted)
                    }
                    ForEach(Array(island.log.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(Brand.mono(11, .medium))
                            .foregroundStyle(Brand.text)
                            .textSelection(.enabled)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(Brand.deep, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                Text("Nach dem Neustart zum Home-Bildschirm wischen: Solange Slimy im Vordergrund ist, zeigt iOS die Insel nicht.")
                    .font(Brand.display(13, .medium))
                    .foregroundStyle(Brand.muted)
            }
            .padding(20)
        }
        .background(Brand.void)
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
        let plugins = Bundle.main.builtInPlugInsURL
        let appex = plugins?.appendingPathComponent("SlimyWidgets.appex")
        let appexExists = appex.map { FileManager.default.fileExists(atPath: $0.path) } ?? false
        let fonts = appex.flatMap { try? FileManager.default.contentsOfDirectory(atPath: $0.path) }?
            .filter { $0.hasSuffix(".ttf") }.count ?? 0
        let model = Self.modelIdentifier
        let hasIsland = Self.hasDynamicIsland(model)

        report = [
            ("iPhone", "\(model) · iOS \(UIDevice.current.systemVersion)", true),
            ("Hat Dynamic Island", hasIsland == nil ? "unbekannt" : (hasIsland! ? "ja" : "nein"), hasIsland != false),
            ("Live-Aktivitäten erlaubt", auth.areActivitiesEnabled ? "ja" : "nein", auth.areActivitiesEnabled),
            ("Insel-Erweiterung installiert", appexExists ? "ja" : "fehlt", appexExists),
            ("Animations-Schriften", "\(fonts) von 66", fonts == 66),
            ("Schrift lädt", UIFont(name: "Slime-Idle-Body", size: 10) != nil ? "ja" : "nein",
             UIFont(name: "Slime-Idle-Body", size: 10) != nil),
            ("Laufende Aktivitäten", activities.isEmpty ? "keine" :
                activities.map { "\($0.activityState)" }.joined(separator: ", "),
             activities.contains { $0.activityState == .active }),
            ("Automatisch zurückholen", island.keepAlive ? "an" : "aus", island.keepAlive),
        ]
        if let problem = island.problem {
            report.append(("Letzter Fehler", problem, false))
        }
    }

    static var modelIdentifier: String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    /// iPhone15,2 (14 Pro) and later Pro models, and every iPhone 15 and newer, have the island.
    static func hasDynamicIsland(_ model: String) -> Bool? {
        let parts = model.replacingOccurrences(of: "iPhone", with: "").split(separator: ",")
        guard model.hasPrefix("iPhone"), parts.count == 2,
              let major = Int(parts[0]), let minor = Int(parts[1]) else { return nil }
        if major == 17, minor == 5 { return false }  // iPhone 16e
        if major >= 16 { return true }
        if major == 15 { return minor >= 2 }
        return false
    }
}
