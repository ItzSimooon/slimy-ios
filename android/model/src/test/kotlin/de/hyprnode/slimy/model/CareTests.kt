package de.hyprnode.slimy.model

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.UUID

private val berlin = SlimyCalendar.gregorian("Europe/Berlin")

/** 4 May 2026, noon in Berlin: no season, holiday or birthday. */
private val noon: Instant = berlin.date(2026, 5, 4, 12)

private fun at(seconds: Double): Instant = noon.addingTimeInterval(seconds)
private fun at(seconds: Int): Instant = at(seconds.toDouble())

/** Anna's phone: Blob is the slime that gets shared. */
private fun annasPhone(): GameState {
    val state = GameState()
    state.hatch(name = "Blob", skin = Skin.ocean, seed = 7uL, now = noon)
    state.settings.sleep.auto = false
    state.player.name = "Anna"
    return state
}

/** Ben's phone: his own slime Mochi waits while he cares for Blob. */
private fun bensPhone(): GameState {
    val state = GameState()
    state.hatch(name = "Mochi", skin = Skin.lime, seed = 9uL, now = noon.addingTimeInterval(-86400.0))
    state.settings.sleep.auto = false
    state.player.name = "Ben"
    state.wallet.coins = 250
    return state
}

private val annasLink get() = CareLink(partnerID = "dev_ben", partnerName = "Ben", role = CareLink.host, cursor = null, version = 1)
private val bensLink get() = CareLink(partnerID = "dev_anna", partnerName = "Anna", role = CareLink.joiner, cursor = null, version = 1)

/** Anna hosts, Ben joined with her slime. */
private fun paired(): Pair<GameState, GameState> {
    val anna = annasPhone()
    anna.startHosting(annasLink, at(60))
    val ben = bensPhone()
    ben.adoptShared(anna.sharedSnapshot(at(60)), bensLink, at(120))
    return anna to ben
}

private fun event(id: String, kind: CareKind, food: Food? = null, game: GameID? = null, amount: Double? = null, by: String, at: Instant) =
    CareEvent(id = id, kind = kind, food = food, game = game, amount = amount, by = by, at = at)

class CareActionTests {
    @Test
    fun testLocalCareIsOnlySentWhileSharing() {
        val state = annasPhone()
        state.pet(at(30))
        assertTrue(state.careBook.outbox.isEmpty())
        assertFalse(state.isSharing)

        state.startHosting(annasLink, at(60))
        assertTrue(state.isSharing)
        assertEquals("Ben", state.care?.partnerName)
        assertTrue(state.progress.achievements["careTogether"] != null)
        state.slime.needs.hunger = 40.0
        assertTrue(state.feed(Food.apple, at(180)).ok)
        assertTrue(state.pet(at(240)).ok)
        assertTrue(state.finish(GameResult(game = GameID.bubblePop, score = 200, session = UUID.randomUUID(), finishedAt = at(300))).ok)
        state.slime.needs.clean = 20.0
        assertTrue(state.bath(at(360)).ok)
        state.slime.needs.energy = 40.0
        assertTrue(state.sleep(at(420)).ok)
        assertTrue(state.wake(at(480)).ok)
        assertTrue(state.claimDailyGift(at(540), berlin) != null)

        val sent = state.takeCareOutbox()
        assertEquals(
            listOf(CareKind.feed, CareKind.pet, CareKind.play, CareKind.bath, CareKind.sleep, CareKind.wake, CareKind.gift),
            sent.map { it.kind },
        )
        assertEquals(Food.apple, sent[0].food)
        assertTrue((sent[1].amount ?: 0.0) > 0)
        assertEquals(GameID.bubblePop, sent[2].game)
        assertEquals(50.0, sent[2].amount ?: 0.0, 0.001)
        assertTrue(sent.all { it.by == "Anna" })
        assertEquals(sent.size, sent.map { it.id }.toSet().size)
        assertTrue(state.careBook.outbox.isEmpty())
        // Failed actions send nothing.
        state.slime.needs.hunger = 99.0
        assertFalse(state.feed(Food.apple, at(600)).ok)
        assertTrue(state.careBook.outbox.isEmpty())
    }

    @Test
    fun testTodaysGameIsSentWithoutAGame() {
        val state = annasPhone()
        state.startHosting(annasLink, at(60))
        state.finish(
            GameResult(game = GameID.daily, score = 50, session = UUID.randomUUID(), finishedAt = at(120), target = 100, day = "2026-05-04"),
        )
        val sent = state.takeCareOutbox()
        assertEquals(listOf(CareKind.play), sent.map { it.kind })
        assertNull(sent[0].game)
    }

    @Test
    fun testPartnerCareAppliesWithoutCoins() {
        val state = annasPhone()
        state.startHosting(annasLink, at(60))
        state.slime.needs.hunger = 30.0
        state.slime.emotions = Emotions()
        val coins = state.wallet.coins
        val feeds = state.progress.stats.feeds
        val xp = state.slime.xp
        val fed = event("e1", CareKind.feed, food = Food.carrot, by = "Ben", at = at(100))
        assertEquals("Ben fed Blob", state.applyPartnerCare(fed, at(120)))
        assertTrue(state.slime.needs.hunger > 45)
        assertEquals(coins, state.wallet.coins)
        assertEquals(feeds, state.progress.stats.feeds)
        assertEquals(1, state.progress.stats.partnerCare)
        assertEquals(xp + 6, state.slime.xp)
        assertTrue(state.slime.emotions[Emotion.love] > 0.1)
        assertTrue(state.slime.emotions[Emotion.joy] > 0.4)
        assertEquals("Ben fed Blob", state.memory.last(MemoryEntry.Kind.partnerCare)?.summary)
        assertTrue(state.memory.recentLines.lastOrNull()?.startsWith("partnerCared.") ?: false)
        assertTrue("a partner's action is never sent back", state.careBook.outbox.isEmpty())
        assertTrue(state.chatterTopics(at(180), berlin).map { it.category }.contains(LineCategory.partnerCared))

        // The same action again changes nothing.
        val hunger = state.slime.needs.hunger
        assertNull(state.applyPartnerCare(fed, at(130)))
        assertEquals(hunger, state.slime.needs.hunger, 0.0)
        assertEquals(1, state.progress.stats.partnerCare)
    }

    @Test
    fun testEveryKindOfPartnerCare() {
        val state = annasPhone()
        state.startHosting(annasLink, at(60))
        state.slime.needs.`fun` = 20.0
        state.applyPartnerCare(event("p", CareKind.play, game = GameID.bubblePop, amount = 100.0, by = "Ben", at = at(100)), at(100))
        assertTrue(state.slime.needs.`fun` > 40)

        state.slime.needs.clean = 10.0
        state.applyPartnerCare(event("b", CareKind.bath, by = "Ben", at = at(110)), at(110))
        assertEquals(100.0, state.slime.needs.clean, 0.001)

        state.slime.needs.love = 30.0
        state.applyPartnerCare(event("pe", CareKind.pet, amount = 12.0, by = "Ben", at = at(120)), at(120))
        assertEquals(42.0, state.slime.needs.love, 0.5)

        assertEquals("Ben put Blob to bed", state.applyPartnerCare(event("s", CareKind.sleep, by = "Ben", at = at(130)), at(130)))
        assertTrue(state.slime.isAsleep)
        state.applyPartnerCare(event("w", CareKind.wake, by = "Ben", at = at(140)), at(140))
        assertFalse(state.slime.isAsleep)

        // A bedtime long past is old news.
        assertNull(state.applyPartnerCare(event("old", CareKind.sleep, by = "Ben", at = at(-3 * 3600)), at(150)))
        assertFalse(state.slime.isAsleep)

        val love = state.slime.needs.love
        assertEquals("Ben gave Blob a present", state.applyPartnerCare(event("g", CareKind.gift, by = "", at = at(160)), at(160)))
        assertTrue(state.slime.needs.love > love)
        assertEquals(6, state.progress.stats.partnerCare)
    }

    @Test
    fun testNothingAppliesWhenNotSharing() {
        val state = annasPhone()
        val e = event("x", CareKind.feed, food = Food.apple, by = "Ben", at = at(10))
        assertNull(state.applyPartnerCare(e, at(20)))
        assertEquals(0, state.progress.stats.partnerCare)
    }
}

class CareSharingTests {
    @Test
    fun testAdoptAndRestoreRoundTrip() {
        val anna = annasPhone()
        anna.startHosting(annasLink, at(60))
        val snapshot = anna.sharedSnapshot(at(60))
        assertEquals("Blob", snapshot.name)
        assertEquals(anna.careBook.me, snapshot.author)
        assertEquals(anna.slime.level, snapshot.level)

        val ben = bensPhone()
        val coins = ben.wallet.coins
        ben.adoptShared(snapshot, bensLink, at(120))
        assertTrue(ben.isSharing)
        assertEquals("Blob", ben.slime.name)
        assertEquals(Skin.ocean, ben.slime.skin)
        assertEquals(anna.slime.xp, ben.slime.xp)
        assertEquals(anna.slime.personality, ben.slime.personality)
        assertEquals(anna.slime.favorite, ben.slime.favorite)
        assertEquals(anna.slime.birthday, ben.slime.birthday)
        val backup = requireNotNull(ben.care?.ownBackup).deepCopy()
        assertEquals("Mochi", backup.name)
        assertTrue(ben.wallet.coins >= coins)
        assertEquals("Anna", ben.memory.last(MemoryEntry.Kind.careStarted)?.detail)
        assertNotEquals(anna.careBook.me, ben.careBook.me)

        // Three days later Ben stops: Mochi is back as it was left.
        ben.stopSharing(at(3 * 86400))
        assertFalse(ben.isSharing)
        assertEquals("Mochi", ben.slime.name)
        assertEquals(backup.xp, ben.slime.xp)
        assertEquals(backup.needs, ben.slime.needs)
        assertEquals(backup.personality, ben.slime.personality)
        assertEquals("Anna", ben.memory.last(MemoryEntry.Kind.careEnded)?.detail)
        assertEquals(CareBook(me = ben.careBook.me), ben.careBook)

        // Anna keeps her slime.
        anna.stopSharing(at(200))
        assertNull(anna.care)
        assertEquals("Blob", anna.slime.name)
    }

    @Test
    fun testJoiningAgainKeepsTheFirstBackup() {
        val (_, ben) = paired()
        val other = annasPhone().sharedSnapshot(at(300))
        ben.adoptShared(other, bensLink, at(400))
        assertEquals("Mochi", requireNotNull(ben.care?.ownBackup).name)
    }

    @Test
    fun testAColourFoundWhileSharingComesHome() {
        val (_, ben) = paired()
        ben.receive(Crates.legendary(ben))
        assertEquals(Skin.axolotl, ben.slime.skin)
        ben.stopSharing(at(500))
        assertEquals("Mochi", ben.slime.name)
        assertTrue(ben.slime.isUnlocked(Skin.axolotl))
        assertEquals(Skin.lime, ben.slime.skin)
    }

    @Test
    fun testActionsAlreadyInTheSnapshotAreSkipped() {
        val anna = annasPhone()
        anna.startHosting(annasLink, at(60))
        anna.slime.needs.hunger = 30.0
        assertTrue(anna.feed(Food.apple, at(100)).ok)
        val fed = anna.takeCareOutbox()[0]
        val snapshot = anna.sharedSnapshot(at(110))
        assertEquals(at(100), snapshot.updatedAt)

        val ben = bensPhone()
        ben.adoptShared(snapshot, bensLink, at(120))
        val hunger = ben.slime.needs.hunger
        assertNull(ben.applyPartnerCare(fed, at(130)))
        assertEquals(hunger, ben.slime.needs.hunger, 0.0)

        val later = event("later", CareKind.pet, amount = 10.0, by = "Anna", at = at(200))
        assertEquals("Anna petted Blob", ben.applyPartnerCare(later, at(210)))
    }

    @Test
    fun testReconcileTakesOnlyANewerSnapshot() {
        val (anna, ben) = paired()
        // Anna renames Blob and feeds it: her snapshot knows something newer.
        anna.slime.name = "Blobby"
        anna.noteSharedChange(at(300))
        anna.slime.needs.hunger = 30.0
        assertTrue(anna.feed(Food.apple, at(400)).ok)
        val newer = anna.sharedSnapshot(at(400))

        ben.slime.emotions = Emotions()
        ben.slime.emotions[Emotion.sadness] = 0.8
        assertTrue(ben.reconcile(newer, at(500)))
        assertEquals("Blobby", ben.slime.name)
        assertEquals(anna.slime.xp, ben.slime.xp)
        // Feelings are blended, not replaced.
        assertTrue(ben.slime.emotions[Emotion.sadness] > 0.2)
        assertTrue(ben.slime.emotions[Emotion.sadness] < 0.7)

        // The same snapshot again is old news.
        assertFalse(ben.reconcile(newer, at(600)))

        // A change here after it: the older snapshot doesn't undo it.
        ben.slime.name = "Mine"
        ben.noteSharedChange(at(700))
        assertFalse(ben.reconcile(newer, at(800)))
        assertEquals("Mine", ben.slime.name)

        // A snapshot this phone wrote itself is skipped.
        val own = ben.sharedSnapshot(at(700))
        val copy = ben.deepCopy()
        copy.careBook.changedAt = DistantPast
        assertFalse(copy.reconcile(own, at(900)))
    }

    @Test
    fun testReconcileNeverLowersWhatOnlyGrows() {
        val (anna, ben) = paired()
        ben.slime.xp = 500
        ben.slime.bond = 70.0
        ben.slime.colours = ben.slime.colours + Skin.grape
        anna.noteSharedChange(at(300))
        assertTrue(ben.reconcile(anna.sharedSnapshot(at(300)), at(310)))
        assertEquals(500, ben.slime.xp)
        assertEquals(70.0, ben.slime.bond, 0.0)
        assertTrue(ben.slime.colours.contains(Skin.grape))
    }

    @Test
    fun testASharedSlimeKeepsWhatThePartnerDressedItIn() {
        val anna = annasPhone()
        anna.inventory.owned = anna.inventory.owned + "hat.crown"
        anna.equip("hat.crown", at(10))
        anna.startHosting(annasLink, at(60))
        val ben = bensPhone()
        ben.adoptShared(anna.sharedSnapshot(at(60)), bensLink, at(120))
        assertEquals("hat.crown", ben.slime.equipped.hat)
        ben.sanitize(at(130))
        assertEquals("hat.crown", ben.slime.equipped.hat)
        // Back on his own, Ben's slime wears only Ben's things.
        ben.stopSharing(at(200))
        assertNull(ben.slime.equipped.hat)
    }

    @Test
    fun testOnlyPersonalThingsStayPersonal() {
        val (_, ben) = paired()
        val coins = ben.wallet.coins
        val owned = ben.inventory.owned
        val records = ben.progress.records
        ben.applyPartnerCare(event("f", CareKind.feed, food = Food.berry, by = "Anna", at = at(300)), at(300))
        assertEquals(coins, ben.wallet.coins)
        assertEquals(owned, ben.inventory.owned)
        assertEquals(records, ben.progress.records)
    }
}

class CareCodingTests {
    @Test
    fun testSnapshotsSurviveTheTrip() {
        val (anna, _) = paired()
        anna.slime.colours = anna.slime.colours + Skin.axolotl
        val snapshot = anna.sharedSnapshot(at(60))
        val api = SlimyJson.api
        val text = api.encodeToString(SlimeSnapshot.serializer(), snapshot)
        val back = api.decodeFromString(SlimeSnapshot.serializer(), text)
        assertEquals(snapshot, back)
        val obj = api.parseToJsonElement(text).jsonObject
        assertEquals("Blob", obj["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun testSnapshotsFromOtherVersionsStillRead() {
        val api = SlimyJson.api
        val json = """
        {"name": "Blob", "updatedAt": "2026-05-04T10:00:00Z", "skin": "rainbow", "xp": 130,
         "needs": {"hunger": 50, "fun": 50, "energy": 50, "clean": 50, "love": 50},
         "colours": ["axolotl", "sparkly"], "favorite": "pizza", "somethingNew": true}
        """
        val odd = api.decodeFromString(SlimeSnapshot.serializer(), json)
        assertEquals(Skin.lime, odd.skin)
        assertEquals(130, odd.xp)
        assertEquals(Slime.level(forXP = 130), odd.level)
        assertEquals(setOf(Skin.axolotl), odd.colours)
        assertEquals(Slime().favorite, odd.favorite)
        assertEquals(odd.updatedAt, odd.takenAt)
        assertNull(odd.author)
        // Without a name or a time it isn't a snapshot.
        assertThrows { api.decodeFromString(SlimeSnapshot.serializer(), """{"xp": 3}""") }
        assertThrows { api.decodeFromString(SlimeSnapshot.serializer(), """{"name": "Blob"}""") }
    }

    @Test
    fun testCareEventsReadTolerantly() {
        val api = SlimyJson.api
        val json = """{"id": "e1", "kind": "feed", "food": "pizza", "amount": 12, "at": "2026-05-04T10:00:00Z"}"""
        val e = api.decodeFromString(CareEvent.serializer(), json)
        assertEquals(CareKind.feed, e.kind)
        assertNull(e.food)
        assertEquals(12.0, e.amount!!, 0.0)
        assertEquals("", e.by)
        assertEquals("Your partner fed Blob", e.summary(slime = "Blob"))
        assertThrows {
            api.decodeFromString(CareEvent.serializer(), """{"id": "e2", "kind": "dance", "at": "2026-05-04T10:00:00Z"}""")
        }
    }

    @Test
    fun testCaringTogetherSurvivesASaveRoundTrip() {
        val (_, ben) = paired()
        ben.pet(at(300))
        assertEquals(1, ben.careBook.outbox.size)
        val back = requireNotNull(GameStorage.decode(GameStorage.encode(ben)))
        assertEquals(ben.care, back.care)
        assertEquals(ben.careBook, back.careBook)
        assertEquals(ben.slime, back.slime)
    }

    @Test
    fun testAnImportedSaveLeavesCaringTogetherBehind() {
        val (_, ben) = paired()
        ben.pet(at(300))
        val data = SaveFile.encode(ben, at(400), appVersion = "1.1.0")
        val back = SaveFile.decode(data.toByteArray(), at(500))
        assertNull(back.care)
        assertEquals("Mochi", back.slime.name)
        assertTrue(back.careBook.outbox.isEmpty())
        assertNull(back.memory.last(MemoryEntry.Kind.careEnded))
    }

    @Test
    fun testPartnerNotesReadBack() {
        val note = PartnerCareNote(person = "An|na", kind = CareKind.bath, slime = "Blob")
        assertEquals(note, PartnerCareNote.parse(note.detail))
        assertEquals("An/na gave Blob a bath", note.summary)
        assertNull(PartnerCareNote.parse("Anna|dance|Blob"))
        assertEquals(CareKind.bath.icon, MemoryEntry(kind = MemoryEntry.Kind.partnerCare, at = noon, detail = note.detail).icon)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            return
        }
        fail("expected a failure")
    }
}
