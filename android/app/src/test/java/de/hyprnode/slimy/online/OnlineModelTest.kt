package de.hyprnode.slimy.online

import de.hyprnode.slimy.model.CareKind
import de.hyprnode.slimy.model.Emotion
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.LossyListSerializer
import de.hyprnode.slimy.model.Reaction
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimeSnapshot
import de.hyprnode.slimy.model.SlimyJson
import de.hyprnode.slimy.model.addingTimeInterval
import de.hyprnode.slimy.ui.screens.care.CareInvite
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Invites, the care activity list, saved care state and the chat's pure rules. */
class OnlineModelTest {
    private val now = Instant.parse("2026-10-25T12:00:00Z")

    // MARK: Invites

    @Test
    fun inviteLinksGiveTheirCode() {
        assertEquals("ABC234", FriendInvite.code("https://slime.hyprnode.de/f/abc234"))
        assertEquals("ABC234", FriendInvite.code("slimy://f/ABC234"))
        assertEquals("ABC234", FriendInvite.code("HTTPS://Slime.Hyprnode.DE/f/ABC234"))
        assertNull(FriendInvite.code("https://example.com/f/ABC234"))
        assertNull(FriendInvite.code("https://slime.hyprnode.de/g/ABC234"))
        assertNull(FriendInvite.code("https://slime.hyprnode.de/f/ABC234/x"))
        // 0, O, 1, I and L aren't in the alphabet.
        assertNull(FriendInvite.code("slimy://f/ABC10O"))
        assertNull(FriendInvite.code("slimy://f/ABC23"))
        assertNull(FriendInvite.code("not a url"))
    }

    @Test
    fun typedCodesAreCleaned() {
        assertEquals("ABC234", FriendInvite.sanitize(" abc-234 xyz"))
        assertEquals("AB", FriendInvite.sanitize("äöüAB"))
        assertEquals(
            "Be my slime friend in Slimy! Code: ABC234 https://slime.hyprnode.de/f/ABC234",
            FriendInvite.message("ABC234"),
        )
    }

    @Test
    fun careInvitesCarryTheirCode() {
        val message = CareInvite.message("XYZ789", "Blob")
        assertEquals("XYZ789", CareInvite.code(message))
        assertEquals("XYZ789", CareInvite.code("code:   xyz789 thanks"))
        assertNull(CareInvite.code("Code: XY"))
        assertNull(CareInvite.code("no code here"))
    }

    // MARK: Care activity

    @Test
    fun activityIsKeptOnceNewestFirstForAWeek() {
        fun entry(id: String, hoursAgo: Double) = CareActivity(
            id = id, kind = CareKind.feed, food = Food.apple, by = "Anna", slimeName = "Blob",
            at = now.addingTimeInterval(-hoursAgo * 3600),
        )
        val old = listOf(entry("a", 5.0), entry("b", 200.0))
        val merged = CareActivity.merged(listOf(entry("c", 1.0), entry("a", 5.0)), old, now)
        assertEquals(listOf("c", "a"), merged.map { it.id })
        val many = (0 until 150).map { entry("x$it", it / 10.0) }
        assertEquals(CareActivity.limit, CareActivity.merged(many, emptyList(), now).size)
    }

    @Test
    fun activityReadsLikeASentence() {
        val wire = CareEventWire(
            id = "e", deviceId = "p", ownerName = "Anna", kind = CareKind.play, game = GameID.bubblePop,
            at = now.addingTimeInterval(-300.0),
        )
        val entry = CareActivity(wire, "Blob")
        assertEquals("Anna played with Blob", entry.text)
        assertEquals("Anna played with Blob · 5 min ago", entry.line(now))
        assertEquals(GameID.bubblePop.title, entry.detail)
        assertEquals("just now", CareActivity.ago(now.addingTimeInterval(-59.0), now))
        assertEquals("1 hour ago", CareActivity.ago(now.addingTimeInterval(-3600.0), now))
        assertEquals("3 hours ago", CareActivity.ago(now.addingTimeInterval(-3 * 3600.0), now))
        assertEquals("yesterday", CareActivity.ago(now.addingTimeInterval(-30 * 3600.0), now))
        assertEquals("4 days ago", CareActivity.ago(now.addingTimeInterval(-4 * 86400.0), now))
    }

    @Test
    fun pendingCareAndActivitySurviveARestart() {
        val snapshot = SlimeSnapshot.of(Slime(name = "Mochi"), now, "other")
        val pending = PendingCare(
            partnerID = "p", partnerName = "Anna", slimeName = "Mochi", snapshot = snapshot, version = 3,
            cursor = "12", confirmed = true, joinedAt = now,
        )
        val text = SlimyJson.api.encodeToString(PendingCare.serializer(), pending)
        assertTrue(text.contains("\"joinedAt\":\"2026-10-25T12:00:00Z\""))
        val back = SlimyJson.api.decodeFromString(PendingCare.serializer(), text)
        assertEquals("Mochi", back.snapshot?.name)
        assertEquals(pending.copy(snapshot = back.snapshot), back)

        val activity = listOf(CareActivity("a", CareKind.pet, by = "Anna", slimeName = "Mochi", at = now))
        val saved = SlimyJson.api.encodeToString(ListSerializer(CareActivity.serializer()), activity)
        val withUnknown = saved.dropLast(1) + """,{"id":"b","kind":"dance","by":"x","slimeName":"y","at":"2026-10-25T12:00:00Z"}]"""
        assertEquals(activity, SlimyJson.api.decodeFromString(LossyListSerializer(CareActivity.serializer()), withUnknown))
    }

    // MARK: Chat

    @Test
    fun savedChatsLoadLeniently() {
        val id = UUID.randomUUID()
        val text = """[
          {"role":"me","text":"hi"},
          {"id":"${id.toString().uppercase()}","role":"slime","text":"hello!","date":1761393600,"emotion":"wonder","intensity":0.8,"reported":true},
          {"role":"robot","text":"?"}
        ]"""
        val messages = SlimyJson.storage.decodeFromString(LossyListSerializer(ChatMessage.serializer()), text)
        assertEquals(2, messages.size)
        assertFalse(messages[0].failed)
        assertEquals(id, messages[1].id)
        assertNull(messages[1].emotion)
        assertTrue(messages[1].reported)
        assertEquals(Instant.ofEpochSecond(1761393600), messages[1].date)

        val reply = ChatMessage(role = ChatMessage.Role.slime, text = "yay", emotion = Emotion.joy, intensity = 0.9)
        val round = SlimyJson.storage.decodeFromString(
            ChatMessage.serializer(), SlimyJson.storage.encodeToString(ChatMessage.serializer(), reply),
        )
        assertEquals(reply, round)
    }

    @Test
    fun feelingsBecomeAnimations() {
        assertEquals(Reaction.celebrate, ChatStore.reaction(Emotion.joy, 0.8))
        assertEquals(Reaction.wave, ChatStore.reaction(Emotion.joy, 0.3))
        assertEquals(Reaction.love, ChatStore.reaction(Emotion.love, 0.6))
        assertEquals(Reaction.blush, ChatStore.reaction(Emotion.love, 0.59))
        assertEquals(Reaction.melt, ChatStore.reaction(Emotion.sadness, 0.1))
        assertEquals(Reaction.hide, ChatStore.reaction(Emotion.fear, 1.0))
    }

    @Test
    fun errorsBecomeNotesWithOrWithoutTryAgain() {
        assertEquals(
            "Your message didn't go through. Check your internet connection and try again." to true,
            ChatStore.describe(APIError.offline()),
        )
        assertEquals("Slimy is reconnecting to its server. Try again in a moment." to true, ChatStore.describe(APIError.server(401, "x")))
        val sleepy = "Your slime is sleepy from all the talking. Try again later."
        assertEquals(sleepy to true, ChatStore.describe(APIError.server(503, sleepy)))
        assertEquals(
            "Talking isn't set up on the server right now." to false,
            ChatStore.describe(APIError.server(503, "Talking isn't set up on the server right now.")),
        )
        assertEquals("Too many messages today." to true, ChatStore.describe(APIError.server(429, "Too many messages today.")))
        assertEquals("Not allowed." to false, ChatStore.describe(APIError.server(400, "Not allowed.")))
        assertEquals(
            "Slimy's server isn't answering right now. Try again in a moment." to true,
            ChatStore.describe(APIError.server(502, null)),
        )
        assertEquals("Something went wrong. Your message wasn't sent." to false, ChatStore.describe(APIError.server(418, null)))
        assertEquals("Go online in Friends to use this." to true, ChatStore.describe(NotOnlineError()))
    }

    @Test
    fun theAiLearnsOnlyAboutTheSlime() {
        val slime = Slime(name = "AVeryLongSlimeName!!", birthday = now.addingTimeInterval(-3 * 86400.0))
        slime.emotions.feel(Emotion.curiosity, 0.6)
        val context = ChatStore.chatContext(slime, now, ZoneOffset.UTC)
        assertEquals("AVeryLongSlimeNa", context.name)
        assertEquals(slime.level, context.level)
        assertEquals(slime.mood, context.mood)
        assertEquals(slime.favorite, context.favorite)
        assertTrue(context.localTime.isNotBlank())
        assertTrue(context.emotions!!.containsKey("curiosity"))
        val json = SlimyJson.api.encodeToString(ChatContext.serializer(), context)
        assertFalse(json.contains("owner"))
    }
}
