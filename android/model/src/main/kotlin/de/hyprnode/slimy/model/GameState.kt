package de.hyprnode.slimy.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

// MARK: - Parts of the save
//
// Every part is a mutable class (Swift structs are values: use `deepCopy()`
// wherever the Swift code copies one and changes the copy). Collections are
// read-only and replaced on change, so copies never share them mutably.

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = Player.Coding::class)
data class Player(
    var name: String = "",
    var createdAt: JsonDate = Instant.now(),
    var xp: Int = 0,
    /** First-launch steps already shown. */
    var tips: Set<String> = emptySet(),
    /** When the app was last opened, for reunion greetings. */
    var lastSeen: JsonDate = Instant.now(),
    var daysActive: Set<String> = emptySet(),
    /** Seasons it has been visited in (Season raw values), for achievements. */
    var seasonsSeen: Set<String> = emptySet(),
) {
    val level: Int get() = Slime.level(forXP = xp / 2)

    internal object Coding : TolerantSerializer<Player>(Player.generatedSerializer()) {
        override fun decode(fields: JsonFields): Player {
            val d = Player()
            val strings = SetSerializer(String.serializer())
            return Player(
                name = fields.string("name", d.name),
                createdAt = fields.date("createdAt", d.createdAt),
                xp = fields.int("xp", d.xp),
                tips = fields.value("tips", strings, d.tips),
                lastSeen = fields.date("lastSeen", d.lastSeen),
                daysActive = fields.value("daysActive", strings, d.daysActive),
                seasonsSeen = fields.value("seasonsSeen", strings, d.seasonsSeen),
            )
        }
    }
}

/**
 * Coins plus a ledger of every reward already paid out. A reward is only
 * ever paid once per id, so replays and duplicate requests can't farm coins.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = Wallet.Coding::class)
data class Wallet(
    var coins: Int = 30,
    var ledger: Map<String, JsonDate> = emptyMap(),
    var earnedTotal: Int = 0,
) {
    fun hasClaimed(id: String): Boolean = ledger[id] != null

    /** Pays a reward once. Returns false if this id was already paid. */
    fun claim(id: String, coins: Int, at: Instant): Boolean {
        if (ledger[id] != null) return false
        ledger = ledger + (id to at)
        this.coins += max(0, coins)
        earnedTotal += max(0, coins)
        return true
    }

    fun spend(amount: Int): Boolean {
        if (amount < 0 || coins < amount) return false
        coins -= amount
        return true
    }

    /** Adds coins that need no receipt, because what paid them can't happen twice. */
    fun earn(amount: Int) {
        coins += max(0, amount)
        earnedTotal += max(0, amount)
    }

    /** Keeps the ledger small. Game and daily receipts expire after 60 days; permanent ones are kept. */
    fun prune(now: Instant) {
        val cutoff = now.addingTimeInterval(-60.0 * 24 * 3600)
        ledger = ledger.filter { (key, date) -> date > cutoff || permanentPrefixes.any { key.startsWith(it) } }
    }

    companion object {
        /** Receipts that are kept forever: they can never be paid twice. */
        val permanentPrefixes = listOf("level:", "achievement:", "buy:", "birthday:", "holiday:")
    }

    internal object Coding : TolerantSerializer<Wallet>(Wallet.generatedSerializer()) {
        override fun decode(fields: JsonFields): Wallet {
            val d = Wallet()
            return Wallet(
                coins = fields.int("coins", d.coins),
                ledger = fields.value("ledger", MapSerializer(String.serializer(), fields.dateSerializer), d.ledger),
                earnedTotal = fields.int("earnedTotal", d.earnedTotal),
            )
        }
    }
}

object FurnitureMapSerializer : FlatMapSerializer<FurnitureSlot, String>(FurnitureSlot.serializer(), String.serializer())
object PantrySerializer : FlatMapSerializer<Food, Int>(Food.serializer(), Int.serializer())
object RecordsSerializer : FlatMapSerializer<GameID, GameRecord>(GameID.serializer(), GameRecord.serializer())
object CratesSerializer : FlatMapSerializer<CrateKind, Int>(CrateKind.serializer(), Int.serializer())

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = Inventory.Coding::class)
data class Inventory(
    var owned: Set<String> = emptySet(),
    var foodsTasted: Set<Food> = emptySet(),
    var favoriteFound: Boolean = false,
    var habitat: Habitat = Habitat.room,
    var habitats: Set<Habitat> = setOf(Habitat.room),
    @Serializable(with = FurnitureMapSerializer::class)
    var furniture: Map<FurnitureSlot, String> = emptyMap(),
    /** Free servings from crates. Feeding uses one before paying coins. */
    @Serializable(with = PantrySerializer::class)
    var pantry: Map<Food, Int> = emptyMap(),
) {
    internal object Coding : TolerantSerializer<Inventory>(Inventory.generatedSerializer()) {
        override fun decode(fields: JsonFields): Inventory {
            val d = Inventory()
            return Inventory(
                owned = fields.value("owned", SetSerializer(String.serializer()), d.owned),
                foodsTasted = fields.value("foodsTasted", SetSerializer(Food.serializer()), d.foodsTasted),
                favoriteFound = fields.bool("favoriteFound", d.favoriteFound),
                habitat = fields.value("habitat", Habitat.serializer(), d.habitat),
                habitats = fields.value("habitats", SetSerializer(Habitat.serializer()), d.habitats),
                furniture = fields.value("furniture", FurnitureMapSerializer, d.furniture),
                pantry = fields.value("pantry", PantrySerializer, d.pantry),
            )
        }
    }
}

@Serializable
enum class StatKey {
    feeds, plays, baths, pets, sleeps, giftsSent, giftsReceived, visits, hugs, friends, eventsSeen, purchases, goodGames;

    companion object {
        val allCases: List<StatKey> get() = entries
    }
}

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = Stats.Coding::class)
data class Stats(
    var feeds: Int = 0,
    var plays: Int = 0,
    var baths: Int = 0,
    var pets: Int = 0,
    var sleeps: Int = 0,
    var giftsSent: Int = 0,
    var giftsReceived: Int = 0,
    var visits: Int = 0,
    var hugs: Int = 0,
    var friends: Int = 0,
    var eventsSeen: Int = 0,
    var purchases: Int = 0,
    var goodGames: Int = 0,
    /** Crates opened. Not a challenge goal, so not a `StatKey`. */
    var cratesOpened: Int = 0,
    /** Care actions your Care Together partner did for the shared slime. */
    var partnerCare: Int = 0,
) {
    operator fun get(key: StatKey): Int = when (key) {
        StatKey.feeds -> feeds
        StatKey.plays -> plays
        StatKey.baths -> baths
        StatKey.pets -> pets
        StatKey.sleeps -> sleeps
        StatKey.giftsSent -> giftsSent
        StatKey.giftsReceived -> giftsReceived
        StatKey.visits -> visits
        StatKey.hugs -> hugs
        StatKey.friends -> friends
        StatKey.eventsSeen -> eventsSeen
        StatKey.purchases -> purchases
        StatKey.goodGames -> goodGames
    }

    operator fun set(key: StatKey, newValue: Int) {
        when (key) {
            StatKey.feeds -> feeds = newValue
            StatKey.plays -> plays = newValue
            StatKey.baths -> baths = newValue
            StatKey.pets -> pets = newValue
            StatKey.sleeps -> sleeps = newValue
            StatKey.giftsSent -> giftsSent = newValue
            StatKey.giftsReceived -> giftsReceived = newValue
            StatKey.visits -> visits = newValue
            StatKey.hugs -> hugs = newValue
            StatKey.friends -> friends = newValue
            StatKey.eventsSeen -> eventsSeen = newValue
            StatKey.purchases -> purchases = newValue
            StatKey.goodGames -> goodGames = newValue
        }
    }

    internal object Coding : TolerantSerializer<Stats>(Stats.generatedSerializer()) {
        override fun decode(fields: JsonFields): Stats = Stats(
            feeds = fields.int("feeds", 0),
            plays = fields.int("plays", 0),
            baths = fields.int("baths", 0),
            pets = fields.int("pets", 0),
            sleeps = fields.int("sleeps", 0),
            giftsSent = fields.int("giftsSent", 0),
            giftsReceived = fields.int("giftsReceived", 0),
            visits = fields.int("visits", 0),
            hugs = fields.int("hugs", 0),
            friends = fields.int("friends", 0),
            eventsSeen = fields.int("eventsSeen", 0),
            purchases = fields.int("purchases", 0),
            goodGames = fields.int("goodGames", 0),
            cratesOpened = fields.int("cratesOpened", 0),
            partnerCare = fields.int("partnerCare", 0),
        )
    }
}

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = Progress.Coding::class)
data class Progress(
    var stats: Stats = Stats(),
    var achievements: Map<String, JsonDate> = emptyMap(),
    @Serializable(with = RecordsSerializer::class)
    var records: Map<GameID, GameRecord> = emptyMap(),
    var challenges: ChallengeBoard = ChallengeBoard(),
    /** Today's quest from the daily plan. */
    var quest: QuestState? = null,
) {
    fun deepCopy(): Progress = copy(
        stats = stats.copy(),
        records = records.mapValues { it.value.copy() },
        challenges = challenges.deepCopy(),
        quest = quest?.copy(),
    )

    internal object Coding : TolerantSerializer<Progress>(Progress.generatedSerializer()) {
        override fun decode(fields: JsonFields): Progress {
            val d = Progress()
            return Progress(
                stats = fields.value("stats", Stats.serializer(), d.stats),
                achievements = fields.value("achievements", MapSerializer(String.serializer(), fields.dateSerializer), d.achievements),
                records = fields.value("records", RecordsSerializer, d.records),
                challenges = fields.value("challenges", ChallengeBoard.serializer(), d.challenges),
                quest = fields.value("quest", QuestState.serializer().nullable, d.quest),
            )
        }
    }
}

/** Things that happened, for dialogue and the memory book. */
@Serializable
data class MemoryEntry(
    @Serializable(with = UuidSerializer::class)
    val id: UUID = UUID.randomUUID(),
    val kind: Kind,
    val at: JsonDate,
    val detail: String = "",
    /** Shown in the memory book. Only important moments are kept forever. */
    val keepsake: Boolean = false,
) {
    /** Entries of kinds this version doesn't know (like the old "inLove") are left out when a save loads. */
    @Serializable
    enum class Kind {
        hatched, fed, favoriteFound, played, highScore, bath, levelUp, stageUp,
        achievement, gift, giftReceived, visit, friendMade, event, reunion,
        birthday, streak, holiday,

        /** A crate was opened. Detail: see `CrateFind`. */
        crate,

        /** The Care Together partner did something. Detail: see `PartnerCareNote`. */
        partnerCare,

        /** Caring together started or ended. Detail: the partner's name. */
        careStarted, careEnded,
    }

    /** How the memory book tells this moment. */
    val summary: String
        get() = when (kind) {
            Kind.hatched -> "$detail hatched"
            Kind.fed -> "Ate ${enumOf<Food>(detail)?.title?.lowercase() ?: "something"}"
            Kind.favoriteFound -> "Found its favourite food: ${enumOf<Food>(detail)?.title ?: detail}"
            Kind.played -> "Played ${game(detail)?.title ?: "a game"}"
            Kind.highScore -> "Record in ${game(detail)?.title ?: "a game"}: ${detail.split(":").lastOrNull { it.isNotEmpty() } ?: ""}"
            Kind.bath -> "Had a bath"
            Kind.levelUp -> "Reached level $detail"
            Kind.stageUp -> "Grew up: ${enumOf<Stage>(detail)?.title ?: detail}"
            Kind.achievement -> "Achievement: ${Achievements.achievement(detail)?.title ?: detail}"
            Kind.gift -> "Sent a gift to $detail"
            Kind.giftReceived -> if (keepsake) "First gift from $detail" else "Gift from $detail"
            Kind.visit -> "Visited $detail"
            Kind.friendMade -> "New friend: $detail"
            Kind.event -> enumOf<PhoneEvent>(detail)?.title ?: "Something happened"
            Kind.reunion -> "Back together after $detail days"
            Kind.birthday -> if (detail == "1") "First birthday" else "Birthday: $detail years old"
            Kind.streak -> "$detail days in a row"
            Kind.holiday -> enumOf<Holiday>(detail)?.greeting ?: "A holiday together"
            Kind.crate -> {
                val find = CrateFind.parse(detail)
                when {
                    find == null -> "Opened a crate"
                    find.rarity == Rarity.legendary -> "Legendary find: ${find.title}"
                    else -> "${find.crate.title}: ${find.title}"
                }
            }
            Kind.partnerCare -> PartnerCareNote.parse(detail)?.summary ?: "Cared for together"
            Kind.careStarted -> if (detail.isEmpty()) "Started caring together" else "Started caring together with $detail"
            Kind.careEnded -> if (detail.isEmpty()) "Stopped caring together" else "Stopped caring together with $detail"
        }

    /** Item sprite for the memory book. */
    val icon: String
        get() = when (kind) {
            Kind.hatched -> "starJelly"
            Kind.fed, Kind.favoriteFound -> enumOf<Food>(detail)?.sprite ?: "apple"
            Kind.played -> game(detail)?.icon ?: "ball"
            Kind.highScore, Kind.achievement -> "furn.trophy"
            Kind.bath -> "bubbleItem"
            Kind.levelUp, Kind.stageUp -> "coin"
            Kind.gift, Kind.giftReceived -> "chest"
            Kind.visit, Kind.friendMade -> "heart"
            Kind.event -> "speech"
            Kind.reunion -> "flower"
            Kind.birthday -> "cake"
            Kind.streak -> "flag"
            Kind.holiday -> enumOf<Holiday>(detail)?.icon ?: "starJelly"
            Kind.crate -> CrateFind.parse(detail)?.icon ?: "chest"
            Kind.partnerCare -> PartnerCareNote.parse(detail)?.kind?.icon ?: "heart"
            Kind.careStarted, Kind.careEnded -> "heart"
        }

}

private fun game(detail: String): GameID? = enumOf<GameID>(detail.split(":").firstOrNull { it.isNotEmpty() } ?: "")

object MemoryEntriesSerializer : LossyListSerializer<MemoryEntry>(MemoryEntry.serializer())

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = Memory.Coding::class)
data class Memory(
    @Serializable(with = MemoryEntriesSerializer::class)
    var entries: List<MemoryEntry> = emptyList(),
    var recentLines: List<String> = emptyList(),
    var lineUsedAt: Map<String, JsonDate> = emptyMap(),
) {
    fun add(entry: MemoryEntry) {
        var all = entries + entry
        // Keep the last 60 everyday entries plus every keepsake.
        val everyday = all.filter { !it.keepsake }
        if (everyday.size > 60) {
            val drop = everyday.take(everyday.size - 60).map { it.id }.toSet()
            all = all.filter { it.id !in drop }
        }
        entries = all
    }

    fun last(kind: MemoryEntry.Kind): MemoryEntry? = entries.lastOrNull { it.kind == kind }

    val keepsakes: List<MemoryEntry> get() = entries.filter { it.keepsake }

    fun used(line: String, at: Instant) {
        var recent = recentLines + line
        if (recent.size > 30) recent = recent.drop(recent.size - 30)
        recentLines = recent
        val used = LinkedHashMap(lineUsedAt)
        used[line] = at
        if (used.size > 200) {
            val oldest = used.entries.sortedBy { it.value }.take(used.size - 200).map { it.key }
            for (key in oldest) used.remove(key)
        }
        lineUsedAt = used
    }

    internal object Coding : TolerantSerializer<Memory>(Memory.generatedSerializer()) {
        override fun decode(fields: JsonFields): Memory {
            val d = Memory()
            return Memory(
                entries = fields.value("entries", MemoryEntriesSerializer, d.entries),
                recentLines = fields.value("recentLines", ListSerializer(String.serializer()), d.recentLines),
                lineUsedAt = fields.value("lineUsedAt", MapSerializer(String.serializer(), fields.dateSerializer), d.lineUsedAt),
            )
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = NotificationPrefs.Coding::class)
data class NotificationPrefs(
    var needs: Boolean = true,
    var daily: Boolean = true,
    var friends: Boolean = true,
    var gifts: Boolean = true,
    var world: Boolean = false,
    var quietFrom: Int = 22,
    var quietUntil: Int = 8,
    /** The player has been asked for permission (we only ask once, in context). */
    var asked: Boolean = false,
) {
    fun isQuiet(hour: Int): Boolean =
        if (quietFrom > quietUntil) (hour >= quietFrom || hour < quietUntil) else (hour >= quietFrom && hour < quietUntil)

    internal object Coding : TolerantSerializer<NotificationPrefs>(NotificationPrefs.generatedSerializer()) {
        override fun decode(fields: JsonFields): NotificationPrefs {
            val d = NotificationPrefs()
            return NotificationPrefs(
                needs = fields.bool("needs", d.needs),
                daily = fields.bool("daily", d.daily),
                friends = fields.bool("friends", d.friends),
                gifts = fields.bool("gifts", d.gifts),
                world = fields.bool("world", d.world),
                quietFrom = fields.int("quietFrom", d.quietFrom),
                quietUntil = fields.int("quietUntil", d.quietUntil),
                asked = fields.bool("asked", d.asked),
            )
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = Settings.Coding::class)
data class Settings(
    var sound: Boolean = true,
    var music: Boolean = false,
    var haptics: Boolean = true,
    var notifications: NotificationPrefs = NotificationPrefs(),
    var sleep: SleepSchedule = SleepSchedule(),
    var liveEvents: Boolean = true,
    /** The slime talks to itself every few seconds while the app is open. */
    var chatty: Boolean = true,
) {
    fun deepCopy(): Settings = copy(notifications = notifications.copy(), sleep = sleep.copy())

    internal object Coding : TolerantSerializer<Settings>(Settings.generatedSerializer()) {
        override fun decode(fields: JsonFields): Settings {
            val d = Settings()
            return Settings(
                sound = fields.bool("sound", d.sound),
                music = fields.bool("music", d.music),
                haptics = fields.bool("haptics", d.haptics),
                notifications = fields.value("notifications", NotificationPrefs.serializer(), d.notifications),
                sleep = fields.value("sleep", SleepSchedule.serializer(), d.sleep),
                liveEvents = fields.bool("liveEvents", d.liveEvents),
                chatty = fields.bool("chatty", d.chatty),
            )
        }
    }
}

// MARK: - The save

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = GameState.Coding::class)
data class GameState(
    var version: Int = currentVersion,
    var hatched: Boolean = false,
    var slime: Slime = Slime(),
    var player: Player = Player(),
    var wallet: Wallet = Wallet(),
    var inventory: Inventory = Inventory(),
    var progress: Progress = Progress(),
    var memory: Memory = Memory(),
    var settings: Settings = Settings(),
    var streak: StreakState = StreakState(),
    /** Crates waiting to be opened. */
    @Serializable(with = CratesSerializer::class)
    var crates: Map<CrateKind, Int> = emptyMap(),
    /** Caring for the slime together with someone, if you are. */
    var care: CareLink? = null,
    /** This phone's side of caring together: actions waiting to be sent, partner actions already applied. */
    var careBook: CareBook = CareBook(),
) {
    /** A copy that shares nothing mutable with this one (Swift's value semantics). */
    fun deepCopy(): GameState = copy(
        slime = slime.deepCopy(),
        player = player.copy(),
        wallet = wallet.copy(),
        inventory = inventory.copy(),
        progress = progress.deepCopy(),
        memory = memory.copy(),
        settings = settings.deepCopy(),
        streak = streak.copy(),
        care = care?.deepCopy(),
        careBook = careBook.copy(),
    )

    /** Fixes anything a damaged or hand-edited save could contain. */
    fun sanitize(now: Instant) {
        slime.sanitize(now)
        wallet.coins = max(0, wallet.coins)
        player.xp = max(0, player.xp)
        streak.count = max(0, streak.count)
        streak.best = max(streak.best, streak.count)
        streak.opened = max(0, streak.opened)
        if (inventory.habitats.isEmpty()) inventory.habitats = setOf(Habitat.room)
        if (!inventory.habitats.contains(inventory.habitat)) inventory.habitat = Habitat.room
        inventory.furniture = inventory.furniture.filter { (slot, id) ->
            Catalog.item(id)?.slot == slot && inventory.owned.contains(id)
        }
        if (care == null) {
            for (id in slime.equipped.all) if (!inventory.owned.contains(id)) unequip(id)
        } else {
            // A shared slime may wear what the other person owns.
            for (id in slime.equipped.all) if (Catalog.item(id) == null) unequip(id)
        }
        inventory.pantry = inventory.pantry.filter { it.value > 0 }.mapValues { min(it.value, Crates.maxStack) }
        crates = crates.filter { it.value > 0 }.mapValues { min(it.value, Crates.maxStack) }
        care?.let { link ->
            link.ownBackup?.sanitize(now)
            care = link
        }
        careBook.sanitize(now)
        wallet.prune(now)
    }

    companion object {
        /**
         * 3: daily gift streak, seasons seen, birthdays and holidays.
         * 4: feelings, crates, colours from crates, the pantry, Care Together.
         */
        const val currentVersion = 4

        fun fresh(): GameState = GameState()
    }

    /** Every field falls back to its default if it is missing or damaged. */
    internal object Coding : TolerantSerializer<GameState>(GameState.generatedSerializer()) {
        override fun decode(fields: JsonFields): GameState {
            val d = GameState()
            return GameState(
                version = fields.int("version", d.version),
                hatched = fields.bool("hatched", d.hatched),
                slime = fields.value("slime", Slime.serializer(), d.slime),
                player = fields.value("player", Player.serializer(), d.player),
                wallet = fields.value("wallet", Wallet.serializer(), d.wallet),
                inventory = fields.value("inventory", Inventory.serializer(), d.inventory),
                progress = fields.value("progress", Progress.serializer(), d.progress),
                memory = fields.value("memory", Memory.serializer(), d.memory),
                settings = fields.value("settings", Settings.serializer(), d.settings),
                streak = fields.value("streak", StreakState.serializer(), d.streak),
                crates = fields.value("crates", CratesSerializer, d.crates),
                care = fields.value("care", CareLink.serializer().nullable, d.care),
                careBook = fields.value("careBook", CareBook.serializer(), d.careBook),
            )
        }
    }
}
