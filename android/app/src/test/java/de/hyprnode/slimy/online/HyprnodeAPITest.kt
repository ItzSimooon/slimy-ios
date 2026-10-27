package de.hyprnode.slimy.online

import de.hyprnode.slimy.model.CareKind
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.Mood
import de.hyprnode.slimy.model.Reaction
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.Slime
import de.hyprnode.slimy.model.SlimeSnapshot
import de.hyprnode.slimy.model.Stage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant

class HyprnodeAPITest {
    private lateinit var server: FakeServer

    @Before
    fun setUp() {
        server = FakeServer()
        HyprnodeAPI.hasNetwork = null
    }

    @After
    fun tearDown() {
        HyprnodeAPI.hasNetwork = null
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private inline fun <reified E : Throwable> expectError(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("Expected ${E::class.simpleName}, got $e", e)
        }
        fail("Expected ${E::class.simpleName}")
        throw IllegalStateException()
    }

    // MARK: Requests

    @Test
    fun registerSendsTheNameWithoutAToken() = runBlocking {
        server.answer(200, """{"deviceId":"d1","token":"t1"}""")
        val registration = server.api(token = null).register("Anna")
        assertEquals("d1", registration.deviceId)
        assertEquals("t1", registration.token)
        val request = server.seen.single()
        assertEquals("POST", request.method)
        assertEquals("devices", request.path)
        assertNull(request.header("Authorization"))
        assertEquals("application/json", request.header("Accept"))
        assertEquals("Anna", json(request.body!!)["ownerName"]!!.jsonPrimitive.content)
    }

    @Test
    fun writesCarryTheTokenAndTheIdempotencyKey() = runBlocking {
        server.answer(200, """{"gained":1}""")
        server.api().interact("friend-1", InteractionKind.gift, Food.apple, key = "KEY-1")
        val request = server.seen.single()
        assertEquals("friends/friend-1/interactions", request.path)
        assertEquals("Bearer test-token", request.header("Authorization"))
        assertEquals("KEY-1", request.header("Idempotency-Key"))
        assertTrue(request.header("Content-Type")!!.startsWith("application/json"))
        val body = json(request.body!!)
        assertEquals("gift", body["kind"]!!.jsonPrimitive.content)
        assertEquals("apple", body["food"]!!.jsonPrimitive.content)
    }

    @Test
    fun aHugLeavesTheFoodOut() = runBlocking {
        server.answer(200, "{}")
        server.api().interact("f", InteractionKind.hug, null, key = "K")
        assertFalse(json(server.seen.single().body!!).containsKey("food"))
    }

    @Test
    fun readsAndDeletesHaveNoBody() = runBlocking {
        server.answer(204)
        server.api().removeFriend("f1")
        server.api().leaveCare()
        server.api().deleteAccount()
        assertEquals(listOf("DELETE friends/f1", "DELETE care", "DELETE me"), server.seen.map { "${it.method} ${it.path}" })
        assertTrue(server.seen.all { it.body == null })
    }

    @Test
    fun codesGoUpInUpperCase() = runBlocking {
        server.answer(200, """{"status":"pending","request":{"id":"r","deviceId":"d","ownerName":"Bo","createdAt":"2026-10-25T12:00:00Z"}}""")
        val result = server.api().join("abc234")
        assertEquals("pending", result.status)
        assertEquals("ABC234", json(server.seen.single().body!!)["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun challengeSeedsAreDecimalStrings() = runBlocking {
        server.answer(
            200,
            """{"id":"c","game":"bubblePop","seed":"18446744073709551615","friendId":"f","friendName":"Bo","sentByMe":true,
            |"myScore":12,"createdAt":"2026-10-25T12:00:00Z","expiresAt":"2026-10-28T12:00:00Z"}""".trimMargin(),
        )
        val challenge = server.api().challenge("f", GameID.bubblePop, ULong.MAX_VALUE, 12, key = "K")
        assertEquals(ULong.MAX_VALUE, challenge.seedValue)
        val body = json(server.seen.single().body!!)
        assertEquals("18446744073709551615", body["seed"]!!.jsonPrimitive.content)
        assertTrue(body["seed"]!!.jsonPrimitive.isString)
    }

    @Test
    fun reportsSayNameOnTheWireAndLeaveNullsOut() = runBlocking {
        server.answer(204)
        server.api().report(HyprnodeAPI.ReportBody(deviceId = "d", reason = ReportReason.inappropriateName), key = "K")
        val body = json(server.seen.single().body!!)
        assertEquals("name", body["reason"]!!.jsonPrimitive.content)
        assertEquals(setOf("deviceId", "reason"), body.keys)
    }

    @Test
    fun profilesUseWholeSecondIsoDates() = runBlocking {
        server.answer(204)
        val profile = SlimeProfile(
            ownerName = "Anna", name = "Blob", skin = Skin.lime, level = 3, stage = Stage.young, mood = Mood.idle,
            happiness = 80, equipped = null, updatedAt = Instant.parse("2026-10-25T12:00:00.789Z"),
        )
        server.api().upload(profile)
        val body = json(server.seen.single().body!!)
        assertEquals("2026-10-25T12:00:00Z", body["updatedAt"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("equipped"))
        assertEquals("young", body["stage"]!!.jsonPrimitive.content)
    }

    @Test
    fun recordsGoUpAsAMapWithoutDaily() = runBlocking {
        server.answer(204)
        val wire = HyprnodeAPI.wireRecords(mapOf(GameID.daily to 10, GameID.slimeJump to 2_000_000, GameID.bubblePop to 0, GameID.colorMatch to 7))
        assertEquals(mapOf("slimeJump" to 1_000_000, "colorMatch" to 7), wire)
        server.api().uploadRecords(wire)
        val records = json(server.seen.single().body!!)["records"]!!.jsonObject
        assertEquals(1_000_000, records["slimeJump"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun cursorsArePercentEncoded() = runBlocking {
        assertEquals("care", HyprnodeAPI.carePath(null))
        assertEquals("care", HyprnodeAPI.carePath(""))
        assertEquals("care?after=12%3A34%2Fx", HyprnodeAPI.carePath("12:34/x"))
        server.answer(200, """{"events":[],"cursor":"c2"}""")
        server.api().inbox("a b")
        val request = server.seen.single()
        assertEquals("inbox", request.path)
        assertEquals("after=a%20b", request.query)
    }

    // MARK: Errors

    @Test
    fun theServersWordsBecomeTheError() = runBlocking {
        server.answer(400, """{"error":"Please choose a different name."}""")
        val error = expectError<APIError.server> { runBlocking { server.api().register("x") } }
        assertEquals(400, error.status)
        assertEquals("Please choose a different name.", error.serverMessage)
        assertEquals("Please choose a different name.", error.errorDescription)
        assertTrue(error.isRefused)
        assertFalse(error.isTransient)
    }

    @Test
    fun aBare404MeansTheServerIsNotSetUp() = runBlocking {
        server.answer(404, "<html>nope</html>")
        expectError<APIError.notReady> { runBlocking { server.api().me() } }
        server.answer(404, """{"error":"Unknown friend."}""")
        val known = expectError<APIError.server> { runBlocking { server.api().friend("x") } }
        assertEquals("Unknown friend.", known.serverMessage)
    }

    @Test
    fun statusesSortIntoBannedRateLimitedAndTransient() = runBlocking {
        server.answer(403, """{"error":"This device can't use online features anymore."}""")
        val banned = expectError<APIError> { runBlocking { server.api().friends() } }
        assertTrue(banned.isBanned)
        assertFalse(banned.shouldQueue)

        server.answer(429, """{"error":"Try again tomorrow."}""")
        val limited = expectError<APIError> { runBlocking { server.api().friends() } }
        assertTrue(limited.isRateLimited)
        assertTrue(limited.isTransient)
        assertFalse(limited.shouldQueue)

        server.answer(503, "")
        val down = expectError<APIError> { runBlocking { server.api().friends() } }
        assertEquals("The server reported error 503.", down.errorDescription)
        assertTrue(down.shouldQueue)

        server.answer(401, """{"error":"Unknown device."}""")
        assertTrue(expectError<APIError> { runBlocking { server.api().friends() } }.isUnauthorized)
    }

    @Test
    fun aTimeoutIsA504WithTheSlowAnswer() = runBlocking {
        server.answer(FakeServer.Answer.fail(SocketTimeoutException("timeout")))
        val error = expectError<APIError.server> { runBlocking { server.api().me() } }
        assertEquals(504, error.status)
        assertEquals(HyprnodeAPI.slowServerAnswer, error.errorDescription)
        assertTrue(error.shouldQueue)

        val context = ChatContext("Blob", 3, 2, Mood.idle, Slime().needs, Food.berry, "12:00")
        val chat = expectError<APIError.server> { runBlocking { server.api().chat("hi", emptyList(), context) } }
        assertEquals("Your slime took too long to answer. Try again in a moment.", chat.errorDescription)
    }

    @Test
    fun networkProblemsAreOfflineOrNotReady() = runBlocking {
        server.answer(FakeServer.Answer.fail(UnknownHostException("slime.test")))
        expectError<APIError.notReady> { runBlocking { server.api().me() } }

        HyprnodeAPI.hasNetwork = { false }
        expectError<APIError.offline> { runBlocking { server.api().me() } }

        HyprnodeAPI.hasNetwork = { true }
        server.answer(FakeServer.Answer.fail(IOException("reset")))
        val offline = expectError<APIError.offline> { runBlocking { server.api().me() } }
        assertTrue(offline.isTransient)
        assertNull(offline.status)
    }

    @Test
    fun anUnreadableAnswerIsADecodingError() = runBlocking {
        server.answer(200, """{"reply": 5""")
        val context = ChatContext("Blob", 3, 2, Mood.idle, Slime().needs, Food.berry, "12:00")
        val error = expectError<Exception> { runBlocking { server.api().chat("hi", emptyList(), context) } }
        assertFalse(error is APIError)
        assertEquals("The reply got lost on the way. Try again.", ChatStore.describe(error).first)
    }

    // MARK: Tolerant decoding

    @Test
    fun friendsThisVersionCantReadAreSkipped() = runBlocking {
        server.answer(
            200,
            """[
              {"id":"a","ownerName":"Anna","since":"2026-01-01T00:00:00Z",
               "slime":{"ownerName":"Anna","name":"Blob","skin":"rainbowUnicorn","level":7,"stage":"titan","mood":"dancing",
                        "happiness":"lots","equipped":{"hat":5},"updatedAt":"2026-10-25T12:00:00Z"}},
              {"id":"b","ownerName":"Bo"},
              {"id":"c","ownerName":"Cy","since":"2026-01-01T00:00:00Z","love":12,"inLove":true,
               "slime":{"ownerName":"Cy","name":"Mo","skin":"grape","level":1,"mood":"sad","updatedAt":"2026-10-25T12:00:00Z"}}
            ]""",
        )
        val friends = server.api().friends()
        assertEquals(listOf("a", "c"), friends.map { it.id })
        val anna = friends[0]
        assertEquals(Skin.lime, anna.slime.skin)
        assertEquals(Mood.idle, anna.slime.mood)
        assertNull(anna.slime.stage)
        assertNull(anna.slime.happiness)
        assertNull(anna.slime.equipped)
        assertEquals(Stage.forLevel(7), anna.stage)
        assertEquals(0, anna.love)
        assertEquals(Mood.sad, friends[1].slime.mood)
    }

    @Test
    fun challengesInUnknownGamesAreSkipped() = runBlocking {
        server.answer(
            200,
            """[{"id":"x","game":"laserTag","seed":"1","friendId":"f","friendName":"Bo","sentByMe":false,
                 "createdAt":"2026-10-25T12:00:00Z","expiresAt":"2026-10-28T12:00:00Z"},
                {"id":"y","game":"slimeStack","seed":"9","friendId":"f","friendName":"Bo","sentByMe":false,"theirScore":40,
                 "createdAt":"2026-10-25T12:00:00Z","expiresAt":"2026-10-28T12:00:00Z"}]""",
        )
        val challenges = server.api().challenges()
        assertEquals(listOf("y"), challenges.map { it.id })
        assertEquals(9uL, challenges[0].seedValue)
    }

    @Test
    fun theLeaderboardSkipsWhatItCantShow() = runBlocking {
        server.answer(
            200,
            """{"games":[
              {"game":"laserTag","entries":[{"deviceId":"a","ownerName":"A","slimeName":"S","best":3}]},
              {"game":"daily","entries":[{"deviceId":"a","ownerName":"A","slimeName":"S","best":3}]},
              {"game":"snackCatcher","entries":[]},
              {"game":"bubblePop","entries":[
                 {"deviceId":"a","ownerName":"A","slimeName":"Low","best":10},
                 {"deviceId":"b","ownerName":"B","slimeName":"Broken"},
                 {"deviceId":"c","ownerName":"C","slimeName":"High","best":50,"skin":"neon","stage":"titan","me":true},
                 {"deviceId":"d","ownerName":"D","slimeName":"Tie","best":10}]}
            ]}""",
        )
        val games = server.api().leaderboard()
        assertEquals(listOf(GameID.bubblePop), games.map { it.game })
        val board = games[0]
        assertEquals(listOf("High", "Low", "Tie"), board.entries.map { it.slimeName })
        assertEquals(Skin.lime, board.entries[0].skin)
        assertEquals(Stage.young, board.entries[0].stage)
        assertEquals("c", board.mine?.deviceId)
        assertEquals(1, board.rank(board.entries[0]))
        assertEquals(2, board.rank(board.entries[1]))
        assertEquals(2, board.rank(board.entries[2]))
    }

    @Test
    fun inboxEventsKeepWhatTheyCanRead() = runBlocking {
        server.answer(
            200,
            """{"events":[
              {"id":"1","kind":"gift","food":"dragonFruitDeluxe","friendId":"f","createdAt":"2026-10-25T12:00:00Z"},
              {"id":"2","kind":"message","preset":"seeYouSoon","friendName":"Bo","createdAt":"2026-10-25T12:00:00Z"},
              {"id":"3","kind":"message","preset":"missYou","createdAt":"2026-10-25T12:00:00Z"}
            ],"cursor":"77"}""",
        )
        val page = server.api().inbox(null)
        assertEquals("77", page.cursor)
        assertEquals(3, page.events.size)
        assertNull(page.events[0].food)
        assertEquals("f", page.events[0].friendId)
        assertNull(page.events[1].preset)
        assertEquals(MessagePreset.missYou, page.events[2].preset)
    }

    @Test
    fun chatRepliesAreReadLeniently() = runBlocking {
        server.answer(200, """{"reply":"hi!","reaction":"moonwalk","emotion":{"name":"  Joy ","intensity":3.5}}""")
        val context = ChatContext("Blob", 3, 2, Mood.idle, Slime().needs, Food.berry, "12:00", emotions = mapOf("joy" to 0.4))
        val reply = server.api().chat("hello", listOf(ChatTurn(ChatTurn.Role.user, "earlier")), context)
        assertEquals("hi!", reply.reply)
        assertNull(reply.reaction)
        assertEquals("joy", reply.emotion?.name)
        assertEquals(1.0, reply.emotion!!.intensity, 0.0)
        val body = json(server.seen.single().body!!)
        assertEquals("hello", body["message"]!!.jsonPrimitive.content)
        assertEquals("Blob", body["slime"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(body["slime"]!!.jsonObject.containsKey("emotions"))

        server.answer(200, """{"reply":"ok","reaction":"wave","emotion":{"intensity":0.2}}""")
        val second = server.api().chat("x", emptyList(), context.copy(emotions = null))
        assertEquals(Reaction.wave, second.reaction)
        assertNull(second.emotion)
        assertFalse(json(server.seen.last().body!!)["slime"]!!.jsonObject.containsKey("emotions"))

        server.answer(200, """{"reply":"ok","emotion":{"name":"love"}}""")
        assertEquals(0.5, server.api().chat("x", emptyList(), context).emotion!!.intensity, 0.0)
    }

    // MARK: Care together

    @Test
    fun careStatusReadsNumbersAndSkipsUnknownKinds() = runBlocking {
        server.answer(
            200,
            """{"partner":{"deviceId":"p","ownerName":"Anna","role":"host"},
                "snapshot":{"name":"Blob","skin":"grape","level":4,"xp":300,"updatedAt":"2026-05-04T10:00:00Z"},
                "version":3,
                "events":[
                  {"id":41,"deviceId":"p","kind":"feed","food":"cookie","at":"2026-05-04T09:00:00Z"},
                  {"id":"e2","deviceId":"p","ownerName":"Anna","kind":"dance","at":"2026-05-04T09:10:00Z"},
                  {"id":"e3","deviceId":"p","ownerName":"Anna","kind":"play","game":"laserTag","amount":75,
                   "at":"2026-05-04T09:20:00Z","createdAt":"2026-05-04T09:21:00Z"}
                ],
                "cursor":1234}""",
        )
        val status = server.api().care("1200")
        assertEquals("care", server.seen.single().path)
        assertEquals("after=1200", server.seen.single().query)
        assertEquals("Anna", status.partner?.ownerName)
        assertEquals(CareRole.joiner, status.partner?.myRole)
        assertEquals("Blob", status.snapshot?.name)
        assertEquals("Blob", status.snapshotName)
        assertEquals(3, status.version)
        assertEquals("1234", status.cursor)
        assertEquals(listOf("41", "e3"), status.events.map { it.id })
        val first = status.events[0]
        assertEquals("Your partner", first.ownerName)
        assertEquals(first.at, first.createdAt)
        assertEquals(Food.cookie, first.food)
        val play = status.events[1]
        assertNull(play.game)
        assertEquals(75.0, play.amount!!, 0.0)
        assertEquals(CareKind.play, play.event.kind)
        assertEquals("Anna", play.event.by)
    }

    @Test
    fun aSnapshotThisVersionCantReadStillGivesItsName() = runBlocking {
        server.answer(200, """{"partner":null,"snapshot":{"name":"Mochi","skin":"grape"},"version":"x","events":"nope"}""")
        val status = server.api().care(null)
        assertNull(status.partner)
        assertNull(status.snapshot)
        assertEquals("Mochi", status.snapshotName)
        assertEquals(0, status.version)
        assertTrue(status.events.isEmpty())
        assertNull(status.cursor)
    }

    @Test
    fun whoeverOwnsTheCodeHosts() = runBlocking {
        server.answer(200, """{"partner":{"deviceId":"p","ownerName":"Anna"},"snapshot":null,"version":0}""")
        val joined = server.api().joinCare("abc234")
        assertEquals(CareRole.host, joined.partner.role)
        assertEquals(CareRole.joiner, joined.partner.myRole)
        assertNull(joined.snapshot)
        assertEquals("ABC234", json(server.seen.single().body!!)["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun careEventsClampTheirAmount() = runBlocking {
        server.answer(204)
        val at = Instant.parse("2026-05-04T09:00:00Z")
        val body = CareEventBody.of(CareKind.play, null, GameID.bubblePop, 999.0, at)
        assertEquals(200.0, body.amount!!, 0.0)
        assertNull(CareEventBody.of(CareKind.pet, null, null, Double.NaN, at).amount)
        assertEquals(0.0, CareEventBody.of(CareKind.pet, null, null, -3.0, at).amount!!, 0.0)
        server.api().sendCare(body, key = "CARE-KEY")
        val request = server.seen.single()
        assertEquals("care/events", request.path)
        assertEquals("CARE-KEY", request.header("Idempotency-Key"))
        val sent = json(request.body!!)
        assertEquals("2026-05-04T09:00:00Z", sent["at"]!!.jsonPrimitive.content)
        assertFalse(sent.containsKey("food"))
    }

    @Test
    fun aSavedSnapshotReportsItsVersion() = runBlocking {
        val snapshot = SlimeSnapshot.of(Slime(name = "Blob"), Instant.parse("2026-05-04T10:00:00Z"), "me")
        server.answer(200, """{"version":4}""")
        assertEquals(CareSnapshotResult.saved(4), server.api().putCareSnapshot(snapshot, 4))
        val request = server.seen.single()
        assertEquals("PUT", request.method)
        assertEquals("care/snapshot", request.path)
        val body = json(request.body!!)
        assertEquals(4, body["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("Blob", body["snapshot"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("2026-05-04T10:00:00Z", body["snapshot"]!!.jsonObject["updatedAt"]!!.jsonPrimitive.content)

        // An answer without a body still means it was stored at the version sent.
        server.answer(204)
        assertEquals(CareSnapshotResult.saved(9), server.api().putCareSnapshot(snapshot, 9))
    }

    @Test
    fun aConflictBringsTheServersNewestSlime() = runBlocking {
        val snapshot = SlimeSnapshot.of(Slime(name = "Blob"), Instant.parse("2026-05-04T10:00:00Z"), "me")
        server.answer(
            409,
            """{"error":"Someone else saved first.","version":7,
                "snapshot":{"name":"Mochi","level":6,"xp":900,"updatedAt":"2026-05-04T11:00:00Z","author":"other"}}""",
        )
        val result = server.api().putCareSnapshot(snapshot, 3)
        result as CareSnapshotResult.conflict
        assertEquals(7, result.version)
        assertEquals("Mochi", result.snapshot?.name)
        assertEquals("other", result.snapshot?.author)

        server.answer(409, """{"error":"You're not caring for a slime together right now."}""")
        assertEquals(CareSnapshotResult.conflict(null, null), server.api().putCareSnapshot(snapshot, 3))
    }

    @Test
    fun aRefusedOrTooBigSnapshotThrows() = runBlocking {
        val snapshot = SlimeSnapshot.of(Slime(name = "Blob"), Instant.now(), "me")
        server.answer(400, """{"error":"Please choose a different name."}""")
        assertTrue(expectError<APIError> { runBlocking { server.api().putCareSnapshot(snapshot, 1) } }.isRefused)
        server.answer(413, """{"error":"Too big."}""")
        assertEquals(413, expectError<APIError> { runBlocking { server.api().putCareSnapshot(snapshot, 1) } }.status)
    }

    @Test
    fun careConflictReadsBrokenBodies() {
        assertNull(HyprnodeAPI.careConflict("not json".toByteArray()))
        val conflict = HyprnodeAPI.careConflict("""{"version":"seven","snapshot":{"skin":"lime"}}""".toByteArray())!!
        assertNull(conflict.version)
        assertNull(conflict.snapshot)
    }

    // MARK: Names

    @Test
    fun namesAreCleanedLikeTheServerDoes() {
        assertEquals("Anna Lena", HyprnodeAPI.cleanedName("  Anna \n\t Lena  "))
        assertEquals("A".repeat(24), HyprnodeAPI.cleanedName("A".repeat(30)))
        assertEquals("", HyprnodeAPI.cleanedName("   "))
    }
}
