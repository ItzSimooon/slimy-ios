package de.hyprnode.slimy.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * An exported save: a `.slimysave` file the player can keep or move to
 * another phone (iPhone or Android). Plain JSON with a small header, so a
 * file can be checked before it replaces anything.
 */
object SaveFile {
    const val format = "de.hyprnode.slimy.save"

    /** Version of this file layout (not of the game state inside). */
    const val schema = 1
    const val fileExtension = "slimysave"

    /** A real save is a few hundred kilobytes at most. */
    const val maxBytes = 8 * 1024 * 1024

    /** Why a file can't be imported. The messages are shown as they are. */
    enum class Problem(val message: String) {
        unreadable("This file couldn't be read. It may be damaged."),
        notASave("This isn't a Slimy save file."),
        newerVersion("This save comes from a newer version of Slimy. Update the app and try again."),
        noSlime("There's no slime in this save yet."),
        tooLarge("This file is too large to be a Slimy save."),

        /** The phone cares for a slime together with someone right now. */
        caringTogether("Stop caring together first, then import the save.");

        val errorDescription: String get() = message
    }

    /** A `Problem` as an exception, for `importSave`. */
    class ProblemException(val problem: Problem) : Exception(problem.message)

    // MARK: Writing

    /** The file's JSON: pretty-printed with sorted keys, dates as seconds since 1970. */
    fun encode(state: GameState, now: Instant, appVersion: String): String {
        val json = SlimyJson.saveFile
        val body = buildJsonObject {
            put("format", format)
            put("schema", schema)
            put("exportedAt", json.encodeToJsonElement(SecondsSince1970Serializer, now))
            put("appVersion", appVersion)
            put("state", json.encodeToJsonElement(GameState.serializer(), state))
        }
        return json.encodeToString(JsonObject.serializer(), SlimyJson.sortedKeys(body) as JsonObject)
    }

    /** "Slimy-Mochi-2026-10-26.slimysave" */
    fun fileName(state: GameState, now: Instant, calendar: SlimyCalendar = SlimyCalendar.current): String {
        val mapped = buildString {
            for (ch in state.slime.name) append(if (ch.isLetterOrDigit()) ch else '-')
        }
        val cleaned = mapped.split("-").filter { it.isNotEmpty() }.joinToString("-")
        val name = if (cleaned.isEmpty()) "Slime" else cleaned.prefixChars(24)
        return "Slimy-$name-${Challenges.dayKey(now, calendar)}.$fileExtension"
    }

    // MARK: Reading

    /** Checks a file and returns the game in it, repaired and ready to use. Throws [ProblemException]. */
    fun decode(bytes: ByteArray, now: Instant): GameState {
        if (bytes.size > maxBytes) throw ProblemException(Problem.tooLarge)
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrElse { throw ProblemException(Problem.unreadable) }
        val root = runCatching { SlimyJson.storage.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw ProblemException(Problem.unreadable)
        val fileFormat = (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (fileFormat != format) throw ProblemException(Problem.notASave)
        val version = (root["schema"] as? JsonPrimitive)?.intOrNull
        if (version == null || version < 1) throw ProblemException(Problem.unreadable)
        if (version > schema) throw ProblemException(Problem.newerVersion)
        val body = root["state"] as? JsonObject ?: throw ProblemException(Problem.unreadable)
        val state = runCatching { SlimyJson.storage.decodeFromJsonElement(GameState.serializer(), body) }.getOrNull()
            ?: throw ProblemException(Problem.unreadable)
        if (state.version > GameState.currentVersion) throw ProblemException(Problem.newerVersion)
        if (!state.hatched) throw ProblemException(Problem.noSlime)
        state.version = GameState.currentVersion
        // Caring together belongs to the phone that made the save, not the
        // file: a joiner's own slime comes back, without a word.
        state.stopSharing(now, quietly = true)
        state.sanitize(now)
        return state
    }
}
