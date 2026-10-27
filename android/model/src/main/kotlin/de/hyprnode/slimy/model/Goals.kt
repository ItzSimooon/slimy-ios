package de.hyprnode.slimy.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** Progress toward a goal, like Swift's `(value: Int, target: Int)`. */
data class ProgressCount(val value: Int, val target: Int) {
    val isDone: Boolean get() = value >= target
}

// MARK: - Achievements

class Achievement(
    val id: String,
    val title: String,
    val detail: String,
    val coins: Int,
    /** A secret until it's earned: lists show it as "???". */
    val hidden: Boolean = false,
    /** A crate that comes with it. */
    val crate: CrateKind? = null,
    val check: (GameState) -> Boolean,
)

object Achievements {
    val all: List<Achievement> = listOf(
        Achievement("firstMeal", "First meal", "Feed your slime.", 10) { it.progress.stats.feeds >= 1 },
        Achievement("favoriteFound", "Favourite food", "Find out its favourite food.", 25) { it.inventory.favoriteFound },
        Achievement("gourmet", "Gourmet", "Try every food once.", 60) { it.inventory.foodsTasted.size == Food.entries.size },
        Achievement("bather", "Water baby", "Give 20 baths.", 40) { it.progress.stats.baths >= 20 },
        Achievement("cuddler", "Cuddle pro", "Pet it 100 times.", 40) { it.progress.stats.pets >= 100 },
        Achievement("sleepyhead", "Good night", "Put it to bed 10 times.", 30) { it.progress.stats.sleeps >= 10 },
        Achievement("arcadeRookie", "First game", "Play a round in the arcade.", 10) { it.progress.stats.plays >= 1 },
        // A finished round, not just a look at the tutorial (which also creates a record).
        Achievement("allGames", "Tried them all", "Play every game once.", 80, crate = CrateKind.mystery) { state ->
            GameID.classics.all { (state.progress.records[it]?.plays ?: 0) > 0 }
        },
        Achievement("arcadeFan", "Regular", "Play 50 rounds.", 60) { it.progress.stats.plays >= 50 },
        Achievement("arcadeStar", "Arcade star", "Play 25 really good rounds.", 100, crate = CrateKind.mystery) { it.progress.stats.goodGames >= 25 },
        Achievement("level5", "Level 5", "Reach level 5.", 30) { it.slime.level >= 5 },
        Achievement("level10", "Level 10", "Reach level 10.", 60) { it.slime.level >= 10 },
        Achievement("grown", "All grown up", "Become a big slime.", 100) { it.slime.stage == Stage.grown },
        Achievement("close", "Close bond", "Build a close bond.", 50) { it.slime.bond >= 50 },
        Achievement("inseparable", "Inseparable", "The closest bond there is.", 120, crate = CrateKind.mystery) { it.slime.bond >= 80 },
        Achievement("collector", "Collector", "Own 12 things.", 80) { it.inventory.owned.size >= 12 },
        Achievement("decorator", "Decorator", "Put something on every spot.", 60) { it.inventory.furniture.size == FurnitureSlot.entries.size },
        Achievement("traveler", "Globetrotter", "Own three homes.", 80) { it.inventory.habitats.size >= 3 },
        Achievement("firstFriend", "First friend", "Connect with someone.", 40) { it.progress.stats.friends >= 1 },
        Achievement("giftGiver", "Generous", "Give away 10 gifts.", 60) { it.progress.stats.giftsSent >= 10 },
        Achievement("eventWatcher", "Attentive", "React to 10 things on your phone.", 30) { it.progress.stats.eventsSeen >= 10 },
        Achievement("week", "One week", "Visit on 7 different days.", 50) { it.player.daysActive.size >= 7 },
        Achievement("rich", "Coin mountain", "Earn 1000 coins in total.", 50) { it.wallet.earnedTotal >= 1000 },
        // Daily gifts and streaks
        Achievement("streak3", "Three in a row", "Open a daily gift three days in a row.", 20) { it.streak.best >= 3 },
        Achievement("streak7", "Full week", "Keep a seven-day streak.", 50) { it.streak.best >= 7 },
        Achievement("streak30", "Every single day", "Keep a 30-day streak.", 150, crate = CrateKind.mystery) { it.streak.best >= 30 },
        Achievement("gifts7", "Gift week", "Open 7 daily gifts.", 30) { it.streak.opened >= 7 },
        Achievement("gifts14", "Two weeks of gifts", "Open 14 daily gifts.", 40) { it.streak.opened >= 14 },
        Achievement("gifts21", "Three weeks of gifts", "Open 21 daily gifts.", 50) { it.streak.opened >= 21 },
        // Special days
        Achievement("birthday", "Happy birthday", "Celebrate your slime's first birthday.", 100) { it.memory.last(MemoryEntry.Kind.birthday) != null },
        Achievement("spooky", "Spooky season", "Visit your slime in October.", 25) { it.player.seasonsSeen.contains(Season.halloween.name) },
        Achievement("cozy", "Cozy season", "Visit your slime in December.", 25) { it.player.seasonsSeen.contains(Season.christmas.name) },
        Achievement("newYear", "Happy New Year", "Visit on New Year's Eve or New Year's Day.", 30) { it.player.seasonsSeen.contains(Season.newYear.name) },
        Achievement("oldFriends", "Old friends", "Visit on 30 different days.", 100, crate = CrateKind.mystery) { it.player.daysActive.size >= 30 },
        // Games
        Achievement("stackStar", "Tower builder", "Play a really good round of Slime Stack.", 50) { it.isGood(GameID.slimeStack) },
        Achievement("dashStar", "Jelly sprinter", "Play a really good round of Jelly Dash.", 50) { it.isGood(GameID.jellyDash) },
        Achievement("dailyRegular", "Daily player", "Play 10 rounds of today's game.", 40) { (it.progress.records[GameID.daily]?.plays ?: 0) >= 10 },
        // Care, style and friends
        Achievement("feast", "Feast", "Feed your slime 100 times.", 50) { it.progress.stats.feeds >= 100 },
        Achievement("dressedUp", "Dressed up", "Wear a hat, glasses and a scarf at once.", 30) {
            it.slime.equipped.hat != null && it.slime.equipped.glasses != null && it.slime.equipped.scarf != null
        },
        Achievement("shopper", "Big spender", "Buy 10 things in the shop.", 40) { it.progress.stats.purchases >= 10 },
        Achievement("level20", "Level 20", "Reach level 20.", 120) { it.slime.level >= 20 },
        Achievement("popular", "Popular", "Make 5 friends.", 60) { it.progress.stats.friends >= 5 },
        Achievement("squishy", "Squishy", "Hug friends 10 times.", 30) { it.progress.stats.hugs >= 10 },
        // Crates and caring together
        Achievement("firstCrate", "Unboxed", "Open your first crate.", 15) { it.progress.stats.cratesOpened >= 1 },
        Achievement("crates10", "Crate fan", "Open 10 crates.", 40) { it.progress.stats.cratesOpened >= 10 },
        Achievement("crates50", "Crate collector", "Open 50 crates.", 120, crate = CrateKind.mystery) { it.progress.stats.cratesOpened >= 50 },
        Achievement("epicFind", "Epic find", "Find something epic in a crate.", 50) { it.hasFound(Rarity.epic) },
        Achievement("legendary", "Legendary", "Find the rarest thing a crate can hold.", 200, hidden = true) { it.hasFound(Rarity.legendary) },
        Achievement("careTogether", "Two caretakers", "Care for a slime together with someone.", 40) {
            it.care != null || it.progress.stats.partnerCare >= 1
        },
        Achievement("careTeam", "Good team", "Get 25 care actions from your Care Together partner.", 60) {
            it.progress.stats.partnerCare >= 25
        },
    )

    fun achievement(id: String): Achievement? = all.firstOrNull { it.id == id }
}

/** Whether the best round on record for a game counts as really good. */
fun GameState.isGood(game: GameID): Boolean = (progress.records[game]?.best ?: 0) >= game.goodScore

/** Whether you found something this rare (or rarer) in a crate yourself. */
fun GameState.hasFound(rarity: Rarity): Boolean = memory.entries.any { entry ->
    if (entry.kind != MemoryEntry.Kind.crate) return@any false
    val find = CrateFind.parse(entry.detail) ?: return@any false
    find.rarity >= rarity
}

// MARK: - Challenges

class ChallengeTemplate(
    val id: String,
    val title: String,
    val goal: Goal,
    val coins: Int,
) {
    sealed class Goal {
        data class stat(val key: StatKey, val target: Int) : Goal()
        data class score(val game: GameID, val target: Int) : Goal()
    }
}

@Serializable
data class ChallengeState(
    var id: String,
    var baseline: Int = 0,
    var best: Int = 0,
    var claimed: Boolean = false,
)

@Serializable
data class ChallengeBoard(
    var dayKey: String = "",
    var weekKey: String = "",
    var daily: List<ChallengeState> = emptyList(),
    var weekly: List<ChallengeState> = emptyList(),
) {
    fun deepCopy(): ChallengeBoard = copy(daily = daily.map { it.copy() }, weekly = weekly.map { it.copy() })
}

object Challenges {
    val dailyPool: List<ChallengeTemplate> = listOf(
        ChallengeTemplate("d.feed3", "Feed 3 times", ChallengeTemplate.Goal.stat(StatKey.feeds, 3), 15),
        ChallengeTemplate("d.pet10", "Pet 10 times", ChallengeTemplate.Goal.stat(StatKey.pets, 10), 12),
        ChallengeTemplate("d.bath1", "Give a bath", ChallengeTemplate.Goal.stat(StatKey.baths, 1), 10),
        ChallengeTemplate("d.play3", "Play 3 rounds", ChallengeTemplate.Goal.stat(StatKey.plays, 3), 18),
        ChallengeTemplate("d.good1", "Play one really good round", ChallengeTemplate.Goal.stat(StatKey.goodGames, 1), 20),
        ChallengeTemplate("d.snack", "Snack Catcher: 30 points", ChallengeTemplate.Goal.score(GameID.snackCatcher, 30), 20),
        ChallengeTemplate("d.bubble", "Bubble Pop: 300 points", ChallengeTemplate.Goal.score(GameID.bubblePop, 300), 20),
        ChallengeTemplate("d.memory", "Memory Garden: round 6", ChallengeTemplate.Goal.score(GameID.memoryGarden, 6), 20),
        ChallengeTemplate("d.color", "Color Match: 20 points", ChallengeTemplate.Goal.score(GameID.colorMatch, 20), 20),
        ChallengeTemplate("d.jump", "Slime Jump: height 25", ChallengeTemplate.Goal.score(GameID.slimeJump, 25), 20),
        ChallengeTemplate("d.stack", "Slime Stack: 25 points", ChallengeTemplate.Goal.score(GameID.slimeStack, 25), 20),
        ChallengeTemplate("d.dash", "Jelly Dash: 120 points", ChallengeTemplate.Goal.score(GameID.jellyDash, 120), 20),
    )

    /** The daily challenges a slime of this level can take on: score goals only for games it can already open. */
    fun dailyOptions(forLevel: Int): List<ChallengeTemplate> = dailyPool.filter { template ->
        val goal = template.goal
        if (goal is ChallengeTemplate.Goal.score) forLevel >= goal.game.unlockLevel else true
    }

    val weeklyPool: List<ChallengeTemplate> = listOf(
        ChallengeTemplate("w.play20", "Play 20 rounds", ChallengeTemplate.Goal.stat(StatKey.plays, 20), 80),
        ChallengeTemplate("w.feed20", "Feed 20 times", ChallengeTemplate.Goal.stat(StatKey.feeds, 20), 60),
        ChallengeTemplate("w.good8", "Play 8 really good rounds", ChallengeTemplate.Goal.stat(StatKey.goodGames, 8), 90),
        ChallengeTemplate("w.gift3", "Give away 3 gifts", ChallengeTemplate.Goal.stat(StatKey.giftsSent, 3), 70),
        ChallengeTemplate("w.bath7", "Give 7 baths", ChallengeTemplate.Goal.stat(StatKey.baths, 7), 60),
        ChallengeTemplate("w.events5", "React to 5 things on your phone", ChallengeTemplate.Goal.stat(StatKey.eventsSeen, 5), 50),
    )

    fun template(id: String): ChallengeTemplate? = (dailyPool + weeklyPool).firstOrNull { it.id == id }

    /** "yyyy-MM-dd" on the Gregorian calendar in the calendar's time zone. */
    fun dayKey(date: Instant, calendar: SlimyCalendar = SlimyCalendar.current): String {
        val d = calendar.localDate(date)
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", d.year, d.monthValue, d.dayOfMonth)
    }

    fun weekKey(date: Instant, calendar: SlimyCalendar = SlimyCalendar.current): String =
        String.format(java.util.Locale.ROOT, "%04d-W%02d", calendar.yearForWeekOfYear(date), calendar.weekOfYear(date))

    /** The same picks for everyone on the same day; reproducible in tests. */
    fun pick(pool: List<ChallengeTemplate>, count: Int, key: String): List<ChallengeTemplate> {
        val rng = SplitMix(SplitMix.seed(key))
        val options = pool.toMutableList()
        val out = mutableListOf<ChallengeTemplate>()
        while (out.size < count && options.isNotEmpty()) {
            out.add(options.removeAt(rng.nextIndex(options.size)))
        }
        return out
    }

    fun progress(challenge: ChallengeState, state: GameState): ProgressCount {
        val template = template(challenge.id) ?: return ProgressCount(0, 1)
        return when (val goal = template.goal) {
            is ChallengeTemplate.Goal.stat ->
                ProgressCount(minOf(goal.target, maxOf(0, state.progress.stats[goal.key] - challenge.baseline)), goal.target)
            is ChallengeTemplate.Goal.score -> ProgressCount(minOf(goal.target, challenge.best), goal.target)
        }
    }

    fun isDone(challenge: ChallengeState, state: GameState): Boolean {
        val p = progress(challenge, state)
        return p.value >= p.target
    }
}
