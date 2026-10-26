import XCTest
@testable import Slimy

private let berlin: Calendar = {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "Europe/Berlin")!
    return c
}()

private let noon = berlin.date(from: DateComponents(year: 2026, month: 5, day: 4, hour: 12))!

/// A slime with a bit of history: a gift streak, a purchase, a record.
private func livedInState() -> GameState {
    var state = GameState()
    state.hatch(name: "Mochi", skin: .ocean, seed: 7, now: noon.addingTimeInterval(-3 * 86400))
    state.settings.sleep.auto = false
    for back in (0...2).reversed() {
        _ = state.claimDailyGift(now: noon.addingTimeInterval(-Double(back) * 86400), calendar: berlin)
    }
    _ = state.finish(GameResult(game: .bubblePop, score: 420, session: UUID(), finishedAt: noon))
    if let hat = Catalog.item("hat.party") { _ = state.buy(hat, now: noon) }
    return state
}

final class CoreSaveTests: XCTestCase {
    private var folder: URL!

    override func setUpWithError() throws {
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("slimy-tests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: folder)
    }

    func testExportImportRoundTrip() throws {
        let state = livedInState()
        let data = try SaveFile.encode(state, at: noon, appVersion: "1.0.0")
        let back = try SaveFile.decode(data, now: noon)
        XCTAssertTrue(back.hatched)
        XCTAssertEqual(back.version, GameState.currentVersion)
        XCTAssertEqual(back.slime.name, "Mochi")
        XCTAssertEqual(back.slime.skin, .ocean)
        XCTAssertEqual(back.slime.xp, state.slime.xp)
        XCTAssertEqual(back.slime.needs, state.slime.needs)
        XCTAssertEqual(back.slime.personality, state.slime.personality)
        XCTAssertEqual(back.wallet, state.wallet)
        XCTAssertEqual(back.inventory, state.inventory)
        XCTAssertEqual(back.progress, state.progress)
        XCTAssertEqual(back.streak, state.streak)
        XCTAssertEqual(back.memory.keepsakes, state.memory.keepsakes)
        XCTAssertEqual(back.streak.count, 3)
        XCTAssertTrue(back.owns("hat.party"))
    }

    func testWritesAFileThatReadsBack() throws {
        let state = livedInState()
        let url = try SaveFile.write(state, to: folder, at: noon, appVersion: "1.0.0", calendar: berlin)
        XCTAssertEqual(url.pathExtension, SaveFile.fileExtension)
        XCTAssertEqual(url.lastPathComponent, "Slimy-Mochi-2026-05-04.slimysave")
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        let back = try SaveFile.read(from: url, now: noon)
        XCTAssertEqual(back.wallet, state.wallet)
        XCTAssertEqual(back.streak, state.streak)
        // Plain JSON with a small header, readable by anyone curious.
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        XCTAssertEqual(object["format"] as? String, SaveFile.format)
        XCTAssertEqual(object["schema"] as? Int, SaveFile.schema)
        XCTAssertEqual(object["appVersion"] as? String, "1.0.0")
    }

    func testFileNamesAreSafe() {
        var state = livedInState()
        state.slime.name = "Mö chi/../!"
        XCTAssertEqual(SaveFile.fileName(for: state, at: noon, calendar: berlin), "Slimy-Mö-chi-2026-05-04.slimysave")
        state.slime.name = "!!!"
        XCTAssertEqual(SaveFile.fileName(for: state, at: noon, calendar: berlin), "Slimy-Slime-2026-05-04.slimysave")
    }

    func testRejectsFilesFromTheFuture() throws {
        var newer = livedInState()
        newer.version = GameState.currentVersion + 1
        let data = try SaveFile.encode(newer, at: noon, appVersion: "9.0")
        XCTAssertThrowsError(try SaveFile.decode(data, now: noon)) { error in
            XCTAssertEqual(error as? SaveFile.Problem, .newerVersion)
        }

        let current = try SaveFile.encode(livedInState(), at: noon, appVersion: "1.0.0")
        var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: current) as? [String: Any])
        object["schema"] = SaveFile.schema + 1
        let future = try JSONSerialization.data(withJSONObject: object)
        XCTAssertThrowsError(try SaveFile.decode(future, now: noon)) { error in
            XCTAssertEqual(error as? SaveFile.Problem, .newerVersion)
        }
    }

    func testRejectsWhatIsNotASave() {
        let cases: [(String, SaveFile.Problem)] = [
            (#"{"hello": 1}"#, .notASave),
            (#"{"format": "something.else", "schema": 1, "state": {}}"#, .notASave),
            ("not json at all", .unreadable),
            (#"[1, 2, 3]"#, .unreadable),
            (#"{"format": "de.hyprnode.slimy.save", "schema": 1, "state": 5}"#, .unreadable),
            (#"{"format": "de.hyprnode.slimy.save", "schema": 1}"#, .unreadable),
            (#"{"format": "de.hyprnode.slimy.save", "schema": 0, "state": {}}"#, .unreadable),
            (#"{"format": "de.hyprnode.slimy.save", "schema": 1, "state": {"hatched": false}}"#, .noSlime),
        ]
        for (json, expected) in cases {
            XCTAssertThrowsError(try SaveFile.decode(Data(json.utf8), now: noon), json) { error in
                XCTAssertEqual(error as? SaveFile.Problem, expected, json)
            }
        }
        XCTAssertThrowsError(try SaveFile.decode(Data(count: SaveFile.maxBytes + 1), now: noon)) { error in
            XCTAssertEqual(error as? SaveFile.Problem, .tooLarge)
        }
        XCTAssertThrowsError(try SaveFile.read(from: folder.appendingPathComponent("missing.slimysave"), now: noon)) { error in
            XCTAssertEqual(error as? SaveFile.Problem, .unreadable)
        }
    }

    func testImportRepairsDamagedValues() throws {
        let json = """
        {"format": "de.hyprnode.slimy.save", "schema": 1, "exportedAt": 1777896000, "appVersion": "1.0.0",
         "state": {"version": 2, "hatched": true,
                   "wallet": {"coins": -50},
                   "slime": {"name": "Blub", "needs": {"hunger": 900, "fun": -3, "energy": 50, "clean": 50, "love": 50}},
                   "streak": {"count": 4, "best": 2, "opened": 4}}}
        """
        let state = try SaveFile.decode(Data(json.utf8), now: noon)
        XCTAssertEqual(state.version, GameState.currentVersion)
        XCTAssertEqual(state.slime.name, "Blub")
        XCTAssertEqual(state.wallet.coins, 0)
        XCTAssertEqual(state.slime.needs.hunger, 100)
        XCTAssertEqual(state.slime.needs.fun, 0)
        XCTAssertEqual(state.streak.best, 4)
    }

    func testProblemsExplainThemselves() {
        for problem in [SaveFile.Problem.unreadable, .notASave, .newerVersion, .noSlime, .tooLarge, .caringTogether] {
            XCTAssertFalse((problem.errorDescription ?? "").isEmpty)
            XCTAssertTrue(problem.localizedDescription.hasSuffix("."))
        }
    }

    func testWidgetSnapshotCarriesTheBedtime() throws {
        var state = livedInState()
        state.settings.sleep.bedtime = 21
        let snapshot = WidgetSnapshot(state, at: noon)
        XCTAssertEqual(snapshot.sleep?.bedtime, 21)

        // Snapshots written before the bedtime was in them still load.
        let data = try GameStorage.encoder.encode(snapshot)
        var object = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        object["sleep"] = nil
        let old = try JSONSerialization.data(withJSONObject: object)
        let back = try GameStorage.decoder.decode(WidgetSnapshot.self, from: old)
        XCTAssertNil(back.sleep)
        XCTAssertEqual(back.name, snapshot.name)
    }

    @MainActor
    func testStoreExportsIntoTheTemporaryFolder() throws {
        let url = try GameStore.shared.exportSave()
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertEqual(url.pathExtension, SaveFile.fileExtension)
        XCTAssertEqual(url.deletingLastPathComponent().standardizedFileURL.path,
                       FileManager.default.temporaryDirectory.standardizedFileURL.path)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
    }
}
