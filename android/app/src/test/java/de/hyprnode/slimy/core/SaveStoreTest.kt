package de.hyprnode.slimy.core

import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.GameStorage
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SlimyCalendar
import de.hyprnode.slimy.model.WidgetSnapshot
import de.hyprnode.slimy.model.hatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SaveStoreTest {
    private val berlin = SlimyCalendar.gregorian("Europe/Berlin")
    private val noon = berlin.date(2026, 10, 25, 12)
    private lateinit var dir: File
    private lateinit var saves: SaveStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("slimy-saves").toFile()
        saves = SaveStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun hatched(name: String = "Testi"): GameState {
        val state = GameState()
        state.hatch(name, Skin.lime, 42uL, noon)
        return state
    }

    @Test
    fun nothingSavedMeansAFreshGame() {
        val loaded = saves.load(noon)
        assertEquals(GameStorage.Source.fresh, loaded.source)
        assertFalse(loaded.state.hatched)
    }

    @Test
    fun roundTrip() {
        val state = hatched()
        state.wallet.coins = 123
        saves.save(state)
        val loaded = saves.load(noon)
        assertEquals(GameStorage.Source.file, loaded.source)
        assertEquals("Testi", loaded.state.slime.name)
        assertEquals(123, loaded.state.wallet.coins)
        assertEquals(state.slime.needs, loaded.state.slime.needs)
        assertEquals(state.progress, loaded.state.progress)
        assertEquals(GameState.currentVersion, loaded.state.version)
    }

    @Test
    fun theSaveIsTheIPhoneFormat() {
        saves.save(hatched())
        val text = saves.fileURL.readText()
        // Seconds since 1970, like GameStorage.encoder on iOS.
        assertTrue(text.contains("\"birthday\":${noon.epochSecond}"))
        assertTrue(text.contains("\"name\":\"Testi\""))
    }

    @Test
    fun thePreviousSaveBecomesTheBackup() {
        saves.save(hatched("First"))
        saves.save(hatched("Second"))
        assertTrue(saves.backupURL.isFile)
        assertTrue(saves.backupURL.readText().contains("\"First\""))
        assertTrue(saves.fileURL.readText().contains("\"Second\""))
    }

    @Test
    fun aDamagedSaveFallsBackToTheBackup() {
        saves.save(hatched("First"))
        saves.save(hatched("Second"))
        saves.fileURL.writeText("{\"version\": 4, \"hatched\": tr")
        val loaded = saves.load(noon)
        assertEquals(GameStorage.Source.backup, loaded.source)
        assertEquals("First", loaded.state.slime.name)
    }

    @Test
    fun damagedSaveAndBackupMeanAFreshGame() {
        saves.fileURL.writeText("not json")
        saves.backupURL.writeText("[]")
        val loaded = saves.load(noon)
        assertEquals(GameStorage.Source.fresh, loaded.source)
    }

    @Test
    fun damagedFieldsFallBackButTheSaveLoads() {
        saves.fileURL.writeText(
            """{"version": 2, "hatched": true, "wallet": {"coins": "lots"},
               "slime": {"name": "Blub", "needs": {"hunger": 5000, "fun": -3, "energy": 50, "clean": 50, "love": 50}},
               "settings": 17, "somethingFromTheFuture": [1, 2, 3]}""",
        )
        val loaded = saves.load(noon)
        assertEquals(GameStorage.Source.file, loaded.source)
        assertEquals("Blub", loaded.state.slime.name)
        assertEquals(30, loaded.state.wallet.coins)
        assertEquals(100.0, loaded.state.slime.needs.hunger, 0.0)
        assertEquals(0.0, loaded.state.slime.needs.`fun`, 0.0)
    }

    @Test
    fun atomicWritesLeaveNoTemporaryFiles() {
        val target = File(dir, "thing.json")
        SaveStore.writeAtomically(target, "one".toByteArray())
        SaveStore.writeAtomically(target, "two".toByteArray())
        assertEquals("two", target.readText())
        assertEquals(listOf("thing.json"), dir.list()!!.toList())
    }

    @Test
    fun atomicWritesCreateTheFolder() {
        val target = File(dir, "nested/deeper/thing.json")
        SaveStore.writeAtomically(target, "hi".toByteArray())
        assertEquals("hi", target.readText())
    }

    @Test
    fun laterSavesAreCoalescedAndFlushed() {
        val done = mutableListOf<Throwable?>()
        for (i in 1..20) {
            val state = hatched()
            state.wallet.coins = i
            saves.saveLater(state) { synchronized(done) { done.add(it) } }
        }
        saves.flush()
        assertEquals(20, saves.load(noon).state.wallet.coins)
        synchronized(done) {
            assertTrue(done.isNotEmpty())
            assertTrue(done.size <= 20)
            assertTrue(done.all { it == null })
        }
    }

    @Test
    fun aSynchronousSaveWinsOverWaitingOnes() {
        val waiting = hatched("Waiting")
        saves.saveLater(waiting)
        saves.save(hatched("Now"))
        saves.flush()
        assertEquals("Now", saves.load(noon).state.slime.name)
    }

    @Test
    fun widgetSnapshotRoundTrip() {
        val state = hatched()
        saves.saveLater(state, WidgetSnapshot.of(state, noon))
        saves.flush()
        val snapshot = saves.readSnapshot()
        assertNotNull(snapshot)
        assertEquals("Testi", snapshot!!.name)
        assertEquals(noon, snapshot.writtenAt)
    }

    @Test
    fun resetDeletesEverything() {
        val state = hatched()
        saves.save(state, WidgetSnapshot.of(state, noon))
        saves.save(state)
        saves.reset()
        assertFalse(saves.fileURL.exists())
        assertFalse(saves.backupURL.exists())
        assertFalse(saves.snapshotURL.exists())
        assertNull(saves.readSnapshot())
        assertEquals(GameStorage.Source.fresh, saves.load(noon).source)
    }

    @Test
    fun soundLengthsComeFromTheWavHeader() {
        val sounds = File("../../Resources/Sounds")
        val coin = File(sounds, "coin.wav")
        if (!coin.isFile) return
        val length = Feedback.wavDurationMillis(coin.readBytes())
        assertNotNull(length)
        assertTrue(length!! in 1..5_000)
        assertNull(Feedback.wavDurationMillis("not a wav file".toByteArray()))
    }
}
