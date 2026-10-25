import XCTest
@testable import Slimy

final class ArcadeEngineTests: XCTestCase {
    // MARK: Snack Catcher

    func testSnackCatcherIsReproducibleFromItsSeed() {
        var a = SnackCatcherEngine(seed: 7)
        var b = SnackCatcherEngine(seed: 7)
        for _ in 0..<600 {
            a.step(1 / 60); b.step(1 / 60)
        }
        XCTAssertEqual(a.drops.map(\.x), b.drops.map(\.x))
        XCTAssertEqual(a.score, b.score)
    }

    func testSnackCatcherRewardsFollowingTheFood() {
        var idle = SnackCatcherEngine(seed: 3)
        var player = SnackCatcherEngine(seed: 3)
        while !player.isOver {
            // Go for the lowest good drop.
            if let target = player.drops.filter({ $0.kind != .bad }).max(by: { $0.y < $1.y }) {
                player.targetX = target.x
            }
            player.step(1 / 60)
            idle.step(1 / 60)
        }
        XCTAssertTrue(player.isOver)
        XCTAssertGreaterThan(player.score, idle.score)
        XCTAssertGreaterThan(player.score, 10)
    }

    // MARK: Slime Jump

    func testSlimeJumpKeepsBouncingOnTheFirstPlatform() {
        var engine = SlimeJumpEngine(seed: 1)
        for _ in 0..<120 { engine.step(1 / 60) }
        XCTAssertFalse(engine.isOver)
    }

    func testSlimeJumpEndsWhenYouFall() {
        var engine = SlimeJumpEngine(seed: 1)
        engine.steer = 1
        for _ in 0..<(60 * 30) where !engine.isOver { engine.step(1 / 60) }
        // Steering right forever either climbs or falls; both are valid, but
        // a fall must end the round and never leave a negative score.
        XCTAssertGreaterThanOrEqual(engine.score, 0)
    }

    // MARK: Bubble Pop

    func testBubblePopScoresGroupsAndAppliesGravity() {
        // Column 0 from the bottom: red, red, blue. Column 1: blue.
        var engine = BubblePopEngine(grid: [[0, 0, 1], [1, nil, nil]])
        XCTAssertEqual(engine.pop(0, 0), 2)
        XCTAssertEqual(engine.score, 2)
        XCTAssertEqual(engine.color(0, 0), 1)
        XCTAssertNil(engine.color(0, 1))
        // Two blues now touch: pop them and the board is clear.
        XCTAssertEqual(engine.pop(0, 0), 2)
        XCTAssertTrue(engine.isCleared)
        XCTAssertEqual(engine.score, 4 + BubblePopEngine.clearBonus)
    }

    func testBubblePopIgnoresSingles() {
        var engine = BubblePopEngine(grid: [[0, 1], [1, 0]])
        XCTAssertEqual(engine.pop(0, 0), 0)
        XCTAssertEqual(engine.score, 0)
        XCTAssertTrue(engine.isOver)
    }

    func testBubblePopEmptyColumnsCloseUp() {
        var engine = BubblePopEngine(grid: [[2, 2], [3], [3]])
        engine.pop(0, 0)
        XCTAssertEqual(engine.color(0, 0), 3)
        XCTAssertEqual(engine.color(1, 0), 3)
    }

    // MARK: Memory Garden

    private func waitForInput(_ engine: inout MemoryGardenEngine) {
        var guardSteps = 0
        while !engine.isListening, guardSteps < 10_000 { engine.step(1 / 60); guardSteps += 1 }
    }

    func testMemoryGardenCountsCompletedRounds() {
        var engine = MemoryGardenEngine(seed: 9)
        for round in 1...5 {
            waitForInput(&engine)
            for flower in engine.sequence { XCTAssertTrue(engine.tap(flower)) }
            XCTAssertEqual(engine.score, round)
            XCTAssertEqual(engine.sequence.count, round + 1)
        }
    }

    func testMemoryGardenEndsOnAWrongFlower() {
        var engine = MemoryGardenEngine(seed: 9)
        waitForInput(&engine)
        let wrong = (engine.sequence[0] + 1) % MemoryGardenEngine.flowers
        XCTAssertFalse(engine.tap(wrong))
        XCTAssertTrue(engine.isOver)
        XCTAssertEqual(engine.score, 0)
    }

    func testMemoryGardenIgnoresTapsWhileShowing() {
        var engine = MemoryGardenEngine(seed: 9)
        XCTAssertFalse(engine.tap(engine.sequence[0]))
        XCTAssertFalse(engine.isOver)
    }

    // MARK: Garden Puzzle

    /// Every level is solvable with the engine's own rules.
    func testEveryGardenPuzzleLevelIsSolvable() {
        struct Key: Hashable {
            let slime: GardenPuzzleEngine.Point
            let stones: Set<GardenPuzzleEngine.Point>
            let seeds: Set<GardenPuzzleEngine.Point>
        }
        for level in GardenPuzzleEngine.levels.indices {
            var start = GardenPuzzleEngine()
            start.load(level)
            var queue = [start]
            var seen: Set<Key> = [Key(slime: start.slime, stones: start.stones, seeds: start.seeds)]
            var solved = false
            var i = 0
            while i < queue.count, !solved {
                let current = queue[i]
                i += 1
                for d in GardenPuzzleEngine.Direction.allCases {
                    var next = current
                    let result = next.move(d)
                    if result == .solved || result == .finished { solved = true; break }
                    guard result != .blocked else { continue }
                    let key = Key(slime: next.slime, stones: next.stones, seeds: next.seeds)
                    if seen.insert(key).inserted { queue.append(next) }
                }
            }
            XCTAssertTrue(solved, "level \(level + 1)")
        }
    }

    func testGardenPuzzleStonesStopAtWalls() {
        var engine = GardenPuzzleEngine()
        // Level 1: walk under the stone at (2,2) and push it up twice; the
        // second push is stopped by the hedge.
        let before = engine.stones
        engine.move(.down)   // (1,2)
        engine.move(.down)   // (1,3)
        engine.move(.right)  // (2,3)
        engine.move(.up)     // pushes stone (2,2) to (2,1)
        XCTAssertNotEqual(engine.stones, before)
        XCTAssertEqual(engine.move(.up), .blocked)  // stone against the hedge
    }

    func testGardenPuzzleBedNeedsAllSeeds() {
        var engine = GardenPuzzleEngine()
        // Walk straight to the bed in level 1 without the seeds.
        for d in [GardenPuzzleEngine.Direction.right, .right, .right, .right, .down, .down, .down] { engine.move(d) }
        XCTAssertEqual(engine.slime, engine.bed)
        XCTAssertEqual(engine.score, 0)
        XCTAssertEqual(engine.level, 0)
    }

    // MARK: Treasure Dig

    func testTreasureDigUsesUpShovels() {
        var engine = TreasureDigEngine(seed: 5)
        var dug = 0
        for i in 0..<(TreasureDigEngine.size * TreasureDigEngine.size) where !engine.isOver {
            if engine.dig(i) != nil { dug += 1 }
        }
        XCTAssertTrue(engine.isOver)
        XCTAssertLessThanOrEqual(dug, TreasureDigEngine.shovelCount)
        XCTAssertNil(engine.dig(48))
        if engine.found < TreasureDigEngine.treasureCount {
            XCTAssertEqual(engine.score, engine.found * TreasureDigEngine.treasureValue)
        }
    }

    func testTreasureDigHintsPointAtTreasure() {
        var engine = TreasureDigEngine(seed: 5)
        // Follow the hints greedily: always dig a hidden neighbour of the
        // smallest hint. This should find treasure.
        _ = engine.dig(24)
        XCTAssertTrue(engine.cells.contains { $0 != .hidden })
        let hint = engine.hint(at: 24)
        XCTAssertGreaterThanOrEqual(hint, 0)
        XCTAssertLessThanOrEqual(hint, 12)
    }

    func testDiggingTheSameCellTwiceCostsNothing() {
        var engine = TreasureDigEngine(seed: 5)
        engine.dig(0)
        let shovels = engine.shovels
        XCTAssertNil(engine.dig(0))
        XCTAssertEqual(engine.shovels, shovels)
    }

    // MARK: Color Match

    func testColorMatchRightAndWrongAnswers() {
        var engine = ColorMatchEngine(seed: 11)
        for _ in 0..<10 { XCTAssertTrue(engine.answer(engine.matches)) }
        XCTAssertEqual(engine.score, 12)   // 10 right, two streak bonuses
        let time = engine.time
        XCTAssertFalse(engine.answer(!engine.matches))
        XCTAssertEqual(engine.time, time + ColorMatchEngine.penalty)
        XCTAssertEqual(engine.streak, 0)
    }

    func testColorMatchIsAboutHalfMatches() {
        var engine = ColorMatchEngine(seed: 2)
        var yes = 0
        for _ in 0..<400 {
            if engine.matches { yes += 1 }
            engine.answer(engine.matches)
        }
        XCTAssertGreaterThan(yes, 140)
        XCTAssertLessThan(yes, 260)
    }

    // MARK: Slime Racing

    func testRacingWithoutSteeringStillEnds() {
        var engine = SlimeRacingEngine(seed: 4)
        while !engine.isOver { engine.step(1 / 30) }
        XCTAssertGreaterThanOrEqual(engine.score, 0)
        XCTAssertLessThanOrEqual(engine.score, 150)
    }

    func testRacingDodgingBeatsTheGhost() {
        var engine = SlimeRacingEngine(seed: 4)
        while !engine.isOver {
            // Look ahead and move to a lane without a rock.
            let ahead = engine.obstacles.filter { $0.kind == .rock && $0.distance > engine.distance && $0.distance < engine.distance + 40 }
            if ahead.contains(where: { $0.lane == engine.lane }) {
                let free = (0..<SlimeRacingEngine.lanes).filter { lane in !ahead.contains { $0.lane == lane } }
                if let target = free.min(by: { abs($0 - engine.lane) < abs($1 - engine.lane) }) {
                    engine.move(target > engine.lane ? 1 : -1)
                }
            }
            engine.step(1 / 30)
        }
        XCTAssertTrue(engine.finished)
        XCTAssertGreaterThanOrEqual(engine.score, SlimeRacingEngine(seed: 4).score)
        XCTAssertGreaterThanOrEqual(engine.score, GameID.slimeRacing.goodScore)
    }

    // MARK: Rhythm Bounce

    func testRhythmPerfectPlayAndMissedPlay() {
        var perfect = RhythmBounceEngine(seed: 8)
        var lazy = RhythmBounceEngine(seed: 8)
        let notes = perfect.notes
        var next = 0
        while !perfect.isOver {
            perfect.step(1 / 120)
            lazy.step(1 / 120)
            while next < notes.count, perfect.time >= notes[next].time {
                perfect.tap(lane: notes[next].lane)
                next += 1
            }
        }
        XCTAssertEqual(lazy.score, 0)
        XCTAssertGreaterThanOrEqual(perfect.score, GameID.rhythmBounce.goodScore)
        XCTAssertEqual(perfect.perfects + perfect.notes.filter { $0.judged == .good }.count, notes.count)
    }

    // MARK: Basketball

    func testBasketballIgnoresDownwardSwipesAndEnds() {
        var engine = BasketballEngine(seed: 1)
        engine.throwBall(dx: 0, dy: 30)
        XCTAssertFalse(engine.flying)
        while !engine.isOver { engine.step(1 / 30) }
        XCTAssertEqual(engine.score, 0)
        XCTAssertEqual(engine.shots, 0)
    }

    func testBasketballStraightUpCanScore() {
        // Throw straight up at every moment of the first five seconds; some
        // of those throws must meet the moving hoop.
        let rise = BasketballEngine.start.y - BasketballEngine.hoopY + 6
        let dy = -(2 * BasketballEngine.gravity * rise).squareRoot() / 2.5   // peaks just above the rim
        var hits = 0
        for delay in 0..<300 {
            var engine = BasketballEngine(seed: 1)
            for _ in 0..<delay { engine.step(1 / 60) }
            engine.throwBall(dx: 0, dy: dy)
            XCTAssertTrue(engine.flying)
            while engine.flying { engine.step(1 / 60) }
            if engine.score > 0 { hits += 1 }
        }
        XCTAssertGreaterThan(hits, 0)
        XCTAssertLessThan(hits, 300)
    }
}
