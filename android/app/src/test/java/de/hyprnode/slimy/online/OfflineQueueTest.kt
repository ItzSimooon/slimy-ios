package de.hyprnode.slimy.online

import de.hyprnode.slimy.model.CareKind
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.LossyListSerializer
import de.hyprnode.slimy.model.SlimyJson
import de.hyprnode.slimy.model.addingTimeInterval
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OfflineQueueTest {
    private val now = Instant.parse("2026-10-25T12:00:00Z")

    private fun hug(friend: String = "f", at: Instant = now, coins: Int = 0) =
        PendingAction(kind = PendingAction.Kind.interaction(friend, InteractionKind.hug), coins = coins, createdAt = at)

    private fun care(at: Instant = now) =
        PendingAction(kind = PendingAction.Kind.care(CareKind.feed, Food.apple, null, null, at), createdAt = at)

    // MARK: Keeping the queue short

    @Test
    fun keysAreSwiftUuidStrings() {
        val action = hug()
        assertEquals(action.id.toString().uppercase(), action.key)
        assertEquals(action.key, action.copy().key)
    }

    @Test
    fun staleActionsGoWhenSomethingIsAdded() {
        val old = hug(at = now.addingTimeInterval(-3.0 * 86400 - 1), coins = 30)
        val recent = hug(at = now.addingTimeInterval(-3.0 * 86400 + 60))
        val (kept, dropped) = OfflineQueue.adding(hug(), listOf(old, recent), now)
        assertEquals(listOf(recent.id), kept.take(1).map { it.id })
        assertEquals(2, kept.size)
        assertEquals(listOf(old), dropped)
    }

    @Test
    fun aFullQueueDropsTheOldestCareActionFirst() {
        val queue = (0 until 49).map { hug("f$it") } + care()
        val (kept, dropped) = OfflineQueue.adding(hug("new"), queue, now)
        assertEquals(OfflineQueue.limit, kept.size)
        assertEquals(1, dropped.size)
        assertTrue(dropped.single().isCare)
        assertTrue(kept.none { it.isCare })
        assertEquals("new", kept.last().friendID)
    }

    @Test
    fun withoutCareActionsTheOldestGoes() {
        val queue = (0 until 50).map { hug("f$it") }
        val (kept, dropped) = OfflineQueue.adding(hug("new"), queue, now)
        assertEquals(50, kept.size)
        assertEquals("f0", dropped.single().friendID)
        assertEquals("f1", kept.first().friendID)
    }

    // MARK: Flushing

    private class Run(val queue: MutableList<PendingAction>) {
        val sent = mutableListOf<String>()
        val refunded = mutableListOf<PendingAction>()
        var banned: APIError? = null
        var online = true

        suspend fun flush(answer: (PendingAction) -> Throwable?): Boolean = OfflineQueue.flush(
            pending = queue.toList(),
            isQueued = { a -> queue.any { it.id == a.id } },
            isOnline = { online },
            send = { a ->
                sent.add(a.key)
                answer(a)?.let { throw it }
            },
            remove = { a, refund ->
                if (refund) refunded.add(a)
                queue.removeAll { it.id == a.id }
            },
            banned = { banned = it },
        )
    }

    @Test
    fun everythingSentLeavesTheQueue() = runBlocking {
        val actions = listOf(hug("a"), hug("b"), care())
        val run = Run(actions.toMutableList())
        val careEnded = run.flush { null }
        assertFalse(careEnded)
        assertTrue(run.queue.isEmpty())
        assertEquals(actions.map { it.key }, run.sent)
        assertTrue(run.refunded.isEmpty())
    }

    @Test
    fun aNetworkProblemStopsAndTheResendUsesTheSameKey() = runBlocking {
        val first = hug("a", coins = 20)
        val second = hug("b")
        val run = Run(mutableListOf(first, second))
        run.flush { APIError.offline() }
        // Stopped at the first one: nothing sent after it, nothing dropped or refunded.
        assertEquals(listOf(first.key), run.sent)
        assertEquals(listOf(first, second), run.queue)
        assertTrue(run.refunded.isEmpty())

        run.flush { null }
        assertEquals(listOf(first.key, first.key, second.key), run.sent)
        assertTrue(run.queue.isEmpty())
    }

    @Test
    fun serverErrorsAndTimeoutsAlsoWait() = runBlocking {
        for (error in listOf(APIError.server(503, null), APIError.server(504, "slow"), APIError.notReady(), APIError.server(401, null))) {
            val run = Run(mutableListOf(hug("a"), hug("b")))
            run.flush { error }
            assertEquals(1, run.sent.size)
            assertEquals(2, run.queue.size)
        }
    }

    @Test
    fun aDailyLimitSkipsOnlyThatAction() = runBlocking {
        val limited = hug("a", coins = 15)
        val fine = hug("b")
        val run = Run(mutableListOf(limited, fine))
        run.flush { if (it == limited) APIError.server(429, "Try again tomorrow.") else null }
        assertEquals(listOf(limited), run.queue)
        assertEquals(listOf(limited.key, fine.key), run.sent)
        assertTrue(run.refunded.isEmpty())
    }

    @Test
    fun aFinalNoDropsItWithARefund() = runBlocking {
        val gift = PendingAction(kind = PendingAction.Kind.interaction("a", InteractionKind.gift, Food.cookie), coins = 25)
        val next = hug("b")
        val run = Run(mutableListOf(gift, next))
        run.flush { if (it == gift) APIError.server(404, "Unknown friend.") else null }
        assertTrue(run.queue.isEmpty())
        assertEquals(listOf(gift), run.refunded)
        assertEquals(2, run.sent.size)
    }

    @Test
    fun aBanStopsEverything() = runBlocking {
        val run = Run(mutableListOf(hug("a"), hug("b")))
        run.flush { APIError.server(403, "Banned.") }
        assertEquals(403, run.banned?.status)
        assertEquals(1, run.sent.size)
        assertEquals(2, run.queue.size)
    }

    @Test
    fun aCareActionAnswered409MeansThePartnerStopped() = runBlocking {
        val run = Run(mutableListOf(care(), hug("a")))
        val careEnded = run.flush { if (it.isCare) APIError.server(409, "You're not caring for a slime together right now.") else null }
        assertTrue(careEnded)
        assertTrue(run.queue.isEmpty())

        val friendRun = Run(mutableListOf(hug("a")))
        assertFalse(friendRun.flush { APIError.server(409, "Too many open challenges.") })
    }

    @Test
    fun actionsThatLeftTheQueueAreSkipped() = runBlocking {
        val gone = hug("a", coins = 10)
        val stays = hug("b")
        val run = Run(mutableListOf(gone, stays))
        run.flush { action ->
            // Removing a friend while the first one is on its way already refunded the second.
            if (action == gone) run.queue.removeAll { it == stays }
            null
        }
        assertEquals(listOf(gone.key), run.sent)
        assertTrue(run.refunded.isEmpty())
    }

    @Test
    fun offlineNothingIsSent() = runBlocking {
        val run = Run(mutableListOf(hug("a")))
        run.online = false
        run.flush { null }
        assertTrue(run.sent.isEmpty())
        assertEquals(1, run.queue.size)
    }

    @Test
    fun anUnreadableAnswerCountsAsFinal() = runBlocking {
        val run = Run(mutableListOf(hug("a", coins = 5)))
        run.flush { kotlinx.serialization.SerializationException("bad") }
        assertTrue(run.queue.isEmpty())
        assertEquals(1, run.refunded.size)
    }

    // MARK: Saving the queue

    @Test
    fun theQueueSurvivesARestart() {
        val queue = listOf(
            hug("a", coins = 3),
            PendingAction(kind = PendingAction.Kind.message("b", MessagePreset.goodNight)),
            PendingAction(kind = PendingAction.Kind.coop("c", 120)),
            PendingAction(kind = PendingAction.Kind.challenge("d", GameID.slimeStack, "18446744073709551615", 40)),
            PendingAction(kind = PendingAction.Kind.answer("e", 41)),
            PendingAction(kind = PendingAction.Kind.care(CareKind.play, null, GameID.bubblePop, 75.0, now)),
        )
        val text = SlimyJson.storage.encodeToString(ListSerializer(PendingAction.serializer()), queue)
        val back = SlimyJson.storage.decodeFromString(LossyListSerializer(PendingAction.serializer()), text)
        assertEquals(queue, back)
        assertEquals(queue.map { it.key }, back.map { it.key })
    }

    @Test
    fun actionsThisVersionCantReadAreDroppedNotTheQueue() {
        val good = SlimyJson.storage.encodeToString(ListSerializer(PendingAction.serializer()), listOf(hug("a")))
        val mixed = good.dropLast(1) + """,{"kind":{"type":"kiss","friendID":"x"}}]"""
        val back = SlimyJson.storage.decodeFromString(LossyListSerializer(PendingAction.serializer()), mixed)
        assertEquals(1, back.size)
        assertEquals("a", back.single().friendID)
    }
}
