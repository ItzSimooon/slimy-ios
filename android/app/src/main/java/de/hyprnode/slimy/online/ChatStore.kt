package de.hyprnode.slimy.online

import android.content.Context
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.model.Emotion
import de.hyprnode.slimy.model.JsonDate
import de.hyprnode.slimy.model.Reaction
import de.hyprnode.slimy.model.UuidSerializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** One line in the conversation with the slime. Kept only on this phone. */
@Serializable
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
}

/**
 * Talking to the slime. Replies come from slime.hyprnode.de/api/v1/chat,
 * which has an AI service answer in character. Nothing is sent before the
 * player agreed to that ([aiConsent]) and went online
 * (`FriendsStore.isOnline`). The conversation itself is kept only on this
 * phone. The Android port of App/Online/ChatStore.swift.
 *
 * One instance lives in `SlimyApplication.chat` (`LocalChatStore` in Compose).
 */
// Implemented by the online/platform worker.
class ChatStore(context: Context, private val store: GameStore, private val friends: FriendsStore) {
    private val context: Context = context.applicationContext

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _isThinking = MutableStateFlow(false)

    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    /** The player agreed that messages go to an AI service to write the replies. Nothing is sent without it. Saved when it changes. */
    val aiConsent = MutableStateFlow(false)

    /** What's typed but not sent yet. Kept while the app runs, so closing the chat doesn't lose it. */
    val draft = MutableStateFlow("")

    /** Whether a message could go out right now. */
    val canSend: Boolean
        get() = aiConsent.value && friends.isOnline.value && !_isThinking.value

    /** Stops sending messages to the AI service. The next message asks again. */
    fun revokeConsent() {
        aiConsent.value = false
    }

    /**
     * Sends a message and waits for the reply. Returns false when nothing was
     * sent: empty text, no consent, not online, or a reply is on its way.
     */
    suspend fun send(text: String): Boolean = false

    /** Sends the message an error note is about once more. */
    suspend fun retry(note: ChatMessage) {}

    /** Reports a slime reply to the Slimy team. Only its text is sent. */
    suspend fun report(message: ChatMessage): Boolean = false

    /** Deletes the conversation, also while a reply is on its way: that reply is dropped when it comes. */
    fun clear() {}

    companion object {
        const val consentKey = "slimy.chat.aiConsent"
        const val privacyURL = "https://slime.hyprnode.de/privacy"

        /** The longest message the server accepts. */
        const val maxLength = 500

        /** The longest reply the server sends. */
        const val maxReplyLength = 240

        /** How much of a reply's feeling rubs off on the slime: a reply at full intensity moves that feeling by about a third. */
        const val feelingShare = 0.35

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
    }
}
