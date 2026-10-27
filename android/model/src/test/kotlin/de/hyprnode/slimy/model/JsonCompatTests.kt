package de.hyprnode.slimy.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * The JSON the iPhone app's Codable model writes, read and written back by
 * the Kotlin model. Fixtures are hand-written in Swift's shapes:
 * `GameStorage.encoder` / `SaveFile` (dates = seconds since 1970) and
 * `HyprnodeAPI.encoder` (dates = ISO 8601), flat arrays for enum-keyed
 * dictionaries, upper-case UUIDs, nil optionals left out.
 */
class JsonCompatTests {
    private fun obj(element: JsonElement?, path: String): JsonObject =
        requireNotNull(element as? JsonObject) { "$path is not an object: $element" }

    private fun JsonObject.o(key: String): JsonObject = obj(this[key], key)

    private fun JsonObject.number(key: String): String {
        val p = this[key] as? JsonPrimitive ?: error("$key missing")
        assertFalse("$key should be a number, was \"${p.content}\"", p.isString)
        return p.content
    }

    private fun JsonObject.string(key: String): String {
        val p = this[key] as? JsonPrimitive ?: error("$key missing")
        assertTrue("$key should be a string", p.isString)
        return p.content
    }

    private fun decodeSave(json: String): GameState = requireNotNull(GameStorage.decode(json)) { "save didn't decode" }

    // MARK: (a) The save, as iOS GameStorage.encoder writes it

    @Test
    fun testReadsAnIPhoneSave() {
        val state = decodeSave(Fixtures.text("save-v4.json"))
        assertEquals(4, state.version)
        assertTrue(state.hatched)

        val slime = state.slime
        assertEquals("Blob", slime.name)
        assertEquals(Skin.axolotl, slime.skin)
        assertEquals(Instant.parse("2025-12-24T08:00:00Z"), slime.birthday)
        assertEquals(72.5, slime.needs.hunger, 0.0)
        assertEquals(64.0, slime.needs.`fun`, 0.0)
        assertEquals(88.25, slime.needs.energy, 0.0)
        assertEquals(620, slime.xp)
        assertEquals(Reaction.blush, slime.reaction)
        assertEquals(Food.honey, slime.favorite)
        assertEquals(GameID.slimeStack, slime.favoriteGame)
        assertEquals(0.88, slime.personality.curious, 0.0)
        assertEquals(57.5, slime.bond, 0.0)
        assertEquals(Utterance("hi router", Instant.parse("2026-05-04T09:55:00Z"), "idle.3"), slime.say)
        assertEquals(PhoneEvent.wifiConnected, slime.lastEvent)
        assertEquals(DistantPast, slime.eventAt)
        assertEquals(listOf(Instant.parse("2026-05-04T09:50:00Z"), Instant.parse("2026-05-04T09:55:00Z")), slime.recentPets)
        assertEquals(Accessories(hat = "hat.crown", glasses = "glasses.round"), slime.equipped)
        assertEquals(Need.hunger, slime.lastNudged)
        assertEquals(DistantPast, slime.lastNudgeAt)
        assertEquals(0.45, slime.emotions[Emotion.joy], 0.0)
        assertEquals(0.05, slime.emotions[Emotion.anger], 0.0)
        assertEquals(setOf(Skin.axolotl, Skin.grape), slime.colours)

        // Fractional seconds as Swift writes them.
        assertEquals(Instant.parse("2026-01-01T00:00:00.250Z"), state.player.createdAt)
        assertEquals(setOf("onboarded", "feed"), state.player.tips)
        assertEquals(setOf("winter", "spring"), state.player.seasonsSeen)

        assertEquals(345, state.wallet.coins)
        assertEquals(1280, state.wallet.earnedTotal)
        assertEquals(Instant.parse("2026-05-04T09:00:00Z"), state.wallet.ledger["gift:2026-05-04"])
        assertTrue(state.wallet.hasClaimed("game:2D0E9C8A-4B1F-4E2A-9A7C-6B5D4E3F2A1B"))

        assertEquals(Habitat.garden, state.inventory.habitat)
        assertEquals(setOf(Habitat.room, Habitat.garden), state.inventory.habitats)
        assertEquals(mapOf(FurnitureSlot.floorLeft to "furn.bed", FurnitureSlot.rug to "furn.rug"), state.inventory.furniture)
        assertEquals(mapOf(Food.cookie to 3, Food.honey to 2), state.inventory.pantry)
        assertEquals(setOf(Food.apple, Food.honey, Food.cookie), state.inventory.foodsTasted)

        assertEquals(120, state.progress.stats.pets)
        assertEquals(3, state.progress.stats.cratesOpened)
        assertEquals(2, state.progress.stats.partnerCare)
        assertEquals(
            GameRecord(best = 420, plays = 3, lastPlayed = Instant.parse("2026-05-03T18:30:00Z"), tutorialSeen = true),
            state.progress.records[GameID.bubblePop],
        )
        assertEquals(GameRecord(best = 12, plays = 1, lastPlayed = null, tutorialSeen = false), state.progress.records[GameID.snackCatcher])
        assertEquals("2026-W19", state.progress.challenges.weekKey)
        assertEquals(listOf("d.feed3", "d.bubble"), state.progress.challenges.daily.map { it.id })
        assertTrue(state.progress.challenges.daily[1].claimed)
        assertEquals(QuestState(day = "2026-05-04", baseline = 30), state.progress.quest)

        assertEquals(3, state.memory.entries.size)
        assertEquals(UUID.fromString("7D3D2A1E-8B9A-4C7E-9C51-2E9B4F0A1B2C"), state.memory.entries[0].id)
        assertEquals("Legendary find: Axolotl", state.memory.entries[1].summary)
        assertEquals("Ben fed Blob", state.memory.entries[2].summary)
        assertEquals(listOf("idle.3", "fed.1"), state.memory.recentLines)

        assertFalse(state.settings.haptics)
        assertFalse(state.settings.chatty)
        assertEquals(23, state.settings.notifications.quietFrom)
        assertEquals(SleepSchedule(bedtime = 23, wake = 8, auto = true), state.settings.sleep)
        assertEquals(StreakState(lastDay = "2026-05-04", count = 4, best = 9, opened = 20), state.streak)
        assertEquals(mapOf(CrateKind.mystery to 1, CrateKind.golden to 2), state.crates)

        val care = requireNotNull(state.care)
        assertEquals("dev_ben", care.partnerID)
        assertEquals(CareLink.joiner, care.role)
        assertNull(care.cursor)
        assertEquals(3, care.version)
        assertEquals("Mochi", care.ownBackup?.name)
        assertEquals(DistantPast, care.ownBackup?.reactionAt)

        val book = state.careBook
        assertEquals("9E8D7C6B-5A49-4837-A625-14F3E2D1C0B9", book.me)
        assertEquals(Instant.parse("2026-05-04T09:00:00Z"), book.changedAt)
        assertEquals(Instant.parse("2026-05-03T18:30:00Z"), book.adoptedAt)
        assertEquals(listOf("ev-1", "ev-2"), book.seen)
        assertEquals(listOf(CareKind.feed, CareKind.play), book.outbox.map { it.kind })
        assertEquals(Food.cookie, book.outbox[0].food)
        assertNull(book.outbox[0].amount)
        assertEquals(GameID.bubblePop, book.outbox[1].game)
        assertEquals(75.0, book.outbox[1].amount!!, 0.0)
        assertEquals(setOf(Skin.grape), book.coloursFound)
    }

    @Test
    fun testWritesTheSaveInSwiftsShapes() {
        val state = decodeSave(Fixtures.text("save-v4.json"))
        val text = GameStorage.encode(state)
        val root = SlimyJson.storage.parseToJsonElement(text).jsonObject

        // Dates are numbers: seconds since 1970, fractions kept, distantPast exact.
        val slime = root.o("slime")
        assertEquals("1766563200", slime.number("birthday"))
        assertEquals("-62135769600", slime.number("eventAt"))
        assertEquals("1767225600.25", root.o("player").number("createdAt"))
        assertEquals("1777885200", root.o("wallet").o("ledger").number("gift:2026-05-04"))

        // Enum raw values, the needs' "fun" key.
        assertEquals("axolotl", slime.string("skin"))
        assertEquals("wifiConnected", slime.string("lastEvent"))
        assertEquals(64.0, slime.o("needs").number("fun").toDouble(), 0.0)

        // nil optionals are left out; everything else is written.
        assertFalse(slime.o("equipped").containsKey("scarf"))
        assertFalse(slime.o("equipped").containsKey("effect"))
        assertFalse(root.o("care").containsKey("cursor"))
        assertFalse(root.o("care").o("ownBackup").containsKey("say"))
        assertFalse(root.o("care").o("ownBackup").containsKey("lastEvent"))
        assertFalse(root.o("careBook")["outbox"]!!.jsonArray[0].jsonObject.containsKey("amount"))
        assertTrue(root.o("careBook")["outbox"]!!.jsonArray[1].jsonObject.containsKey("amount"))
        assertTrue(root.o("settings").containsKey("chatty"))
        assertTrue(root.o("progress").o("stats").containsKey("partnerCare"))

        // [Enum: V] dictionaries are flat arrays: [key, value, key, value].
        val furniture = root.o("inventory")["furniture"] as JsonArray
        assertEquals(listOf("floorLeft", "furn.bed", "rug", "furn.rug"), furniture.map { it.jsonPrimitive.content })
        val pantry = root.o("inventory")["pantry"] as JsonArray
        assertEquals("cookie", pantry[0].jsonPrimitive.content)
        assertEquals(3, pantry[1].jsonPrimitive.int)
        val records = root.o("progress")["records"] as JsonArray
        assertEquals(4, records.size)
        assertEquals("bubblePop", records[0].jsonPrimitive.content)
        assertEquals("1777833000", records[1].jsonObject.number("lastPlayed"))
        assertFalse(records[3].jsonObject.containsKey("lastPlayed"))
        val crates = root["crates"] as JsonArray
        assertEquals(listOf("mystery", "1", "golden", "2"), crates.map { it.jsonPrimitive.content })

        // [String: V] dictionaries are objects; sets are arrays; UUIDs upper case.
        assertTrue(root.o("progress")["achievements"] is JsonObject)
        assertTrue(root.o("memory")["lineUsedAt"] is JsonObject)
        assertTrue(root.o("player")["tips"] is JsonArray)
        assertTrue(root.o("inventory")["habitats"] is JsonArray)
        val id = root.o("memory")["entries"]!!.jsonArray[0].jsonObject.string("id")
        assertEquals(id.uppercase(), id)

        // And it reads back to the same game.
        assertEquals(state, decodeSave(text))
    }

    @Test
    fun testAFreshGameRoundTripsExactly() {
        // Instant.now() has nanoseconds; dates must come back to the nanosecond.
        val state = GameState()
        state.hatch(name = "Testi", skin = Skin.bubblegum, seed = 3uL, now = Instant.now())
        state.memory.add(MemoryEntry(kind = MemoryEntry.Kind.fed, at = Instant.now(), detail = "apple"))
        assertEquals(state, decodeSave(GameStorage.encode(state)))
        val fresh = GameState()
        assertEquals(fresh, decodeSave(GameStorage.encode(fresh)))
    }

    // MARK: (b) A .slimysave file

    @Test
    fun testImportsAnIPhoneExport() {
        val now = Instant.parse("2026-05-04T10:00:00Z")
        val state = SaveFile.decode(Fixtures.text("export.slimysave").toByteArray(), now)
        // Caring together stays with the phone that made the file: the joiner's own slime is back,
        // with the colour found meanwhile, without a word in the memory book.
        assertNull(state.care)
        assertEquals("Mochi", state.slime.name)
        assertEquals(setOf(Skin.ocean, Skin.grape), state.slime.colours)
        assertTrue(state.careBook.outbox.isEmpty())
        assertNull(state.memory.last(MemoryEntry.Kind.careEnded))
        assertEquals(GameState.currentVersion, state.version)
        // Importing runs the clock once (on the shared slime, then the own one comes back), like on iOS.
        assertTrue(state.wallet.coins >= 90)
        assertTrue(state.wallet.hasClaimed("level:2"))
        assertEquals(2, state.crateCount(CrateKind.wooden))
        assertEquals("Ben", state.player.name)
        assertEquals(StreakState(lastDay = "2026-05-04", count = 2, best = 3, opened = 5), state.streak)
    }

    @Test
    fun testExportsWithAHeaderSortedKeysAndNumberDates() {
        val now = Instant.parse("2026-05-04T10:00:00Z")
        val state = SaveFile.decode(Fixtures.text("export.slimysave").toByteArray(), now)
        val text = SaveFile.encode(state, now, appVersion = "1.1.0")
        val root = SlimyJson.storage.parseToJsonElement(text).jsonObject
        assertEquals(listOf("appVersion", "exportedAt", "format", "schema", "state"), root.keys.toList())
        assertEquals("1777888800", root.number("exportedAt"))
        assertEquals(SaveFile.format, root.string("format"))
        assertEquals("1", root.number("schema"))
        val inner = root.o("state")
        assertEquals(inner.keys.sorted(), inner.keys.toList())
        assertEquals(inner.o("slime").keys.sorted(), inner.o("slime").keys.toList())
        assertTrue(text.contains("\n  \""))
        // Back again, the same game.
        val back = SaveFile.decode(text.toByteArray(), now)
        assertEquals(state.slime, back.slime)
        assertEquals(state.wallet, back.wallet)
        assertEquals(state.inventory, back.inventory)
        assertEquals(state.progress, back.progress)
    }

    // MARK: (c) Care Together through the server (ISO 8601)

    @Test
    fun testReadsSnapshotsAndEventsFromTheServer() {
        val root = SlimyJson.api.parseToJsonElement(Fixtures.text("care-api.json")).jsonObject
        val snapshot = SlimyJson.api.decodeFromJsonElement(SlimeSnapshot.serializer(), root["snapshot"]!!)
        assertEquals("Blob", snapshot.name)
        assertEquals(Skin.axolotl, snapshot.skin)
        assertEquals(5, snapshot.level)
        assertEquals(620, snapshot.xp)
        assertEquals(88.25, snapshot.needs.energy, 0.0)
        assertEquals("hat.crown", snapshot.equipped.hat)
        assertEquals(0.45, snapshot.emotions[Emotion.joy], 0.0)
        assertEquals(Instant.parse("2025-12-24T08:00:00Z"), snapshot.hatchedAt)
        assertEquals(Instant.parse("2026-05-04T10:00:00Z"), snapshot.updatedAt)
        assertEquals(Instant.parse("2026-05-04T09:55:00Z"), snapshot.takenAt)
        assertEquals(setOf(Skin.axolotl, Skin.grape), snapshot.colours)
        assertEquals("9E8D7C6B-5A49-4837-A625-14F3E2D1C0B9", snapshot.author)

        val events = SlimyJson.api.decodeFromJsonElement(ListSerializer(CareEvent.serializer()), root["events"]!!)
        assertEquals(listOf(CareKind.feed, CareKind.play, CareKind.pet), events.map { it.kind })
        assertEquals(Instant.parse("2026-05-04T09:00:00Z"), events[0].at)
        assertEquals(11.5, events[2].amount!!, 0.0)
        assertEquals("Your partner petted Blob", events[2].summary(slime = "Blob"))
    }

    @Test
    fun testWritesSnapshotsAndEventsForTheServer() {
        val root = SlimyJson.api.parseToJsonElement(Fixtures.text("care-api.json")).jsonObject
        val snapshot = SlimyJson.api.decodeFromJsonElement(SlimeSnapshot.serializer(), root["snapshot"]!!)
        val out = SlimyJson.api.encodeToJsonElement(SlimeSnapshot.serializer(), snapshot).jsonObject
        assertEquals("2026-05-04T10:00:00Z", out.string("updatedAt"))
        assertEquals("2025-12-24T08:00:00Z", out.string("hatchedAt"))
        assertEquals("2026-05-04T09:55:00Z", out.string("takenAt"))
        assertEquals(setOf("axolotl", "grape"), (out["colours"] as JsonArray).map { it.jsonPrimitive.content }.toSet())
        assertTrue(out.o("needs").containsKey("fun"))
        assertFalse(out.o("equipped").containsKey("scarf"))
        assertEquals(snapshot, SlimyJson.api.decodeFromJsonElement(SlimeSnapshot.serializer(), out))

        // No author: the key is left out.
        val anonymous = snapshot.copy(author = null)
        assertFalse(SlimyJson.api.encodeToJsonElement(SlimeSnapshot.serializer(), anonymous).jsonObject.containsKey("author"))

        // Swift's .iso8601 can't read fractions, so they're never written.
        val precise = CareEvent(
            id = "x", kind = CareKind.bath, by = "Ben", at = Instant.parse("2026-05-04T10:00:00.987654321Z"),
        )
        val event = SlimyJson.api.encodeToJsonElement(CareEvent.serializer(), precise).jsonObject
        assertEquals("2026-05-04T10:00:00Z", event.string("at"))
        assertFalse(event.containsKey("food"))
        assertFalse(event.containsKey("game"))
        assertFalse(event.containsKey("amount"))
        assertEquals("bath", event.string("kind"))
    }

    @Test
    fun testTheSameSlimeSnapshotFromAGameState() {
        val state = decodeSave(Fixtures.text("save-v4.json"))
        val snapshot = state.sharedSnapshot(Instant.parse("2026-05-04T10:30:00Z"))
        val text = SlimyJson.api.encodeToString(SlimeSnapshot.serializer(), snapshot)
        assertEquals(snapshot, SlimyJson.api.decodeFromString(SlimeSnapshot.serializer(), text))
        assertEquals(Instant.parse("2026-05-04T09:00:00Z"), snapshot.updatedAt)
    }

    // MARK: (d) The daily plan

    @Test
    fun testReadsAndWritesDailyPlans() {
        val json = SlimyJson.storage
        val plans = json.decodeFromString(ListSerializer(DailyPlan.serializer()), Fixtures.text("daily-plans.json"))
        assertEquals(2, plans.size)
        val first = plans[0]
        assertEquals("2026-05-04", first.day)
        assertEquals(GameControl.drag, first.game.control)
        assertEquals(GameGoal.timed, first.game.goal)
        assertEquals(GameTheme.day, first.game.theme)
        assertEquals(PowerKind.shield, first.game.entities[2].power)
        assertNull(first.game.entities[0].power)
        assertEquals(DailyQuest.Goal.stat(StatKey.feeds, 3), first.quest.goal)
        val second = plans[1]
        assertEquals(DailyQuest.Goal.dailyScore(120), second.quest.goal)
        assertEquals("ai", second.source)
        assertEquals(GameControl.flap, second.game.control)

        val out = json.encodeToJsonElement(ListSerializer(DailyPlan.serializer()), plans).jsonArray
        val statGoal = out[0].jsonObject.o("quest").o("goal")
        assertEquals("stat", statGoal.string("kind"))
        assertEquals("feeds", statGoal.string("stat"))
        assertEquals("3", statGoal.number("target"))
        val scoreGoal = out[1].jsonObject.o("quest").o("goal")
        assertEquals("dailyScore", scoreGoal.string("kind"))
        assertFalse(scoreGoal.containsKey("stat"))
        assertEquals("120", scoreGoal.number("target"))
        val entities = out[0].jsonObject.o("game")["entities"]!!.jsonArray
        assertFalse(entities[0].jsonObject.containsKey("power"))
        assertEquals("shield", entities[2].jsonObject.string("power"))
        assertEquals(plans, json.decodeFromJsonElement(ListSerializer(DailyPlan.serializer()), out))
    }

    // MARK: Tolerant decoding, like Swift's decodeIfPresent + defaults

    @Test
    fun testUnknownKeysAndValuesFromANewerApp() {
        val json = """
        {"version": 4, "hatched": true, "fromTheFuture": {"a": 1},
         "slime": {"name": "Blub", "skin": "rainbow", "reaction": "dance", "colours": ["axolotl", "sparkly", "grape"],
                   "lastEvent": "earthquake", "needs": {"hunger": 40, "fun": 41, "energy": 42, "clean": 43, "love": 44, "thirst": 9}},
         "inventory": {"owned": ["hat.party"], "foodsTasted": ["apple", "pizza"], "habitat": "volcano",
                       "furniture": ["floorLeft", "furn.bed"]},
         "progress": {"records": ["bubblePop", {"best": 3, "plays": 1, "tutorialSeen": true}, "laserTag", {"best": 1, "plays": 1, "tutorialSeen": true}],
                      "stats": {"feeds": 4}},
         "memory": {"entries": [
           {"id": "7D3D2A1E-8B9A-4C7E-9C51-2E9B4F0A1B2C", "kind": "hatched", "at": 1767225600, "detail": "Blub", "keepsake": true},
           {"id": "not-a-uuid", "kind": "fed", "at": 1767225600, "detail": "apple", "keepsake": false},
           {"id": "1A2B3C4D-1111-2222-3333-444455556666", "kind": "fed", "detail": "apple", "keepsake": false},
           {"id": "2B3C4D5E-1111-2222-3333-444455556666", "kind": "fed", "at": 1767225600, "detail": "apple", "keepsake": false}]},
         "care": {"partnerID": "dev_x", "partnerName": "X", "role": "observer", "version": 1},
         "careBook": {"me": "ME", "changedAt": 1767225600, "outbox": [
           {"id": "a", "kind": "feed", "by": "X", "at": 1767225600},
           {"id": "b", "kind": "dance", "by": "X", "at": 1767225600},
           {"id": "c", "kind": "pet", "amount": "lots", "by": "X", "at": 1767225600},
           {"kind": "pet", "by": "X", "at": 1767225600}],
           "coloursFound": ["grape", "sparkly"]}}
        """
        val state = decodeSave(json)
        // Unknown single values fall back to their default.
        assertEquals(Skin.lime, state.slime.skin)
        assertEquals(Reaction.none, state.slime.reaction)
        assertNull(state.slime.lastEvent)
        assertEquals(Habitat.room, state.inventory.habitat)
        assertEquals(44.0, state.slime.needs.love, 0.0)
        // Colours keep the ones this version knows.
        assertEquals(setOf(Skin.axolotl, Skin.grape), state.slime.colours)
        assertEquals(setOf(Skin.grape), state.careBook.coloursFound)
        // A set or dictionary with an unknown member falls back whole, like Swift's.
        assertTrue(state.inventory.foodsTasted.isEmpty())
        assertTrue(state.progress.records.isEmpty())
        assertEquals(setOf("hat.party"), state.inventory.owned)
        assertEquals(4, state.progress.stats.feeds)
        // One damaged memory entry or care event never costs the others.
        assertEquals(2, state.memory.entries.size)
        assertEquals(UUID.fromString("2B3C4D5E-1111-2222-3333-444455556666"), state.memory.entries[1].id)
        assertEquals(listOf("a", "c"), state.careBook.outbox.map { it.id })
        assertNull(state.careBook.outbox[1].amount)
        // An unknown role is read from whether there is a backup.
        assertEquals(CareLink.host, state.care?.role)
    }

    @Test
    fun testDamagedPartsFallBackAlone() {
        val json = """
        {"version": "four", "hatched": true,
         "slime": {"name": "Blub", "xp": "many", "needs": {"hunger": "full"}, "personality": [1, 2], "say": {"text": "hi"}},
         "player": {"name": 7, "xp": 50},
         "wallet": {"coins": 12, "ledger": {"level:2": "yesterday"}},
         "progress": {"stats": "x", "achievements": {"firstMeal": 1767225600}, "challenges": 5, "quest": {"best": 3}},
         "settings": {"sound": false, "notifications": {"quietFrom": "late"}, "sleep": {"bedtime": 21}},
         "streak": [], "crates": ["golden"], "care": {"partnerID": ""}, "careBook": {"seen": "x"}}
        """
        val state = decodeSave(json)
        val d = GameState()
        assertEquals(GameState.currentVersion, state.version)
        assertEquals("Blub", state.slime.name)
        assertEquals(0, state.slime.xp)
        assertEquals(Needs(), state.slime.needs)
        assertEquals(Personality.neutral, state.slime.personality)
        assertNull(state.slime.say)
        assertEquals("", state.player.name)
        assertEquals(50, state.player.xp)
        assertEquals(12, state.wallet.coins)
        assertTrue(state.wallet.ledger.isEmpty())
        assertEquals(Stats(), state.progress.stats)
        assertNotNull(state.progress.achievements["firstMeal"])
        assertEquals(ChallengeBoard(), state.progress.challenges)
        assertNull(state.progress.quest)
        assertFalse(state.settings.sound)
        assertEquals(d.settings.notifications.quietFrom, state.settings.notifications.quietFrom)
        assertEquals(21, state.settings.sleep.bedtime)
        assertEquals(StreakState(), state.streak)
        assertTrue(state.crates.isEmpty())
        assertNull(state.care)
        assertTrue(state.careBook.seen.isEmpty())
        assertFalse(state.careBook.me.isEmpty())
    }

    @Test
    fun testNullsReadAsMissing() {
        val json = """
        {"version": 4, "hatched": true, "slime": {"name": "Blub", "say": null, "lastEvent": null, "skin": null},
         "care": null, "progress": {"quest": null}}
        """
        val state = decodeSave(json)
        assertEquals("Blub", state.slime.name)
        assertNull(state.slime.say)
        assertEquals(Skin.lime, state.slime.skin)
        assertNull(state.care)
        assertNull(state.progress.quest)
    }

    @Test
    fun testDatesInEitherFormReadInBothConfigurations() {
        val seconds = """{"text": "hi", "at": 1777888800.5}"""
        val iso = """{"text": "hi", "at": "2026-05-04T10:00:00Z"}"""
        val half = Instant.parse("2026-05-04T10:00:00.500Z")
        val whole = Instant.parse("2026-05-04T10:00:00Z")
        for (json in listOf(SlimyJson.storage, SlimyJson.api)) {
            assertEquals(half, json.decodeFromString(Utterance.serializer(), seconds).at)
            assertEquals(whole, json.decodeFromString(Utterance.serializer(), iso).at)
        }
        val u = Utterance("hi", half)
        assertEquals("1777888800.5", SlimyJson.storage.encodeToJsonElement(Utterance.serializer(), u).jsonObject.number("at"))
        assertEquals("2026-05-04T10:00:00Z", SlimyJson.api.encodeToJsonElement(Utterance.serializer(), u).jsonObject.string("at"))
    }
}
