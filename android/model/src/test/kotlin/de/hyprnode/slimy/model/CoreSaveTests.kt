package de.hyprnode.slimy.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

private val berlin = SlimyCalendar.gregorian("Europe/Berlin")
private val noon: Instant = berlin.date(2026, 5, 4, 12)

/** A slime with a bit of history: a gift streak, a purchase, a record. */
private fun livedInState(): GameState {
    val state = GameState()
    state.hatch(name = "Mochi", skin = Skin.ocean, seed = 7uL, now = noon.addingTimeInterval(-3.0 * 86400))
    state.settings.sleep.auto = false
    for (back in (0..2).reversed()) {
        state.claimDailyGift(noon.addingTimeInterval(-back.toDouble() * 86400), berlin)
    }
    state.finish(GameResult(game = GameID.bubblePop, score = 420, session = UUID.randomUUID(), finishedAt = noon))
    Catalog.item("hat.party")?.let { state.buy(it, noon) }
    return state
}

private fun problemOf(block: () -> Unit): SaveFile.Problem? = try {
    block()
    null
} catch (e: SaveFile.ProblemException) {
    e.problem
}

class CoreSaveTests {
    private lateinit var folder: File

    @Before
    fun setUp() {
        folder = Files.createTempDirectory("slimy-tests-").toFile()
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    @Test
    fun testExportImportRoundTrip() {
        val state = livedInState()
        val data = SaveFile.encode(state, noon, appVersion = "1.0.0")
        val back = SaveFile.decode(data.toByteArray(), noon)
        assertTrue(back.hatched)
        assertEquals(GameState.currentVersion, back.version)
        assertEquals("Mochi", back.slime.name)
        assertEquals(Skin.ocean, back.slime.skin)
        assertEquals(state.slime.xp, back.slime.xp)
        assertEquals(state.slime.needs, back.slime.needs)
        assertEquals(state.slime.personality, back.slime.personality)
        assertEquals(state.wallet, back.wallet)
        assertEquals(state.inventory, back.inventory)
        assertEquals(state.progress, back.progress)
        assertEquals(state.streak, back.streak)
        assertEquals(state.memory.keepsakes, back.memory.keepsakes)
        assertEquals(3, back.streak.count)
        assertTrue(back.owns("hat.party"))
    }

    // iOS writes and reads the file itself in SaveFile; on Android the file handling lives in the
    // app (GameStore), so the test writes the encoded text to the file name the model gives.
    @Test
    fun testWritesAFileThatReadsBack() {
        val state = livedInState()
        val file = File(folder, SaveFile.fileName(state, noon, berlin))
        file.writeText(SaveFile.encode(state, noon, appVersion = "1.0.0"))
        assertEquals(SaveFile.fileExtension, file.extension)
        assertEquals("Slimy-Mochi-2026-05-04.slimysave", file.name)
        assertTrue(file.exists())
        val back = SaveFile.decode(file.readBytes(), noon)
        assertEquals(state.wallet, back.wallet)
        assertEquals(state.streak, back.streak)
        // Plain JSON with a small header, readable by anyone curious.
        val obj = SlimyJson.storage.parseToJsonElement(file.readText()).jsonObject
        assertEquals(SaveFile.format, obj["format"]?.jsonPrimitive?.content)
        assertEquals(SaveFile.schema, obj["schema"]?.jsonPrimitive?.int)
        assertEquals("1.0.0", obj["appVersion"]?.jsonPrimitive?.content)
    }

    @Test
    fun testFileNamesAreSafe() {
        val state = livedInState()
        state.slime.name = "Mö chi/../!"
        assertEquals("Slimy-Mö-chi-2026-05-04.slimysave", SaveFile.fileName(state, noon, berlin))
        state.slime.name = "!!!"
        assertEquals("Slimy-Slime-2026-05-04.slimysave", SaveFile.fileName(state, noon, berlin))
    }

    @Test
    fun testRejectsFilesFromTheFuture() {
        val newer = livedInState()
        newer.version = GameState.currentVersion + 1
        val data = SaveFile.encode(newer, noon, appVersion = "9.0")
        assertEquals(SaveFile.Problem.newerVersion, problemOf { SaveFile.decode(data.toByteArray(), noon) })

        val current = SaveFile.encode(livedInState(), noon, appVersion = "1.0.0")
        val obj = SlimyJson.storage.parseToJsonElement(current).jsonObject
        val future = JsonObject(obj + ("schema" to JsonPrimitive(SaveFile.schema + 1))).toString()
        assertEquals(SaveFile.Problem.newerVersion, problemOf { SaveFile.decode(future.toByteArray(), noon) })
    }

    @Test
    fun testRejectsWhatIsNotASave() {
        val cases = listOf(
            """{"hello": 1}""" to SaveFile.Problem.notASave,
            """{"format": "something.else", "schema": 1, "state": {}}""" to SaveFile.Problem.notASave,
            "not json at all" to SaveFile.Problem.unreadable,
            "[1, 2, 3]" to SaveFile.Problem.unreadable,
            """{"format": "de.hyprnode.slimy.save", "schema": 1, "state": 5}""" to SaveFile.Problem.unreadable,
            """{"format": "de.hyprnode.slimy.save", "schema": 1}""" to SaveFile.Problem.unreadable,
            """{"format": "de.hyprnode.slimy.save", "schema": 0, "state": {}}""" to SaveFile.Problem.unreadable,
            """{"format": "de.hyprnode.slimy.save", "schema": 1, "state": {"hatched": false}}""" to SaveFile.Problem.noSlime,
        )
        for ((json, expected) in cases) {
            assertEquals(json, expected, problemOf { SaveFile.decode(json.toByteArray(), noon) })
        }
        assertEquals(SaveFile.Problem.tooLarge, problemOf { SaveFile.decode(ByteArray(SaveFile.maxBytes + 1), noon) })
        // iOS also checks reading a missing file; reading files is the app's job on Android.
    }

    @Test
    fun testImportRepairsDamagedValues() {
        val json = """
        {"format": "de.hyprnode.slimy.save", "schema": 1, "exportedAt": 1777896000, "appVersion": "1.0.0",
         "state": {"version": 2, "hatched": true,
                   "wallet": {"coins": -50},
                   "slime": {"name": "Blub", "needs": {"hunger": 900, "fun": -3, "energy": 50, "clean": 50, "love": 50}},
                   "streak": {"count": 4, "best": 2, "opened": 4}}}
        """
        val state = SaveFile.decode(json.toByteArray(), noon)
        assertEquals(GameState.currentVersion, state.version)
        assertEquals("Blub", state.slime.name)
        assertEquals(0, state.wallet.coins)
        assertEquals(100.0, state.slime.needs.hunger, 0.0)
        assertEquals(0.0, state.slime.needs.`fun`, 0.0)
        assertEquals(4, state.streak.best)
    }

    @Test
    fun testProblemsExplainThemselves() {
        for (problem in SaveFile.Problem.entries) {
            assertFalse(problem.errorDescription.isEmpty())
            assertTrue(SaveFile.ProblemException(problem).message!!.endsWith("."))
        }
    }

    @Test
    fun testWidgetSnapshotCarriesTheBedtime() {
        val state = livedInState()
        state.settings.sleep.bedtime = 21
        val snapshot = WidgetSnapshot.of(state, noon)
        assertEquals(21, snapshot.sleep?.bedtime)

        // Snapshots written before the bedtime was in them still load.
        val data = SlimyJson.storage.encodeToString(WidgetSnapshot.serializer(), snapshot)
        val obj = SlimyJson.storage.parseToJsonElement(data).jsonObject
        val old = JsonObject(obj - "sleep").toString()
        val back = SlimyJson.storage.decodeFromString(WidgetSnapshot.serializer(), old)
        assertNull(back.sleep)
        assertEquals(snapshot.name, back.name)
    }

    // testStoreExportsIntoTheTemporaryFolder exercises GameStore.exportSave, which is part of :app on Android.

}
