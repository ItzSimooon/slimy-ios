package de.hyprnode.slimy.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * The save's format and rules, without the file system: the app's
 * persistence (`core/`) reads and writes the bytes, atomically, in its
 * private files. The JSON is the iPhone app's `slimy-game.json`.
 */
object GameStorage {
    enum class Source { file, backup, migrated, fresh }

    const val fileName = "slimy-game.json"
    const val backupName = "slimy-game.backup.json"

    /** The save's JSON (`GameStorage.encoder`: dates as seconds since 1970). */
    fun encode(state: GameState): String = SlimyJson.storage.encodeToString(GameState.serializer(), state)

    /** A save's JSON, or null when it can't be read at all. Missing or damaged fields fall back. */
    fun decode(json: String): GameState? =
        runCatching { SlimyJson.storage.decodeFromString(GameState.serializer(), json) }.getOrNull()

    /** A loaded save made current: the latest version, repaired. */
    fun prepared(state: GameState, now: Instant): GameState {
        val s = state.deepCopy()
        s.version = GameState.currentVersion
        s.sanitize(now)
        return s
    }

    /**
     * Version 1 (iOS builds 1–11 kept a single slime in UserDefaults under
     * "slimy.state.v1"; dates in Foundation's default seconds since 2001).
     * Kept for parity; Android never wrote version 1 saves.
     */
    fun migrateV1(json: String?, hatched: Boolean, now: Instant): GameState? {
        if (json == null) return null
        val old = runCatching { SlimyJson.storage.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        fun str(key: String): String? = (old[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        fun date(key: String): Instant? = (old[key] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
            ?.let { instantOf(it + referenceDate1970) }
        fun int(obj: JsonObject?, key: String): Int? = runCatching { obj?.get(key)?.jsonPrimitive?.intOrNull }.getOrNull()

        val name = str("name") ?: "Blob"
        val birthday = date("birthday")
        val state = GameState()
        val seed = SplitMix.seed(name + swiftDouble(birthday?.timeIntervalSince1970 ?: 0.0))
        state.slime = Slime.newborn(name, enumOf<Skin>(str("skin")) ?: Skin.lime, seed)
        state.slime.birthday = birthday ?: now
        state.slime.needs = runCatching { SlimyJson.storage.decodeFromJsonElement(Needs.serializer(), old["needs"]!!) }.getOrNull() ?: Needs()
        state.slime.xp = int(old, "xp") ?: 0
        state.slime.isAsleep = runCatching { old["isAsleep"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
        state.slime.lastUpdate = date("lastUpdate") ?: now
        enumOf<Food>(str("favorite"))?.let { state.slime.favorite = it }
        state.wallet.coins = int(old, "coins") ?: 30
        val counters = runCatching { old["counters"]?.jsonObject }.getOrNull()
        state.progress.stats.feeds = int(counters, "feeds") ?: 0
        state.progress.stats.plays = int(counters, "plays") ?: 0
        state.progress.stats.baths = int(counters, "baths") ?: 0
        state.progress.stats.pets = int(counters, "pets") ?: 0
        val best = int(counters, "bestScore")
        if (best != null && best > 0) {
            state.progress.records = state.progress.records +
                (GameID.snackCatcher to GameRecord(best = best, plays = 1, lastPlayed = null, tutorialSeen = true))
        }
        state.hatched = hatched
        if (state.hatched) state.player.tips = state.player.tips + "onboarded"
        state.memory.add(MemoryEntry(kind = MemoryEntry.Kind.hatched, at = state.slime.birthday, detail = state.slime.name, keepsake = true))
        return state
    }

    /** Seconds from 1970 to Foundation's reference date (2001-01-01). */
    private const val referenceDate1970 = 978_307_200.0

    /** `"\(double)"` as Swift prints a Double: "0.0", "1234.5". */
    private fun swiftDouble(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite() && kotlin.math.abs(value) < 1e16) "${value.toLong()}.0" else value.toString()
}

/**
 * What the Home Screen widgets and the slime notification show. Written by
 * the app whenever the save changes; widgets project needs forward from it.
 */
@Serializable
data class WidgetSnapshot(
    val name: String,
    val skin: Skin,
    val stage: Stage,
    val level: Int,
    val needs: Needs,
    val isAsleep: Boolean,
    val line: String,
    val coins: Int,
    val writtenAt: JsonDate,
    val personality: Personality,
    val equipped: Accessories,
    val habitat: Habitat,
    /** The player's bedtime, so widgets know when it sleeps. */
    val sleep: SleepSchedule? = null,
) {
    /** Needs as they are probably now, without the app running. Uses the saved bedtime unless [schedule] gives another. */
    fun projectedNeeds(now: Instant, schedule: SleepSchedule? = null, calendar: SlimyCalendar = SlimyCalendar.current): Needs {
        val slime = Slime()
        slime.needs = needs.copy()
        slime.isAsleep = isAsleep
        slime.personality = personality.copy()
        slime.lastUpdate = writtenAt
        slime.advance(now, schedule ?: sleep ?: SleepSchedule(), calendar)
        return slime.needs
    }

    companion object {
        fun of(state: GameState, now: Instant): WidgetSnapshot = WidgetSnapshot(
            name = state.slime.name,
            skin = state.slime.skin,
            stage = state.slime.stage,
            level = state.slime.level,
            needs = state.slime.needs.copy(),
            isAsleep = state.slime.isAsleep,
            line = state.line(now),
            coins = state.wallet.coins,
            writtenAt = now,
            personality = state.slime.personality.copy(),
            equipped = state.slime.equipped.copy(),
            habitat = state.inventory.habitat,
            sleep = state.settings.sleep.copy(),
        )
    }
}
