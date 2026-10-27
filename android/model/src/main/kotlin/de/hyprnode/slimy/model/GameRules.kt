package de.hyprnode.slimy.model

import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Something worth celebrating that came out of an action. */
sealed class GameEvent {
    data class levelUp(val level: Int) : GameEvent()
    data class stageUp(val stage: Stage) : GameEvent()
    data class achievement(val id: String) : GameEvent()
    data class favoriteFound(val food: Food) : GameEvent()
    data class record(val game: GameID, val score: Int) : GameEvent()
    data class challenge(val id: String) : GameEvent()

    /** The slime turned this many years old today. */
    data class birthday(val years: Int) : GameEvent()
    data class holiday(val holiday: Holiday) : GameEvent()

    /** A crate turned up (every fifth level, after a great round). */
    data class crate(val kind: CrateKind, val source: CrateSource) : GameEvent()
}

data class Outcome(
    val ok: Boolean,
    val line: String,
    val events: List<GameEvent> = emptyList(),
)

/** A `String` passed by reference, for the Swift rules' `inout String` lines. */
class StringRef(var value: String)

/** A need it told you about and what it said. */
data class Nudge(val need: Need, val line: String)

// MARK: Time

/**
 * Moves the world to [now]: needs, sleep and feelings, daily challenges,
 * special days, achievements.
 */
fun GameState.tick(now: Instant, calendar: SlimyCalendar = SlimyCalendar.current): List<GameEvent> {
    if (!hatched) return emptyList()
    slime.advance(now, settings.sleep, calendar)
    refreshChallenges(now, calendar)
    player.daysActive = player.daysActive + Challenges.dayKey(now, calendar)
    if (player.daysActive.size > 400) {
        player.daysActive = player.daysActive.sorted().takeLast(400).toSet()
    }
    if (slime.wish == null) slime.lastNudged = null
    val special = celebrateSpecialDays(now, calendar)
    return special + checkAchievements(now)
}

/** Birthdays and holidays: a gift and a memory, once a year each. */
private fun GameState.celebrateSpecialDays(now: Instant, calendar: SlimyCalendar): List<GameEvent> {
    player.seasonsSeen = player.seasonsSeen + Season.at(now, calendar).name
    val year = calendar.year(now)
    val events = mutableListOf<GameEvent>()
    val years = Birthday.years(slime.birthday, now, calendar)
    if (years != null && wallet.claim(Birthday.receipt(year), coins = Birthday.coins(years), at = now)) {
        inventory.owned = inventory.owned + "furn.cake"
        slime.needs.add(Need.love, 15.0)
        slime.needs.add(Need.`fun`, 15.0)
        slime.feel(Emotion.joy, 0.5)
        slime.feel(Emotion.excitement, 0.4)
        slime.feel(Emotion.love, 0.2)
        memory.add(MemoryEntry(kind = MemoryEntry.Kind.birthday, at = now, detail = "$years", keepsake = true))
        // A birthday that starts at midnight doesn't wake it up.
        if (!slime.isAsleep) {
            slime.react(Reaction.celebrate, now)
            val line = Dialogue.say(LineCategory.birthday, this, LineContext(years = years), now, calendar)
            slime.say = Utterance(text = line, at = now)
        }
        events.add(GameEvent.birthday(years))
    }
    val holiday = Holiday.at(now, calendar)
    if (holiday != null && wallet.claim(holiday.receipt(year), coins = holiday.coins, at = now)) {
        memory.add(MemoryEntry(kind = MemoryEntry.Kind.holiday, at = now, detail = holiday.name, keepsake = true))
        slime.feel(Emotion.joy, 0.3)
        slime.feel(Emotion.excitement, 0.3)
        if (!slime.isAsleep) {
            slime.react(Reaction.celebrate, now)
            val line = Dialogue.say(holiday.lines, this, LineContext(), now, calendar)
            slime.say = Utterance(text = line, at = now)
        }
        events.add(GameEvent.holiday(holiday))
    }
    return events
}

/** Whether today is the slime's hatch-day anniversary. */
fun GameState.isBirthday(now: Instant, calendar: SlimyCalendar = SlimyCalendar.current): Boolean =
    hatched && Birthday.years(slime.birthday, now, calendar) != null

/** The app was opened. Greets you, without guilt, if you were away. */
fun GameState.arrive(now: Instant, calendar: SlimyCalendar = SlimyCalendar.current): String? {
    if (!hatched) return null
    val away = now.timeIntervalSince(player.lastSeen)
    player.lastSeen = now
    if (!(away > 4 * 3600) || slime.isAsleep) return null
    val days = (away / 86400).toInt()
    // Glad you're back, whatever the time away.
    slime.feel(Emotion.joy, if (days >= 2) 0.4 else 0.25)
    slime.feel(Emotion.love, if (days >= 2) 0.25 else 0.15)
    val rng = SplitMix(SplitMix.seed("arrive${(now.timeIntervalSince1970 / 60).toLong()}"))
    val seasonal = Season.at(now, calendar).lines
    val line: String
    if (days >= 2) {
        line = Dialogue.say(LineCategory.longReunion, this, LineContext(days = days), now, calendar)
        memory.add(MemoryEntry(kind = MemoryEntry.Kind.reunion, at = now, detail = "$days"))
    } else if (seasonal != null && rng.unit() < 0.5) {
        line = Dialogue.say(seasonal, this, LineContext(), now, calendar)
    } else {
        line = Dialogue.say(LineCategory.reunion, this, LineContext(), now, calendar)
    }
    slime.react(Reaction.wave, now)
    slime.say = Utterance(text = line, at = now)
    return line
}

fun GameState.hatch(name: String, skin: Skin, seed: ULong, now: Instant) {
    val trimmed = name.trimmedSwift()
    slime = Slime.newborn(if (trimmed.isEmpty()) "Blob" else trimmed.prefixChars(16), skin, seed)
    slime.birthday = now
    slime.lastUpdate = now
    slime.react(Reaction.wave, now)
    slime.feel(Emotion.curiosity, 0.4)
    slime.feel(Emotion.joy, 0.2)
    hatched = true
    player.lastSeen = now
    memory.add(MemoryEntry(kind = MemoryEntry.Kind.hatched, at = now, detail = slime.name, keepsake = true))
    refreshChallenges(now)
}

// MARK: Care

/** Feeds the slime. A free serving from the pantry is used first; then it costs the food's price. */
fun GameState.feed(food: Food, now: Instant): Outcome {
    tick(now)
    val fromPantry = servings(food) > 0
    if (!(fromPantry || slime.level >= food.unlockLevel)) {
        return Outcome(ok = false, line = "${food.title} unlocks at level ${food.unlockLevel}.")
    }
    if (!(fromPantry || wallet.coins >= food.price)) {
        return Outcome(ok = false, line = Dialogue.say(LineCategory.noCoins, this, now = now))
    }
    if (!(slime.needs.hunger < 97)) {
        return Outcome(ok = false, line = Dialogue.say(LineCategory.stuffed, this, now = now))
    }
    if (fromPantry) {
        val left = servings(food) - 1
        inventory.pantry = if (left > 0) inventory.pantry + (food to left) else inventory.pantry - food
    } else {
        wallet.spend(food.price)
    }
    slime.isAsleep = false
    val isFavorite = food == slime.favorite
    slime.needs.add(Need.hunger, food.nutrition * (if (isFavorite) 1.3 else 1.0))
    slime.needs.add(Need.`fun`, food.funBonus)
    if (food == Food.starJelly) {
        for (need in Need.entries) slime.needs.add(need, 20.0)
        slime.feel(Emotion.excitement, 0.3)
    }
    if (isFavorite) {
        slime.needs.add(Need.love, 6.0)
        slime.feel(Emotion.joy, 0.3)
        slime.feel(Emotion.love, 0.2)
    } else {
        slime.feel(Emotion.joy, 0.15 + 0.1 * slime.personality.foodie)
    }
    inventory.foodsTasted = inventory.foodsTasted + food
    progress.stats.feeds += 1
    slime.bond = min(100.0, slime.bond + 0.5)
    slime.personality.drift(Personality.Trait.foodie)
    slime.react(Reaction.eat, now)
    noteCare(CareKind.feed, food = food, now = now)

    val events = mutableListOf<GameEvent>()
    val line: StringRef
    val ctx = LineContext(food = food.title.lowercase())
    if (isFavorite && !inventory.favoriteFound) {
        inventory.favoriteFound = true
        events.add(GameEvent.favoriteFound(food))
        memory.add(MemoryEntry(kind = MemoryEntry.Kind.favoriteFound, at = now, detail = food.name, keepsake = true))
        line = StringRef(Dialogue.say(LineCategory.favoriteFound, this, ctx, now))
        slime.react(Reaction.celebrate, now)
    } else if (isFavorite) {
        line = StringRef(Dialogue.say(LineCategory.fedFavorite, this, ctx, now))
    } else {
        line = StringRef(Dialogue.say(LineCategory.fed, this, ctx, now))
    }
    memory.add(MemoryEntry(kind = MemoryEntry.Kind.fed, at = now, detail = food.name))
    events += gainXP(6, now, line)
    events += checkAchievements(now)
    slime.say = Utterance(text = line.value, at = now)
    return Outcome(ok = true, line = line.value, events = events)
}

fun GameState.pet(now: Instant): Outcome {
    tick(now)
    slime.recentPets = slime.recentPets.filter { now.timeIntervalSince(it) < 600 } + now
    val streak = slime.recentPets.size
    val loveBefore = slime.needs.love
    slime.needs.add(Need.love, max(2.0, 14 - (streak - 1).toDouble() * 4))
    val loveGiven = slime.needs.love - loveBefore
    slime.needs.add(Need.`fun`, 2.0)
    progress.stats.pets += 1
    slime.personality.drift(Personality.Trait.cuddly, by = 0.002)
    // Each pet in a row means a little less.
    slime.feel(Emotion.love, 0.18 * max(0.25, 1 - (streak - 1).toDouble() * 0.2))
    slime.feel(Emotion.joy, 0.05)
    noteCare(CareKind.pet, amount = loveGiven, now = now)
    val line: StringRef
    val events = mutableListOf<GameEvent>()
    if (slime.isAsleep) {
        line = StringRef(Dialogue.say(LineCategory.petAsleep, this, now = now))
    } else if (streak > 5) {
        line = StringRef(Dialogue.say(LineCategory.petAgain, this, now = now))
        slime.react(Reaction.blush, now)
    } else {
        line = StringRef(Dialogue.say(LineCategory.pet, this, now = now))
        slime.react(if (slime.bond >= 50 && streak == 1) Reaction.blush else Reaction.love, now)
    }
    if (streak <= 3) {
        slime.bond = min(100.0, slime.bond + 1)
        events += gainXP(3, now, line)
    }
    events += checkAchievements(now)
    slime.say = Utterance(text = line.value, at = now)
    return Outcome(ok = true, line = line.value, events = events)
}

fun GameState.bath(now: Instant): Outcome {
    tick(now)
    if (!(slime.needs.clean <= 95)) {
        return Outcome(ok = false, line = Dialogue.say(LineCategory.alreadyClean, this, now = now))
    }
    slime.isAsleep = false
    slime.needs.clean = 100.0
    slime.needs.add(Need.love, 4.0)
    progress.stats.baths += 1
    slime.bond = min(100.0, slime.bond + 0.5)
    slime.react(Reaction.bath, now)
    // Some slimes are grumpy about baths for a little while.
    val grumpy = slime.personality.dislikesBaths
    if (grumpy) {
        slime.feel(Emotion.anger, 0.3)
        slime.feel(Emotion.joy, 0.1)
    } else {
        slime.feel(Emotion.joy, 0.3)
    }
    noteCare(CareKind.bath, now = now)
    memory.add(MemoryEntry(kind = MemoryEntry.Kind.bath, at = now))
    val line = StringRef(Dialogue.say(if (grumpy) LineCategory.bathGrumpy else LineCategory.bath, this, now = now))
    val events = gainXP(6, now, line) + checkAchievements(now)
    slime.say = Utterance(text = line.value, at = now)
    return Outcome(ok = true, line = line.value, events = events)
}

fun GameState.sleep(now: Instant): Outcome {
    tick(now)
    if (!(slime.needs.energy <= 85)) {
        return Outcome(ok = false, line = Dialogue.say(LineCategory.notSleepy, this, now = now))
    }
    slime.isAsleep = true
    slime.reaction = Reaction.none
    progress.stats.sleeps += 1
    slime.personality.drift(Personality.Trait.sleepy, by = 0.002)
    slime.feel(Emotion.sleepiness, 0.1)
    slime.feel(Emotion.excitement, -0.2)
    noteCare(CareKind.sleep, now = now)
    val line = Dialogue.say(LineCategory.sleep, this, now = now)
    slime.say = Utterance(text = line, at = now)
    return Outcome(ok = true, line = line, events = checkAchievements(now))
}

fun GameState.wake(now: Instant): Outcome {
    tick(now)
    slime.isAsleep = false
    val tired = slime.needs.energy < 50
    if (tired) {
        slime.needs.add(Need.`fun`, -5.0)
        slime.feel(Emotion.sleepiness, 0.15)
        slime.feel(Emotion.anger, 0.1)
    } else {
        slime.feel(Emotion.joy, 0.1)
        slime.feel(Emotion.sleepiness, -0.3)
    }
    slime.react(Reaction.stretch, now)
    noteCare(CareKind.wake, now = now)
    val line = Dialogue.say(if (tired) LineCategory.wakeTired else LineCategory.wake, this, now = now)
    slime.say = Utterance(text = line, at = now)
    return Outcome(ok = true, line = line)
}

// MARK: Arcade

val GameState.canPlay: Boolean get() = slime.needs.energy >= 12

fun GameState.record(game: GameID): GameRecord = progress.records[game] ?: GameRecord()

fun GameState.markTutorialSeen(game: GameID) {
    progress.records = progress.records + (game to record(game).copy(tutorialSeen = true))
}

/**
 * Pays out a finished round once. A second call with the same session
 * changes nothing. A great round sometimes brings a mystery crate, decided
 * by the round's session.
 */
fun GameState.finish(result: GameResult): Outcome {
    val now = result.finishedAt
    tick(now)
    if (!wallet.claim("game:${result.session.uuidString}", coins = result.coins, at = now)) {
        return Outcome(ok = false, line = "Already collected.")
    }
    val game = result.game
    val favorite = game == slime.favoriteGame
    slime.isAsleep = false
    slime.needs.add(Need.`fun`, result.funGain * (if (favorite) 1.3 else 1.0))
    slime.needs.add(Need.energy, -game.energyCost)
    slime.needs.add(Need.clean, -5.0)
    slime.needs.add(Need.hunger, -4.0)
    if (favorite) slime.bond = min(100.0, slime.bond + 1)
    slime.personality.drift(Personality.Trait.playful)
    progress.stats.plays += 1
    val great = result.score >= result.goodScore
    if (great) progress.stats.goodGames += 1
    val quest = progress.quest
    if (game == GameID.daily && result.day != null && quest != null && quest.day == result.day) {
        progress.quest = quest.copy(best = max(quest.best, result.score))
    }
    val ratio = max(0, result.score).toDouble() / result.goodScore.toDouble()
    noteCare(CareKind.play, game = game, amount = ratio * 100, now = now)

    val record = (progress.records[game] ?: GameRecord()).copy()
    // Every day's game is different, so its scores aren't records.
    val isRecord = game != GameID.daily && result.score > record.best && record.plays > 0
    record.best = max(record.best, result.score)
    record.plays += 1
    record.lastPlayed = now
    progress.records = progress.records + (game to record)

    progress.challenges.daily = progress.challenges.daily.map { c ->
        val goal = Challenges.template(c.id)?.goal
        if (goal is ChallengeTemplate.Goal.score && goal.game == game) c.copy(best = max(c.best, result.score)) else c
    }

    val events = mutableListOf<GameEvent>()
    val line: StringRef
    val ctx = LineContext(score = result.score, game = game.title)
    if (isRecord) {
        events.add(GameEvent.record(game, result.score))
        memory.add(MemoryEntry(kind = MemoryEntry.Kind.highScore, at = now, detail = "${game.name}:${result.score}", keepsake = true))
        slime.feel(Emotion.joy, 0.35)
        slime.feel(Emotion.excitement, 0.4)
        line = StringRef(Dialogue.say(LineCategory.highScore, this, ctx, now))
        slime.react(Reaction.celebrate, now)
    } else if (result.score < result.goodScore / 4) {
        slime.feel(Emotion.excitement, 0.15)
        slime.feel(Emotion.joy, 0.05)
        line = StringRef(Dialogue.say(LineCategory.lowScore, this, ctx, now))
        slime.react(Reaction.play, now)
    } else {
        slime.feel(Emotion.excitement, 0.3)
        slime.feel(Emotion.joy, if (great) 0.15 else 0.08)
        line = StringRef(Dialogue.say(LineCategory.played, this, ctx, now))
        slime.react(Reaction.play, now)
    }
    if (great && Crates.dropsAfterRound(result.session) && addCrate(CrateKind.mystery)) {
        events.add(GameEvent.crate(CrateKind.mystery, CrateSource.greatRound))
    }
    memory.add(MemoryEntry(kind = MemoryEntry.Kind.played, at = now, detail = "${game.name}:${result.score}"))
    player.xp += result.xp / 2
    events += gainXP(result.xp, now, line)
    events += checkAchievements(now)
    slime.say = Utterance(text = line.value, at = now)
    return Outcome(ok = true, line = line.value, events = events)
}

// MARK: Daily plan

/** Starts tracking the quest of a new day's plan. */
fun GameState.startQuest(plan: DailyPlan) {
    if (progress.quest?.day == plan.day) return
    val q = QuestState(day = plan.day)
    val goal = plan.quest.goal
    if (goal is DailyQuest.Goal.stat) q.baseline = progress.stats[goal.key]
    progress.quest = q
}

fun GameState.questProgress(plan: DailyPlan): ProgressCount {
    val q = progress.quest
    if (q == null || q.day != plan.day) return ProgressCount(0, 1)
    return when (val goal = plan.quest.goal) {
        is DailyQuest.Goal.stat -> ProgressCount(min(goal.target, max(0, progress.stats[goal.key] - q.baseline)), goal.target)
        is DailyQuest.Goal.dailyScore -> ProgressCount(min(goal.target, q.best), goal.target)
    }
}

/** Pays the quest once, when it's done. */
fun GameState.claimQuest(plan: DailyPlan, now: Instant): Outcome {
    val p = questProgress(plan)
    if (p.value < p.target) return Outcome(ok = false, line = "Not done yet.")
    if (!wallet.claim("quest:${plan.day}", coins = plan.quest.coins, at = now)) {
        return Outcome(ok = false, line = "Already collected.")
    }
    progress.quest = progress.quest?.copy(claimed = true)
    slime.react(Reaction.celebrate, now)
    slime.feel(Emotion.joy, 0.2)
    slime.feel(Emotion.excitement, 0.15)
    val line = StringRef("+${plan.quest.coins} coins")
    val events = gainXP(15, now, line) + checkAchievements(now)
    return Outcome(ok = true, line = line.value, events = events)
}

// MARK: Shop and wardrobe

fun GameState.isAvailable(unlock: Unlock, now: Instant = Instant.now(), calendar: SlimyCalendar = SlimyCalendar.current): Boolean =
    when (unlock) {
        is Unlock.level -> slime.level >= unlock.n
        is Unlock.achievement -> progress.achievements[unlock.id] != null
        is Unlock.bond -> slime.bond >= unlock.n.toDouble()
        is Unlock.season -> calendar.month(now) == unlock.month
    }

fun GameState.owns(id: String): Boolean = inventory.owned.contains(id)

fun GameState.buy(item: CatalogItem, now: Instant): Outcome {
    if (owns(item.id)) return Outcome(ok = false, line = "You already have it.")
    if (!isAvailable(item.unlock, now)) return Outcome(ok = false, line = "Still locked.")
    if (!wallet.spend(item.price)) return Outcome(ok = false, line = Dialogue.say(LineCategory.noCoins, this, now = now))
    wallet.ledger = wallet.ledger + ("buy:${item.id}" to now)
    inventory.owned = inventory.owned + item.id
    progress.stats.purchases += 1
    slime.react(Reaction.celebrate, now)
    slime.feel(Emotion.joy, 0.15)
    slime.feel(Emotion.curiosity, 0.1)
    val line = Dialogue.say(LineCategory.bought, this, LineContext(item = item.title), now)
    slime.say = Utterance(text = line, at = now)
    return Outcome(ok = true, line = line, events = checkAchievements(now))
}

fun GameState.buy(habitat: Habitat, now: Instant): Outcome {
    if (inventory.habitats.contains(habitat)) return Outcome(ok = false, line = "You already have it.")
    if (!isAvailable(habitat.unlock, now)) return Outcome(ok = false, line = "Still locked.")
    if (!wallet.spend(habitat.price)) return Outcome(ok = false, line = Dialogue.say(LineCategory.noCoins, this, now = now))
    wallet.ledger = wallet.ledger + ("buy:habitat.${habitat.name}" to now)
    inventory.habitats = inventory.habitats + habitat
    inventory.habitat = habitat
    slime.react(Reaction.curious, now)
    slime.feel(Emotion.curiosity, 0.35)
    slime.feel(Emotion.excitement, 0.2)
    val line = Dialogue.say(LineCategory.bought, this, LineContext(item = habitat.title), now)
    return Outcome(ok = true, line = line, events = checkAchievements(now))
}

fun GameState.equip(id: String, now: Instant) {
    if (!owns(id)) return
    val item = Catalog.item(id) ?: return
    when (item.kind) {
        ItemKind.hat -> slime.equipped.hat = id
        ItemKind.glasses -> slime.equipped.glasses = id
        ItemKind.scarf -> slime.equipped.scarf = id
        ItemKind.effect -> slime.equipped.effect = id
        ItemKind.furniture -> item.slot?.let { inventory.furniture = inventory.furniture + (it to id) }
    }
    slime.react(Reaction.blush, now)
    slime.feel(Emotion.joy, 0.1)
    slime.say = Utterance(text = Dialogue.say(LineCategory.equipped, this, now = now), at = now)
}

fun GameState.unequip(id: String) {
    if (slime.equipped.hat == id) slime.equipped.hat = null
    if (slime.equipped.glasses == id) slime.equipped.glasses = null
    if (slime.equipped.scarf == id) slime.equipped.scarf = null
    if (slime.equipped.effect == id) slime.equipped.effect = null
    inventory.furniture = inventory.furniture.filterValues { it != id }
}

fun GameState.isEquipped(id: String): Boolean =
    slime.equipped.all.contains(id) || inventory.furniture.values.contains(id)

fun GameState.move(to: Habitat) {
    if (!inventory.habitats.contains(to)) return
    inventory.habitat = to
}

// MARK: Challenges

fun GameState.refreshChallenges(now: Instant, calendar: SlimyCalendar = SlimyCalendar.current) {
    val day = Challenges.dayKey(now, calendar)
    if (progress.challenges.dayKey != day) {
        progress.challenges.dayKey = day
        // Same seed for everyone each day; locked games are left out.
        val pool = Challenges.dailyOptions(forLevel = slime.level)
        progress.challenges.daily = Challenges.pick(pool, 3, day).map { template ->
            val c = ChallengeState(id = template.id)
            val goal = template.goal
            if (goal is ChallengeTemplate.Goal.stat) c.baseline = progress.stats[goal.key]
            c
        }
    }
    val week = Challenges.weekKey(now, calendar)
    if (progress.challenges.weekKey != week) {
        progress.challenges.weekKey = week
        progress.challenges.weekly = Challenges.pick(Challenges.weeklyPool, 2, week).map { template ->
            val c = ChallengeState(id = template.id)
            val goal = template.goal
            if (goal is ChallengeTemplate.Goal.stat) c.baseline = progress.stats[goal.key]
            c
        }
    }
}

fun GameState.claimChallenge(id: String, now: Instant): Outcome {
    val board = progress.challenges
    val isDaily = board.daily.any { it.id == id }
    val challenge = (board.daily + board.weekly).firstOrNull { it.id == id }
    val template = Challenges.template(id)
    if (challenge == null || template == null) return Outcome(ok = false, line = "This challenge is over.")
    if (!Challenges.isDone(challenge, this)) return Outcome(ok = false, line = "Not done yet.")
    val key = "challenge:${if (isDaily) board.dayKey else board.weekKey}:$id"
    if (!wallet.claim(key, coins = template.coins, at = now)) return Outcome(ok = false, line = "Already collected.")
    if (isDaily) {
        val i = progress.challenges.daily.indexOfFirst { it.id == id }
        if (i >= 0) progress.challenges.daily = progress.challenges.daily.mapIndexed { j, c -> if (j == i) c.copy(claimed = true) else c }
    } else {
        val i = progress.challenges.weekly.indexOfFirst { it.id == id }
        if (i >= 0) progress.challenges.weekly = progress.challenges.weekly.mapIndexed { j, c -> if (j == i) c.copy(claimed = true) else c }
    }
    player.xp += if (isDaily) 10 else 30
    slime.feel(Emotion.joy, if (isDaily) 0.15 else 0.25)
    val line = StringRef("+${template.coins} coins")
    val events = mutableListOf<GameEvent>(GameEvent.challenge(id))
    events += gainXP(if (isDaily) 8 else 25, now, line)
    return Outcome(ok = true, line = line.value, events = events)
}

// MARK: Phone, friends, chatter

fun GameState.handle(event: PhoneEvent, now: Instant): String {
    tick(now)
    event.effect?.let { slime.needs.add(it.need, it.amount) }
    event.feeling?.let { slime.feel(it.emotion, it.amount) }
    if (!slime.isAsleep || event.wakesUp) {
        slime.isAsleep = false
        slime.react(event.reaction, now)
    }
    val rng = SplitMix(SplitMix.seed("${event.name}${now.timeIntervalSince1970.toLong()}"))
    val line = event.lines[rng.nextIndex(event.lines.size)]
    slime.lastEvent = event
    slime.eventAt = now
    slime.say = Utterance(text = line, at = now)
    progress.stats.eventsSeen += 1
    memory.add(MemoryEntry(kind = MemoryEntry.Kind.event, at = now, detail = event.name))
    checkAchievements(now)
    return line
}

fun GameState.receiveGift(food: Food, from: String, now: Instant): String {
    tick(now)
    slime.needs.add(Need.hunger, food.nutrition)
    slime.needs.add(Need.love, 8.0)
    slime.feel(Emotion.joy, 0.3)
    slime.feel(Emotion.excitement, 0.15)
    slime.feel(Emotion.love, 0.15)
    inventory.foodsTasted = inventory.foodsTasted + food
    progress.stats.giftsReceived += 1
    slime.react(Reaction.celebrate, now)
    memory.add(MemoryEntry(kind = MemoryEntry.Kind.giftReceived, at = now, detail = from, keepsake = progress.stats.giftsReceived == 1))
    val line = Dialogue.say(LineCategory.giftReceived, this, LineContext(friend = from), now)
    slime.say = Utterance(text = line, at = now)
    return line
}

/**
 * Something done together with a friend's slime: a hug, a visit or a gift
 * sent. Counts it, cheers the slime up and returns its line.
 */
fun GameState.friendMoment(category: LineCategory, friend: String, now: Instant): String {
    tick(now)
    slime.needs.add(Need.love, 10.0)
    slime.needs.add(Need.`fun`, 5.0)
    gainXP(4, now, StringRef(""))
    when (category) {
        LineCategory.gift -> {
            progress.stats.giftsSent += 1
            memory.add(MemoryEntry(kind = MemoryEntry.Kind.gift, at = now, detail = friend))
            slime.feel(Emotion.joy, 0.2)
            slime.feel(Emotion.love, 0.1)
        }
        LineCategory.friendVisit -> {
            progress.stats.visits += 1
            memory.add(MemoryEntry(kind = MemoryEntry.Kind.visit, at = now, detail = friend))
            slime.feel(Emotion.joy, 0.25)
            slime.feel(Emotion.excitement, 0.25)
        }
        LineCategory.friendHug -> {
            progress.stats.hugs += 1
            slime.feel(Emotion.love, 0.2)
            slime.feel(Emotion.joy, 0.15)
        }
        else -> slime.feel(Emotion.joy, 0.15)
    }
    slime.react(if (category == LineCategory.friendVisit) Reaction.wave else Reaction.love, now)
    val line = Dialogue.say(category, this, LineContext(friend = friend), now)
    slime.say = Utterance(text = line, at = now)
    checkAchievements(now)
    return line
}

fun GameState.madeFriend(friend: String, now: Instant) {
    progress.stats.friends += 1
    memory.add(MemoryEntry(kind = MemoryEntry.Kind.friendMade, at = now, detail = friend, keepsake = true))
    slime.feel(Emotion.joy, 0.3)
    slime.feel(Emotion.excitement, 0.25)
    slime.say = Utterance(text = Dialogue.say(LineCategory.friendNew, this, LineContext(friend = friend), now), at = now)
    checkAchievements(now)
}

/** Says what it needs once per need, not over and over. Returns the line if the island should tell you. */
fun GameState.nudge(now: Instant): Nudge? {
    tick(now)
    val wish = slime.wish ?: return null
    if (slime.isAsleep) return null
    if (slime.lastNudged == wish && now.timeIntervalSince(slime.lastNudgeAt) < 6 * 3600) return null
    slime.lastNudged = wish
    slime.lastNudgeAt = now
    val line = Dialogue.say(Dialogue.category(wish), this, LineContext(game = slime.favoriteGame.title), now)
    slime.say = Utterance(text = line, at = now)
    return Nudge(wish, line)
}

/** How many of its most recent lines small talk never repeats. */
const val chatterMemory = 12

/**
 * Small talk: something that fits right now, picked from what it needs, how
 * it feels, what just happened, the season, where it lives, the weather, the
 * time of day and its character. Never one of its last 12 lines, and never
 * a guilt trip.
 */
fun GameState.chatter(now: Instant, calendar: SlimyCalendar = SlimyCalendar.current): String {
    tick(now, calendar)
    val recent = memory.recentLines.takeLast(chatterMemory).toSet()
    // Deterministic per moment, like the lines themselves.
    val rng = SplitMix(
        SplitMix.seed("chatter${now.timeIntervalSince1970.toLong()}|${memory.lineUsedAt.size}|${memory.recentLines.lastOrNull() ?: ""}"),
    )
    val topics = chatterTopics(now, calendar).toMutableList()
    while (topics.isNotEmpty()) {
        val total = topics.fold(0.0) { acc, t -> acc + max(0.0, t.weight) }
        var roll = rng.unit() * total
        var index = topics.size - 1
        for ((i, topic) in topics.withIndex()) {
            roll -= max(0.0, topic.weight)
            if (roll < 0) {
                index = i
                break
            }
        }
        val topic = topics.removeAt(index)
        val line = Dialogue.fresh(topic.category, this, now, calendar, recent, rng)
        if (line != null) return speak(line, topic.context, now)
    }
    // Everything that fits was said lately: any line of the fallback kind it hasn't said in a while.
    val fallback = if (slime.isAsleep) LineCategory.sleeping else LineCategory.idle
    val unsaid = (Dialogue.lines[fallback] ?: emptyList()).filter { !recent.contains(it.id) }
    val line = if (unsaid.isEmpty()) Dialogue.pick(fallback, this, now, calendar) else unsaid[rng.nextIndex(unsaid.size)]
    return speak(line, LineContext(game = slime.favoriteGame.title), now)
}

/** One thing it could talk about, and how much it wants to. */
data class ChatterTopic(
    val category: LineCategory,
    val weight: Double,
    val context: LineContext = LineContext(),
)

/** What it could talk about right now, weighted. */
fun GameState.chatterTopics(now: Instant, calendar: SlimyCalendar = SlimyCalendar.current): List<ChatterTopic> {
    val base = LineContext(
        game = slime.favoriteGame.title,
        streak = streakInfo(now, calendar).current,
        years = Birthday.years(slime.birthday, now, calendar) ?: 0,
    )
    if (slime.isAsleep) return listOf(ChatterTopic(LineCategory.sleeping, 1.0, base))
    val topics = mutableListOf<ChatterTopic>()

    // What it would like, gently, a little more often the lower it gets.
    val low = slime.needs.lowest
    if (low.value < 50) {
        topics.add(ChatterTopic(Dialogue.category(low.need), 2 + 4 * (50 - low.value) / 50, base))
    }

    // How it feels: the leading feeling most, strong others too.
    val feelings = slime.emotions
    val dominant = feelings.dominant
    for (e in Emotion.entries) {
        if (feelings[e] < 0.3) continue
        val lead = e == dominant
        topics.add(ChatterTopic(Dialogue.category(e), (if (lead) 1.5 else 0.5) + 3.5 * feelings[e], base))
    }

    // What just happened.
    fun recent(kind: MemoryEntry.Kind, window: Double): MemoryEntry? {
        val entry = memory.last(kind) ?: return null
        val age = now.timeIntervalSince(entry.at)
        return if (age >= 0 && age < window) entry else null
    }
    recent(MemoryEntry.Kind.fed, 15.0 * 60)?.let { fed ->
        topics.add(ChatterTopic(LineCategory.afterFood, 1.5, base.copy(food = enumOf<Food>(fed.detail)?.title?.lowercase() ?: "snack")))
    }
    recent(MemoryEntry.Kind.played, 15.0 * 60)?.let { played ->
        val id = played.detail.split(":").firstOrNull { it.isNotEmpty() } ?: ""
        topics.add(ChatterTopic(LineCategory.afterGame, 1.5, base.copy(game = enumOf<GameID>(id)?.title ?: slime.favoriteGame.title)))
    }
    val cared = recent(MemoryEntry.Kind.partnerCare, 20.0 * 60)
    val note = cared?.let { PartnerCareNote.parse(it.detail) }
    if (note != null) {
        topics.add(ChatterTopic(LineCategory.partnerCared, 2.0, base.copy(friend = note.person)))
    }
    val friendKinds = listOf(MemoryEntry.Kind.visit, MemoryEntry.Kind.gift, MemoryEntry.Kind.giftReceived, MemoryEntry.Kind.friendMade)
    val friendly = memory.entries.lastOrNull { it.kind in friendKinds }
    if (friendly != null && friendly.detail.isNotEmpty()) {
        val age = now.timeIntervalSince(friendly.at)
        if (age >= 0 && age < 20 * 60) {
            topics.add(ChatterTopic(LineCategory.afterFriend, 1.5, base.copy(friend = friendly.detail)))
        }
    }
    recent(MemoryEntry.Kind.crate, 15.0 * 60)?.let { crate ->
        topics.add(ChatterTopic(LineCategory.afterCrate, 1.5, base.copy(item = CrateFind.parse(crate.detail)?.title?.lowercase() ?: "that")))
    }

    // Special days, the season, where it lives, the time of day.
    if (base.years > 0) topics.add(ChatterTopic(LineCategory.birthday, 2.5, base))
    val season = Season.at(now, calendar)
    topics.add(ChatterTopic(season.talk, if (season.lines != null) 1.4 else 0.6, base))
    topics.add(ChatterTopic(Dialogue.category(inventory.habitat), 0.7, base))
    // The weather, wherever it can see the sky: outside or through the room's window.
    if (inventory.habitat.hasWeather || inventory.habitat == Habitat.room) {
        val sky = Sky.at(now, calendar)
        sky.talk?.let { talk -> topics.add(ChatterTopic(talk, if (sky == Sky.cloudy) 0.6 else 1.3, base)) }
    }
    topics.add(ChatterTopic(Dialogue.timeOfDay(calendar.hour(now)), 1.0, base))

    // Closeness, the streak and its character.
    if (slime.bond >= 50) topics.add(ChatterTopic(LineCategory.idleClose, 0.8, base))
    if (base.streak >= 3) topics.add(ChatterTopic(LineCategory.streak, 0.35, base))
    if (slime.personality.curious > 0.5) {
        topics.add(ChatterTopic(LineCategory.curious, 0.4 * slime.personality.curious, base))
    }
    topics.add(ChatterTopic(LineCategory.idle, 1.0, base))
    return topics
}

/** Says a line now and remembers it. */
private fun GameState.speak(line: Line, context: LineContext, now: Instant): String {
    memory.used(line.id, now)
    val text = Dialogue.fill(line.text, context, this)
    slime.say = Utterance(text = text, at = now, lineID = line.id)
    return text
}

/** The line to show right now without changing anything. */
fun GameState.line(now: Instant): String {
    slime.currentLine(now)?.let { return it }
    if (slime.isAsleep) return "zzz"
    val wish = slime.wish
    if (wish != null) {
        return Dialogue.fill(Dialogue.lines[Dialogue.category(wish)]?.firstOrNull()?.text ?: "", LineContext(), this)
    }
    return "blub."
}

// MARK: Progress

/** Adds xp. Every new level pays coins once; every fifth level also brings a mystery crate. */
fun GameState.gainXP(amount: Int, now: Instant, line: StringRef): List<GameEvent> {
    val levelBefore = slime.level
    val stageBefore = slime.stage
    slime.xp += amount
    val events = mutableListOf<GameEvent>()
    if (slime.level > levelBefore) {
        for (level in (levelBefore + 1)..slime.level) {
            val paid = wallet.claim("level:$level", coins = 10 * level, at = now)
            events.add(GameEvent.levelUp(level))
            memory.add(MemoryEntry(kind = MemoryEntry.Kind.levelUp, at = now, detail = "$level", keepsake = level % 5 == 0))
            if (paid && level % 5 == 0 && addCrate(CrateKind.mystery)) {
                events.add(GameEvent.crate(CrateKind.mystery, CrateSource.levelUp))
            }
        }
        slime.feel(Emotion.joy, 0.3)
        slime.feel(Emotion.excitement, 0.35)
        line.value = Dialogue.say(LineCategory.levelUp, this, LineContext(level = slime.level), now)
        slime.react(Reaction.celebrate, now)
    }
    if (slime.stage > stageBefore) {
        events.add(GameEvent.stageUp(slime.stage))
        memory.add(MemoryEntry(kind = MemoryEntry.Kind.stageUp, at = now, detail = slime.stage.name, keepsake = true))
        line.value = Dialogue.say(LineCategory.stageUp, this, now = now)
    }
    return events
}

/** Awards every achievement that is newly done: its coins, xp, a memory and, for some, a crate. */
fun GameState.checkAchievements(now: Instant): List<GameEvent> {
    val events = mutableListOf<GameEvent>()
    for (achievement in Achievements.all) {
        if (progress.achievements[achievement.id] != null || !achievement.check(this)) continue
        progress.achievements = progress.achievements + (achievement.id to now)
        wallet.claim("achievement:${achievement.id}", coins = achievement.coins, at = now)
        achievement.crate?.let { addCrate(it) }
        player.xp += 20
        memory.add(MemoryEntry(kind = MemoryEntry.Kind.achievement, at = now, detail = achievement.id, keepsake = true))
        events.add(GameEvent.achievement(achievement.id))
    }
    return events
}

/** A random session id for a round, like Swift's `UUID()`. */
fun newSession(): UUID = UUID.randomUUID()
