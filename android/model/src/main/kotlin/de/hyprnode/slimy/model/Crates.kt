package de.hyprnode.slimy.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * A crate to open. Wooden and golden crates are sold in the shop for coins
 * (never for real money). Mystery crates can't be bought: they come with the
 * seventh daily gift, every fifth level, now and then after a great round,
 * and with some achievements.
 */
@Serializable
enum class CrateKind {
    wooden, golden, mystery;

    val id: String get() = name

    val title: String
        get() = when (this) {
            wooden -> "Wooden crate"
            golden -> "Golden crate"
            mystery -> "Mystery crate"
        }

    /** Price in coins, or null when it can't be bought. */
    val price: Int?
        get() = when (this) {
            wooden -> 80
            golden -> 300
            mystery -> null
        }

    /** Item sprite. */
    val sprite: String get() = "crate.$name"

    /** What's inside, in a sentence for the shop. */
    val blurb: String
        get() = when (this) {
            wooden -> "Coins, treats and things for your slime and its home."
            golden -> "Bigger finds, and a tiny chance of something legendary."
            mystery -> "Can't be bought. The best chance of something legendary."
        }

    companion object {
        val allCases: List<CrateKind> get() = entries
    }
}

/** How rare a crate's reward is. Ordered: common < rare < epic < legendary. */
@Serializable
enum class Rarity {
    common, rare, epic, legendary;

    val title: String
        get() = when (this) {
            common -> "Common"
            rare -> "Rare"
            epic -> "Epic"
            legendary -> "Legendary"
        }

    /** Colour for the UI, as `0xRRGGBB` (use `argb()` for drawing). */
    val tint: Long
        get() = when (this) {
            common -> 0x8E8E93
            rare -> 0x0A84FF
            epic -> 0xBF5AF2
            legendary -> 0xFF9F0A
        }

    companion object {
        val allCases: List<Rarity> get() = entries
    }
}

/** What came out of a crate. */
data class CrateReward(
    val kind: Kind,
    val rarity: Rarity,
    /** "120 coins", "Cookie × 3", "Crown", "Grape colour", "Axolotl". */
    val title: String,
    /** Item sprite name. */
    val icon: String,
    /** Set when it was already yours: the coins you got instead. */
    val convertedCoins: Int?,
) {
    sealed class Kind {
        data class coins(val amount: Int) : Kind()

        /** Free servings of a food, kept in the pantry. */
        data class food(val food: Food, val count: Int) : Kind()

        /** A catalog item: something to wear or furniture. */
        data class item(val id: String) : Kind()

        /** A colour for the slime. */
        data class skin(val skin: Skin) : Kind()
        data class crate(val kind: CrateKind) : Kind()
    }

    val isDuplicate: Boolean get() = convertedCoins != null

    /** The crate that comes along with the coins when the secret colour is found a second time. */
    val bonusCrate: CrateKind?
        get() {
            if (convertedCoins == null) return null
            val k = kind as? Kind.skin ?: return null
            return if (k.skin.isSecret) Crates.legendaryBonusCrate else null
        }

    /** What you got, in a sentence. */
    val detail: String
        get() {
            if (convertedCoins != null) {
                val bonus = bonusCrate
                if (bonus != null) {
                    return "Already yours, so you get $convertedCoins coins and a ${bonus.title.lowercase()} instead."
                }
                return "Already yours, so you get $convertedCoins coins instead."
            }
            return when (kind) {
                is Kind.coins -> "Added to your coins."
                is Kind.food -> "Free servings in the kitchen."
                is Kind.item ->
                    if (Catalog.item(kind.id)?.kind == ItemKind.furniture) "New furniture for your home." else "Something new to wear."
                is Kind.skin -> if (kind.skin.isSecret) "The secret colour." else "A new colour."
                is Kind.crate -> "Another crate to open."
            }
        }
}

/** Where a crate came from, for the celebration. */
@Serializable
enum class CrateSource {
    shop, dailyGift, levelUp, greatRound, achievement, crate;

    val reason: String
        get() = when (this) {
            shop -> "Bought in the shop."
            dailyGift -> "The seventh day's gift."
            levelUp -> "Every fifth level brings one. Open it whenever you like."
            greatRound -> "Found after a great round. Open it whenever you like."
            achievement -> "A reward for an achievement."
            crate -> "Found inside another crate."
        }
}

/** A crate opening as the memory book keeps it: "golden|epic|hat.crown|Crown". */
data class CrateFind(
    val crate: CrateKind,
    val rarity: Rarity,
    val icon: String,
    val title: String,
) {
    val detail: String get() = "${crate.name}|${rarity.name}|$icon|$title"

    companion object {
        /** `CrateFind(detail:)`: null when the text isn't one. */
        fun parse(detail: String): CrateFind? {
            val parts = detail.split("|", limit = 4)
            if (parts.size != 4) return null
            val crate = enumOf<CrateKind>(parts[0]) ?: return null
            val rarity = enumOf<Rarity>(parts[1]) ?: return null
            if (parts[3].isEmpty()) return null
            return CrateFind(crate, rarity, if (parts[2].isEmpty()) "chest" else parts[2], parts[3])
        }
    }
}

/** A drop rate row, like Swift's `(rarity: Rarity, chance: Double)`. */
data class DropRate(val rarity: Rarity, val chance: Double)

/**
 * Drop rates and rewards. Rolls are made with a seeded `SplitMix`, so the
 * same seed always gives the same reward (and tests are stable).
 */
object Crates {
    /** Most crates of a kind, and most servings of a food, kept at once. */
    const val maxStack = 99

    /** The one legendary reward. It is in no shop. */
    val legendarySkin: Skin = Skin.axolotl

    /** Finding the secret colour again pays this, plus a golden crate. */
    const val legendaryDuplicateCoins = 500
    val legendaryBonusCrate: CrateKind = CrateKind.golden

    /** Chance of a mystery crate after a round at or above the good score. */
    const val greatRoundChance = 0.04

    /** The chance of each rarity. Every list adds up to 1. Wooden crates never hold anything legendary. */
    fun dropRates(kind: CrateKind): List<DropRate> = when (kind) {
        CrateKind.wooden -> listOf(DropRate(Rarity.common, 0.72), DropRate(Rarity.rare, 0.24), DropRate(Rarity.epic, 0.04))
        CrateKind.golden -> listOf(
            DropRate(Rarity.common, 0.35), DropRate(Rarity.rare, 0.444), DropRate(Rarity.epic, 0.2),
            DropRate(Rarity.legendary, 0.006),
        )
        CrateKind.mystery -> listOf(
            DropRate(Rarity.common, 0.5), DropRate(Rarity.rare, 0.33), DropRate(Rarity.epic, 0.15),
            DropRate(Rarity.legendary, 0.02),
        )
    }

    /** The rarity a roll in 0..<1 lands on. */
    fun rarity(kind: CrateKind, roll: Double): Rarity {
        val rates = dropRates(kind)
        var left = if (roll.isFinite()) roll else 0.0
        for (row in rates) {
            if (left < row.chance) return row.rarity
            left -= row.chance
        }
        // Only rounding gets here; it never makes anything rarer.
        return rates.firstOrNull()?.rarity ?: Rarity.common
    }

    /** Rolls a crate: the rarity, then what of that rarity. [state] decides what is already yours. Changes nothing. */
    fun roll(kind: CrateKind, state: GameState, rng: SplitMix): CrateReward {
        val tier = rarity(kind, rng.unit())
        return reward(kind, tier, state, rng)
    }

    /** A reward of the given rarity from this kind of crate. */
    fun reward(kind: CrateKind, rarity: Rarity, state: GameState, rng: SplitMix): CrateReward {
        if (rarity == Rarity.legendary) return legendary(state)
        val options = prizes(rarity).toMutableList()
        while (options.isNotEmpty()) {
            val index = pick(options.map { it.second }, rng)
            val prize = options.removeAt(index).first
            make(prize, kind, rarity, state, rng)?.let { return it }
        }
        return coins(kind, rarity, rng)
    }

    /** The secret colour, or what it turns into when the slime has it already. */
    fun legendary(state: GameState): CrateReward {
        val skin = legendarySkin
        val owned = state.slime.isUnlocked(skin)
        return CrateReward(
            kind = CrateReward.Kind.skin(skin), rarity = Rarity.legendary, title = skin.title, icon = "axolotl",
            convertedCoins = if (owned) legendaryDuplicateCoins else null,
        )
    }

    /** Whether a finished round brings a mystery crate. Decided by the round's session. */
    fun dropsAfterRound(session: UUID): Boolean {
        val rng = SplitMix(SplitMix.seed("crate:${session.uuidString}"))
        return rng.unit() < greatRoundChance
    }

    // MARK: Pools

    /** Catalog items a crate can hold at this rarity. Only things the shop sells by level. */
    fun items(rarity: Rarity): List<CatalogItem> = Catalog.items.filter { item ->
        if (item.unlock !is Unlock.level) return@filter false
        when (rarity) {
            Rarity.common -> !item.rare && item.price <= 45
            Rarity.rare -> !item.rare && item.price > 45
            Rarity.epic -> item.rare
            Rarity.legendary -> false
        }
    }

    /** Foods a crate can hold at this rarity for a slime of this level. */
    fun foods(rarity: Rarity, level: Int): List<Food> {
        val pool = when (rarity) {
            Rarity.common -> listOf(Food.carrot, Food.berry, Food.mushroom, Food.cookie)
            Rarity.rare -> listOf(Food.soup, Food.honey, Food.pudding, Food.cake)
            Rarity.epic, Rarity.legendary -> emptyList()
        }
        return pool.filter { it.unlockLevel <= level }
    }

    /** Colours an epic reward can be: everything but the hatching colours and the secret one. */
    val colours: List<Skin> get() = Skin.entries.filter { !it.isSecret && !Skin.hatchChoices.contains(it) }

    fun coinRange(kind: CrateKind, rarity: Rarity): IntRange = when (kind) {
        CrateKind.wooden -> when (rarity) {
            Rarity.common -> 20..40
            Rarity.rare -> 60..100
            else -> 150..220
        }
        CrateKind.golden -> when (rarity) {
            Rarity.common -> 60..100
            Rarity.rare -> 140..220
            else -> 320..480
        }
        CrateKind.mystery -> when (rarity) {
            Rarity.common -> 40..70
            Rarity.rare -> 100..160
            else -> 240..360
        }
    }

    /** Coins for something that was already yours. */
    fun duplicateCoins(price: Int, rarity: Rarity): Int {
        val floor = when (rarity) {
            Rarity.common -> 15
            Rarity.rare -> 40
            Rarity.epic -> 100
            Rarity.legendary -> legendaryDuplicateCoins
        }
        return maxOf(floor, price / 2)
    }

    /** Coins for a colour the slime already has. */
    const val duplicateColourCoins = 150

    // MARK: Making rewards

    private enum class Prize { coins, food, item, colour, crate }

    private fun prizes(rarity: Rarity): List<Pair<Prize, Double>> = when (rarity) {
        Rarity.common -> listOf(Prize.coins to 0.45, Prize.food to 0.25, Prize.item to 0.3)
        Rarity.rare -> listOf(Prize.coins to 0.35, Prize.food to 0.15, Prize.item to 0.35, Prize.crate to 0.15)
        Rarity.epic -> listOf(Prize.coins to 0.25, Prize.item to 0.35, Prize.colour to 0.25, Prize.crate to 0.15)
        Rarity.legendary -> emptyList()
    }

    private fun make(prize: Prize, kind: CrateKind, rarity: Rarity, state: GameState, rng: SplitMix): CrateReward? =
        when (prize) {
            Prize.coins -> coins(kind, rarity, rng)
            Prize.food -> {
                val pool = foods(rarity, state.slime.level)
                if (pool.isEmpty()) null else {
                    val food = pool[rng.nextIndex(pool.size)]
                    val count = if (rarity == Rarity.common) 3 else 2
                    CrateReward(
                        kind = CrateReward.Kind.food(food, count), rarity = rarity, title = "${food.title} × $count",
                        icon = food.sprite, convertedCoins = null,
                    )
                }
            }
            Prize.item -> {
                val pool = items(rarity)
                if (pool.isEmpty()) null else {
                    val item = pool[rng.nextIndex(pool.size)]
                    val owned = state.owns(item.id)
                    CrateReward(
                        kind = CrateReward.Kind.item(item.id), rarity = rarity, title = item.title, icon = item.sprite,
                        convertedCoins = if (owned) duplicateCoins(item.price, rarity) else null,
                    )
                }
            }
            Prize.colour -> {
                val pool = colours
                if (pool.isEmpty()) null else {
                    val skin = pool[rng.nextIndex(pool.size)]
                    val owned = state.slime.isUnlocked(skin)
                    CrateReward(
                        kind = CrateReward.Kind.skin(skin), rarity = rarity, title = "${skin.title} colour", icon = "gem",
                        convertedCoins = if (owned) duplicateColourCoins else null,
                    )
                }
            }
            Prize.crate -> {
                val inside = if (rarity == Rarity.epic) CrateKind.golden else CrateKind.wooden
                if (state.crateCount(inside) >= maxStack) null
                else CrateReward(
                    kind = CrateReward.Kind.crate(inside), rarity = rarity, title = inside.title, icon = inside.sprite,
                    convertedCoins = null,
                )
            }
        }

    private fun coins(kind: CrateKind, rarity: Rarity, rng: SplitMix): CrateReward {
        val amount = rng.int(coinRange(kind, rarity))
        return CrateReward(
            kind = CrateReward.Kind.coins(amount), rarity = rarity, title = "$amount coins", icon = "coin",
            convertedCoins = null,
        )
    }

    /** Index of a weighted choice. */
    private fun pick(weights: List<Double>, rng: SplitMix): Int {
        val total = weights.sum()
        if (total <= 0) return 0
        var roll = rng.unit() * total
        for ((i, weight) in weights.withIndex()) {
            roll -= weight
            if (roll < 0) return i
        }
        return weights.size - 1
    }
}

/** A crate opened and what came of it. */
data class OpenedCrate(val reward: CrateReward, val outcome: Outcome)

// MARK: - Rules

/** Crates of a kind waiting to be opened. */
fun GameState.crateCount(kind: CrateKind): Int = maxOf(0, crates[kind] ?: 0)

/** Crates of every kind waiting to be opened. */
val GameState.cratesWaiting: Int get() = CrateKind.entries.sumOf { crateCount(it) }

/** Adds crates, up to `Crates.maxStack` of a kind. Returns whether there was room. */
fun GameState.addCrate(kind: CrateKind, count: Int = 1): Boolean {
    val have = crateCount(kind)
    if (count <= 0 || have >= Crates.maxStack) return false
    crates = crates + (kind to minOf(Crates.maxStack, have + count))
    return true
}

/** Buys a crate for coins. False if it isn't for sale, there isn't room, or the coins aren't enough. */
fun GameState.buyCrate(kind: CrateKind, now: Instant): Boolean {
    val price = kind.price ?: return false
    if (crateCount(kind) >= Crates.maxStack || !wallet.spend(price)) return false
    addCrate(kind)
    progress.stats.purchases += 1
    checkAchievements(now)
    return true
}

/** Opens one crate of a kind and hands out what was in it. Null if there is none. The same seed always gives the same reward. */
fun GameState.openCrate(kind: CrateKind, now: Instant, seed: ULong): OpenedCrate? {
    if (crateCount(kind) <= 0) return null
    tick(now)
    val left = crateCount(kind) - 1
    crates = if (left > 0) crates + (kind to left) else crates - kind
    val rng = SplitMix(seed)
    val reward = Crates.roll(kind, this, rng)
    receive(reward)
    progress.stats.cratesOpened += 1
    val find = CrateFind(kind, reward.rarity, reward.icon, reward.title)
    memory.add(MemoryEntry(kind = MemoryEntry.Kind.crate, at = now, detail = find.detail, keepsake = reward.rarity >= Rarity.epic))

    when (reward.rarity) {
        Rarity.common -> {
            slime.feel(Emotion.excitement, 0.3)
            slime.feel(Emotion.joy, 0.1)
        }
        Rarity.rare -> {
            slime.feel(Emotion.excitement, 0.4)
            slime.feel(Emotion.joy, 0.15)
        }
        Rarity.epic -> {
            slime.feel(Emotion.excitement, 0.55)
            slime.feel(Emotion.joy, 0.25)
        }
        Rarity.legendary -> {
            slime.feel(Emotion.excitement, 1.0)
            slime.feel(Emotion.joy, 0.8)
            slime.feel(Emotion.love, 0.3)
        }
    }

    var line = ""
    if (!slime.isAsleep) {
        val category = when {
            reward.rarity == Rarity.legendary && !reward.isDuplicate -> LineCategory.crateLegendary
            reward.isDuplicate -> LineCategory.crateDuplicate
            else -> LineCategory.crateOpened
        }
        line = Dialogue.say(category, this, LineContext(item = reward.title.lowercase()), now)
        slime.react(if (reward.rarity >= Rarity.epic) Reaction.celebrate else Reaction.curious, now)
        slime.say = Utterance(text = line, at = now)
    }
    val events = checkAchievements(now)
    return OpenedCrate(reward, Outcome(ok = true, line = line, events = events))
}

/** Hands out a crate's reward. */
fun GameState.receive(reward: CrateReward) {
    val converted = reward.convertedCoins
    if (converted != null) {
        wallet.earn(converted)
        reward.bonusCrate?.let { addCrate(it) }
        return
    }
    when (val kind = reward.kind) {
        is CrateReward.Kind.coins -> wallet.earn(kind.amount)
        is CrateReward.Kind.food ->
            inventory.pantry = inventory.pantry + (kind.food to minOf(Crates.maxStack, servings(kind.food) + maxOf(0, kind.count)))
        is CrateReward.Kind.item -> inventory.owned = inventory.owned + kind.id
        is CrateReward.Kind.skin -> {
            slime.colours = slime.colours + kind.skin
            // Found while caring together: it comes home with your own slime.
            if (care != null) careBook.coloursFound = careBook.coloursFound + kind.skin
            // The legendary find changes the slime right away.
            if (kind.skin.isSecret) slime.skin = kind.skin
        }
        is CrateReward.Kind.crate -> addCrate(kind.kind)
    }
}

/** Free servings of a food in the pantry. */
fun GameState.servings(food: Food): Int = maxOf(0, inventory.pantry[food] ?: 0)
