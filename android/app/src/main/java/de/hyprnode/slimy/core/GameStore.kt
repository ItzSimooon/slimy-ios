package de.hyprnode.slimy.core

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.Achievements
import de.hyprnode.slimy.model.Birthday
import de.hyprnode.slimy.model.CareEvent
import de.hyprnode.slimy.model.CareLink
import de.hyprnode.slimy.model.Challenges
import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.CrateReward
import de.hyprnode.slimy.model.DailyGift
import de.hyprnode.slimy.model.DistantPast
import de.hyprnode.slimy.model.Emotion
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.GameEvent
import de.hyprnode.slimy.model.GameResult
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.GameStorage
import de.hyprnode.slimy.model.Holiday
import de.hyprnode.slimy.model.MemoryEntry
import de.hyprnode.slimy.model.Outcome
import de.hyprnode.slimy.model.PhoneEvent
import de.hyprnode.slimy.model.Reaction
import de.hyprnode.slimy.model.SaveFile
import de.hyprnode.slimy.model.Settings
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimeSnapshot
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.StreakInfo
import de.hyprnode.slimy.model.WidgetSnapshot
import de.hyprnode.slimy.model.adoptShared
import de.hyprnode.slimy.model.applyPartnerCare
import de.hyprnode.slimy.model.arrive
import de.hyprnode.slimy.model.bath
import de.hyprnode.slimy.model.buyCrate
import de.hyprnode.slimy.model.canPlay
import de.hyprnode.slimy.model.chatter
import de.hyprnode.slimy.model.claimDailyGift
import de.hyprnode.slimy.model.feed
import de.hyprnode.slimy.model.finish
import de.hyprnode.slimy.model.hatch
import de.hyprnode.slimy.model.handle
import de.hyprnode.slimy.model.noteSharedChange
import de.hyprnode.slimy.model.openCrate
import de.hyprnode.slimy.model.pet
import de.hyprnode.slimy.model.prefixChars
import de.hyprnode.slimy.model.reconcile
import de.hyprnode.slimy.model.servings
import de.hyprnode.slimy.model.sharedSnapshot
import de.hyprnode.slimy.model.sleep
import de.hyprnode.slimy.model.startHosting
import de.hyprnode.slimy.model.stopSharing
import de.hyprnode.slimy.model.streakInfo
import de.hyprnode.slimy.model.takeCareOutbox
import de.hyprnode.slimy.model.tick
import de.hyprnode.slimy.model.timeIntervalSince
import de.hyprnode.slimy.model.trimmedSwift
import de.hyprnode.slimy.model.wake
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** A moment worth a little fanfare on screen. */
data class Celebration(
    val title: String,
    val detail: String,
    val icon: String,
    /** A happy moment: a good time to ask for a rating. */
    val asksForReview: Boolean = false,
    val id: UUID = UUID.randomUUID(),
)

/** A short message over the app. Two toasts with the same text are still different toasts. */
data class Toast(
    val text: String,
    val good: Boolean,
    val id: UUID = UUID.randomUUID(),
)

/**
 * Owns the save. Every change goes through [apply] or [update], which save,
 * refresh the widgets and the slime notification, and turn game events into
 * celebrations. The Android counterpart of the iPhone app's `GameStore`.
 *
 * One instance lives in `SlimyApplication` (`(application as SlimyApplication).store`,
 * `LocalGameStore` in Compose). [state] only ever holds published copies:
 * the model classes are mutable, so a rule always runs on a fresh copy that
 * then replaces the state; never change a state you got from here.
 *
 * Thread-safe: activities, widgets, workers and notification actions may
 * call it from any thread. Hooks and [onCare] are called on the main thread.
 */
class GameStore internal constructor(
    private val context: Context?,
    private val saves: SaveStore,
    private val prefs: SharedPreferences?,
    /** The app's version name, for exports and the rating prompt. */
    val appVersion: String,
) {
    /** The rest of the app that cares about changes: the slime notification, reminders, widgets. */
    interface Hooks {
        /** The game changed in a way the slime notification should show (`IslandController.sync`). */
        fun syncIsland(state: GameState) {}

        /** The slime is gone: take the slime notification down (`IslandController.stop`). */
        fun stopIsland() {}

        /** Reminders should be planned again for this state (`Notifier.reschedule`). */
        fun reschedule(state: GameState) {}

        /** The widget snapshot was rewritten; widgets should redraw (`WidgetCenter.reloadAllTimelines`). */
        fun reloadWidgets(state: GameState) {}
    }

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val hooks = CopyOnWriteArrayList<Hooks>()

    private val loaded = saves.load()
    private val _state = MutableStateFlow(loaded.state)
    private val _toast = MutableStateFlow<Toast?>(null)
    private val _celebrations = MutableStateFlow<List<Celebration>>(emptyList())
    private val _announcements = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** The game. A new value after every change; never change it in place. */
    val state: StateFlow<GameState> = _state.asStateFlow()

    /** The toast on screen, if any. */
    val toast: StateFlow<Toast?> = _toast.asStateFlow()

    /** Moments waiting to be celebrated, oldest first. */
    val celebrations: StateFlow<List<Celebration>> = _celebrations.asStateFlow()

    /**
     * Lines the slime says while its speech is already on screen: no toast,
     * but TalkBack should read them (`UIAccessibility.post(.announcement)`).
     */
    val announcements: SharedFlow<String> = _announcements.asSharedFlow()

    /** Where the save came from on launch. */
    val loadSource: GameStorage.Source = loaded.source

    /** Why the last save failed, or null when it worked. */
    @Volatile
    var saveError: String? = null
        private set

    @Volatile
    private var lastWidgetReload: Instant = DistantPast

    @Volatile
    private var lastChatterSave: Instant = DistantPast

    /**
     * Set by the friends code. Called (on the main thread) for every care
     * action done on this phone while you care for the slime together, to
     * send it to the partner. Setting it sends what is already waiting.
     */
    var onCare: ((CareEvent) -> Unit)? = null
        set(value) {
            synchronized(lock) {
                field = value
                if (sendCare()) persist()
            }
        }

    /** The store for the app: saves in `filesDir`, the rating flag in shared preferences. */
    constructor(context: Context) : this(
        context = context.applicationContext,
        saves = SaveStore(context.applicationContext.filesDir),
        prefs = context.applicationContext.getSharedPreferences("slimy", Context.MODE_PRIVATE),
        appVersion = versionName(context.applicationContext),
    )

    val slime: Slime get() = _state.value.slime

    // MARK: Hooks

    fun addHooks(hooks: Hooks) {
        this.hooks.addIfAbsent(hooks)
    }

    fun removeHooks(hooks: Hooks) {
        this.hooks.remove(hooks)
    }

    // MARK: Toasts and celebrations

    fun showToast(text: String, good: Boolean = true) {
        _toast.value = Toast(text, good)
    }

    /** Clears the toast, but only [toast] if given (so a newer one stays). */
    fun dismissToast(toast: Toast? = null) {
        if (toast == null) _toast.value = null else _toast.compareAndSet(toast, null)
    }

    /** Removes a celebration that was shown. */
    fun dismissCelebration(id: UUID) {
        synchronized(lock) { _celebrations.value = _celebrations.value.filter { it.id != id } }
    }

    /** Removes the oldest celebration (the one on screen). */
    fun popCelebration(): Celebration? = synchronized(lock) {
        val first = _celebrations.value.firstOrNull() ?: return null
        _celebrations.value = _celebrations.value.drop(1)
        first
    }

    // MARK: Changing the game

    /**
     * Runs a rule, then saves and tells everything that cares.
     *
     * `toast = false` is for screens where the slime's speech bubble is
     * already on screen: a line the slime is saying isn't repeated in a toast
     * (TalkBack still reads it out). Lines it isn't saying still get their toast.
     */
    fun apply(
        sound: Sound? = null,
        island: Boolean = true,
        toast: Boolean = true,
        change: (GameState) -> Outcome,
    ): Outcome = synchronized(lock) {
        val before = _state.value
        val copy = before.deepCopy()
        val out = change(copy)
        noteLook(before, copy)
        _state.value = copy
        afterChange(out, before, sound, island, toast)
        out
    }

    /** For changes without an outcome. */
    fun update(change: (GameState) -> Unit) {
        synchronized(lock) {
            val before = _state.value
            val copy = before.deepCopy()
            change(copy)
            noteLook(before, copy)
            _state.value = copy
            sendCare()
            celebrate(unannounced(before, emptyList()))
            persist()
            syncIsland()
        }
    }

    private fun afterChange(out: Outcome, before: GameState, sound: Sound?, island: Boolean, showToast: Boolean) {
        sendCare()
        persist()
        if (out.line.isNotEmpty()) {
            if (showToast || _state.value.slime.currentLine(Instant.now()) != out.line) {
                _toast.value = Toast(out.line, out.ok)
            } else {
                _announcements.tryEmit(out.line)
            }
        }
        if (out.ok) {
            sound?.let { Feedback.shared.play(it) }
        } else {
            Feedback.shared.play(Sound.nope)
        }
        celebrate(out.events + unannounced(before, out.events))
        reschedule()
        if (island) syncIsland()
    }

    fun tick(now: Instant = Instant.now()) {
        synchronized(lock) {
            val before = _state.value
            val copy = before.deepCopy()
            val events = copy.tick(now)
            _state.value = copy
            celebrate(events + unannounced(before, events))
            persist()
        }
    }

    /** The app came to the front. */
    fun arrive(now: Instant = Instant.now()) {
        synchronized(lock) {
            val before = _state.value
            val copy = before.deepCopy()
            val events = copy.tick(now)
            val line = copy.arrive(now)
            _state.value = copy
            celebrate(events + unannounced(before, events))
            persist()
            if (line != null) _toast.value = Toast(line, good = true)
        }
    }

    // MARK: Care shortcuts

    fun feed(food: Food, toast: Boolean = true): Outcome =
        apply(sound = Sound.eat, toast = toast) { it.feed(food, Instant.now()) }

    fun pet(toast: Boolean = true): Outcome =
        apply(sound = Sound.pet, toast = toast) { it.pet(Instant.now()) }

    fun bath(): Outcome = apply(sound = Sound.splash) { it.bath(Instant.now()) }

    fun toggleSleep(toast: Boolean = true): Outcome {
        if (_state.value.slime.isAsleep) return apply(sound = Sound.wake, toast = toast) { it.wake(Instant.now()) }
        return apply(sound = Sound.sleep, toast = toast) { it.sleep(Instant.now()) }
    }

    fun finish(result: GameResult): Outcome = apply(sound = Sound.coin) { it.finish(result) }

    /** A quick bounce from the slime notification's Play button: a small round of fun without the arcade. */
    fun quickPlay() {
        val s = _state.value
        if (!s.canPlay) {
            _toast.value = Toast("${s.slime.name} is too tired to play.", good = false)
            return
        }
        finish(GameResult(game = s.slime.favoriteGame, score = 0, session = UUID.randomUUID(), finishedAt = Instant.now()))
    }

    /** Picks the best food the player has or can afford: a free serving of the favourite counts. */
    fun quickFeed(): Outcome {
        val s = _state.value
        val fav = s.slime.favorite
        val canHaveFavorite = s.servings(fav) > 0 || (s.wallet.coins >= fav.price && s.slime.level >= fav.unlockLevel)
        return feed(if (canHaveFavorite) fav else Food.apple)
    }

    fun handle(event: PhoneEvent): String {
        var line = ""
        apply(sound = null) { s ->
            line = s.handle(event, Instant.now())
            Outcome(ok = true, line = "")
        }
        return line
    }

    /**
     * Small talk while you watch: home calls this every 8 to 16 seconds, most
     * of the time. Returns whether it said something: it stays quiet before
     * hatching and when "Talks on its own" is off.
     *
     * A light path: no care is sent, and the save, the widgets and the slime
     * notification catch up at most once a minute. Coins the clock brought
     * are saved right away.
     */
    fun chatter(): Boolean = synchronized(lock) {
        val before = _state.value
        if (!before.hatched || !before.settings.chatty) return false
        val copy = before.deepCopy()
        copy.chatter(Instant.now())
        _state.value = copy
        celebrate(unannounced(before, emptyList()))
        val now = Instant.now()
        if (copy.wallet == before.wallet && copy.crates == before.crates && now.timeIntervalSince(lastChatterSave) <= 60) {
            return true
        }
        lastChatterSave = now
        persist()
        syncIsland()
        true
    }

    // MARK: Feelings

    /** Adds to a feeling (negative amounts take away), for example the feeling the chat's reply expressed. */
    fun feel(e: Emotion, amount: Double) {
        if (!amount.isFinite() || amount == 0.0) return
        update { it.slime.feel(e, amount) }
    }

    // MARK: Crates

    /** Buys a crate for coins. False if it isn't for sale, the coins aren't enough or there's no room. */
    fun buyCrate(kind: CrateKind): Boolean {
        var bought = false
        apply(sound = Sound.coin, island = false) { s ->
            bought = s.buyCrate(kind, Instant.now())
            Outcome(ok = bought, line = "")
        }
        return bought
    }

    /** Opens a crate and hands out what was in it. Null if there is none of that kind. No toast. */
    fun openCrate(kind: CrateKind): CrateReward? {
        var reward: CrateReward? = null
        val seed = randomSeed()
        apply(toast = false) { s ->
            val opened = s.openCrate(kind, Instant.now(), seed) ?: return@apply Outcome(ok = false, line = "")
            reward = opened.reward
            Outcome(ok = true, line = "", events = opened.outcome.events)
        }
        return reward
    }

    // MARK: Caring together

    val isSharing: Boolean get() = _state.value.care != null

    /** The partner's care actions: applied to the shared slime (no coins), each only once, with a toast. */
    fun applyPartnerCare(events: List<CareEvent>) {
        synchronized(lock) {
            if (_state.value.care == null || events.isEmpty()) return
            val before = _state.value
            val copy = before.deepCopy()
            val now = Instant.now()
            val said = mutableListOf<String>()
            var person = ""
            for (event in events.sortedBy { it.at }) {
                val summary = copy.applyPartnerCare(event, now) ?: continue
                said.add(summary)
                person = event.by.trimmedSwift()
            }
            _state.value = copy
            val last = said.lastOrNull()
            if (last == null) {
                persist()
                return
            }
            celebrate(unannounced(before, emptyList()))
            persist()
            _toast.value = if (said.size == 1) {
                Toast(last, good = true)
            } else {
                val who = person.ifEmpty { copy.care?.partnerName ?: "Your partner" }
                Toast("$who looked after ${copy.slime.name} ${said.size} times", good = true)
            }
            reschedule()
            syncIsland()
        }
    }

    /** The shared slime to send to the partner's phone. */
    fun sharedSnapshot(): SlimeSnapshot = _state.value.sharedSnapshot(Instant.now())

    /** The joiner's side: the shared slime moves in, your own is kept safe in `link.ownBackup`. */
    fun adoptShared(snapshot: SlimeSnapshot, link: CareLink) {
        synchronized(lock) {
            val before = _state.value
            val copy = before.deepCopy()
            copy.adoptShared(snapshot, link, Instant.now())
            _state.value = copy
            // A different slime: the widgets show it right away.
            lastWidgetReload = DistantPast
            celebrate(unannounced(before, emptyList()))
            persist()
            reschedule()
            syncIsland()
        }
    }

    /** Takes over the partner's snapshot if it is newer than what this phone knows; feelings are blended. */
    fun reconcile(snapshot: SlimeSnapshot) {
        synchronized(lock) {
            val before = _state.value
            val copy = before.deepCopy()
            if (!copy.reconcile(snapshot, Instant.now())) return
            _state.value = copy
            celebrate(unannounced(before, emptyList()))
            persist()
            syncIsland()
        }
    }

    /** The host's side: someone joined with your code. Keeps the link. */
    fun startHosting(link: CareLink) {
        synchronized(lock) {
            val before = _state.value
            val copy = before.deepCopy()
            copy.startHosting(link, Instant.now())
            _state.value = copy
            celebrate(unannounced(before, emptyList()))
            persist()
            syncIsland()
        }
    }

    /** Ends caring together on this phone. The joiner's own slime comes back; the host keeps the slime. */
    fun stopSharing() {
        synchronized(lock) {
            if (_state.value.care == null) return
            val before = _state.value
            val copy = before.deepCopy()
            copy.stopSharing(Instant.now())
            _state.value = copy
            lastWidgetReload = DistantPast
            celebrate(unannounced(before, emptyList()))
            persist()
            reschedule()
            syncIsland()
        }
    }

    // MARK: Daily gift

    /** The gift calendar and streak right now. */
    val streak: StreakInfo get() = _state.value.streakInfo(Instant.now())

    /** Opens today's gift. Returns what was in it, or null if today's gift was already opened. */
    fun claimDailyGift(): DailyGift? {
        var gift: DailyGift? = null
        apply(sound = Sound.treasure) { s ->
            val claimed = s.claimDailyGift(Instant.now()) ?: return@apply Outcome(ok = false, line = "")
            gift = claimed.gift
            // The gift screen shows the slime's words; no toast on top.
            Outcome(ok = true, line = "", events = claimed.outcome.events)
        }
        return gift
    }

    // MARK: Identity

    /** Hatches the slime. A random character unless [seed] fixes one. */
    fun hatch(name: String, skin: Skin, seed: ULong? = null) {
        synchronized(lock) {
            val copy = _state.value.deepCopy()
            copy.hatch(name, skin, seed ?: randomSeed(), Instant.now())
            _state.value = copy
            // A new slime: the widgets show it right away.
            lastWidgetReload = DistantPast
            persist()
        }
        Feedback.shared.play(Sound.levelUp)
    }

    fun rename(name: String) {
        val trimmed = name.trimmedSwift()
        if (trimmed.isEmpty()) return
        update { it.slime.name = trimmed.prefixChars(16) }
    }

    fun wear(skin: Skin) {
        if (!_state.value.slime.isUnlocked(skin)) return
        update {
            it.slime.skin = skin
            it.slime.react(Reaction.blush, Instant.now())
        }
    }

    fun markTip(id: String) {
        if (_state.value.player.tips.contains(id)) return
        update { it.player.tips = it.player.tips + id }
    }

    fun setSettings(change: (Settings) -> Unit) {
        update { change(it.settings) }
    }

    /**
     * Starts over. Saved coins and items go with the slime. Does nothing while
     * you care for the slime together: stop that first, so a joiner's own
     * slime comes back instead of being lost.
     */
    fun releaseSlime() {
        synchronized(lock) {
            if (_state.value.care != null) return
            saves.reset()
            _celebrations.value = emptyList()
            _state.value = GameState.fresh()
            lastWidgetReload = DistantPast
            persist()
            // Reminders about the slime that's gone are taken back.
            reschedule()
            onMain { hooks.forEach { it.stopIsland() } }
        }
    }

    // MARK: Save files

    /**
     * Writes the whole game to a `.slimysave` file in the cache's exports
     * folder, ready to share (see [saveFileUri]). Earlier exports there are
     * removed first.
     */
    fun exportSave(): File {
        val context = context ?: throw IOException("No app context")
        val folder = File(context.cacheDir, exportFolder)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Can't create ${folder.path}")
        folder.listFiles()?.forEach { file ->
            if (file.name.endsWith(".${SaveFile.fileExtension}") && file.name.startsWith("Slimy-")) file.delete()
        }
        val state = _state.value
        val now = Instant.now()
        val file = File(folder, SaveFile.fileName(state, now))
        SaveStore.writeAtomically(file, SaveFile.encode(state, now, appVersion).toByteArray(Charsets.UTF_8))
        return file
    }

    /** Replaces the game with the one in a `.slimysave` file picked with the system file picker. */
    fun importSave(uri: Uri) {
        val resolver = context?.contentResolver ?: throw IOException("No app context")
        if (_state.value.care != null) throw SaveFile.ProblemException(SaveFile.Problem.caringTogether)
        val size = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
        if (size != null && size > SaveFile.maxBytes) throw SaveFile.ProblemException(SaveFile.Problem.tooLarge)
        val input = try {
            resolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: throw SaveFile.ProblemException(SaveFile.Problem.unreadable)
        input.use { importSave(it) }
    }

    /**
     * Replaces the game with the one in a `.slimysave` file. The file is
     * checked and written to disk first, so if anything is wrong nothing
     * changes; the game it replaces stays behind as the backup save. Throws
     * [SaveFile.ProblemException] (with a message to show as it is), or an
     * `IOException` when the save can't be written. Not while you care for
     * the slime together.
     */
    fun importSave(input: InputStream) {
        if (_state.value.care != null) throw SaveFile.ProblemException(SaveFile.Problem.caringTogether)
        val bytes = readCapped(input)
        val now = Instant.now()
        val imported = SaveFile.decode(bytes, now)
        imported.tick(now)
        // Coming home after a move isn't a reunion.
        imported.player.lastSeen = now
        synchronized(lock) {
            if (_state.value.care != null) throw SaveFile.ProblemException(SaveFile.Problem.caringTogether)
            saves.save(imported, WidgetSnapshot.of(imported, now))
            saveError = null
            _celebrations.value = emptyList()
            _toast.value = null
            _state.value = imported
            lastWidgetReload = now
            onMain { hooks.forEach { it.reloadWidgets(imported) } }
            reschedule()
            syncIsland()
        }
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > SaveFile.maxBytes) throw SaveFile.ProblemException(SaveFile.Problem.tooLarge)
            }
        } catch (e: IOException) {
            throw SaveFile.ProblemException(SaveFile.Problem.unreadable)
        }
        return out.toByteArray()
    }

    // MARK: Asking for a rating

    /** A rating is asked for at most once per app version. */
    val mayAskForReview: Boolean get() = prefs?.getString(reviewKey, null) != appVersion

    fun noteReviewAsked() {
        prefs?.edit()?.putString(reviewKey, appVersion)?.apply()
    }

    // MARK: Lifecycle

    /** Writes everything still waiting to disk (the app goes to the background). */
    fun flush() {
        saves.flush()
    }

    /** The widget snapshot last written, for widgets and the slime notification. */
    fun readSnapshot(): WidgetSnapshot? = saves.readSnapshot()

    // MARK: Plumbing

    /** Sends the care actions waiting in the save to the partner, if someone listens. Returns whether anything was sent. */
    private fun sendCare(): Boolean {
        val listener = onCare ?: return false
        if (_state.value.careBook.outbox.isEmpty()) return false
        val copy = _state.value.deepCopy()
        val events = copy.takeCareOutbox()
        _state.value = copy
        onMain { for (event in events) listener(event) }
        return true
    }

    /** What the partner's phone needs to see of the shared slime's look. */
    private data class ShareableLook(
        val name: String,
        val skin: Skin,
        val xp: Int,
        val bond: Double,
        val equipped: Accessories,
        val colours: Set<Skin>,
    ) {
        constructor(slime: Slime) : this(slime.name, slime.skin, slime.xp, slime.bond, slime.equipped.copy(), slime.colours)
    }

    /** A change made on this phone that the partner should see makes the next snapshot win. */
    private fun noteLook(before: GameState, state: GameState) {
        if (state.care == null || ShareableLook(before.slime) == ShareableLook(state.slime)) return
        state.noteSharedChange(Instant.now())
    }

    /**
     * Moments a change brought that its outcome didn't mention: birthdays,
     * holidays, and achievements earned on the side.
     */
    private fun unannounced(old: GameState, known: List<GameEvent>): List<GameEvent> {
        val state = _state.value
        val out = mutableListOf<GameEvent>()
        val birthday = state.memory.last(MemoryEntry.Kind.birthday)
        if (birthday != null && birthday.id != old.memory.last(MemoryEntry.Kind.birthday)?.id) {
            birthday.detail.toIntOrNull()?.let { out.add(GameEvent.birthday(it)) }
        }
        val holidayEntry = state.memory.last(MemoryEntry.Kind.holiday)
        if (holidayEntry != null && holidayEntry.id != old.memory.last(MemoryEntry.Kind.holiday)?.id) {
            Holiday.entries.firstOrNull { it.name == holidayEntry.detail }?.let { out.add(GameEvent.holiday(it)) }
        }
        val earned = state.progress.achievements
        for (achievement in Achievements.all) {
            if (earned[achievement.id] != null && old.progress.achievements[achievement.id] == null) {
                out.add(GameEvent.achievement(achievement.id))
            }
        }
        return out.filter { it !in known }
    }

    private fun celebrate(events: List<GameEvent>) {
        if (events.isEmpty()) return
        val name = _state.value.slime.name
        val added = mutableListOf<Celebration>()
        for (event in events) {
            when (event) {
                is GameEvent.levelUp -> {
                    added.add(Celebration("Level ${event.level}", "+${10 * event.level} coins", "coin", asksForReview = event.level >= 3))
                    Feedback.shared.play(Sound.levelUp)
                }
                is GameEvent.stageUp -> {
                    added.add(Celebration("$name grew up", event.stage.title, "starJelly"))
                    Feedback.shared.play(Sound.levelUp)
                }
                is GameEvent.achievement -> {
                    val a = Achievements.achievement(event.id) ?: continue
                    val crate = a.crate?.let { " and a ${it.title.lowercase()}" } ?: ""
                    added.add(
                        Celebration(
                            a.title, "${a.detail} +${a.coins} coins$crate", "furn.trophy",
                            asksForReview = event.id == "close" || event.id == "legendary",
                        ),
                    )
                    Feedback.shared.play(Sound.success)
                }
                is GameEvent.favoriteFound ->
                    added.add(Celebration("Favourite food found", event.food.title, event.food.sprite))
                is GameEvent.record ->
                    added.add(Celebration("New record", "${event.game.title}: ${event.score}", event.game.icon))
                is GameEvent.challenge -> {
                    val t = Challenges.template(event.id) ?: continue
                    added.add(Celebration("Challenge done", t.title, "coin"))
                }
                is GameEvent.birthday -> {
                    val together = if (event.years == 1) "One year together" else "${event.years} years together"
                    val cake = if (event.years == 1) " and a cake for the room" else ""
                    added.add(
                        Celebration(
                            "Happy birthday, $name", "$together. +${Birthday.coins(event.years)} coins$cake.", "cake",
                        ),
                    )
                    Feedback.shared.play(Sound.levelUp)
                }
                is GameEvent.holiday -> {
                    added.add(Celebration(event.holiday.greeting, "A little gift: +${event.holiday.coins} coins", event.holiday.icon))
                    Feedback.shared.play(Sound.success)
                }
                is GameEvent.crate -> {
                    added.add(Celebration("A ${event.kind.title.lowercase()}", event.source.reason, event.kind.sprite))
                    Feedback.shared.play(Sound.treasure)
                }
            }
        }
        if (added.isNotEmpty()) _celebrations.value = _celebrations.value + added
    }

    /** Saves (on the save thread), rewrites the widget snapshot and, at most once a minute, reloads the widgets. */
    private fun persist() {
        val state = _state.value
        val now = Instant.now()
        // Widgets get a budget of reloads; don't spend it on every tap.
        val reload = now.timeIntervalSince(lastWidgetReload) > 60
        if (reload) lastWidgetReload = now
        saves.saveLater(state, WidgetSnapshot.of(state, now)) { error ->
            saveError = error?.let { it.message ?: it.javaClass.simpleName }
            if (reload) onMain { hooks.forEach { it.reloadWidgets(state) } }
        }
    }

    private fun reschedule() {
        val state = _state.value
        onMain { hooks.forEach { it.reschedule(state) } }
    }

    private fun syncIsland() {
        val state = _state.value
        onMain { hooks.forEach { it.syncIsland(state) } }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    companion object {
        private const val reviewKey = "slimy.review.askedVersion"

        /** Folder in the cache dir that exported saves go into (shared with the FileProvider). */
        const val exportFolder = "exports"

        private fun randomSeed(): ULong {
            var seed = Random.nextLong().toULong()
            while (seed == 0uL) seed = Random.nextLong().toULong()
            return seed
        }

        private fun versionName(context: Context): String = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }
}

/** A `content://` uri for an exported save, to share with `ACTION_SEND` (FileProvider "<package>.files"). */
fun Context.saveFileUri(file: File): Uri =
    androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
