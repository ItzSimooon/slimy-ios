package de.hyprnode.slimy.screenshots

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import de.hyprnode.slimy.core.DailyStore
import de.hyprnode.slimy.core.GameStore
import de.hyprnode.slimy.core.LocalChatStore
import de.hyprnode.slimy.core.LocalDailyStore
import de.hyprnode.slimy.core.LocalFriendsStore
import de.hyprnode.slimy.core.LocalGameStore
import de.hyprnode.slimy.core.LocalIsland
import de.hyprnode.slimy.core.SaveStore
import de.hyprnode.slimy.model.Accessories
import de.hyprnode.slimy.model.CrateKind
import de.hyprnode.slimy.model.Emotion
import de.hyprnode.slimy.model.Food
import de.hyprnode.slimy.model.GameID
import de.hyprnode.slimy.model.GameRecord
import de.hyprnode.slimy.model.GameResult
import de.hyprnode.slimy.model.GameState
import de.hyprnode.slimy.model.GameStorage
import de.hyprnode.slimy.model.Habitat
import de.hyprnode.slimy.model.Mood
import de.hyprnode.slimy.model.Needs
import de.hyprnode.slimy.model.Skin
import de.hyprnode.slimy.model.SlimyJson
import de.hyprnode.slimy.model.StringRef
import de.hyprnode.slimy.model.checkAchievements
import de.hyprnode.slimy.model.gainXP
import de.hyprnode.slimy.model.tick
import de.hyprnode.slimy.model.Catalog
import de.hyprnode.slimy.model.bath
import de.hyprnode.slimy.model.buy
import de.hyprnode.slimy.model.claimDailyGift
import de.hyprnode.slimy.model.equip
import de.hyprnode.slimy.model.feed
import de.hyprnode.slimy.model.finish
import de.hyprnode.slimy.model.hatch
import de.hyprnode.slimy.model.pet
import de.hyprnode.slimy.online.Challenge
import de.hyprnode.slimy.online.ChatMessage
import de.hyprnode.slimy.online.ChatStore
import de.hyprnode.slimy.online.Friend
import de.hyprnode.slimy.online.FriendRequest
import de.hyprnode.slimy.online.FriendsStore
import de.hyprnode.slimy.online.HyprnodeAPI
import de.hyprnode.slimy.online.RequestList
import de.hyprnode.slimy.online.SlimeProfile
import de.hyprnode.slimy.online.TokenKey
import de.hyprnode.slimy.online.TokenVault
import de.hyprnode.slimy.platform.IslandController
import de.hyprnode.slimy.ui.theme.SlimyTheme
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The demo the screenshots show, close to the iPhone app's screenshot mode
 * (`GameStore.prepareScreenshotDemo`): Mochi, a lime slime hatched nine days
 * ago and cared for every day since, now level 6 with 525 coins, a few
 * things bought and worn, two wooden crates and a golden one to open.
 */
internal object Demo {
    const val owner = "Sam"
    private const val day = 86_400L

    /** A hatched, settled-in slime with nine days of history, built with the game's own rules. */
    fun state(now: Instant): GameState {
        val start = now.minusSeconds(9 * day + 2 * 3600)
        val s = GameState()
        s.hatch("Mochi", Skin.lime, 2026uL, start)
        s.player.name = owner
        s.player.tips = s.player.tips + "onboarded"
        s.settings.notifications.asked = true
        // Pocket money for nine days of snacks and a few things from the shop.
        s.wallet.earn(900)

        val foods = listOf(Food.apple, Food.berry, Food.carrot, Food.cookie, Food.berry, Food.soup, Food.apple, Food.honey, Food.berry)
        val games = listOf(
            GameID.snackCatcher, GameID.bubblePop, GameID.slimeJump, GameID.memoryGarden, GameID.snackCatcher,
            GameID.colorMatch, GameID.treasureDig, GameID.snackCatcher, GameID.basketball,
        )
        for (d in 0 until 9) {
            val t = start.plusSeconds(d * day + 3600)
            s.claimDailyGift(t)
            s.feed(foods[d], t)
            s.pet(t.plusSeconds(600))
            s.finish(GameResult(games[d], score = 38 + d * 9, session = UUID(7L, d.toLong()), finishedAt = t.plusSeconds(1800)))
            if (d % 3 == 1) s.bath(t.plusSeconds(3000))
        }
        val shopping = start.plusSeconds(8 * day + 5000)
        for (id in listOf("hat.flower", "glasses.round", "scarf.red", "furn.bed", "furn.plant", "furn.rug", "furn.lights", "furn.poster")) {
            Catalog.item(id)?.let { s.buy(it, shopping) }
        }
        for (id in listOf("hat.flower", "scarf.red", "furn.bed", "furn.plant", "furn.rug", "furn.lights", "furn.poster")) {
            s.equip(id, shopping)
        }
        s.buy(Habitat.garden, shopping)
        s.inventory.habitat = Habitat.room

        // Level 6 the way the game gets there: each level up is remembered and paid.
        if (s.slime.xp < 720) s.gainXP(720 - s.slime.xp, shopping.plusSeconds(600), StringRef(""))
        s.player.xp = 1_440
        s.slime.bond = 46.0
        // Whatever is still due (achievements, feelings settling) is paid now, not when a screen ticks.
        s.checkAchievements(now)
        s.tick(now)
        // The numbers the screenshots promise: level 6, 525 coins, three crates.
        s.wallet.coins = 525
        s.crates = mapOf(CrateKind.wooden to 2, CrateKind.golden to 1)
        s.inventory.pantry = mapOf(Food.cookie to 2, Food.berry to 1)
        s.progress.records = s.progress.records + (
            GameID.snackCatcher to GameRecord(best = 112, plays = 6, lastPlayed = start.plusSeconds(8 * day), tutorialSeen = true)
        )
        // Content and awake, a little hungry and ready for a bath.
        s.slime.needs = Needs(hunger = 68.0, `fun` = 74.0, energy = 82.0, clean = 55.0, love = 90.0)
        s.slime.isAsleep = false
        // A good morning: cheerful, a little curious.
        s.slime.feel(Emotion.joy, 0.6)
        s.slime.feel(Emotion.curiosity, 0.2)
        s.slime.say = null
        s.slime.lastUpdate = now
        s.player.lastSeen = now
        return s
    }

    /** Before hatching: the egg on the first screen. */
    fun egg(): GameState = GameState()

    private fun profile(owner: String, name: String, skin: Skin, level: Int, happiness: Int?, now: Instant, mood: Mood = Mood.idle, equipped: Accessories? = null) =
        SlimeProfile(
            ownerName = owner, name = name, skin = skin, level = level, mood = mood,
            happiness = happiness, equipped = equipped, updatedAt = now.minusSeconds(1800),
        )

    fun friends(now: Instant): List<Friend> = listOf(
        Friend(
            id = "f-lina", ownerName = "Lina",
            slime = profile("Lina", "Pip", Skin.bubblegum, 8, 86, now, equipped = Accessories(hat = "hat.party")),
            since = now.minusSeconds(20 * day),
        ),
        Friend(
            id = "f-noah", ownerName = "Noah",
            slime = profile("Noah", "Bloop", Skin.ocean, 4, 61, now, equipped = Accessories(glasses = "glasses.shades")),
            since = now.minusSeconds(6 * day),
        ),
        Friend(
            id = "f-ava", ownerName = "Ava",
            slime = profile("Ava", "Jellybean", Skin.grape, 11, null, now, mood = Mood.sleep),
            since = now.minusSeconds(40 * day),
        ),
    )

    fun requests(now: Instant) = RequestList(
        incoming = listOf(
            FriendRequest(
                id = "r-kai", deviceId = "d-kai", ownerName = "Kai",
                slime = profile("Kai", "Mossy", Skin.lava, 3, 77, now), createdAt = now.minusSeconds(2 * 3600),
            ),
        ),
    )

    fun challenges(now: Instant) = listOf(
        Challenge(
            id = "c-1", game = GameID.bubblePop, seed = "4242", friendId = "f-lina", friendName = "Lina",
            sentByMe = false, theirScore = 128, createdAt = now.minusSeconds(3 * 3600), expiresAt = now.plusSeconds(2 * day),
        ),
    )

    fun chat(now: Instant) = listOf(
        ChatMessage(role = ChatMessage.Role.me, text = "Hi Mochi! How was your day?", date = now.minusSeconds(600)),
        ChatMessage(
            role = ChatMessage.Role.slime, text = "Squishy and great! I caught 112 snacks and found a berry in my crate.",
            date = now.minusSeconds(590), emotion = Emotion.joy, intensity = 0.7,
        ),
        ChatMessage(role = ChatMessage.Role.me, text = "What should we play next?", date = now.minusSeconds(300)),
        ChatMessage(
            role = ChatMessage.Role.slime, text = "Bubble Pop! Pip beat me yesterday. I want a rematch.",
            date = now.minusSeconds(290), emotion = Emotion.excitement, intensity = 0.6,
        ),
    )
}

/** An AES key in memory, standing in for the Android Keystore (which Robolectric doesn't have). */
private class MemoryKey : TokenKey {
    private var key: SecretKey? = null

    override fun key(create: Boolean): SecretKey? {
        if (key == null && create) key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return key
    }
}

/** slime.hyprnode.de for screenshots: answers each path with the demo's friends, nothing leaves the test. */
private class DemoServer(now: Instant) {
    private val api = HyprnodeAPI.encoder
    private val routes: Map<String, String> = mapOf(
        "GET me" to """{"deviceId":"d-me","ownerName":"${Demo.owner}","privacy":{"acceptRequests":true,"shareMood":true}}""",
        "GET friends" to api.encodeToString(ListSerializer(Friend.serializer()), Demo.friends(now)),
        "GET friend-requests" to api.encodeToString(RequestList.serializer(), Demo.requests(now)),
        "GET challenges" to api.encodeToString(ListSerializer(Challenge.serializer()), Demo.challenges(now)),
        "GET inbox" to """{"events":[]}""",
        "GET friends/leaderboard" to """{"games":[]}""",
    )

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> answer(chain.request()) }).build()

    private fun answer(request: Request): Response {
        val path = request.url.encodedPath.removePrefix("/api/v1/")
        val body = routes["${request.method} $path"]
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (body != null) 200 else 204)
            .message("Demo")
            .body((body ?: "").toResponseBody("application/json".toMediaType()))
            .build()
    }

    companion object {
        const val base = "https://slime.demo/api/v1/"
    }
}

/**
 * Every store the screens read, built like `SlimyApplication` builds them but
 * from the demo: the save in a private folder, the friends from [DemoServer]
 * when [online], the chat history and consent in the online preferences.
 */
internal class DemoWorld(
    context: Context,
    state: GameState,
    online: Boolean = false,
    chat: List<ChatMessage> = emptyList(),
    chatConsent: Boolean = false,
) {
    val store: GameStore
    val daily: DailyStore
    val friends: FriendsStore
    val chat: ChatStore
    val island: IslandController

    init {
        val app = context.applicationContext
        val now = Instant.now()
        val dir = File(app.filesDir, "demo").apply { mkdirs() }
        File(dir, GameStorage.fileName).writeText(GameStorage.encode(state))
        store = GameStore(app, SaveStore(dir), app.getSharedPreferences("slimy", Context.MODE_PRIVATE), "1.1.0")
        store.crateSeed = { 20_261_026uL }
        daily = DailyStore(app, store)
        daily.refresh()

        val prefs = app.getSharedPreferences(FriendsStore.prefsName, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("slimy.friends.owner", Demo.owner)
            .putBoolean("slimy.friends.online", online)
            .putBoolean(ChatStore.consentKey, chatConsent)
            .apply()
        if (chat.isNotEmpty()) {
            prefs.edit()
                .putString("slimy.chat.v1", SlimyJson.storage.encodeToString(ListSerializer(ChatMessage.serializer()), chat))
                .apply()
        }
        val vault = TokenVault(File(dir, "friends-token"), MemoryKey())
        if (online) vault.save("demo-token")
        val server = DemoServer(now)
        friends = FriendsStore(app, store, vault) { token -> HyprnodeAPI(token, server.client, DemoServer.base) }
        HyprnodeAPI.hasNetwork = { true }
        this.chat = ChatStore(app, store, friends)
        island = IslandController(app, store, friends)
    }

    /** The app's providers and theme around a screen, like `MainActivity` and `AppRoot`. */
    @Composable
    fun Show(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalGameStore provides store,
            LocalDailyStore provides daily,
            LocalFriendsStore provides friends,
            LocalChatStore provides chat,
            LocalIsland provides island,
        ) {
            SlimyTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    content()
                }
            }
        }
    }
}
