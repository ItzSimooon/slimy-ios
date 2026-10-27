package de.hyprnode.slimy.online

import android.content.Context
import android.content.SharedPreferences
import de.hyprnode.slimy.core.Feedback
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.model.Emotion
import de.hyprnode.slimy.model.JsonDate
import de.hyprnode.slimy.model.JsonFields
import de.hyprnode.slimy.model.LossyListSerializer
import de.hyprnode.slimy.model.Reaction
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimyJson
import de.hyprnode.slimy.model.Sound
import de.hyprnode.slimy.model.TolerantSerializer
import de.hyprnode.slimy.model.UuidSerializer
import de.hyprnode.slimy.model.enumOf
import de.hyprnode.slimy.model.prefixChars
import de.hyprnode.slimy.model.trimmedSwift
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/** One line in the conversation with the slime. Kept only on this phone. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = ChatMessage.Coding::class)
data class ChatMessage(
    val role: Role,
    val text: String,
    val date: JsonDate = Instant.now(),
    /** On your message: it didn't reach the slime and isn't sent as history. On a note: the note explains an error. */
    val failed: Boolean = false,
    /** On an error note: the message that Try Again sends again. */
    @Serializable(with = UuidSerializer::class)
    val retryID: UUID? = null,
    /** On a slime reply: the feeling it expressed, if the server named one. */
    val emotion: Emotion? = null,
    /** How strongly the reply expressed [emotion], 0...1. */
    val intensity: Double? = null,
    /** On a slime reply: you reported it. */
    val reported: Boolean = false,
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
) {
    @Serializable
    enum class Role { me, slime, note }

    /** Tolerant, so chats saved by older versions still load; a feeling this version doesn't know just isn't shown. */
    internal object Coding : TolerantSerializer<ChatMessage>(ChatMessage.generatedSerializer()) {
        override fun decode(fields: JsonFields): ChatMessage = ChatMessage(
            id = fields.value("id", UuidSerializer.nullable, null) ?: UUID.randomUUID(),
            role = fields.require("role", Role.serializer()),
            text = fields.require("text", String.serializer()),
            date = fields.date("date", Instant.now()),
            failed = fields.bool("failed", false),
            reported = fields.bool("reported", false),
            retryID = fields.value("retryID", UuidSerializer.nullable, null),
            emotion = fields.value("emotion", Emotion.serializer().nullable, null),
            intensity = fields.value("intensity", Double.serializer().nullable, null),
        )
    }
}

/**
 * Talking to the slime. Replies come from slime.hyprnode.de/api/v1/chat,
 * which has an AI service answer in character. Nothing is sent before the
 * player agreed to that ([aiConsent]) and went online
 * (`FriendsStore.isOnline`). The conversation itself is kept only on this
 * phone. The Android port of App/Online/ChatStore.swift.
 *
 * One instance lives in `SlimyApplication.chat` (`LocalChatStore` in Compose).
 * Its state lives on the main thread; [send], [retry] and [report] move
 * there themselves.
 */
class ChatStore(context: Context, private val store: GameStore, private val friends: FriendsStore) {
    private val context: Context = context.applicationContext
    private val prefs: SharedPreferences = this.context.getSharedPreferences(FriendsStore.prefsName, Context.MODE_PRIVATE)

    /** Where screens launch sends, so a reply on its way still lands when the chat closes. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _isThinking = MutableStateFlow(false)

    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    /** The player agreed that messages go to an AI service to write the replies. Nothing is sent without it. Saved when it changes. */
    val aiConsent = MutableStateFlow(prefs.getBoolean(consentKey, false))

    /** What's typed but not sent yet. Kept while the app runs, so closing the chat doesn't lose it. */
    val draft = MutableStateFlow("")

    /** Goes up with every [clear]. A reply that was on its way to a cleared conversation is dropped instead of landing in the next one. */
    private var conversation = 0

    init {
        val saved = prefs.getString(historyKey, null)?.let {
            runCatching { SlimyJson.storage.decodeFromString(LossyListSerializer(ChatMessage.serializer()), it) }.getOrNull()
        }
        if (saved != null) {
            val last = saved.lastOrNull()
            // The app was closed while a reply was on its way: that reply
            // never comes, so the message gets a Try Again instead of hanging.
            _messages.value = if (last != null && last.role == ChatMessage.Role.me && !last.failed) {
                saved.dropLast(1) + last.copy(failed = true) + ChatMessage(
                    role = ChatMessage.Role.note, text = "Your message didn't get an answer. Try again.",
                    failed = true, retryID = last.id,
                )
            } else {
                saved
            }
        }
        scope.launch {
            aiConsent.collect { agreed ->
                if (prefs.getBoolean(consentKey, false) != agreed) prefs.edit().putBoolean(consentKey, agreed).apply()
            }
        }
    }

    /** Whether a message could go out right now. */
    val canSend: Boolean
        get() = aiConsent.value && friends.isOnline.value && !_isThinking.value

    /** Stops sending messages to the AI service. The next message asks again. */
    fun revokeConsent() {
        aiConsent.value = false
    }

    /** The app came to the front. Nothing to do: the chat only talks when spoken to. */
    fun onForeground() {}

    /** The app left the screen: the conversation is on disk already; this writes it once more. */
    fun onBackground() {
        scope.launch { save() }
    }

    /**
     * Sends a message and waits for the reply. Returns false when nothing was
     * sent: empty text, no consent, not online, or a reply is on its way.
     */
    suspend fun send(text: String): Boolean = onMain {
        val trimmed = text.trimmedSwift().prefixChars(maxLength)
        if (trimmed.isEmpty() || !canSend) return@onMain false
        val message = ChatMessage(role = ChatMessage.Role.me, text = trimmed)
        _messages.value = _messages.value + message
        save()
        deliver(message)
        true
    }

    /** Sends the message an error note is about once more. */
    suspend fun retry(note: ChatMessage): Unit = onMain {
        val target = note.retryID ?: return@onMain
        if (!canSend) return@onMain
        val message = _messages.value.firstOrNull { it.id == target } ?: return@onMain
        // Back at the end, so the reply follows it.
        val again = message.copy(failed = false, date = Instant.now())
        _messages.value = _messages.value.filter { it.id != note.id && it.id != target } + again
        save()
        deliver(again)
    }

    /** Reports a slime reply to the Slimy team. Only its text is sent. */
    suspend fun report(message: ChatMessage): Boolean = onMain {
        if (message.role != ChatMessage.Role.slime || message.reported) return@onMain false
        val text = message.text.prefixChars(500)
        val sent = friends.report(deviceID = null, reason = ReportReason.chat, note = null, text = text)
        if (sent && _messages.value.any { it.id == message.id }) {
            _messages.value = _messages.value.map { if (it.id == message.id) it.copy(reported = true) else it }
            save()
        }
        sent
    }

    /** Deletes the conversation, also while a reply is on its way: that reply is dropped when it comes. */
    fun clear() {
        conversation += 1
        _isThinking.value = false
        _messages.value = emptyList()
        save()
    }

    // MARK: Sending

    private suspend fun deliver(message: ChatMessage) {
        val round = conversation
        _isThinking.value = true
        try {
            val history = turns(message.id)
            val slimeContext = chatContext(store.state.value.slime, Instant.now())
            try {
                val api = friends.authorizedAPI()
                val reply = api.chat(message.text, history, slimeContext)
                // Cleared while the reply was on its way.
                if (round != conversation || _messages.value.none { it.id == message.id }) return
                // A message the server's filter stops still gets an in-character
                // reply with `confused`, so it shows like any other reply.
                val text = reply.reply.trimmedSwift().prefixChars(maxReplyLength)
                // A feeling this version doesn't know is left out.
                val felt = reply.emotion?.let { enumOf<Emotion>(it.name) }
                val intensity = if (felt == null) null else reply.emotion?.intensity
                _messages.value = _messages.value + ChatMessage(
                    role = ChatMessage.Role.slime, text = text.ifEmpty { "*wobbles*" },
                    emotion = felt, intensity = intensity,
                )
                express(felt, intensity ?: 0.0, reply.reaction)
                Feedback.shared.play(Sound.pop)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A ban takes the device offline; a device the server forgot
                // registers again, so Try Again can work.
                friends.handleServerError(e)
                if (round != conversation || _messages.value.none { it.id == message.id }) return
                val problem = describe(e)
                _messages.value = _messages.value.map { if (it.id == message.id) it.copy(failed = true) else it } +
                    ChatMessage(
                        role = ChatMessage.Role.note, text = problem.first, failed = true,
                        retryID = if (problem.second) message.id else null,
                    )
                Feedback.shared.play(Sound.nope)
            }
        } finally {
            // After a clear the wait is already over, and a message in the new
            // conversation may be waiting for its own reply by now.
            if (round == conversation) {
                _isThinking.value = false
                save()
            }
        }
    }

    /** The slime feels a bit more of what its reply expressed and shows it. */
    private fun express(emotion: Emotion?, intensity: Double, asked: Reaction?) {
        var reaction = asked ?: Reaction.none
        if (emotion != null) {
            store.feel(emotion, intensity * feelingShare)
            if (reaction == Reaction.none && intensity >= 0.15) reaction = reaction(emotion, intensity)
        }
        if (reaction == Reaction.none) return
        val chosen = reaction
        store.update { it.slime.react(chosen, Instant.now()) }
    }

    /** The conversation before a message, as the server wants it: only what was really said, newest last. */
    private fun turns(before: UUID): List<ChatTurn> {
        val turns = mutableListOf<ChatTurn>()
        for (m in _messages.value) {
            if (m.id == before) break
            if (m.failed) continue
            when (m.role) {
                ChatMessage.Role.me -> turns.add(ChatTurn(ChatTurn.Role.user, m.text))
                ChatMessage.Role.slime -> turns.add(ChatTurn(ChatTurn.Role.slime, m.text))
                ChatMessage.Role.note -> continue
            }
        }
        return turns.takeLast(historyTurns)
    }

    private fun save() {
        val kept = _messages.value.takeLast(keep)
        val text = SlimyJson.storage.encodeToString(ListSerializer(ChatMessage.serializer()), kept)
        prefs.edit().putString(historyKey, text).apply()
    }

    private suspend fun <T> onMain(block: suspend () -> T): T =
        withContext(Dispatchers.Main.immediate) { block() }

    companion object {
        const val consentKey = "slimy.chat.aiConsent"
        const val privacyURL = "https://slime.hyprnode.de/privacy"

        /** The longest message the server accepts. */
        const val maxLength = 500

        /** The longest reply the server sends. */
        const val maxReplyLength = 240

        /** How much of a reply's feeling rubs off on the slime: a reply at full intensity moves that feeling by about a third. */
        const val feelingShare = 0.35

        private const val historyKey = "slimy.chat.v1"

        /** Messages kept on the phone. */
        private const val keep = 80

        /** Earlier turns sent along so the slime knows what you talked about. */
        private const val historyTurns = 12

        /** The server's answer when no AI service is set up. Trying again right away can't help. */
        private const val notSetUp = "Talking isn't set up on the server right now."

        /** The animation that shows a feeling. */
        fun reaction(showing: Emotion, intensity: Double): Reaction = when (showing) {
            Emotion.joy -> if (intensity >= 0.7) Reaction.celebrate else Reaction.wave
            Emotion.sadness -> Reaction.melt
            Emotion.anger -> Reaction.sulk
            Emotion.fear -> Reaction.hide
            Emotion.love -> if (intensity >= 0.6) Reaction.love else Reaction.blush
            Emotion.curiosity -> Reaction.curious
            Emotion.boredom -> Reaction.stretch
            Emotion.excitement -> Reaction.celebrate
            Emotion.sleepiness -> Reaction.stretch
        }

        /**
         * What the AI service learns about the slime, including how it feels
         * right now, so the reply comes in that mood. No owner name, no device
         * ID, and no friend's or caretaker's name.
         */
        internal fun chatContext(slime: Slime, now: Instant, zone: ZoneId = ZoneId.systemDefault()): ChatContext {
            val feelings = slime.emotions.wire
            return ChatContext(
                name = slime.name.prefixChars(16),
                ageDays = slime.ageDays(now),
                level = slime.level,
                mood = slime.mood,
                needs = slime.needs.copy(),
                favorite = slime.favorite,
                localTime = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zone).format(now),
                emotions = feelings.ifEmpty { null },
            )
        }

        /** What to tell the player, and whether trying again could help. */
        internal fun describe(error: Throwable): Pair<String, Boolean> {
            if (error is APIError) {
                if (error is APIError.offline) {
                    return "Your message didn't go through. Check your internet connection and try again." to true
                }
                if (error.isUnauthorized) return "Slimy is reconnecting to its server. Try again in a moment." to true
                if (error is APIError.server) {
                    // The server's own words, shown as they are: for example
                    // "Your slime is sleepy from all the talking. Try again later."
                    // (503) or a rate limit (429).
                    val message = error.serverMessage
                    if (message != null) return message to (error.isTransient && message != notSetUp)
                    // No JSON error, for example a proxy page while the server restarts.
                    return (
                        if (error.status >= 500) {
                            "Slimy's server isn't answering right now. Try again in a moment."
                        } else {
                            "Something went wrong. Your message wasn't sent."
                        }
                        ) to error.isTransient
                }
                return error.errorDescription to error.isTransient
            }
            if (error is SerializationException || error is IllegalArgumentException) {
                return "The reply got lost on the way. Try again." to true
            }
            if (error is NotOnlineError) return error.errorDescription to true
            val described = error.message
            if (!described.isNullOrBlank()) return described to true
            return "Something went wrong. Your message wasn't sent." to true
        }
    }
}
