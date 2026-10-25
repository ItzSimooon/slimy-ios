import SwiftUI
import UIKit

// The calm games. They are built from real buttons, so VoiceOver can play
// them: every cell says what it is.

/// Tile with the window chrome, used for grid cells.
private struct Tile<Content: View>: View {
    var fill: Color
    @ViewBuilder var content: Content

    var body: some View {
        ZStack { content }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(NotchedRect(notch: 2).fill(fill))
            .overlay(NotchedRect(notch: 2).strokeBorder(Theme.border.opacity(0.6), lineWidth: 2))
    }
}

/// Small white marks that tell the colours apart without colour.
private let colorMarks: [[String]] = [
    [".#.#.", "#####", "#####", ".###.", "..#.."],
    ["..#..", ".###.", "#####", "#####", ".###."],
    ["..#..", "#####", ".###.", ".#.#.", "#...#"],
    ["..#..", "..#..", "#####", "..#..", "..#.."],
]
private let bubbleColors: [UInt32] = [0xE0577F, 0x4FA3D9, 0xE8A92A, 0x4FB84E]
private let bubbleNames = ["Rosa", "Blau", "Gelb", "Grün"]

// MARK: - Blasen-Platzer

struct BubblePopView: View {
    let session: GameSession
    @State private var engine: BubblePopEngine

    init(session: GameSession) {
        self.session = session
        _engine = State(initialValue: BubblePopEngine(seed: session.seed))
    }

    var body: some View {
        GeometryReader { geo in
            let cell = min(geo.size.width / CGFloat(BubblePopEngine.columns),
                           geo.size.height / CGFloat(BubblePopEngine.rows))
            VStack(spacing: 0) {
                ForEach((0..<BubblePopEngine.rows).reversed(), id: \.self) { row in
                    HStack(spacing: 0) {
                        ForEach(0..<BubblePopEngine.columns, id: \.self) { column in
                            bubble(column, row).frame(width: cell, height: cell)
                        }
                    }
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .background(GameBackdrop(top: 0xBFE3F0, bottom: 0xE8F6F8))
        }
        .onAppear(perform: report)
    }

    @ViewBuilder
    private func bubble(_ column: Int, _ row: Int) -> some View {
        if let color = engine.color(column, row) {
            let size = engine.group(column, row).count
            Button {
                let popped = withAnimation(.easeOut(duration: 0.15)) { engine.pop(column, row) }
                Feedback.shared.play(popped >= 2 ? (popped >= 6 ? .perfect : .pop) : .nope)
                report()
                if engine.isOver {
                    if engine.isCleared { Feedback.shared.play(.success) }
                    session.end()
                }
            } label: {
                ZStack {
                    Circle().fill(Color(hex: bubbleColors[color]))
                    Circle().strokeBorder(Theme.border.opacity(0.5), lineWidth: 2)
                    IconShape(rows: colorMarks[color]).fill(.white.opacity(0.8)).padding(cell * 0.3)
                    Rectangle().fill(.white.opacity(0.7)).frame(width: cell * 0.12, height: cell * 0.12)
                        .offset(x: -cell * 0.2, y: -cell * 0.2)
                }
                .padding(2)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(bubbleNames[color]), Gruppe von \(size)")
            .accessibilityHint(size >= 2 ? "Platzen lassen" : "Zu klein")
        } else {
            Color.clear.accessibilityHidden(true)
        }
    }

    private func report() {
        session.score = engine.score
        let left = engine.grid.reduce(0) { $0 + $1.compactMap { $0 }.count }
        session.status = "\(left) übrig"
    }
}

// MARK: - Gedächtnisgarten

struct MemoryGardenView: View {
    let session: GameSession
    @State private var engine: MemoryGardenEngine
    @State private var flash: (flower: Int, at: Date)?
    @State private var lastGlow: Int?

    init(session: GameSession) {
        self.session = session
        _engine = State(initialValue: MemoryGardenEngine(seed: session.seed))
    }

    var body: some View {
        VStack(spacing: 16) {
            Text(engine.isListening ? "Du bist dran" : "Schau genau hin")
                .font(Theme.title(20)).foregroundStyle(Theme.ink)
                .padding(.top, 16)
                .accessibilityAddTraits(.updatesFrequently)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
                ForEach(0..<MemoryGardenEngine.flowers, id: \.self) { i in
                    flower(i)
                }
            }
            .padding(.horizontal, 18)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(GameBackdrop(top: 0xCFEBC0, bottom: 0x9BD08A))
        .onAppear { session.status = "Runde 1" }
        .gameLoop(running: session.running) { dt in
            engine.step(dt)
            let glow = engine.glowing
            if glow != lastGlow, let glow {
                Feedback.shared.play(.tone(glow))
                if UIAccessibility.isVoiceOverRunning {
                    UIAccessibility.post(notification: .announcement, argument: "Blume \(glow + 1)")
                }
            }
            lastGlow = glow
        }
    }

    private func flower(_ i: Int) -> some View {
        let flashing = flash.map { $0.flower == i && Date.now.timeIntervalSince($0.at) < 0.25 } ?? false
        let lit = engine.glowing == i || flashing
        return Button {
            guard engine.isListening else { return }
            flash = (i, .now)
            if engine.tap(i) {
                Feedback.shared.play(.tone(i))
                session.score = engine.score
                session.status = "Runde \(engine.score + 1)"
            } else {
                Feedback.shared.play(.nope)
                session.end()
            }
            Task {
                try? await Task.sleep(for: .milliseconds(260))
                flash = nil
            }
        } label: {
            Tile(fill: lit ? Color(hex: 0xFFF3B0) : Color(hex: 0x6FAF5A)) {
                PixelItem(name: "flower")
                    .padding(14)
                    .scaleEffect(lit ? 1.15 : 1)
                    .opacity(lit ? 1 : 0.75)
            }
            .aspectRatio(1, contentMode: .fit)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Blume \(i + 1)")
        .accessibilityAddTraits(lit ? .isSelected : [])
    }
}

// MARK: - Garten-Rätsel

struct GardenPuzzleView: View {
    let session: GameSession
    @State private var engine = GardenPuzzleEngine()
    @State private var time = 0.0

    var body: some View {
        VStack(spacing: 12) {
            GeometryReader { geo in
                let cell = min(geo.size.width / CGFloat(engine.width), geo.size.height / CGFloat(engine.height))
                VStack(spacing: 0) {
                    ForEach(0..<engine.height, id: \.self) { y in
                        HStack(spacing: 0) {
                            ForEach(0..<engine.width, id: \.self) { x in
                                tile(GardenPuzzleEngine.Point(x: x, y: y), cell: cell).frame(width: cell, height: cell)
                            }
                        }
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 16).onEnded { value in
                    let t = value.translation
                    if abs(t.width) > abs(t.height) { move(t.width > 0 ? .right : .left) } else { move(t.height > 0 ? .down : .up) }
                })
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(boardDescription)
            }
            .padding(10)

            HStack(spacing: 8) {
                PixelButton(title: "Neu", systemIcon: "arrow.counterclockwise", kind: .quiet, compact: true) {
                    engine.restartLevel()
                }
                Spacer()
                arrow(.left, "arrow.left", "Links")
                VStack(spacing: 6) {
                    arrow(.up, "arrow.up", "Hoch")
                    arrow(.down, "arrow.down", "Runter")
                }
                arrow(.right, "arrow.right", "Rechts")
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 10)
        }
        .background(Color(hex: 0x8FCB76))
        .onAppear { session.status = "Feld 1/\(GardenPuzzleEngine.levels.count)" }
        .gameLoop(running: session.running) { dt in time += dt }
    }

    private func arrow(_ d: GardenPuzzleEngine.Direction, _ icon: String, _ label: String) -> some View {
        Button { move(d) } label: {
            Image(systemName: icon).font(.system(size: 16, weight: .black)).foregroundStyle(Theme.ink)
                .frame(width: 48, height: 40)
        }
        .buttonStyle(KeyStyle(fill: Theme.paper))
        .accessibilityLabel(label)
    }

    private func move(_ d: GardenPuzzleEngine.Direction) {
        guard session.running else { return }
        switch engine.move(d) {
        case .blocked: Feedback.shared.play(.nope)
        case .moved: Feedback.shared.play(.hop)
        case .pushed: Feedback.shared.play(.land)
        case .seed: Feedback.shared.play(.coin)
        case .solved:
            Feedback.shared.play(.success)
            session.status = "Feld \(engine.level + 1)/\(GardenPuzzleEngine.levels.count)"
        case .finished:
            Feedback.shared.play(.success)
            session.score = engine.score
            session.end()
        }
        session.score = engine.score
        if UIAccessibility.isVoiceOverRunning {
            UIAccessibility.post(notification: .announcement, argument: boardDescription)
        }
    }

    @ViewBuilder
    private func tile(_ p: GardenPuzzleEngine.Point, cell: CGFloat) -> some View {
        if engine.walls.contains(p) {
            Rectangle().fill(Color(hex: 0x2F6B3A))
                .overlay(Rectangle().fill(Color(hex: 0x3F8F4A)).padding(3).offset(y: -2))
        } else {
            ZStack {
                Rectangle().fill((p.x + p.y) % 2 == 0 ? Color(hex: 0xA8DB8C) : Color(hex: 0x9DD382))
                if p == engine.bed {
                    Rectangle().fill(Color(hex: 0x8A5A3C)).padding(3)
                    if engine.seeds.isEmpty { PixelItem(name: "flower").padding(4) }
                }
                if engine.seeds.contains(p) { PixelItem(name: "seed").padding(6) }
                if engine.stones.contains(p) { PixelItem(name: "rock").padding(2) }
                if engine.slime == p {
                    GameSlime(anim: .idle, height: cell * 0.8, time: time)
                }
            }
        }
    }

    private var boardDescription: String {
        let s = engine.slime
        func describe(_ d: GardenPuzzleEngine.Direction) -> String {
            let p = s.moved(d)
            if engine.walls.contains(p) { return "Hecke" }
            if engine.stones.contains(p) { return "Stein" }
            if engine.seeds.contains(p) { return "Samen" }
            if p == engine.bed { return "Beet" }
            return "frei"
        }
        return "Feld \(engine.level + 1). Noch \(engine.seeds.count) Samen. Oben \(describe(.up)), unten \(describe(.down)), links \(describe(.left)), rechts \(describe(.right))."
    }
}

// MARK: - Schatzsuche

struct TreasureDigView: View {
    let session: GameSession
    @State private var engine: TreasureDigEngine

    init(session: GameSession) {
        self.session = session
        _engine = State(initialValue: TreasureDigEngine(seed: session.seed))
    }

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 6) {
                ForEach(0..<TreasureDigEngine.treasureCount, id: \.self) { i in
                    PixelItem(name: "chest").frame(width: 22, height: 22).opacity(i < engine.found ? 1 : 0.25)
                }
            }
            .padding(.top, 12)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(engine.found) von \(TreasureDigEngine.treasureCount) Schätzen gefunden")

            GeometryReader { geo in
                let cell = min(geo.size.width, geo.size.height) / CGFloat(TreasureDigEngine.size)
                VStack(spacing: 0) {
                    ForEach(0..<TreasureDigEngine.size, id: \.self) { y in
                        HStack(spacing: 0) {
                            ForEach(0..<TreasureDigEngine.size, id: \.self) { x in
                                cellView(y * TreasureDigEngine.size + x).frame(width: cell, height: cell)
                            }
                        }
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .padding(10)
        }
        .background(Color(hex: 0x7CC46A))
        .onAppear(perform: report)
    }

    @ViewBuilder
    private func cellView(_ i: Int) -> some View {
        switch engine.cells[i] {
        case .hidden:
            Button {
                let cell = engine.dig(i)
                Feedback.shared.play(cell == .treasure ? .treasure : .dig)
                report()
                if engine.isOver {
                    Task {
                        try? await Task.sleep(for: .milliseconds(700))
                        session.end()
                    }
                }
            } label: {
                Tile(fill: (i % 2 == 0) ? Color(hex: 0x9DD382) : Color(hex: 0x92CB77)) {
                    Rectangle().fill(Color(hex: 0x6FAF5A)).frame(width: 4, height: 4).offset(x: -5, y: 3)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Gras, Reihe \(i / TreasureDigEngine.size + 1), Spalte \(i % TreasureDigEngine.size + 1)")
            .accessibilityHint("Graben")
        case .empty:
            let hint = engine.hint(at: i)
            Tile(fill: Color(hex: 0x8A5A3C)) {
                Text("\(hint)").font(Theme.label(16))
                    .foregroundStyle(hint <= 1 ? Color(hex: 0xFFE07A) : Color(hex: 0xF1E2C4))
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Loch. Nächster Schatz \(hint) Felder entfernt")
        case .treasure:
            Tile(fill: Color(hex: 0x8A5A3C)) {
                PixelItem(name: "chest").padding(5)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Schatz")
        }
    }

    private func report() {
        session.score = engine.score
        session.status = "\(engine.shovels) Schaufeln"
    }
}

// MARK: - Farbenspiel

struct ColorMatchView: View {
    let session: GameSession
    @State private var engine: ColorMatchEngine
    @State private var feedback: (right: Bool, at: Date)?

    private static let colors: [UInt32] = [0xD9483B, 0x4FA3D9, 0xE8C02A, 0x4FB84E]
    private static let shapes: [[String]] = [
        ["..###..", ".#####.", "#######", "#######", "#######", ".#####.", "..###.."],
        ["#######", "#######", "#######", "#######", "#######", "#######", "#######"],
        ["...#...", "..###..", "..###..", ".#####.", ".#####.", "#######", "#######"],
        ["...#...", "...#...", "#######", ".#####.", "..###..", ".##.##.", "##...##"],
    ]

    init(session: GameSession) {
        self.session = session
        _engine = State(initialValue: ColorMatchEngine(seed: session.seed))
    }

    var body: some View {
        VStack(spacing: 14) {
            Text(engine.rule == .color ? "Gleiche FARBE?" : "Gleiche FORM?")
                .font(Theme.title(26)).foregroundStyle(Theme.ink)
                .padding(.top, 16)
                .accessibilityAddTraits(.isHeader)
            HStack(spacing: 14) {
                card(engine.target, title: "Vorlage")
                card(engine.piece, title: "Teil")
            }
            .padding(.horizontal, 16)
            .overlay {
                if let feedback, Date.now.timeIntervalSince(feedback.at) < 0.3 {
                    NotchedRect().strokeBorder(feedback.right ? Theme.accent : Theme.danger, lineWidth: 6)
                        .padding(.horizontal, 8)
                        .allowsHitTesting(false)
                }
            }
            Spacer()
            HStack(spacing: 12) {
                PixelButton(title: "Nein", kind: .danger) { answer(false) }
                PixelButton(title: "Ja") { answer(true) }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.paperShade)
        .onAppear { session.status = clock(engine.timeLeft) }
        .gameLoop(running: session.running) { dt in
            engine.step(dt)
            session.status = clock(engine.timeLeft)
            if engine.isOver { session.end() }
        }
    }

    private func card(_ piece: ColorMatchEngine.Piece, title: String) -> some View {
        PixelWindow {
            Text(title).font(Theme.label(13)).foregroundStyle(Theme.inkSoft)
            IconShape(rows: Self.shapes[piece.shape])
                .fill(Color(hex: Self.colors[piece.color]))
                .aspectRatio(1, contentMode: .fit)
                .padding(10)
            Text("\(ColorMatchEngine.colorNames[piece.color]) · \(ColorMatchEngine.shapeNames[piece.shape])")
                .font(Theme.text(13)).foregroundStyle(Theme.ink)
                .frame(maxWidth: .infinity)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title): \(ColorMatchEngine.colorNames[piece.color]), \(ColorMatchEngine.shapeNames[piece.shape])")
    }

    private func answer(_ yes: Bool) {
        guard session.running else { return }
        let right = engine.answer(yes)
        feedback = (right, .now)
        Feedback.shared.play(right ? .pop : .nope)
        session.score = engine.score
        Task {
            try? await Task.sleep(for: .milliseconds(320))
            feedback = nil
        }
    }
}
