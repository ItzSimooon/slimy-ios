import XCTest
@testable import Slimy

final class DailyPlanTests: XCTestCase {
    private func days(_ n: Int) -> [String] {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        let start = c.date(from: DateComponents(year: 2026, month: 1, day: 1))!
        return (0..<n).map { DailyPlanner.dayKey(c.date(byAdding: .day, value: $0, to: start)!, calendar: c) }
    }

    func testEveryDayHasAValidPlan() {
        for day in days(366) {
            let plan = DailyPlanner.plan(for: day)
            let g = plan.game
            XCTAssertEqual(plan.day, day)
            XCTAssertEqual(g.id, day)
            XCTAssertFalse(g.title.isEmpty, day)
            XCTAssertGreaterThanOrEqual(g.tutorial.count, 2, day)
            XCTAssertTrue(g.entities.contains { $0.role == .collect }, day)
            XCTAssertLessThanOrEqual(g.entities.count, 6, day)
            XCTAssertLessThanOrEqual(g.entities.map(\.rate).reduce(0, +), 3.5 + 1e-9, day)
            XCTAssertTrue(g.entities.allSatisfy { GameSpec.allowedSprites.contains($0.sprite) }, day)
            XCTAssertGreaterThanOrEqual(g.goodScore, 10, day)
        }
    }

    func testSameDaySameGameAndDaysVary() {
        XCTAssertEqual(DailyPlanner.plan(for: "2026-10-26"), DailyPlanner.plan(for: "2026-10-26"))
        let plans = days(60).map(DailyPlanner.plan(for:))
        XCTAssertGreaterThan(Set(plans.map(\.game.title)).count, 30)
        XCTAssertEqual(Set(plans.map(\.game.control)).count, GameControl.allCases.count)
        XCTAssertGreaterThan(Set(plans.map(\.game.theme)).count, 6)
    }

    func testBadSpecsAreClamped() {
        let evil = GameSpec(
            id: "x", title: String(repeating: "A", count: 200), blurb: "", tutorial: [], control: .drag, goal: .survive,
            duration: 9999, lives: 0, theme: .night,
            entities: [
                EntitySpec(sprite: "../../etc/passwd", role: .collect, power: nil, motion: .straight, speed: 1, rate: 1, points: 5, size: 1),
                EntitySpec(sprite: "rock", role: .avoid, power: nil, motion: .homing, speed: 99, rate: 50, points: 500, size: 40),
                EntitySpec(sprite: "rock", role: .avoid, power: nil, motion: .zigzag, speed: .nan, rate: 50, points: -3, size: 1),
            ],
            ramp: 7).sanitized()
        XCTAssertLessThanOrEqual(evil.title.count, 28)
        XCTAssertEqual(evil.duration, 90)
        XCTAssertEqual(evil.lives, 3, "survive needs lives")
        XCTAssertEqual(evil.ramp, 0.5)
        XCTAssertTrue(evil.entities.contains { $0.role == .collect }, "a collectible is added when none is valid")
        XCTAssertFalse(evil.entities.contains { $0.sprite.contains("/") })
        let avoid = evil.entities.filter { $0.role == .avoid }
        XCTAssertLessThanOrEqual(avoid.map(\.rate).reduce(0, +), 1.6 + 1e-9)
        XCTAssertTrue(avoid.allSatisfy { $0.speed <= 2 && $0.size <= 1.6 && $0.points <= 10 && $0.points >= 0 })
        XCTAssertFalse(evil.tutorial.isEmpty)
    }

    /// Plans saved by older versions (some made on the server) still load.
    func testSavedPlanDecodes() throws {
        let json = """
        {"day": "2026-10-26", "source": "ai", "story": "i dreamed of jelly.",
         "quest": {"title": "Score 80", "coins": 25, "goal": {"kind": "dailyScore", "target": 80}},
         "game": {"id": "2026-10-26", "title": "Moonlight Munch", "blurb": "Catch stars.", "tutorial": ["Drag.", "Catch."],
                  "control": "drag", "goal": "timed", "duration": 45, "lives": 3, "theme": "night", "ramp": 0.2,
                  "entities": [{"sprite": "starJelly", "role": "collect", "motion": "wave", "speed": 1, "rate": 1.2, "points": 2, "size": 1},
                               {"sprite": "rock", "role": "avoid", "motion": "homing", "speed": 1.2, "rate": 0.6, "points": 3, "size": 1.1},
                               {"sprite": "bubbleItem", "role": "power", "power": "shield", "motion": "wave", "speed": 0.8, "rate": 0.1, "points": 0, "size": 1}]}}
        """
        let plan = try JSONDecoder().decode(DailyPlan.self, from: Data(json.utf8)).sanitized()
        XCTAssertEqual(plan.game.title, "Moonlight Munch")
        XCTAssertEqual(plan.game.entities.count, 3)
        XCTAssertEqual(plan.quest.goal, .dailyScore(80))
        let stat = try JSONDecoder().decode(DailyQuest.self, from: Data("""
        {"title": "Feed 3", "coins": 15, "goal": {"kind": "stat", "stat": "feeds", "target": 3}}
        """.utf8))
        XCTAssertEqual(stat.goal, .stat(.feeds, 3))
    }

    func testPlanSurvivesASaveRoundTrip() throws {
        let plan = DailyPlanner.plan(for: "2026-10-26")
        let data = try JSONEncoder().encode(plan)
        let loaded = try JSONDecoder().decode(DailyPlan.self, from: data)
        XCTAssertEqual(loaded, plan)
        XCTAssertEqual(plan.source, "local")
    }

    func testQuestPaysOnceAndTracksDailyScore() {
        var state = GameState()
        let noon = Date(timeIntervalSince1970: 1_793_000_000)
        state.hatch(name: "Testi", skin: .lime, seed: 1, now: noon)
        var plan = DailyPlanner.plan(for: "2026-10-26")
        plan.quest = DailyQuest(title: "Score 50", goal: .dailyScore(50), coins: 20)
        state.startQuest(plan)
        XCTAssertFalse(state.claimQuest(plan, now: noon).ok)
        _ = state.finish(GameResult(game: .daily, score: 60, session: UUID(), finishedAt: noon, target: plan.game.goodScore, day: plan.day))
        XCTAssertEqual(state.questProgress(plan).value, 50)
        let coins = state.wallet.coins
        XCTAssertTrue(state.claimQuest(plan, now: noon).ok)
        XCTAssertGreaterThanOrEqual(state.wallet.coins, coins + 20)
        let after = state.wallet.coins
        XCTAssertFalse(state.claimQuest(plan, now: noon).ok)
        XCTAssertEqual(state.wallet.coins, after)
    }

    func testDailyScoresAreNotRecords() {
        var state = GameState()
        let noon = Date(timeIntervalSince1970: 1_793_000_000)
        state.hatch(name: "Testi", skin: .lime, seed: 1, now: noon)
        _ = state.finish(GameResult(game: .daily, score: 10, session: UUID(), finishedAt: noon, target: 100, day: "a"))
        let out = state.finish(GameResult(game: .daily, score: 500, session: UUID(), finishedAt: noon, target: 100, day: "b"))
        XCTAssertFalse(out.events.contains { if case .record = $0 { return true } else { return false } })
    }
}

/// A simple bot plays generated games, to prove they can be played and end.
final class BlockEngineTests: XCTestCase {
    private func play(_ spec: GameSpec, seed: UInt64 = 7) -> BlockEngine {
        var e = BlockEngine(spec: spec, seed: seed)
        var steps = 0
        while !e.isOver, steps < 60 * 200 {
            drive(&e)
            e.step(1 / 60)
            _ = e.drainEvents()
            steps += 1
        }
        return e
    }

    private func drive(_ e: inout BlockEngine) {
        let spec = e.spec
        let good = e.things.filter { spec.entities[$0.kind].role != .avoid }
        let bad = e.things.filter { spec.entities[$0.kind].role == .avoid }
        switch spec.control {
        case .drag:
            if let t = good.filter({ $0.y < 150 }).max(by: { $0.y < $1.y }) { e.target = (t.x, e.target.y) }
        case .lanes:
            let danger = bad.filter { $0.y > 90 && $0.y < 150 }.map { Int(($0.x - 5) / 30) }
            let wanted = good.filter { $0.y > 60 && $0.y < 150 }.max(by: { $0.y < $1.y }).map { Int(($0.x - 5) / 30) }
            let goal = wanted.flatMap { danger.contains($0) ? nil : $0 } ?? (0...2).first { !danger.contains($0) } ?? 1
            if goal != e.lane { e.moveLane(goal > e.lane ? 1 : -1) }
        case .steer:
            if let t = good.min(by: { abs($0.x - e.x) + abs($0.y - e.y) < abs($1.x - e.x) + abs($1.y - e.y) }) {
                e.target = (t.x, t.y)
            }
        case .jump:
            if bad.contains(where: { $0.x > e.x + 2 && $0.x < e.x + 22 && $0.y > BlockEngine.ground - 12 }) { e.tap() }
        case .flap:
            let goalY = good.filter { $0.x > e.x }.min(by: { $0.x < $1.x })?.y ?? 80
            if e.y > goalY + 6 { e.tap() }
        }
    }

    func testGeneratedGamesCanBePlayedAndEnd() {
        var scored = 0
        let n = 90
        for i in 0..<n {
            let spec = DailyPlanner.plan(for: "2027-\(String(format: "%02d", i % 12 + 1))-\(String(format: "%02d", i % 28 + 1))").game
            let e = play(spec, seed: UInt64(i + 1))
            XCTAssertTrue(e.isOver, spec.title)
            XCTAssertGreaterThanOrEqual(e.score, 0)
            if e.score > 0 { scored += 1 }
        }
        XCTAssertGreaterThanOrEqual(scored, n * 9 / 10, "the bot should score in almost every game")
    }

    func testSameSeedSameRound() {
        let spec = DailyPlanner.plan(for: "2026-10-26").game
        XCTAssertEqual(play(spec, seed: 3).score, play(spec, seed: 3).score)
    }

    func testShieldBlocksAHit() {
        var spec = DailyPlanner.plan(for: "2026-10-26").game
        spec.control = .drag
        spec.lives = 5
        spec.goal = .timed
        spec.entities = [
            EntitySpec(sprite: "bubbleItem", role: .power, power: .shield, motion: .straight, speed: 1, rate: 0.25, points: 0, size: 1),
            EntitySpec(sprite: "rock", role: .avoid, power: nil, motion: .straight, speed: 1, rate: 0.5, points: 3, size: 1),
            EntitySpec(sprite: "coin", role: .collect, power: nil, motion: .straight, speed: 1, rate: 0.5, points: 1, size: 1),
        ]
        var e = BlockEngine(spec: spec.sanitized(), seed: 1)
        var sawBlock = false, sawHit = false
        for _ in 0..<(60 * 60) where !e.isOver {
            // Stand under whatever is lowest; catch power-ups and rocks alike.
            if let t = e.things.max(by: { $0.y < $1.y }) { e.target = (t.x, e.target.y) }
            e.step(1 / 60)
            for ev in e.drainEvents() {
                if case .blocked = ev { sawBlock = true }
                if case .hit = ev { sawHit = true }
            }
        }
        XCTAssertTrue(sawBlock)
        XCTAssertTrue(sawHit)
    }
}
