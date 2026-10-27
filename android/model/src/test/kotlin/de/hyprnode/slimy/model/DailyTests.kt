package de.hyprnode.slimy.model

import de.hyprnode.slimy.model.arcade.BlockEngine
import de.hyprnode.slimy.model.arcade.BoardPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.UUID

class DailyPlanTests {
    private fun days(n: Int): List<String> {
        val c = SlimyCalendar.gregorian("UTC")
        val start = c.date(2026, 1, 1)
        return (0 until n).map { DailyPlanner.dayKey(c.addingDays(it, start), c) }
    }

    @Test
    fun everyDayHasAValidPlan() {
        for (day in days(366)) {
            val plan = DailyPlanner.plan(day)
            val g = plan.game
            assertEquals(day, plan.day)
            assertEquals(day, g.id)
            assertFalse(day, g.title.isEmpty())
            assertTrue(day, g.tutorial.size >= 2)
            assertTrue(day, g.entities.any { it.role == EntityRole.collect })
            assertTrue(day, g.entities.size <= 6)
            assertTrue(day, g.entities.sumOf { it.rate } <= 3.5 + 1e-9)
            assertTrue(day, g.entities.all { GameSpec.allowedSprites.contains(it.sprite) })
            assertTrue(day, g.goodScore >= 10)
        }
    }

    @Test
    fun sameDaySameGameAndDaysVary() {
        assertEquals(DailyPlanner.plan("2026-10-26"), DailyPlanner.plan("2026-10-26"))
        val plans = days(60).map { DailyPlanner.plan(it) }
        assertTrue(plans.map { it.game.title }.toSet().size > 30)
        assertEquals(GameControl.allCases.size, plans.map { it.game.control }.toSet().size)
        assertTrue(plans.map { it.game.theme }.toSet().size > 6)
    }

    /** The day's plan is the same on iPhone and Android: the generator follows the Swift one bit for bit. */
    @Test
    fun planMatchesTheIPhoneGenerator() {
        // SplitMix and its FNV-1a seed are the base of every shared plan.
        assertEquals(0xaf63dc4c8601ec8cuL, SplitMix.seed("a"))
        val rng = SplitMix(1uL)
        assertEquals(0x910a2dec89025cc1uL, rng.next())
        val a = DailyPlanner.plan("2026-10-26")
        val b = DailyPlanner.plan("2026-10-27")
        assertTrue(a.game.title != b.game.title || a.game.control != b.game.control || a.game.theme != b.game.theme)
    }

    @Test
    fun badSpecsAreClamped() {
        val evil = GameSpec(
            id = "x", title = "A".repeat(200), blurb = "", tutorial = emptyList(), control = GameControl.drag,
            goal = GameGoal.survive, duration = 9999.0, lives = 0, theme = GameTheme.night,
            entities = listOf(
                EntitySpec(sprite = "../../etc/passwd", role = EntityRole.collect, power = null, motion = EntityMotion.straight,
                    speed = 1.0, rate = 1.0, points = 5, size = 1.0),
                EntitySpec(sprite = "rock", role = EntityRole.avoid, power = null, motion = EntityMotion.homing,
                    speed = 99.0, rate = 50.0, points = 500, size = 40.0),
                EntitySpec(sprite = "rock", role = EntityRole.avoid, power = null, motion = EntityMotion.zigzag,
                    speed = Double.NaN, rate = 50.0, points = -3, size = 1.0),
            ),
            ramp = 7.0,
        ).sanitized()
        assertTrue(evil.title.length <= 28)
        assertEquals(90.0, evil.duration, 0.0)
        assertEquals("survive needs lives", 3, evil.lives)
        assertEquals(0.5, evil.ramp, 0.0)
        assertTrue("a collectible is added when none is valid", evil.entities.any { it.role == EntityRole.collect })
        assertFalse(evil.entities.any { it.sprite.contains("/") })
        val avoid = evil.entities.filter { it.role == EntityRole.avoid }
        assertTrue(avoid.sumOf { it.rate } <= 1.6 + 1e-9)
        assertTrue(avoid.all { it.speed <= 2 && it.size <= 1.6 && it.points <= 10 && it.points >= 0 })
        assertFalse(evil.tutorial.isEmpty())
    }

    /** Plans saved by older versions (some made on the server) still load. */
    @Test
    fun savedPlanDecodes() {
        val json = """
        {"day": "2026-10-26", "source": "ai", "story": "i dreamed of jelly.",
         "quest": {"title": "Score 80", "coins": 25, "goal": {"kind": "dailyScore", "target": 80}},
         "game": {"id": "2026-10-26", "title": "Moonlight Munch", "blurb": "Catch stars.", "tutorial": ["Drag.", "Catch."],
                  "control": "drag", "goal": "timed", "duration": 45, "lives": 3, "theme": "night", "ramp": 0.2,
                  "entities": [{"sprite": "starJelly", "role": "collect", "motion": "wave", "speed": 1, "rate": 1.2, "points": 2, "size": 1},
                               {"sprite": "rock", "role": "avoid", "motion": "homing", "speed": 1.2, "rate": 0.6, "points": 3, "size": 1.1},
                               {"sprite": "bubbleItem", "role": "power", "power": "shield", "motion": "wave", "speed": 0.8, "rate": 0.1, "points": 0, "size": 1}]}}
        """
        val plan = SlimyJson.storage.decodeFromString(DailyPlan.serializer(), json).sanitized()
        assertEquals("Moonlight Munch", plan.game.title)
        assertEquals(3, plan.game.entities.size)
        assertEquals(DailyQuest.Goal.dailyScore(80), plan.quest.goal)
        val stat = SlimyJson.storage.decodeFromString(
            DailyQuest.serializer(),
            """{"title": "Feed 3", "coins": 15, "goal": {"kind": "stat", "stat": "feeds", "target": 3}}""",
        )
        assertEquals(DailyQuest.Goal.stat(StatKey.feeds, 3), stat.goal)
    }

    @Test
    fun planSurvivesASaveRoundTrip() {
        val plan = DailyPlanner.plan("2026-10-26")
        val data = SlimyJson.storage.encodeToString(DailyPlan.serializer(), plan)
        val loaded = SlimyJson.storage.decodeFromString(DailyPlan.serializer(), data)
        assertEquals(plan, loaded)
        assertEquals("local", plan.source)
        // The quest goal is written like the Swift encoder writes it.
        assertTrue(data.contains("\"kind\":"))
    }

    @Test
    fun questPaysOnceAndTracksDailyScore() {
        val state = GameState()
        val noon = instantOf(1_793_000_000.0)
        state.hatch(name = "Testi", skin = Skin.lime, seed = 1uL, now = noon)
        val plan = DailyPlanner.plan("2026-10-26")
        plan.quest = DailyQuest(title = "Score 50", goal = DailyQuest.Goal.dailyScore(50), coins = 20)
        state.startQuest(plan)
        assertFalse(state.claimQuest(plan, noon).ok)
        state.finish(GameResult(game = GameID.daily, score = 60, session = UUID.randomUUID(), finishedAt = noon,
            target = plan.game.goodScore, day = plan.day))
        assertEquals(50, state.questProgress(plan).value)
        val coins = state.wallet.coins
        assertTrue(state.claimQuest(plan, noon).ok)
        assertTrue(state.wallet.coins >= coins + 20)
        val after = state.wallet.coins
        assertFalse(state.claimQuest(plan, noon).ok)
        assertEquals(after, state.wallet.coins)
    }

    @Test
    fun dailyScoresAreNotRecords() {
        val state = GameState()
        val noon = instantOf(1_793_000_000.0)
        state.hatch(name = "Testi", skin = Skin.lime, seed = 1uL, now = noon)
        state.finish(GameResult(game = GameID.daily, score = 10, session = UUID.randomUUID(), finishedAt = noon, target = 100, day = "a"))
        val out = state.finish(GameResult(game = GameID.daily, score = 500, session = UUID.randomUUID(), finishedAt = noon, target = 100, day = "b"))
        assertFalse(out.events.any { it is GameEvent.record })
    }
}

/** A simple bot plays generated games, to prove they can be played and end. */
class BlockEngineTests {
    private fun play(spec: GameSpec, seed: ULong = 7uL): BlockEngine {
        val e = BlockEngine(spec, seed)
        var steps = 0
        while (!e.isOver && steps < 60 * 200) {
            drive(e)
            e.step(1.0 / 60)
            e.drainEvents()
            steps += 1
        }
        return e
    }

    private fun drive(e: BlockEngine) {
        val spec = e.spec
        val good = e.things.filter { spec.entities[it.kind].role != EntityRole.avoid }
        val bad = e.things.filter { spec.entities[it.kind].role == EntityRole.avoid }
        when (spec.control) {
            GameControl.drag -> {
                good.filter { it.y < 150 }.maxByOrNull { it.y }?.let { e.target = BoardPoint(it.x, e.target.y) }
            }
            GameControl.lanes -> {
                val danger = bad.filter { it.y > 90 && it.y < 150 }.map { ((it.x - 5) / 30).toInt() }
                val wanted = good.filter { it.y > 60 && it.y < 150 }.maxByOrNull { it.y }?.let { ((it.x - 5) / 30).toInt() }
                val goal = wanted?.takeIf { !danger.contains(it) } ?: (0..2).firstOrNull { !danger.contains(it) } ?: 1
                if (goal != e.lane) e.moveLane(if (goal > e.lane) 1 else -1)
            }
            GameControl.steer -> {
                good.minByOrNull { kotlin.math.abs(it.x - e.x) + kotlin.math.abs(it.y - e.y) }?.let { e.target = BoardPoint(it.x, it.y) }
            }
            GameControl.jump -> {
                if (bad.any { it.x > e.x + 2 && it.x < e.x + 22 && it.y > BlockEngine.ground - 12 }) e.tap()
            }
            GameControl.flap -> {
                val goalY = good.filter { it.x > e.x }.minByOrNull { it.x }?.y ?: 80.0
                if (e.y > goalY + 6) e.tap()
            }
        }
    }

    @Test
    fun generatedGamesCanBePlayedAndEnd() {
        var scored = 0
        val n = 90
        for (i in 0 until n) {
            val day = String.format(Locale.ROOT, "2027-%02d-%02d", i % 12 + 1, i % 28 + 1)
            val spec = DailyPlanner.plan(day).game
            val e = play(spec, (i + 1).toULong())
            assertTrue(spec.title, e.isOver)
            assertTrue(e.score >= 0)
            if (e.score > 0) scored += 1
        }
        assertTrue("the bot should score in almost every game", scored >= n * 9 / 10)
    }

    @Test
    fun sameSeedSameRound() {
        val spec = DailyPlanner.plan("2026-10-26").game
        assertEquals(play(spec, 3uL).score, play(spec, 3uL).score)
    }

    @Test
    fun shieldBlocksAHit() {
        val spec = DailyPlanner.plan("2026-10-26").game.deepCopy()
        spec.control = GameControl.drag
        spec.lives = 5
        spec.goal = GameGoal.timed
        spec.entities = listOf(
            EntitySpec(sprite = "bubbleItem", role = EntityRole.power, power = PowerKind.shield, motion = EntityMotion.straight,
                speed = 1.0, rate = 0.25, points = 0, size = 1.0),
            EntitySpec(sprite = "rock", role = EntityRole.avoid, power = null, motion = EntityMotion.straight,
                speed = 1.0, rate = 0.5, points = 3, size = 1.0),
            EntitySpec(sprite = "coin", role = EntityRole.collect, power = null, motion = EntityMotion.straight,
                speed = 1.0, rate = 0.5, points = 1, size = 1.0),
        )
        val e = BlockEngine(spec.sanitized(), 1uL)
        var sawBlock = false
        var sawHit = false
        repeat(60 * 60) {
            if (e.isOver) return@repeat
            // Stand under whatever is lowest; catch power-ups and rocks alike.
            e.things.maxByOrNull { it.y }?.let { e.target = BoardPoint(it.x, e.target.y) }
            e.step(1.0 / 60)
            for (ev in e.drainEvents()) {
                if (ev is BlockEngine.Event.blocked) sawBlock = true
                if (ev is BlockEngine.Event.hit) sawHit = true
            }
        }
        assertTrue(sawBlock)
        assertTrue(sawHit)
    }
}
