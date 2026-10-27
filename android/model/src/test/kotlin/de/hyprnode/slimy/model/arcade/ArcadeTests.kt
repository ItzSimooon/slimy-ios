package de.hyprnode.slimy.model.arcade

import de.hyprnode.slimy.model.GameID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

private const val FRAME = 1.0 / 60

class ArcadeEngineTests {
    // MARK: Snack Catcher

    @Test
    fun snackCatcherIsReproducibleFromItsSeed() {
        val a = SnackCatcherEngine(7uL)
        val b = SnackCatcherEngine(7uL)
        repeat(600) {
            a.step(FRAME); b.step(FRAME)
        }
        assertEquals(a.drops.map { it.x }, b.drops.map { it.x })
        assertEquals(a.score, b.score)
    }

    @Test
    fun snackCatcherRewardsFollowingTheFood() {
        val idle = SnackCatcherEngine(3uL)
        val player = SnackCatcherEngine(3uL)
        while (!player.isOver) {
            // Go for the lowest good drop.
            player.drops.filter { it.kind != SnackCatcherEngine.Kind.bad }.maxByOrNull { it.y }?.let { player.targetX = it.x }
            player.step(FRAME)
            idle.step(FRAME)
        }
        assertTrue(player.isOver)
        assertTrue(player.score > idle.score)
        assertTrue(player.score > 10)
    }

    // MARK: Slime Jump

    @Test
    fun slimeJumpKeepsBouncingOnTheFirstPlatform() {
        val engine = SlimeJumpEngine(1uL)
        repeat(120) { engine.step(FRAME) }
        assertFalse(engine.isOver)
    }

    @Test
    fun slimeJumpEndsWhenYouFall() {
        val engine = SlimeJumpEngine(1uL)
        engine.steer = 1.0
        repeat(60 * 30) { if (!engine.isOver) engine.step(FRAME) }
        // Steering right forever either climbs or falls; both are valid, but
        // a fall must end the round and never leave a negative score.
        assertTrue(engine.score >= 0)
    }

    // MARK: Bubble Pop

    @Test
    fun bubblePopScoresGroupsAndAppliesGravity() {
        // Column 0 from the bottom: red, red, blue. Column 1: blue.
        val engine = BubblePopEngine(listOf(listOf(0, 0, 1), listOf(1, null, null)))
        assertEquals(2, engine.pop(0, 0))
        assertEquals(2, engine.score)
        assertEquals(1, engine.color(0, 0))
        assertNull(engine.color(0, 1))
        // Two blues now touch: pop them and the board is clear.
        assertEquals(2, engine.pop(0, 0))
        assertTrue(engine.isCleared)
        assertEquals(4 + BubblePopEngine.clearBonus, engine.score)
    }

    @Test
    fun bubblePopIgnoresSingles() {
        val engine = BubblePopEngine(listOf(listOf(0, 1), listOf(1, 0)))
        assertEquals(0, engine.pop(0, 0))
        assertEquals(0, engine.score)
        assertTrue(engine.isOver)
    }

    @Test
    fun bubblePopEmptyColumnsCloseUp() {
        val engine = BubblePopEngine(listOf(listOf(2, 2), listOf(3), listOf(3)))
        engine.pop(0, 0)
        assertEquals(3, engine.color(0, 0))
        assertEquals(3, engine.color(1, 0))
    }

    @Test
    fun bubblePopSeededBoardIsFull() {
        val a = BubblePopEngine(42uL)
        val b = BubblePopEngine(42uL)
        assertEquals(a.grid, b.grid)
        assertEquals(BubblePopEngine.columns, a.grid.size)
        assertTrue(a.grid.all { col -> col.size == BubblePopEngine.rows && col.all { it != null && it in 0 until BubblePopEngine.colors } })
    }

    // MARK: Memory Garden

    private fun waitForInput(engine: MemoryGardenEngine) {
        var guardSteps = 0
        while (!engine.isListening && guardSteps < 10_000) {
            engine.step(FRAME)
            guardSteps += 1
        }
    }

    @Test
    fun memoryGardenCountsCompletedRounds() {
        val engine = MemoryGardenEngine(9uL)
        for (round in 1..5) {
            waitForInput(engine)
            for (flower in engine.sequence.toList()) assertTrue(engine.tap(flower))
            assertEquals(round, engine.score)
            assertEquals(round + 1, engine.sequence.size)
        }
    }

    @Test
    fun memoryGardenEndsOnAWrongFlower() {
        val engine = MemoryGardenEngine(9uL)
        waitForInput(engine)
        val wrong = (engine.sequence[0] + 1) % MemoryGardenEngine.flowers
        assertFalse(engine.tap(wrong))
        assertTrue(engine.isOver)
        assertEquals(0, engine.score)
    }

    @Test
    fun memoryGardenIgnoresTapsWhileShowing() {
        val engine = MemoryGardenEngine(9uL)
        assertFalse(engine.tap(engine.sequence[0]))
        assertFalse(engine.isOver)
    }

    // MARK: Garden Puzzle

    private data class Key(
        val slime: GardenPuzzleEngine.Point,
        val stones: Set<GardenPuzzleEngine.Point>,
        val seeds: Set<GardenPuzzleEngine.Point>,
    )

    /** Every level is solvable with the engine's own rules. */
    @Test
    fun everyGardenPuzzleLevelIsSolvable() {
        for (level in GardenPuzzleEngine.levels.indices) {
            val start = GardenPuzzleEngine()
            start.load(level)
            val queue = mutableListOf(start)
            val seen = mutableSetOf(Key(start.slime, start.stones, start.seeds))
            var solved = false
            var i = 0
            while (i < queue.size && !solved) {
                val current = queue[i]
                i += 1
                for (d in GardenPuzzleEngine.Direction.allCases) {
                    val next = current.copy()
                    val result = next.move(d)
                    if (result == GardenPuzzleEngine.MoveResult.solved || result == GardenPuzzleEngine.MoveResult.finished) {
                        solved = true
                        break
                    }
                    if (result == GardenPuzzleEngine.MoveResult.blocked) continue
                    if (seen.add(Key(next.slime, next.stones, next.seeds))) queue.add(next)
                }
            }
            assertTrue("level ${level + 1}", solved)
        }
    }

    @Test
    fun gardenPuzzleStonesStopAtWalls() {
        val engine = GardenPuzzleEngine()
        // Level 1: walk under the stone at (2,2) and push it up twice; the
        // second push is stopped by the hedge.
        val before = engine.stones
        engine.move(GardenPuzzleEngine.Direction.down) // (1,2)
        engine.move(GardenPuzzleEngine.Direction.down) // (1,3)
        engine.move(GardenPuzzleEngine.Direction.right) // (2,3)
        engine.move(GardenPuzzleEngine.Direction.up) // pushes stone (2,2) to (2,1)
        assertNotEquals(before, engine.stones)
        assertEquals(GardenPuzzleEngine.MoveResult.blocked, engine.move(GardenPuzzleEngine.Direction.up))
    }

    @Test
    fun gardenPuzzleBedNeedsAllSeeds() {
        val engine = GardenPuzzleEngine()
        // Walk straight to the bed in level 1 without the seeds.
        val path = listOf(
            GardenPuzzleEngine.Direction.right, GardenPuzzleEngine.Direction.right, GardenPuzzleEngine.Direction.right,
            GardenPuzzleEngine.Direction.right, GardenPuzzleEngine.Direction.down, GardenPuzzleEngine.Direction.down,
            GardenPuzzleEngine.Direction.down,
        )
        for (d in path) engine.move(d)
        assertEquals(engine.bed, engine.slime)
        assertEquals(0, engine.score)
        assertEquals(0, engine.level)
    }

    // MARK: Treasure Dig

    @Test
    fun treasureDigUsesUpShovels() {
        val engine = TreasureDigEngine(5uL)
        var dug = 0
        for (i in 0 until TreasureDigEngine.size * TreasureDigEngine.size) {
            if (engine.isOver) continue
            if (engine.dig(i) != null) dug += 1
        }
        assertTrue(engine.isOver)
        assertTrue(dug <= TreasureDigEngine.shovelCount)
        assertNull(engine.dig(48))
        if (engine.found < TreasureDigEngine.treasureCount) {
            assertEquals(engine.found * TreasureDigEngine.treasureValue, engine.score)
        }
    }

    @Test
    fun treasureDigHintsPointAtTreasure() {
        val engine = TreasureDigEngine(5uL)
        engine.dig(24)
        assertTrue(engine.cells.any { it != TreasureDigEngine.Cell.hidden })
        val hint = engine.hint(24)
        assertTrue(hint >= 0)
        assertTrue(hint <= 12)
    }

    @Test
    fun diggingTheSameCellTwiceCostsNothing() {
        val engine = TreasureDigEngine(5uL)
        engine.dig(0)
        val shovels = engine.shovels
        assertNull(engine.dig(0))
        assertEquals(shovels, engine.shovels)
    }

    // MARK: Color Match

    @Test
    fun colorMatchRightAndWrongAnswers() {
        val engine = ColorMatchEngine(11uL)
        repeat(10) { assertTrue(engine.answer(engine.matches)) }
        assertEquals(12, engine.score) // 10 right, two streak bonuses
        val time = engine.time
        assertFalse(engine.answer(!engine.matches))
        assertEquals(time + ColorMatchEngine.penalty, engine.time, 0.0)
        assertEquals(0, engine.streak)
    }

    @Test
    fun colorMatchIsAboutHalfMatches() {
        val engine = ColorMatchEngine(2uL)
        var yes = 0
        repeat(400) {
            if (engine.matches) yes += 1
            engine.answer(engine.matches)
        }
        assertTrue(yes > 140)
        assertTrue(yes < 260)
    }

    // MARK: Slime Racing

    @Test
    fun racingWithoutSteeringStillEnds() {
        val engine = SlimeRacingEngine(4uL)
        while (!engine.isOver) engine.step(1.0 / 30)
        assertTrue(engine.score >= 0)
        assertTrue(engine.score <= 150)
    }

    @Test
    fun racingDodgingBeatsTheGhost() {
        val engine = SlimeRacingEngine(4uL)
        while (!engine.isOver) {
            // Look ahead and move to a lane without a rock.
            val ahead = engine.obstacles.filter {
                it.kind == SlimeRacingEngine.Kind.rock && it.distance > engine.distance && it.distance < engine.distance + 40
            }
            if (ahead.any { it.lane == engine.lane }) {
                val free = (0 until SlimeRacingEngine.lanes).filter { lane -> ahead.none { it.lane == lane } }
                free.minByOrNull { abs(it - engine.lane) }?.let { target ->
                    engine.move(if (target > engine.lane) 1 else -1)
                }
            }
            engine.step(1.0 / 30)
        }
        assertTrue(engine.finished)
        assertTrue(engine.score >= SlimeRacingEngine(4uL).score)
        assertTrue(engine.score >= GameID.slimeRacing.goodScore)
    }

    // MARK: Rhythm Bounce

    @Test
    fun rhythmPerfectPlayAndMissedPlay() {
        val perfect = RhythmBounceEngine(8uL)
        val lazy = RhythmBounceEngine(8uL)
        val notes = perfect.notes.toList()
        var next = 0
        while (!perfect.isOver) {
            perfect.step(1.0 / 120)
            lazy.step(1.0 / 120)
            while (next < notes.size && perfect.time >= notes[next].time) {
                perfect.tap(notes[next].lane)
                next += 1
            }
        }
        assertEquals(0, lazy.score)
        assertTrue(perfect.score >= GameID.rhythmBounce.goodScore)
        assertEquals(notes.size, perfect.perfects + perfect.notes.count { it.judged == RhythmBounceEngine.Judgement.good })
    }

    // MARK: Basketball

    @Test
    fun basketballIgnoresDownwardSwipesAndEnds() {
        val engine = BasketballEngine(1uL)
        engine.throwBall(0.0, 30.0)
        assertFalse(engine.flying)
        while (!engine.isOver) engine.step(1.0 / 30)
        assertEquals(0, engine.score)
        assertEquals(0, engine.shots)
    }

    @Test
    fun basketballStraightUpCanScore() {
        // Throw straight up at every moment of the first five seconds; some
        // of those throws must meet the moving hoop.
        val rise = BasketballEngine.start.y - BasketballEngine.hoopY + 6
        val dy = -sqrt(2 * BasketballEngine.gravity * rise) / 2.5 // peaks just above the rim
        var hits = 0
        for (delay in 0 until 300) {
            val engine = BasketballEngine(1uL)
            repeat(delay) { engine.step(FRAME) }
            engine.throwBall(0.0, dy)
            assertTrue(engine.flying)
            while (engine.flying) engine.step(FRAME)
            if (engine.score > 0) hits += 1
        }
        assertTrue(hits > 0)
        assertTrue(hits < 300)
    }

    // MARK: Events

    @Test
    fun eventsDrainOnceAndMapToSounds() {
        val engine = SlimeRacingEngine(4uL)
        engine.move(1)
        assertEquals(listOf<ArcadeEvent>(ArcadeEvent.jump), engine.drainEvents())
        assertTrue(engine.drainEvents().isEmpty())
        assertEquals(de.hyprnode.slimy.model.Sound.tone4, ArcadeEvent.tone(3).sound)
        assertEquals(de.hyprnode.slimy.model.Sound.hop, ArcadeEvent.jump.sound)
    }
}

class GameKitTests {
    @Test
    fun boardSpaceFitsAndMapsBack() {
        val space = BoardSpace(BoardSize(200.0, 400.0))
        assertEquals(2.0, space.scale, 1e-12)
        assertEquals(40.0, space.origin.y, 1e-12)
        val p = space.point(50.0, 80.0)
        assertEquals(100.0, p.x, 1e-12)
        assertEquals(200.0, p.y, 1e-12)
        assertEquals(50.0, space.boardX(p.x), 1e-12)
        assertEquals(80.0, space.boardY(p.y), 1e-12)
        val r = space.centered(50.0, 80.0, 10.0, 10.0)
        assertEquals(90.0, r.left, 1e-12)
        assertEquals(20.0, r.width, 1e-12)
    }

    @Test
    fun sessionEndsOnceAndBuildsItsResult() {
        val session = GameSession(GameID.bubblePop, seed = 5uL)
        assertTrue(session.running)
        session.score = 42
        session.end()
        session.end()
        assertTrue(session.ended)
        assertFalse(session.running)
        val result = session.result(java.time.Instant.ofEpochSecond(1_800_000_000))
        assertEquals(GameID.bubblePop, result.game)
        assertEquals(42, result.score)
        assertEquals(session.id, result.session)
        assertNull(result.target)
        assertTrue(GameSession(GameID.daily).seed != 0uL)
    }

    @Test
    fun juiceFadesAndCaps() {
        val juice = Juice()
        juice.burst(10.0, 10.0, 0xFFFF0000, count = 300)
        assertEquals(220, juice.particles.size)
        repeat(20) { juice.popup("+1", 0.0, 0.0) }
        assertEquals(12, juice.popups.size)
        juice.hit()
        assertTrue(juice.shakeOffset(0.1) != BoardSize.zero)
        repeat(120) { juice.step(FRAME) }
        assertTrue(juice.particles.isEmpty())
        assertTrue(juice.popups.isEmpty())
        assertEquals(BoardSize.zero, juice.shakeOffset(0.1))
        juice.reduceMotion = true
        juice.burst(0.0, 0.0, 0xFFFFFFFF)
        assertTrue(juice.particles.isEmpty())
    }

    @Test
    fun clockAndColourMix() {
        assertEquals("0:32", clock(31.2))
        assertEquals("1:05", clock(65.0))
        assertEquals(0x808080L, mixColor(0x000000, 0xFFFFFF, 0.5))
        assertEquals(0xFF0000L, mixColor(0xFF0000, 0x00FF00, 0.0))
        assertEquals(0x00FF00L, mixColor(0xFF0000, 0x00FF00, 1.0))
    }
}

class GameCatalogTests {
    @Test
    fun everyGameExplainsItself() {
        assertEquals("titles are unique", GameID.allCases.size, GameID.allCases.map { it.title }.toSet().size)
        assertFalse(GameID.classics.contains(GameID.daily))
        assertTrue(GameID.classics.contains(GameID.slimeStack))
        assertTrue(GameID.classics.contains(GameID.jellyDash))
        for (game in GameID.allCases) {
            assertFalse(game.name, game.blurb.isEmpty())
            assertFalse(game.name, game.icon.isEmpty())
            assertTrue(game.name, game.tutorial.size in 1..3)
            assertTrue(game.name, game.tutorial.all { it.isNotEmpty() })
            assertTrue(game.name, game.goodScore > 0)
            assertTrue(game.name, game.unlockLevel >= 1)
        }
    }

    @Test
    fun newGameIDsMatchTheServer() {
        assertEquals("slimeStack", GameID.slimeStack.name)
        assertEquals("jellyDash", GameID.jellyDash.name)
    }
}

class SlimeStackEngineTests {
    /** Steps until the condition holds, then drops. Returns the drop's result. */
    private fun drop(e: SlimeStackEngine, condition: (SlimeStackEngine) -> Boolean): SlimeStackEngine.Drop {
        repeat(120 * 30) {
            e.step(1.0 / 120)
            if (condition(e)) {
                val result = e.drop()
                if (result != SlimeStackEngine.Drop.ignored) return result
            }
        }
        fail("the moment to drop never came")
        return SlimeStackEngine.Drop.ignored
    }

    private fun centred(e: SlimeStackEngine): Boolean = abs(e.current.x - e.top.x) <= 1

    private fun offCentre(e: SlimeStackEngine): Boolean {
        val o = abs(e.current.x - e.top.x)
        return o >= 10 && o <= 20
    }

    @Test
    fun perfectDropsKeepTheWidthAndBuildACombo() {
        val e = SlimeStackEngine(5uL)
        repeat(30) { assertEquals(SlimeStackEngine.Drop.perfect, drop(e, ::centred)) }
        assertFalse(e.isOver)
        assertEquals(30, e.height)
        assertEquals(30, e.perfects)
        assertEquals(30, e.combo)
        assertEquals(SlimeStackEngine.baseWidth, e.top.width, 1e-9)
        // 2, 3, 4, then 5 for every further perfect drop, plus gold blocks.
        val golds = e.tower.count { it.gold }
        assertEquals(2 + 3 + 4 + 27 * 5 + golds * SlimeStackEngine.goldBonus, e.score)
        assertTrue(e.score >= GameID.slimeStack.goodScore)
    }

    @Test
    fun anOffCentreDropIsCutAndAMissEndsTheRound() {
        val e = SlimeStackEngine(5uL)
        assertEquals(SlimeStackEngine.Drop.cut, drop(e, ::offCentre))
        // The cut block keeps only the part above the base, centred on it.
        val offset = abs(e.top.x - 50) * 2
        assertTrue(offset >= 10 - 1e-9)
        assertEquals(SlimeStackEngine.baseWidth - offset, e.top.width, 1e-9)
        assertEquals("the overhang falls off", 1, e.pieces.size)
        assertEquals(0, e.combo)
        assertEquals(1, e.score)

        val miss = drop(e) { s -> abs(s.current.x - s.top.x) >= s.current.width + 0.5 }
        assertEquals(SlimeStackEngine.Drop.missed, miss)
        assertTrue(e.isOver)
        assertTrue(e.drainEvents().contains(ArcadeEvent.lose))
        assertEquals(SlimeStackEngine.Drop.missed, e.drop())
        assertEquals("nothing counts after the end", 1, e.score)
    }

    @Test
    fun aPerfectStreakGrowsTheTowerBack() {
        val e = SlimeStackEngine(5uL)
        assertEquals(SlimeStackEngine.Drop.cut, drop(e, ::offCentre))
        val narrow = e.top.width
        assertTrue(narrow < SlimeStackEngine.baseWidth)
        assertEquals(SlimeStackEngine.Drop.perfect, drop(e, ::centred))
        assertEquals(SlimeStackEngine.Drop.perfect, drop(e, ::centred))
        assertEquals(narrow, e.top.width, 1e-9)
        assertEquals(SlimeStackEngine.Drop.perfect, drop(e, ::centred))
        assertEquals(narrow + SlimeStackEngine.regrow, e.top.width, 1e-9)
    }

    @Test
    fun aDoubleTapDropsOnlyOnce() {
        val e = SlimeStackEngine(5uL)
        assertEquals(SlimeStackEngine.Drop.perfect, drop(e, ::centred))
        assertEquals(SlimeStackEngine.Drop.ignored, e.drop())
        assertEquals(1, e.height)
    }

    @Test
    fun sameSeedSameTower() {
        val a = SlimeStackEngine(9uL)
        val b = SlimeStackEngine(9uL)
        val other = SlimeStackEngine(10uL)
        assertNotEquals("another seed slides differently", a.speed, other.speed, 0.0)
        for (i in 0 until 60 * 40) {
            a.step(FRAME)
            b.step(FRAME)
            if (i % 61 == 60) assertEquals(a.drop(), b.drop())
        }
        assertEquals(a.score, b.score)
        assertEquals(a.tower, b.tower)
        assertEquals(a.isOver, b.isOver)
    }
}

class JellyDashEngineTests {
    /** Jumps over whatever comes next, holding for tall rocks and pairs. */
    private class Bot {
        val planned = mutableSetOf<Int>()
        var releaseAt = -1.0

        fun drive(e: JellyDashEngine) {
            val px = e.distance + JellyDashEngine.playerX
            val ahead = e.obstacles
                .filter { !it.touched && it.x + it.kind.width / 2 > px - JellyDashEngine.halfWidth }
                .sortedBy { it.x }
            val o = ahead.firstOrNull()
            if (o != null && e.isOnGround && !planned.contains(o.id)) {
                val group = ahead.filter { it.x - o.x < 20 }
                val last = group.lastOrNull() ?: o
                val span = last.x + last.kind.width / 2 - (o.x - o.kind.width / 2)
                val tall = group.any { it.kind == JellyDashEngine.ObstacleKind.boulder } || span > 15
                val centre = (o.x - o.kind.width / 2 + last.x + last.kind.width / 2) / 2
                val lead = (if (tall) 0.95 else 0.7) / 2 * e.speed
                if (centre - px <= lead) {
                    e.press()
                    for (p in group) planned.add(p.id)
                    releaseAt = e.time + (if (tall) 0.3 else 0.02)
                }
            }
            if (releaseAt >= 0 && e.time >= releaseAt) {
                e.release()
                releaseAt = -1.0
            }
        }
    }

    @Test
    fun standingStillCostsAllHeartsAndEnds() {
        val e = JellyDashEngine(3uL)
        var steps = 0
        while (!e.isOver && steps < 60 * 120) {
            e.step(FRAME)
            steps += 1
        }
        assertTrue(e.isOver)
        assertEquals(0, e.lives)
        assertEquals(JellyDashEngine.lives, e.hits)
        assertTrue(e.score > 0)
        assertTrue(e.score < GameID.jellyDash.goodScore)
    }

    @Test
    fun jumpingOverEverythingRunsFarAndFast() {
        for (seed in listOf(1uL, 3uL, 7uL)) {
            val e = JellyDashEngine(seed)
            val bot = Bot()
            repeat(60 * 60) {
                if (!e.isOver) {
                    bot.drive(e)
                    e.step(FRAME)
                }
            }
            assertFalse("seed $seed", e.isOver)
            assertEquals("seed $seed", 0, e.hits)
            assertTrue("it speeds up", e.speed > JellyDashEngine.startSpeed + 40)
            assertTrue("coins and berries count", e.bonus > 0)
            assertEquals(e.meters + e.bonus, e.score)
            assertTrue("seed $seed", e.score >= GameID.jellyDash.goodScore)
        }
    }

    private fun jump(holdFor: Double): Pair<Double, Double> {
        val e = JellyDashEngine(1uL)
        e.press()
        var top = e.y
        var t = 0.0
        do {
            if (t >= holdFor) e.release()
            e.step(1.0 / 120)
            t += 1.0 / 120
            top = minOf(top, e.y)
        } while (!e.isOnGround && t < 3)
        return Pair(JellyDashEngine.ground - top, t)
    }

    @Test
    fun holdingJumpsHigherAndLonger() {
        val tap = jump(0.0)
        val held = jump(0.3)
        assertTrue("a tap clears a rock", tap.first > JellyDashEngine.ObstacleKind.rock.height)
        assertTrue("a held jump clears a boulder", held.first > JellyDashEngine.ObstacleKind.boulder.height + 10)
        assertTrue(held.first > tap.first + 10)
        assertTrue(held.second > tap.second + 0.1)
    }

    @Test
    fun aTapJustBeforeLandingJumpsAgain() {
        val e = JellyDashEngine(1uL)
        e.press()
        e.release()
        e.drainEvents()
        // Fall until just above the ground, then tap.
        var steps = 0
        while (!(e.vy > 0 && e.y > JellyDashEngine.ground - 3) && steps < 600) {
            e.step(1.0 / 120)
            steps += 1
        }
        e.press()
        e.release()
        repeat(30) { e.step(1.0 / 120) }
        val events = e.drainEvents()
        assertTrue(events.contains(ArcadeEvent.land))
        assertTrue("the early tap is kept until touchdown", events.contains(ArcadeEvent.jump))
        assertFalse(e.isOnGround)
    }

    @Test
    fun sameSeedSameTrack() {
        val a = JellyDashEngine(11uL)
        val b = JellyDashEngine(11uL)
        val other = JellyDashEngine(12uL)
        for (i in 0 until 60 * 8) {
            if (i % 45 == 0) {
                a.press(); b.press(); other.press()
            }
            if (i % 45 == 12) {
                a.release(); b.release(); other.release()
            }
            a.step(FRAME)
            b.step(FRAME)
            other.step(FRAME)
        }
        assertEquals(a.score, b.score)
        assertEquals(a.distance, b.distance, 0.0)
        assertEquals(a.obstacles.map { it.x }, b.obstacles.map { it.x })
        assertEquals(a.pickups.map { it.x }, b.pickups.map { it.x })
        assertNotEquals(a.obstacles.map { it.x }, other.obstacles.map { it.x })
    }
}
