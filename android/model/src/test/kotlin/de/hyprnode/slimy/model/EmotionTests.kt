package de.hyprnode.slimy.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

private val berlin = SlimyCalendar.gregorian("Europe/Berlin")

private fun date(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Instant =
    berlin.date(year, month, day, hour, minute)

/** 4 May 2026, noon in Berlin: no season, holiday or birthday. */
private val noon: Instant = date(2026, 5, 4)

private fun hatchedState(now: Instant = noon): GameState {
    val state = GameState()
    state.hatch(name = "Testi", skin = Skin.lime, seed = 42uL, now = now)
    state.settings.sleep.auto = false
    return state
}

class EmotionModelTests {
    @Test
    fun testStartsCalm() {
        val e = Emotions()
        assertEquals(0.3, e[Emotion.joy], 0.0)
        for (emotion in Emotion.entries) {
            if (emotion != Emotion.joy) assertEquals(emotion.rawValue, 0.0, e[emotion], 0.0)
        }
        assertEquals(Emotion.joy, e.dominant)
        assertEquals(0.3, e.intensity, 1e-9)
    }

    @Test
    fun testValuesStayInRange() {
        val e = Emotions()
        e[Emotion.anger] = 7.0
        e[Emotion.fear] = -2.0
        e[Emotion.love] = Double.NaN
        e[Emotion.joy] = Double.POSITIVE_INFINITY
        assertEquals(1.0, e[Emotion.anger], 0.0)
        assertEquals(0.0, e[Emotion.fear], 0.0)
        assertEquals(0.0, e[Emotion.love], 0.0)
        assertEquals(0.0, e[Emotion.joy], 0.0)
    }

    @Test
    fun testFeelingRisesAndSoftensItsOpposites() {
        val e = Emotions()
        e[Emotion.sadness] = 0.5
        e.feel(Emotion.joy, 0.4)
        assertEquals(0.7, e[Emotion.joy], 1e-9)
        assertEquals(0.5 - 0.4 * Emotions.damping, e[Emotion.sadness], 1e-9)
        // Taking a feeling away leaves the others alone.
        e.feel(Emotion.joy, -0.2)
        assertEquals(0.5, e[Emotion.joy], 1e-9)
        assertEquals(0.5 - 0.4 * Emotions.damping, e[Emotion.sadness], 1e-9)
        e.feel(Emotion.anger, Double.NaN)
        assertEquals(0.0, e[Emotion.anger], 0.0)
        for (emotion in Emotion.entries) {
            assertFalse(emotion.rawValue, emotion.opposites.contains(emotion))
            assertFalse(emotion.title.isEmpty())
            assertEquals(emotion.adjective.lowercase(), emotion.adjective)
        }
    }

    @Test
    fun testDominantAndStrongest() {
        val e = Emotions()
        e[Emotion.curiosity] = 0.8
        e[Emotion.excitement] = 0.5
        assertEquals(Emotion.curiosity, e.dominant)
        assertEquals(0.8, e.intensity, 1e-9)
        assertEquals(listOf(Emotion.curiosity, Emotion.excitement), e.strongest(2))
        assertEquals(listOf(Emotion.curiosity), e.strongest(5, atLeast = 0.6))
    }

    @Test
    fun testWireLeavesOutFaintFeelingsAndRounds() {
        val e = Emotions()
        e[Emotion.joy] = 0.456
        e[Emotion.fear] = 0.04
        e[Emotion.love] = 0.05
        assertEquals(mapOf("joy" to 0.46, "love" to 0.05), e.wire)
    }

    @Test
    fun testBlending() {
        val a = Emotions()
        a[Emotion.sadness] = 0.8
        val b = Emotions()
        b[Emotion.sadness] = 0.2
        assertEquals(0.5, a.blended(b, weight = 0.5)[Emotion.sadness], 1e-9)
        assertEquals(0.8, a.blended(b, weight = 0.0)[Emotion.sadness], 1e-9)
        assertEquals(0.2, a.blended(b, weight = 7.0)[Emotion.sadness], 1e-9)
    }

    @Test
    fun testCodingAndSavesFromBeforeFeelings() {
        val e = Emotions()
        e[Emotion.love] = 0.7
        val json = SlimyJson.storage
        val back = json.decodeFromString(Emotions.serializer(), json.encodeToString(Emotions.serializer(), e))
        assertEquals(e, back)

        val partial = json.decodeFromString(Emotions.serializer(), """{"fear": 2, "anger": "lots"}""")
        assertEquals(1.0, partial[Emotion.fear], 0.0)
        assertEquals(0.0, partial[Emotion.anger], 0.0)
        assertEquals(0.3, partial[Emotion.joy], 0.0)

        val slime = json.decodeFromString(Slime.serializer(), """{"name": "Old"}""")
        assertEquals("Old", slime.name)
        assertEquals(Emotions(), slime.emotions)
        assertTrue(slime.colours.isEmpty())
    }
}

class EmotionTimeTests {
    @Test
    fun testSettlesHalfwayInThirtyMinutes() {
        val e = Emotions()
        e[Emotion.excitement] = 1.0
        e.settle(toward = Emotions(), over = 30.0 * 60)
        assertEquals(0.5, e[Emotion.excitement], 1e-9)
        e.settle(toward = Emotions(), over = -100.0)
        e.settle(toward = Emotions(), over = Double.NaN)
        assertEquals(0.5, e[Emotion.excitement], 1e-9)
    }

    @Test
    fun testTickSettlesTowardTheBaseline() {
        val state = hatchedState()
        state.slime.emotions[Emotion.excitement] = 1.0
        val start = state.slime.lastUpdate
        state.tick(start.addingTimeInterval(30.0 * 60), berlin)
        assertTrue(state.slime.emotions[Emotion.excitement] < 0.6)
        assertTrue(state.slime.emotions[Emotion.excitement] > 0.45)

        state.tick(start.addingTimeInterval(6.0 * 3600), berlin)
        val base = Emotions.baseline(state.slime.needs, state.slime.personality, state.slime.bond, state.slime.isAsleep)
        assertEquals(base[Emotion.excitement], state.slime.emotions[Emotion.excitement], 0.05)
    }

    @Test
    fun testClockGoingBackChangesNoFeelings() {
        val state = hatchedState()
        state.slime.emotions[Emotion.excitement] = 1.0
        state.tick(state.slime.lastUpdate.addingTimeInterval(-3600.0), berlin)
        assertEquals(1.0, state.slime.emotions[Emotion.excitement], 0.0)
        // And a huge jump ahead just lands on the baseline.
        state.tick(state.slime.lastUpdate.addingTimeInterval(400.0 * 86400), berlin)
        assertTrue(state.slime.emotions[Emotion.excitement] < 0.2)
        for (emotion in Emotion.entries) {
            assertTrue(emotion.rawValue, state.slime.emotions[emotion] in 0.0..1.0)
        }
    }

    @Test
    fun testNeedsDriveSadnessBoredomAndSleepiness() {
        val personality = Personality(playful = 0.5, cuddly = 0.5, foodie = 0.5, sleepy = 0.5, curious = 0.5)
        val happy = Emotions.baseline(Needs(), personality, bond = 30.0, asleep = false)
        val low = Needs()
        low.love = 10.0
        low.`fun` = 10.0
        low.energy = 10.0
        val neglected = Emotions.baseline(low, personality, bond = 30.0, asleep = false)
        assertTrue(neglected[Emotion.sadness] > happy[Emotion.sadness] + 0.2)
        assertTrue(neglected[Emotion.boredom] > happy[Emotion.boredom] + 0.2)
        assertTrue(neglected[Emotion.sleepiness] > happy[Emotion.sleepiness] + 0.2)
        assertTrue(neglected[Emotion.joy] < happy[Emotion.joy])
        assertEquals(Emotion.joy, happy.dominant)

        val hungry = Needs()
        hungry.hunger = 5.0
        assertTrue(Emotions.baseline(hungry, personality, bond = 30.0, asleep = false)[Emotion.anger] > 0.2)

        val asleep = Emotions.baseline(Needs(), personality, bond = 30.0, asleep = true)
        assertEquals(Emotion.sleepiness, asleep.dominant)
        assertEquals(0.0, asleep[Emotion.excitement], 0.0)
    }

    @Test
    fun testStrongSadnessShowsAsASadMood() {
        val slime = hatchedState().slime
        assertEquals(Mood.idle, slime.mood)
        slime.emotions[Emotion.sadness] = 0.7
        assertEquals(Mood.sad, slime.mood)
        slime.isAsleep = true
        assertEquals(Mood.sleep, slime.mood)
    }
}

class EmotionRuleTests {
    @Test
    fun testFoodBringsJoyAndTheFavouriteLove() {
        val state = hatchedState()
        state.slime.favorite = Food.berry
        state.slime.needs.hunger = 20.0
        state.slime.emotions = Emotions()
        assertTrue(state.feed(Food.carrot, noon.addingTimeInterval(60.0)).ok)
        assertTrue(state.slime.emotions[Emotion.joy] > 0.4)
        assertTrue(state.slime.emotions[Emotion.love] < 0.05)

        val before = state.slime.emotions.copy()
        assertTrue(state.feed(Food.berry, noon.addingTimeInterval(120.0)).ok)
        assertTrue(state.slime.emotions[Emotion.love] > before[Emotion.love] + 0.15)
        assertTrue(state.slime.emotions[Emotion.joy] > before[Emotion.joy])
    }

    @Test
    fun testPettingBringsLove() {
        val state = hatchedState()
        state.slime.emotions = Emotions()
        state.pet(noon.addingTimeInterval(60.0))
        assertTrue(state.slime.emotions[Emotion.love] > 0.15)
    }

    @Test
    fun testBathsCheerUpButSomeSlimesGrumble() {
        val state = hatchedState()
        state.slime.personality = Personality(playful = 0.6, cuddly = 0.5, foodie = 0.5, sleepy = 0.3, curious = 0.7)
        assertFalse(state.slime.personality.dislikesBaths)
        state.slime.needs.clean = 30.0
        state.slime.emotions = Emotions()
        assertTrue(state.bath(noon.addingTimeInterval(60.0)).ok)
        assertTrue(state.slime.emotions[Emotion.joy] > 0.5)
        assertEquals(0.0, state.slime.emotions[Emotion.anger], 0.01)

        val grumpy = hatchedState()
        grumpy.slime.personality = Personality(playful = 0.2, cuddly = 0.5, foodie = 0.5, sleepy = 0.8, curious = 0.2)
        assertTrue(grumpy.slime.personality.dislikesBaths)
        grumpy.slime.needs.clean = 30.0
        grumpy.slime.emotions = Emotions()
        assertTrue(grumpy.bath(noon.addingTimeInterval(60.0)).ok)
        assertTrue(grumpy.slime.emotions[Emotion.anger] > 0.2)
        assertTrue(grumpy.memory.recentLines.lastOrNull()?.startsWith("bathGrumpy.") ?: false)
        // It doesn't last.
        grumpy.tick(noon.addingTimeInterval(2.0 * 3600), berlin)
        assertTrue(grumpy.slime.emotions[Emotion.anger] < 0.1)
    }

    @Test
    fun testGamesExciteAndRecordsDelight() {
        val state = hatchedState()
        state.slime.emotions = Emotions()
        state.finish(GameResult(game = GameID.bubblePop, score = 100, session = UUID.randomUUID(), finishedAt = noon.addingTimeInterval(60.0)))
        assertTrue(state.slime.emotions[Emotion.excitement] > 0.25)

        val before = state.slime.emotions.copy()
        val record = state.finish(
            GameResult(game = GameID.bubblePop, score = 900, session = UUID.randomUUID(), finishedAt = noon.addingTimeInterval(120.0)),
        )
        assertTrue(record.events.contains(GameEvent.record(GameID.bubblePop, 900)))
        assertTrue(state.slime.emotions[Emotion.joy] > before[Emotion.joy] + 0.2)
        assertTrue(state.slime.emotions[Emotion.excitement] > before[Emotion.excitement] + 0.2)
    }

    @Test
    fun testFriendsCheerItUp() {
        val state = hatchedState()
        state.slime.emotions = Emotions()
        state.friendMoment(LineCategory.friendVisit, friend = "Mochi", now = noon.addingTimeInterval(60.0))
        assertTrue(state.slime.emotions[Emotion.joy] > 0.45)
        assertTrue(state.slime.emotions[Emotion.excitement] > 0.2)
        assertEquals("Mochi", state.memory.last(MemoryEntry.Kind.visit)?.detail)
        assertEquals(1, state.progress.stats.visits)

        state.slime.emotions = Emotions()
        state.friendMoment(LineCategory.friendHug, friend = "Mochi", now = noon.addingTimeInterval(120.0))
        assertTrue(state.slime.emotions[Emotion.love] > 0.15)
        assertEquals(1, state.progress.stats.hugs)
    }

    @Test
    fun testPhoneEventsStirFeelings() {
        val state = hatchedState()
        state.slime.emotions = Emotions()
        state.handle(PhoneEvent.batteryCritical, noon.addingTimeInterval(60.0))
        assertEquals(0.3, state.slime.emotions[Emotion.fear], 1e-9)
        for (event in PhoneEvent.entries) {
            event.feeling?.let { assertTrue(event.rawValue, it.amount > 0) }
        }
    }
}

class ChatterTests {
    @Test
    fun testNeverRepeatsOneOfItsLast12Lines() {
        val state = hatchedState()
        val said = mutableListOf<String>()
        for (i in 0 until 300) {
            val text = state.chatter(noon.addingTimeInterval(i * 11.0), berlin)
            assertFalse(text.isEmpty())
            assertFalse(text, text.contains("{"))
            val id = state.memory.recentLines.lastOrNull() ?: ""
            assertFalse("$id at $i", said.takeLast(chatterMemory).contains(id))
            assertEquals(id, state.slime.say?.lineID)
            said.add(id)
        }
        assertTrue(said.toSet().size > 40)
    }

    @Test
    fun testAsleepItOnlyMumbles() {
        val state = hatchedState()
        state.slime.isAsleep = true
        for (i in 0 until 40) {
            state.chatter(noon.addingTimeInterval(i * 11.0), berlin)
            assertTrue(state.memory.recentLines.lastOrNull()?.startsWith("sleeping.") ?: false)
        }
    }

    @Test
    fun testAStrongFeelingComesUpOften() {
        val state = hatchedState()
        var excited = 0
        for (i in 0 until 60) {
            state.slime.emotions = Emotions()
            state.slime.emotions[Emotion.excitement] = 1.0
            state.chatter(noon.addingTimeInterval(i * 11.0), berlin)
            if (state.memory.recentLines.lastOrNull()?.startsWith("feelExcited.") == true) excited += 1
        }
        assertTrue("$excited", excited > 10)
    }

    @Test
    fun testLowNeedsComeUpGently() {
        val state = hatchedState()
        state.slime.needs.hunger = 5.0
        var hungry = 0
        for (i in 0 until 40) {
            state.chatter(noon.addingTimeInterval(i * 11.0), berlin)
            if (state.memory.recentLines.lastOrNull()?.startsWith("wishHunger.") == true) hungry += 1
        }
        assertTrue("$hungry", hungry > 4)
    }

    @Test
    fun testTopicsFollowTheWorldAndWhatJustHappened() {
        val state = hatchedState()
        state.inventory.habitats = state.inventory.habitats + Habitat.beach
        state.inventory.habitat = Habitat.beach
        val summerEvening = state.chatterTopics(date(2026, 7, 4, 21), berlin).map { it.category }
        assertTrue(summerEvening.contains(LineCategory.beachTalk))
        assertTrue(summerEvening.contains(LineCategory.summer))
        assertTrue(summerEvening.contains(LineCategory.evening))
        assertFalse(summerEvening.contains(LineCategory.afterFood))

        assertTrue(state.feed(Food.apple, noon.addingTimeInterval(60.0)).ok)
        assertTrue(state.chatterTopics(noon.addingTimeInterval(5.0 * 60), berlin).map { it.category }.contains(LineCategory.afterFood))
        assertFalse(state.chatterTopics(noon.addingTimeInterval(60.0 * 60), berlin).map { it.category }.contains(LineCategory.afterFood))

        state.slime.isAsleep = true
        assertEquals(listOf(LineCategory.sleeping), state.chatterTopics(noon, berlin).map { it.category })
    }

    @Test
    fun testItTalksAboutTheWeatherItCanSee() {
        // Every three-hour block of two weeks in July and two in December.
        val starts = listOf(date(2026, 7, 1, 0), date(2026, 12, 1, 0))
        val moments = starts.flatMap { start -> (0 until 14 * 8).map { start.addingTimeInterval(it * 3.0 * 3600) } }
        val weatherTalk = setOf(LineCategory.rainTalk, LineCategory.snowTalk, LineCategory.cloudTalk)
        val seen = mutableSetOf<Sky>()
        val state = hatchedState()
        for (habitat in Habitat.entries) {
            state.inventory.habitats = state.inventory.habitats + habitat
            state.inventory.habitat = habitat
            for (moment in moments) {
                val sky = Sky.at(moment, berlin)
                seen.add(sky)
                val talk = state.chatterTopics(moment, berlin).map { it.category }.toSet().intersect(weatherTalk)
                if (habitat == Habitat.space || habitat == Habitat.crystal || sky == Sky.clear) {
                    assertTrue("${habitat.rawValue} ${sky.rawValue}", talk.isEmpty())
                } else {
                    assertEquals("${habitat.rawValue} ${sky.rawValue}", setOfNotNull(sky.talk), talk)
                }
            }
        }
        assertEquals(Sky.entries.toSet(), seen)
    }

    @Test
    fun testNoLineGuiltTrips() {
        val guilt = listOf(
            "you forgot", "you left me", "abandon", "neglect", "your fault", "why didn't you", "you never",
            "all alone", "you don't care", "ignored",
        )
        for ((category, lines) in Dialogue.lines) {
            for (line in lines) {
                val text = line.text.lowercase()
                for (phrase in guilt) assertFalse("${category.rawValue}: ${line.text}", text.contains(phrase))
            }
        }
    }
}
