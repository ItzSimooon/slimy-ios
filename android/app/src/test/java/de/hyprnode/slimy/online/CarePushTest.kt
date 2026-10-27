package de.hyprnode.slimy.online

import de.hyprnode.slimy.model.CareLink
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimeSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Sending the shared slime and settling a version conflict with the other phone. */
class CarePushTest {
    private val now = Instant.parse("2026-05-04T10:00:00Z")

    /** The care side of a phone: its link and its slime's name, changed by reconciling. */
    private class Phone(version: Int, var name: String = "Blob") {
        var link: CareLink? = CareLink(partnerID = "partner", partnerName = "Anna", role = CareRole.host, version = version)
        val reconciled = mutableListOf<SlimeSnapshot>()
        val versionsSent = mutableListOf<Int>()
        val namesSent = mutableListOf<String>()

        fun snapshot(at: Instant) = SlimeSnapshot.of(Slime(name = name), at, "me")

        suspend fun push(at: Instant, put: suspend (SlimeSnapshot, Int) -> CareSnapshotResult): CarePush.Outcome =
            CarePush.rounds(
                link = { link },
                snapshot = { snapshot(at) },
                put = { snapshot, version ->
                    versionsSent.add(version)
                    put(snapshot, version)
                },
                setVersion = { v -> link = link?.copy(version = v) },
                reconcile = { theirs ->
                    reconciled.add(theirs)
                    name = theirs.name
                },
                onSending = { namesSent.add(it) },
            )
    }

    private fun theirs(name: String) = SlimeSnapshot.of(Slime(name = name), now.plusSeconds(60), "other")

    @Test
    fun aSnapshotGoesOutAtTheNextVersion() = runBlocking {
        val phone = Phone(version = 3)
        val outcome = phone.push(now) { _, version -> CareSnapshotResult.saved(version) }
        assertEquals(CarePush.Outcome.saved(4), outcome)
        assertEquals(listOf(4), phone.versionsSent)
        assertEquals(4, phone.link?.version)
        assertTrue(phone.reconciled.isEmpty())
    }

    @Test
    fun aConflictReconcilesAndTriesOnceMore() = runBlocking {
        val phone = Phone(version = 3)
        val answers = ArrayDeque(listOf(CareSnapshotResult.conflict(7, theirs("Mochi")), CareSnapshotResult.saved(8)))
        val outcome = phone.push(now) { _, _ -> answers.removeFirst() }
        assertEquals(CarePush.Outcome.saved(8), outcome)
        // The second try is one past the server's newest version, with the reconciled slime.
        assertEquals(listOf(4, 8), phone.versionsSent)
        assertEquals(listOf("Blob", "Mochi"), phone.namesSent)
        assertEquals(listOf("Mochi"), phone.reconciled.map { it.name })
        assertEquals(8, phone.link?.version)
    }

    @Test
    fun stillBehindAfterTheSecondTry() = runBlocking {
        val phone = Phone(version = 1)
        var newest = 5
        val outcome = phone.push(now) { _, _ -> CareSnapshotResult.conflict(newest++, null) }
        assertEquals(CarePush.Outcome.behind, outcome)
        assertEquals(listOf(2, 6), phone.versionsSent)
        assertEquals(6, phone.link?.version)
        assertTrue(phone.reconciled.isEmpty())
    }

    @Test
    fun aConflictWithoutAVersionMeansNobodyCaresTogether() = runBlocking {
        val phone = Phone(version = 2)
        val outcome = phone.push(now) { _, _ -> CareSnapshotResult.conflict(null, theirs("Mochi")) }
        assertEquals(CarePush.Outcome.notPaired, outcome)
        assertEquals(1, phone.versionsSent.size)
        assertTrue(phone.reconciled.isEmpty())
        assertEquals(2, phone.link?.version)
    }

    @Test
    fun sharingThatEndedMeanwhileStopsThePush() = runBlocking {
        val phone = Phone(version = 2)
        val outcome = phone.push(now) { _, version ->
            phone.link = null
            CareSnapshotResult.saved(version)
        }
        assertEquals(CarePush.Outcome.gone, outcome)
        assertNull(phone.link)

        val other = Phone(version = 2)
        val switched = other.push(now) { _, _ ->
            other.link = other.link?.copy(partnerID = "someone-else")
            CareSnapshotResult.conflict(9, theirs("Mochi"))
        }
        assertEquals(CarePush.Outcome.gone, switched)
        assertTrue(other.reconciled.isEmpty())
    }

    @Test
    fun noLinkNoPush() = runBlocking {
        val phone = Phone(version = 0)
        phone.link = null
        assertEquals(CarePush.Outcome.gone, phone.push(now) { _, v -> CareSnapshotResult.saved(v) })
        assertTrue(phone.versionsSent.isEmpty())
    }

    @Test
    fun errorsReachTheCaller() {
        val phone = Phone(version = 0)
        val error = runCatching {
            runBlocking { phone.push(now) { _, _ -> throw APIError.server(400, "Please choose a different name.") } }
        }.exceptionOrNull()
        assertTrue((error as APIError).isRefused)
        assertEquals(listOf("Blob"), phone.namesSent)
    }

    /** The whole round trip through the HTTP client: a 409 from the server, then the stored second try. */
    @Test
    fun conflictOverHttp() = runBlocking {
        val server = FakeServer()
        server.answer(
            FakeServer.Answer.json(
                409,
                """{"error":"The slime changed on the other phone.","version":5,
                    "snapshot":{"name":"Mochi","xp":800,"updatedAt":"2026-05-04T10:05:00Z","author":"other"}}""",
            ),
            FakeServer.Answer.json(200, """{"version":6}"""),
        )
        val api = server.api()
        val phone = Phone(version = 2)
        val outcome = phone.push(now) { snapshot, version -> api.putCareSnapshot(snapshot, version) }
        assertEquals(CarePush.Outcome.saved(6), outcome)
        assertEquals(listOf("Mochi"), phone.reconciled.map { it.name })
        val bodies = server.seen.map { Json.parseToJsonElement(it.body!!).jsonObject }
        assertEquals(listOf(3, 6), bodies.map { it["version"]!!.jsonPrimitive.content.toInt() })
        assertEquals("Mochi", bodies[1]["snapshot"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        // A version conflict isn't replayed by the server, so no idempotency key is needed.
        assertTrue(server.seen.all { it.header("Idempotency-Key") == null })
    }
}
