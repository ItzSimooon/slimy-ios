package de.hyprnode.slimy.model

import java.time.Instant

private typealias T = Personality.Trait

/** When the slime says something. */
enum class LineCategory {
    morning, day, evening, night, reunion, longReunion,
    wishHunger, wishFun, wishEnergy, wishClean, wishLove,
    fed, fedFavorite, favoriteFound, stuffed, noCoins,
    played, highScore, lowScore, tooTired,
    bath, bathGrumpy, alreadyClean,
    pet, petAgain, petAsleep,
    sleep, notSleepy, wake, wakeTired, sleeping,
    levelUp, stageUp, achievement, bought, equipped,
    gift, giftReceived, friendHug, friendVisit, friendNew, missFriend,
    idle, idleClose, curious,
    dailyGift, giftWeek, streak, streakFresh,
    spooky, cozy, newYear, birthday,

    // How it feels, for small talk.
    feelJoy, feelSad, feelAngry, feelScared, feelLove, feelCurious, feelBored, feelExcited, feelSleepy,

    // What just happened, for small talk.
    afterFood, afterGame, afterFriend, afterCrate, partnerCared,

    // Caring together and crates.
    careStarted, careEnded, crateOpened, crateDuplicate, crateLegendary,

    // The seasons, where it lives and the weather.
    spring, summer, autumn, winter,
    roomTalk, gardenTalk, beachTalk, spaceTalk, crystalTalk,
    rainTalk, snowTalk, cloudTalk;

    companion object {
        val allCases: List<LineCategory> get() = entries
    }
}

data class Line(
    val id: String,
    val text: String,
    val traits: List<Personality.Trait> = emptyList(),
    val minBond: Int = 0,
    val hours: IntRange? = null,
)

/** Values filled into {placeholders}. */
data class LineContext(
    val food: String = "",
    val favorite: String = "",
    val friend: String = "",
    val score: Int = 0,
    val game: String = "",
    val level: Int = 0,
    val days: Int = 0,
    val item: String = "",
    val streak: Int = 0,
    val years: Int = 0,
)

object Dialogue {
    /**
     * Picks a fitting line the slime hasn't said lately, weighted by
     * personality, records it in [state], and returns the text.
     */
    fun say(
        category: LineCategory,
        state: GameState,
        context: LineContext = LineContext(),
        now: Instant = Instant.now(),
        calendar: SlimyCalendar = SlimyCalendar.current,
    ): String {
        val line = pick(category, state, now, calendar)
        state.memory.used(line.id, now)
        return fill(line.text, context, state)
    }

    fun pick(category: LineCategory, state: GameState, now: Instant, calendar: SlimyCalendar = SlimyCalendar.current): Line {
        val hour = calendar.hour(now)
        val bank = lines[category] ?: listOf(Line(id = "fallback", text = "blub."))
        val fitting = bank.filter { line ->
            line.minBond.toDouble() <= state.slime.bond && (line.hours?.contains(hour) ?: true)
        }
        val pool = fitting.ifEmpty { bank }
        val fresh = pool.filter { !state.memory.recentLines.contains(it.id) }
        val options = fresh.ifEmpty { pool }

        // Deterministic per moment, so tests are stable and a redraw doesn't reshuffle.
        val rng = SplitMix(
            SplitMix.seed("${category.name}${(now.timeIntervalSince1970 / 7).toLong()}${state.memory.recentLines.size}"),
        )
        val weights = options.map { line -> 1 + 2 * line.traits.sumOf { state.slime.personality[it] } }
        var roll = rng.unit() * weights.sum()
        for ((line, weight) in options.zip(weights)) {
            roll -= weight
            if (roll <= 0) return line
        }
        return options[options.size - 1]
    }

    fun fill(text: String, context: LineContext, state: GameState): String {
        var out = text
        val values = linkedMapOf(
            "{name}" to state.slime.name,
            "{owner}" to state.player.name.ifEmpty { "friend" },
            "{food}" to context.food,
            "{fav}" to context.favorite.ifEmpty { state.slime.favorite.title.lowercase() },
            "{friend}" to context.friend,
            "{score}" to "${context.score}",
            "{game}" to context.game,
            "{level}" to "${context.level}",
            "{days}" to "${context.days}",
            "{item}" to context.item,
            "{streak}" to "${context.streak}",
            "{years}" to if (context.years == 1) "1 year" else "${context.years} years",
        )
        for ((key, value) in values) out = out.replace(key, value)
        return out
    }

    /** Small talk that fits the hour. */
    fun timeOfDay(hour: Int): LineCategory = when (hour) {
        in 5 until 11 -> LineCategory.morning
        in 11 until 17 -> LineCategory.day
        in 17 until 22 -> LineCategory.evening
        else -> LineCategory.night
    }

    fun category(need: Need): LineCategory = when (need) {
        Need.hunger -> LineCategory.wishHunger
        Need.`fun` -> LineCategory.wishFun
        Need.energy -> LineCategory.wishEnergy
        Need.clean -> LineCategory.wishClean
        Need.love -> LineCategory.wishLove
    }

    /** Small talk for a feeling. */
    fun category(emotion: Emotion): LineCategory = when (emotion) {
        Emotion.joy -> LineCategory.feelJoy
        Emotion.sadness -> LineCategory.feelSad
        Emotion.anger -> LineCategory.feelAngry
        Emotion.fear -> LineCategory.feelScared
        Emotion.love -> LineCategory.feelLove
        Emotion.curiosity -> LineCategory.feelCurious
        Emotion.boredom -> LineCategory.feelBored
        Emotion.excitement -> LineCategory.feelExcited
        Emotion.sleepiness -> LineCategory.feelSleepy
    }

    /** Small talk about where it lives. */
    fun category(habitat: Habitat): LineCategory = when (habitat) {
        Habitat.room -> LineCategory.roomTalk
        Habitat.garden -> LineCategory.gardenTalk
        Habitat.beach -> LineCategory.beachTalk
        Habitat.space -> LineCategory.spaceTalk
        Habitat.crystal -> LineCategory.crystalTalk
    }

    /**
     * A line of the category that fits the bond and the hour and isn't in
     * [avoiding], weighted by personality; lines said in the last half hour
     * count less. Null if every fitting line is recent.
     */
    fun fresh(
        category: LineCategory,
        state: GameState,
        now: Instant,
        calendar: SlimyCalendar = SlimyCalendar.current,
        avoiding: Set<String>,
        rng: SplitMix,
    ): Line? {
        val hour = calendar.hour(now)
        val options = (lines[category] ?: emptyList()).filter { line ->
            !avoiding.contains(line.id) && line.minBond.toDouble() <= state.slime.bond && (line.hours?.contains(hour) ?: true)
        }
        if (options.isEmpty()) return null
        val weights = options.map { line ->
            val character = 1 + 2 * line.traits.sumOf { state.slime.personality[it] }
            val saidAt = state.memory.lineUsedAt[line.id] ?: DistantPast
            val lately = now.timeIntervalSince(saidAt) < 30 * 60
            character * (if (lately) 0.3 else 1.0)
        }
        var roll = rng.unit() * weights.sum()
        for ((line, weight) in options.zip(weights)) {
            roll -= weight
            if (roll < 0) return line
        }
        return options[options.size - 1]
    }

    // MARK: - Lines

    /** Every line by category. Ids are "<category>.<index>", the same as on iOS. */
    val lines: Map<LineCategory, List<Line>> by lazy {
        val out = HashMap<LineCategory, List<Line>>()
        for ((category, items) in table() + feelings() + moments() + world()) out[category] = items
        out
    }

    private class L(val text: String, val traits: List<Personality.Trait>, val bond: Int, val hours: IntRange?)

    private fun l(c: LineCategory, items: List<L>): Pair<LineCategory, List<Line>> =
        c to items.mapIndexed { i, item ->
            Line(id = "${c.name}.$i", text = item.text, traits = item.traits, minBond = item.bond, hours = item.hours)
        }

    private fun s(c: LineCategory, texts: List<String>): Pair<LineCategory, List<Line>> =
        l(c, texts.map { L(it, emptyList(), 0, null) })

    private fun table(): List<Pair<LineCategory, List<Line>>> = listOf(
        l(LineCategory.morning, listOf(
            L("morning.", listOf(), 0, null),
            L("good morning. i'm awake. almost.", listOf(T.sleepy), 0, null),
            L("morning! what are we doing today?", listOf(T.playful, T.curious), 0, null),
            L("breakfast would be nice", listOf(T.foodie), 0, null),
            L("morning, {owner}. glad you're here.", listOf(T.cuddly), 30, null),
            L("the sun is up. so am i. mostly.", listOf(T.sleepy), 0, null),
            L("i dreamed we went to the beach", listOf(T.curious), 0, null),
            L("morning stretch. boing.", listOf(T.playful), 0, null),
            L("breakfast idea: {fav}", listOf(T.foodie), 0, null),
        )),
        l(LineCategory.day, listOf(
            L("hello.", listOf(), 0, null),
            L("i wobbled a little while you were gone", listOf(), 0, null),
            L("there you are", listOf(T.cuddly), 10, null),
            L("i've been thinking. about {fav}.", listOf(T.foodie), 0, null),
            L("i studied a speck of dust. fascinating.", listOf(T.curious), 0, null),
            L("wanna play something?", listOf(T.playful), 0, null),
            L("busy day? i'll keep the wobble warm.", listOf(T.cuddly), 0, null),
            L("i counted the floorboards. there are a lot.", listOf(T.curious), 0, null),
            L("midday bounce check: very bouncy", listOf(T.playful), 0, null),
            L("is it snack o'clock?", listOf(T.foodie), 0, null),
        )),
        l(LineCategory.evening, listOf(
            L("evening. cozy.", listOf(), 0, null),
            L("today was good, right?", listOf(T.cuddly), 20, null),
            L("i'm getting sleepy", listOf(T.sleepy), 0, null),
            L("one more game before bed?", listOf(T.playful), 0, null),
            L("the light is all orange. pretty.", listOf(), 0, null),
            L("let's take it slow tonight", listOf(T.sleepy), 0, null),
            L("tell me about your day?", listOf(T.curious), 10, null),
            L("evening snack? just asking.", listOf(T.foodie), 0, null),
        )),
        l(LineCategory.night, listOf(
            L("psst. it's night.", listOf(), 0, null),
            L("you're still up too?", listOf(T.curious), 0, null),
            L("counting slime sheep", listOf(T.sleepy), 0, null),
            L("the moon is wobbling too", listOf(), 0, null),
            L("shh. the room is sleeping.", listOf(T.sleepy), 0, null),
            L("night thought: you're nice", listOf(T.cuddly), 30, null),
            L("i can see one star from here", listOf(T.curious), 0, null),
        )),
        l(LineCategory.reunion, listOf(
            L("you're back", listOf(), 0, null),
            L("hi! i waited for you. a little.", listOf(T.cuddly), 0, null),
            L("finally. i have so much to tell you. not really.", listOf(T.curious), 0, null),
            L("good to see you", listOf(), 20, null),
            L("hi! i kept everything wobbly for you", listOf(), 0, null),
            L("oh good, it's you", listOf(T.cuddly), 10, null),
        )),
        l(LineCategory.longReunion, listOf(
            L("{days} days! i missed you. are you okay?", listOf(), 0, null),
            L("there you are. i spent the time wobbling.", listOf(), 0, null),
            L("welcome back. i saved you a spot.", listOf(T.cuddly), 0, null),
        )),
        l(LineCategory.wishHunger, listOf(
            L("hungry. very hungry.", listOf(), 0, null),
            L("my tummy is making noises", listOf(), 0, null),
            L("all i can think about is {fav}", listOf(T.foodie), 0, null),
            L("an apple would be nice right now", listOf(), 0, null),
            L("a little snack would be perfect", listOf(), 0, null),
            L("snack time soon?", listOf(), 0, null),
            L("i could eat a whole {fav}", listOf(T.foodie), 0, null),
            L("my tummy says hello", listOf(), 0, null),
        )),
        l(LineCategory.wishFun, listOf(
            L("i'm bored", listOf(), 0, null),
            L("play? please?", listOf(T.playful), 0, null),
            L("i keep thinking about {game}", listOf(T.playful), 0, null),
            L("i'm staring at the wall. the wall stares back.", listOf(T.curious), 0, null),
            L("one round of {game}?", listOf(T.playful), 0, null),
            L("a game would be fun", listOf(), 0, null),
            L("let's play something together?", listOf(T.playful), 0, null),
            L("i've got bounce to spare", listOf(T.playful), 0, null),
        )),
        l(LineCategory.wishEnergy, listOf(
            L("eyes. so. heavy.", listOf(), 0, null),
            L("i could sleep standing up. i don't have legs.", listOf(T.sleepy), 0, null),
            L("bed?", listOf(), 0, null),
            L("i might nap right here", listOf(T.sleepy), 0, null),
            L("a nap sounds lovely", listOf(), 0, null),
            L("sleepy goo, sleepy me", listOf(T.sleepy), 0, null),
            L("my wobble is getting slow", listOf(), 0, null),
        )),
        l(LineCategory.wishClean, listOf(
            L("i'm sticking to myself", listOf(), 0, null),
            L("something is stuck inside me. i think.", listOf(T.curious), 0, null),
            L("a bath would be nice", listOf(), 0, null),
            L("bubbles? i love bubbles.", listOf(), 0, null),
            L("a little splash would be nice", listOf(), 0, null),
            L("i'm a bit dusty", listOf(), 0, null),
            L("bath time soon? just a thought.", listOf(), 0, null),
        )),
        l(LineCategory.wishLove, listOf(
            L("pet me a little?", listOf(), 0, null),
            L("a little company would be nice", listOf(T.cuddly), 0, null),
            L("cuddle break?", listOf(T.cuddly), 0, null),
            L("a quick pat would make my day", listOf(), 0, null),
            L("a pat would be nice", listOf(), 0, null),
            L("come say hi?", listOf(T.cuddly), 0, null),
            L("i saved you a cuddle", listOf(T.cuddly), 0, null),
        )),
        l(LineCategory.fed, listOf(
            L("nom. {food}.", listOf(), 0, null),
            L("{food}. good.", listOf(), 0, null),
            L("thanks. that was tasty.", listOf(), 0, null),
            L("*chews very loudly*", listOf(T.foodie), 0, null),
            L("{food}! tastes like more.", listOf(T.foodie), 0, null),
        )),
        s(LineCategory.fedFavorite, listOf(
            "{food}! MY FAVOURITE",
            "you just know what i like",
            "best {food} in the world",
        )),
        s(LineCategory.favoriteFound, listOf(
            "{food}! that's it. my favourite food.",
        )),
        s(LineCategory.stuffed, listOf(
            "i'm about to burst",
            "no room left. really.",
            "later. i'm full.",
        )),
        s(LineCategory.noCoins, listOf(
            "not enough coins",
            "we can't afford that. playing helps.",
        )),
        l(LineCategory.played, listOf(
            L("wheee. {score} points.", listOf(), 0, null),
            L("that was fun", listOf(), 0, null),
            L("again?", listOf(T.playful), 0, null),
            L("{score}. not bad.", listOf(), 0, null),
        )),
        s(LineCategory.highScore, listOf(
            "new record! {score}!",
            "{score}! i've never been this good",
            "a record. i'm a little proud.",
        )),
        s(LineCategory.lowScore, listOf(
            "next time for sure",
            "practice, practice",
            "it was fun anyway",
        )),
        s(LineCategory.tooTired, listOf(
            "too tired to play",
            "sleep first, then play",
        )),
        s(LineCategory.bath, listOf(
            "squeaky clean",
            "blub. clean.",
            "i'm shiny",
        )),
        s(LineCategory.bathGrumpy, listOf(
            "brr. wet. i'll forgive you.",
            "baths. not my favourite. but i'm shiny.",
            "hmph. clean. fine.",
            "i'm soggy and a bit grumpy",
            "okay, the bubbles were nice. a little.",
        )),
        s(LineCategory.alreadyClean, listOf(
            "i'm already clean",
            "can't get any cleaner",
        )),
        l(LineCategory.pet, listOf(
            L("hehe. tickles.", listOf(), 0, null),
            L("more of that", listOf(T.cuddly), 0, null),
            L("*purrs. i think.*", listOf(T.cuddly), 20, null),
            L("love you, {owner}", listOf(), 60, null),
            L("that's nice", listOf(), 0, null),
        )),
        s(LineCategory.petAgain, listOf(
            "okay okay, you like me. got it.",
            "i'm all flat from petting",
        )),
        s(LineCategory.petAsleep, listOf(
            "*snores happily*",
            "*smiles in its sleep*",
        )),
        s(LineCategory.sleep, listOf(
            "good night",
            "sleep well. me too.",
            "lights out",
        )),
        s(LineCategory.notSleepy, listOf(
            "i'm not tired yet",
            "sleep? now? no.",
        )),
        s(LineCategory.wake, listOf(
            "good morning",
            "well rested",
            "hello world",
        )),
        s(LineCategory.wakeTired, listOf(
            "five more minutes …",
            "who woke me up",
        )),
        s(LineCategory.sleeping, listOf(
            "zzz … dreaming of {fav}",
            "zzz",
            "*sleeping deeply*",
            "zzz … wobble … zzz",
            "*mumbles something about {fav}*",
            "zzz … bubbles … zzz",
            "*snores very softly*",
            "zzz … one more game … zzz",
            "*wiggles in its sleep*",
            "zzz … {owner} … zzz",
            "*dreams of jumping very high*",
            "mm … soft …",
            "zzz … crunchy leaves … zzz",
            "*a tiny happy sigh*",
            "zzz … bounce … bounce …",
            "*rolls over*",
            "zzz … the moon is jelly … zzz",
            "*giggles in its sleep*",
        )),
        s(LineCategory.levelUp, listOf(
            "level {level}!",
            "level {level}. i'm getting big.",
        )),
        s(LineCategory.stageUp, listOf(
            "i grew. look at me.",
            "i feel different. bigger.",
        )),
        s(LineCategory.achievement, listOf(
            "achievement! {item}",
            "we did it: {item}",
        )),
        s(LineCategory.bought, listOf(
            "{item}! thank you.",
            "ooh, {item}",
        )),
        s(LineCategory.equipped, listOf(
            "suits me, right?",
            "i look good",
            "fancy.",
        )),
        s(LineCategory.gift, listOf(
            "a gift for {friend} is on its way",
            "{friend} will love it",
        )),
        s(LineCategory.giftReceived, listOf(
            "{friend} sent us something!",
            "mail from {friend}",
        )),
        s(LineCategory.friendHug, listOf(
            "*squishes {friend}*",
            "hug with {friend}. soft.",
        )),
        s(LineCategory.friendVisit, listOf(
            "visiting {friend}",
            "{friend} has a lovely home",
        )),
        s(LineCategory.friendNew, listOf(
            "new friend: {friend}",
            "{friend}! hi!",
        )),
        s(LineCategory.missFriend, listOf(
            "i miss {friend}",
            "{friend} hasn't been around in a while",
        )),
        l(LineCategory.idle, listOf(
            L("all good here", listOf(), 0, null),
            L("blub.", listOf(), 0, null),
            L("just wobbling along", listOf(), 0, null),
            L("what are you up to?", listOf(T.curious), 0, null),
            L("i saw a fly. it was fast.", listOf(T.curious), 0, null),
            L("not hungry. but {fav} always works.", listOf(T.foodie), 0, null),
            L("practicing my bounce", listOf(T.playful), 0, null),
            L("yawn", listOf(T.sleepy), 0, null),
            L("i'm here if you need me", listOf(T.cuddly), 10, null),
            L("the sun is nice today", listOf(), 0, 9..18),
            L("it's dark outside", listOf(), 0, 19..23),
            L("i'm practicing being round", listOf(), 0, null),
            L("wobble. wobble. pause. wobble.", listOf(T.playful), 0, null),
            L("do you think clouds are slimes?", listOf(T.curious), 0, null),
            L("i saved you a warm spot", listOf(T.cuddly), 20, null),
            L("thinking about {fav}. again.", listOf(T.foodie), 0, null),
            L("tiny nap later? tiny nap later.", listOf(T.sleepy), 0, null),
            L("i'm thinking about nothing. it's relaxing.", listOf(), 0, null),
            L("a little wobble for no reason", listOf(), 0, null),
            L("i like the sound of the room", listOf(), 0, null),
            L("doing my slime things", listOf(), 0, null),
            L("i practiced a new face. it's this one.", listOf(T.playful), 0, null),
            L("blub blub", listOf(), 0, null),
            L("just here. being round.", listOf(), 0, null),
            L("small thought: you're nice", listOf(T.cuddly), 20, null),
        )),
        l(LineCategory.idleClose, listOf(
            L("i'm glad you exist", listOf(), 50, null),
            L("you're my favourite human", listOf(T.cuddly), 70, null),
            L("remember our first day?", listOf(), 50, null),
            L("we make a good team", listOf(), 50, null),
            L("i like it when you're around", listOf(T.cuddly), 60, null),
        )),
        s(LineCategory.curious, listOf(
            "what's that?",
            "hm?",
            "let me have a look",
        )),
        // The daily gift and the streak. Never a word about missed days.
        s(LineCategory.dailyGift, listOf(
            "a gift! for me? for us.",
            "ooh. it rattles.",
            "unwrapping. carefully. very carefully.",
            "coins! we're rich. a little.",
            "i love surprises",
            "same time tomorrow?",
        )),
        s(LineCategory.giftWeek, listOf(
            "day seven. the big one!",
            "a {item}! what's inside?",
            "a whole week of gifts. look at us.",
            "it rattles. it's a {item}!",
        )),
        l(LineCategory.streak, listOf(
            L("{streak} days in a row. we're a team.", listOf(), 0, null),
            L("day {streak}. i've been counting.", listOf(T.curious), 0, null),
            L("you keep coming back. i like that a lot.", listOf(T.cuddly), 0, null),
            L("{streak} days! that's a habit now.", listOf(), 0, null),
            L("streak buddies", listOf(T.playful), 0, null),
            L("every day with you is a good day", listOf(T.cuddly), 20, null),
            L("{streak} days. my wobble is getting stronger.", listOf(T.playful), 0, null),
        )),
        s(LineCategory.streakFresh, listOf(
            "day one. my favourite kind of day.",
            "fresh start. hi!",
            "hi again. so good to see you.",
            "new streak, same slime",
        )),
        // The seasons.
        l(LineCategory.spooky, listOf(
            L("boo. did i scare you?", listOf(T.playful), 0, null),
            L("i carved a pumpkin. it looks like me.", listOf(), 0, null),
            L("the bats are friendly. mostly.", listOf(T.curious), 0, null),
            L("i'm dressing up as a ghost. a green one.", listOf(T.playful), 0, null),
            L("spooky season. i'm only a little scared.", listOf(T.cuddly), 0, null),
            L("something went bump. it was me.", listOf(), 0, null),
            L("trick or treat? i pick treat.", listOf(T.foodie), 0, null),
            L("the pumpkins are smiling at me", listOf(), 0, null),
        )),
        l(LineCategory.cozy, listOf(
            L("snow! i want to be a snowslime.", listOf(T.playful), 0, null),
            L("it's so cozy in here", listOf(), 0, null),
            L("the lights keep blinking at me", listOf(T.curious), 0, null),
            L("hot cocoa weather", listOf(T.foodie), 0, null),
            L("i wrapped myself in a blanket. i'm a gift.", listOf(T.cuddly), 0, null),
            L("the tree smells like a forest", listOf(), 0, null),
            L("warm goo, cold window", listOf(T.sleepy), 0, null),
            L("let's stay in today", listOf(T.sleepy), 0, null),
        )),
        s(LineCategory.newYear, listOf(
            "happy new year!",
            "new year. same goo. better goo.",
            "the sky is going pop pop pop",
            "my resolution: more wobbling",
            "did you see the fireworks?",
        )),
        s(LineCategory.birthday, listOf(
            "it's my birthday! {years} old today.",
            "{years} of wobbling with you",
            "cake? for me? for me!",
            "best birthday ever",
            "make a wish. i made one too.",
            "thank you for hatching me",
        )),
    )
    // How it feels.
    private fun feelings(): List<Pair<LineCategory, List<Line>>> = listOf(
        l(LineCategory.feelJoy, listOf(
            L("i feel all bouncy inside", listOf(), 0, null),
            L("today is a good wobble day", listOf(), 0, null),
            L("everything is nice. i checked.", listOf(), 0, null),
            L("happy goo, happy me", listOf(), 0, null),
            L("i might hum. slimes can hum.", listOf(), 0, null),
            L("i'm so glad i'm me", listOf(), 0, null),
            L("look at me. very content.", listOf(), 0, null),
            L("my jelly is doing a little dance", listOf(T.playful), 0, null),
            L("good vibes only. and snacks.", listOf(T.foodie), 0, null),
            L("i like it here. with you.", listOf(T.cuddly), 0, null),
            L("wheee. no reason. just wheee.", listOf(T.playful), 0, null),
            L("i'm happy and i know it", listOf(), 0, null),
        )),
        l(LineCategory.feelSad, listOf(
            L("a bit droopy today", listOf(), 0, null),
            L("i'm a little blue. not the colour.", listOf(), 0, null),
            L("feeling soft and squishy. the sad kind.", listOf(), 0, null),
            L("can i sit here with you for a bit?", listOf(T.cuddly), 0, null),
            L("small rain cloud in my goo", listOf(), 0, null),
            L("i'll be okay. i'm just a puddle right now.", listOf(), 0, null),
            L("some days are wobbly", listOf(), 0, null),
            L("a hug would help. maybe.", listOf(T.cuddly), 0, null),
            L("i'm quiet today. that's okay.", listOf(), 0, null),
            L("my bounce is resting", listOf(), 0, null),
            L("sad slime, soft heart", listOf(), 0, null),
            L("i think i need a cozy minute", listOf(T.sleepy), 0, null),
        )),
        l(LineCategory.feelAngry, listOf(
            L("hmph.", listOf(), 0, null),
            L("i'm grumpy. a tiny bit. okay, medium.", listOf(), 0, null),
            L("my goo is all bristly", listOf(), 0, null),
            L("don't poke the jelly. well. maybe softly.", listOf(), 0, null),
            L("i'm cross. i forgot why.", listOf(T.curious), 0, null),
            L("grr. that's my scary noise.", listOf(T.playful), 0, null),
            L("i'm stomping. you can't tell, no feet.", listOf(), 0, null),
            L("grumpy mode. it'll pass.", listOf(), 0, null),
            L("grumpy tummy. a snack would fix it.", listOf(T.foodie), 0, null),
            L("huff. puff. okay, better.", listOf(), 0, null),
        )),
        l(LineCategory.feelScared, listOf(
            L("eep.", listOf(), 0, null),
            L("did you hear that?", listOf(T.curious), 0, null),
            L("i'm hiding. you didn't see me.", listOf(), 0, null),
            L("everything's a bit big right now", listOf(), 0, null),
            L("stay close for a moment?", listOf(T.cuddly), 0, null),
            L("my goo went all wobbly. the scared kind.", listOf(), 0, null),
            L("it's fine. it's fine. is it fine?", listOf(), 0, null),
            L("brave slime. brave slime. eep.", listOf(), 0, null),
            L("i'll just be very small for a bit", listOf(), 0, null),
            L("you're here. that helps.", listOf(T.cuddly), 0, null),
        )),
        l(LineCategory.feelLove, listOf(
            L("i like you a whole lot", listOf(), 0, null),
            L("you're my favourite person", listOf(T.cuddly), 20, null),
            L("my goo goes warm when you're here", listOf(), 0, null),
            L("can we stay like this?", listOf(T.cuddly), 0, null),
            L("i saved all my best wobbles for you", listOf(T.playful), 0, null),
            L("heart full. goo full.", listOf(T.foodie), 0, null),
            L("you make the room nicer", listOf(), 0, null),
            L("i'm so lucky you found my egg", listOf(), 0, null),
            L("if i had arms i'd hug you", listOf(T.cuddly), 0, null),
            L("you, me, this. perfect.", listOf(), 0, null),
            L("i love our little life", listOf(), 30, null),
            L("glad it's you, {owner}", listOf(), 10, null),
        )),
        l(LineCategory.feelCurious, listOf(
            L("what's that over there?", listOf(), 0, null),
            L("i have a question. i forgot it.", listOf(), 0, null),
            L("how does the sun stay up?", listOf(), 0, null),
            L("i wonder what's behind the wall", listOf(), 0, null),
            L("can slimes learn to whistle?", listOf(T.playful), 0, null),
            L("if i stretch really far, where do i end?", listOf(), 0, null),
            L("i'm investigating. very serious.", listOf(), 0, null),
            L("is the moon made of jelly too?", listOf(T.foodie), 0, null),
            L("what do you do all day out there?", listOf(T.cuddly), 0, null),
            L("i found a crumb. studying it.", listOf(T.foodie), 0, null),
            L("why is water wet? asking for me.", listOf(), 0, null),
            L("what does purple taste like?", listOf(T.foodie), 0, null),
        )),
        l(LineCategory.feelBored, listOf(
            L("hmm. hmm hmm.", listOf(), 0, null),
            L("i counted my wobbles. 312.", listOf(), 0, null),
            L("i'm looking for something to do", listOf(), 0, null),
            L("game? maybe? if you feel like it.", listOf(T.playful), 0, null),
            L("i invented a new game. it's called waiting.", listOf(), 0, null),
            L("tap tap tap. that's me drumming.", listOf(T.playful), 0, null),
            L("the ceiling has 4 spots. i named them.", listOf(T.curious), 0, null),
            L("i could go for a little adventure", listOf(T.curious), 0, null),
            L("bored goo. needs a bounce.", listOf(), 0, null),
            L("let's do something silly", listOf(T.playful), 0, null),
        )),
        l(LineCategory.feelExcited, listOf(
            L("something good is happening. i can feel it.", listOf(), 0, null),
            L("i can't sit still. i'm vibrating.", listOf(), 0, null),
            L("best. day. ever. maybe.", listOf(), 0, null),
            L("wheeee! again! again!", listOf(T.playful), 0, null),
            L("my goo is fizzing", listOf(), 0, null),
            L("boing boing boing", listOf(T.playful), 0, null),
            L("i have so much energy right now", listOf(), 0, null),
            L("let's do everything!", listOf(T.curious), 0, null),
            L("that was amazing. was it amazing? it was.", listOf(), 0, null),
            L("i'm a firework. a squishy one.", listOf(), 0, null),
            L("zoom. zoom. zoom.", listOf(T.playful), 0, null),
            L("can we play? can we? can we?", listOf(T.playful), 0, null),
        )),
        l(LineCategory.feelSleepy, listOf(
            L("yaaawn", listOf(), 0, null),
            L("my eyes are doing the slow blink", listOf(), 0, null),
            L("just resting my goo for a second", listOf(), 0, null),
            L("sleepy. so sleepy. very cozy.", listOf(T.sleepy), 0, null),
            L("i could nap on a cloud", listOf(T.sleepy), 0, null),
            L("my bounce went to bed already", listOf(), 0, null),
            L("five minutes of nap would fix me", listOf(T.sleepy), 0, null),
            L("mm. warm. drowsy.", listOf(), 0, null),
            L("if i'm quiet, i'm dozing", listOf(), 0, null),
            L("is it bedtime? it feels like bedtime.", listOf(), 0, null),
        )),
    )
    // What just happened, caring together, crates.
    private fun moments(): List<Pair<LineCategory, List<Line>>> = listOf(
        l(LineCategory.afterFood, listOf(
            L("still thinking about that {food}", listOf(T.foodie), 0, null),
            L("tummy happy", listOf(), 0, null),
            L("that hit the spot", listOf(), 0, null),
            L("{food}. ten out of ten.", listOf(T.foodie), 0, null),
            L("my goo is digesting. very busy.", listOf(), 0, null),
            L("i can still taste it. mmm.", listOf(T.foodie), 0, null),
            L("full and round. rounder than usual.", listOf(), 0, null),
            L("thanks for the snack", listOf(T.cuddly), 0, null),
        )),
        l(LineCategory.afterGame, listOf(
            L("that round of {game} was fun", listOf(), 0, null),
            L("my bounce is still going", listOf(T.playful), 0, null),
            L("i'm practicing {game} in my head", listOf(T.curious), 0, null),
            L("rematch later?", listOf(T.playful), 0, null),
            L("we're getting good at {game}", listOf(), 0, null),
            L("phew. good game.", listOf(), 0, null),
            L("i liked that one", listOf(), 0, null),
            L("i'm still a bit out of breath", listOf(T.sleepy), 0, null),
        )),
        l(LineCategory.afterFriend, listOf(
            L("i liked seeing {friend}", listOf(), 0, null),
            L("{friend} is really nice", listOf(T.cuddly), 0, null),
            L("we should see {friend} again soon", listOf(), 0, null),
            L("friends make the day wobblier", listOf(T.playful), 0, null),
            L("i'm telling everyone about {friend}", listOf(), 0, null),
            L("{friend} said hi. i said hi back.", listOf(), 0, null),
            L("my friend list is my treasure", listOf(), 0, null),
            L("still smiling about {friend}", listOf(), 0, null),
        )),
        l(LineCategory.afterCrate, listOf(
            L("still thinking about that crate", listOf(), 0, null),
            L("what if the next crate has a surprise?", listOf(T.curious), 0, null),
            L("{item}! i keep looking at it.", listOf(), 0, null),
            L("unboxing is my new favourite sport", listOf(T.playful), 0, null),
            L("crates are like gifts that rattle", listOf(), 0, null),
            L("that crate was a good one", listOf(), 0, null),
            L("i love the sound a crate makes", listOf(), 0, null),
            L("i'd like to live in a crate. a small one.", listOf(T.curious), 0, null),
        )),
        l(LineCategory.partnerCared, listOf(
            L("{friend} looked after me. so nice.", listOf(), 0, null),
            L("{friend} was here! sort of.", listOf(T.playful), 0, null),
            L("two people care for me. i'm famous.", listOf(), 0, null),
            L("{friend} says hi. i think.", listOf(), 0, null),
            L("i got some care from {friend}", listOf(T.cuddly), 0, null),
            L("lucky me. two of you.", listOf(), 0, null),
            L("{friend} and you make a good team", listOf(), 0, null),
            L("{friend} was just here. i'm all warm.", listOf(T.cuddly), 0, null),
            L("double the care. double the wobble.", listOf(T.playful), 0, null),
            L("tell {friend} i said thanks", listOf(), 0, null),
        )),
        s(LineCategory.careStarted, listOf(
            "{friend} is looking after me too now",
            "two of you? best slime life.",
            "hi, {friend}! we're a team now.",
            "one slime, two friends. perfect.",
            "a new caretaker. i'll be extra wobbly.",
        )),
        s(LineCategory.careEnded, listOf(
            "just us again. that's nice too.",
            "{friend} was lovely. bye for now.",
            "back home. i missed this spot.",
            "hello again. i kept everything wobbly.",
        )),
        s(LineCategory.crateOpened, listOf(
            "ooh, {item}!",
            "{item}! for us?",
            "what's in the box? {item}!",
            "{item}. i approve.",
            "crate cracked. {item} inside.",
            "look! {item}!",
            "another one! {item}!",
            "i shook it and {item} came out",
            "{item}. what a find.",
            "the crate had {item}. lucky.",
        )),
        s(LineCategory.crateDuplicate, listOf(
            "we have that already. coins it is.",
            "a twin! turned into coins.",
            "already ours. shiny coins instead.",
            "coins! almost as good.",
            "a double. so we get coins.",
        )),
        s(LineCategory.crateLegendary, listOf(
            "what. what. look at this!",
            "i'm... i'm an axolotl?!",
            "something legendary. i'm shaking.",
            "frilly gills! i have frilly gills!",
            "the rarest thing. and it's ours.",
        )),
    )
    // The seasons and where it lives.
    private fun world(): List<Pair<LineCategory, List<Line>>> = listOf(
        l(LineCategory.spring, listOf(
            L("the flowers are waking up", listOf(), 0, null),
            L("spring! everything's green like me.", listOf(), 0, null),
            L("i smell rain and blossoms", listOf(T.curious), 0, null),
            L("the birds are back. loud ones.", listOf(), 0, null),
            L("spring cleaning? i'll just wobble.", listOf(T.sleepy), 0, null),
        )),
        l(LineCategory.summer, listOf(
            L("it's warm. i'm a bit melty.", listOf(), 0, null),
            L("summer! ice cream weather.", listOf(T.foodie), 0, null),
            L("the days are so long. more wobble time.", listOf(T.playful), 0, null),
            L("sunny goo, happy goo", listOf(), 0, null),
            L("let's find some shade", listOf(T.sleepy), 0, null),
        )),
        l(LineCategory.autumn, listOf(
            L("the leaves are turning orange", listOf(), 0, null),
            L("crunchy leaf season", listOf(T.playful), 0, null),
            L("sweater weather. i'm the sweater.", listOf(T.cuddly), 0, null),
            L("the wind is doing loops", listOf(T.curious), 0, null),
            L("cozy autumn wobble", listOf(T.sleepy), 0, null),
        )),
        l(LineCategory.winter, listOf(
            L("chilly. i'm staying jiggly.", listOf(), 0, null),
            L("winter. perfect for blankets.", listOf(T.sleepy), 0, null),
            L("my goo is a bit cold", listOf(T.cuddly), 0, null),
            L("frosty window, warm slime", listOf(), 0, null),
            L("short days, long naps", listOf(T.sleepy), 0, null),
        )),
        l(LineCategory.roomTalk, listOf(
            L("home sweet home", listOf(), 0, null),
            L("this room is just my size", listOf(), 0, null),
            L("i rearranged the dust", listOf(T.curious), 0, null),
            L("my corner is the best corner", listOf(), 0, null),
            L("i like our little room", listOf(T.cuddly), 0, null),
        )),
        l(LineCategory.gardenTalk, listOf(
            L("the grass tickles", listOf(), 0, null),
            L("a bee waved at me. i think.", listOf(T.curious), 0, null),
            L("i'm basically a garden slime now", listOf(), 0, null),
            L("the flowers smell nice", listOf(), 0, null),
            L("if it rains, i'll grow", listOf(T.playful), 0, null),
            L("i heard the wind in the trees", listOf(T.sleepy), 0, null),
        )),
        l(LineCategory.beachTalk, listOf(
            L("sand gets everywhere. everywhere.", listOf(), 0, null),
            L("the waves keep saying hi", listOf(), 0, null),
            L("i'm a beach blob now", listOf(T.playful), 0, null),
            L("salty air. tasty air.", listOf(T.foodie), 0, null),
            L("i found a shell. it's mine now.", listOf(T.curious), 0, null),
            L("the sea is so big", listOf(), 0, null),
        )),
        l(LineCategory.spaceTalk, listOf(
            L("no gravity. i'm floating.", listOf(T.playful), 0, null),
            L("hi, stars", listOf(), 0, null),
            L("space is very quiet", listOf(T.sleepy), 0, null),
            L("i can see the whole world from here", listOf(T.curious), 0, null),
            L("is that a comet? make a wish.", listOf(), 0, null),
            L("astro-slime reporting in", listOf(), 0, null),
        )),
        l(LineCategory.crystalTalk, listOf(
            L("the crystals hum a little", listOf(), 0, null),
            L("everything sparkles in here", listOf(), 0, null),
            L("i can see a hundred of me", listOf(T.curious), 0, null),
            L("glowy cave, glowy goo", listOf(), 0, null),
            L("the crystals are warm. who knew.", listOf(), 0, null),
        )),
        // Weather: said outside and at the room's window alike.
        l(LineCategory.rainTalk, listOf(
            L("pitter patter. it's raining.", listOf(), 0, null),
            L("listen. rain.", listOf(), 0, null),
            L("rain! my favourite sound.", listOf(), 0, null),
            L("rainy day. good for naps.", listOf(T.sleepy), 0, null),
            L("i want to count the raindrops", listOf(T.curious), 0, null),
            L("rain makes me extra squishy", listOf(T.playful), 0, null),
            L("rainy days are for staying close", listOf(T.cuddly), 0, null),
            L("soup weather", listOf(T.foodie), 0, null),
        )),
        l(LineCategory.snowTalk, listOf(
            L("snow! everything's so quiet.", listOf(), 0, null),
            L("look, it's snowing", listOf(), 0, null),
            L("no two snowflakes alike. like slimes.", listOf(T.curious), 0, null),
            L("i want to catch a snowflake", listOf(T.playful), 0, null),
            L("snowy day. stay cozy with me?", listOf(T.cuddly), 0, null),
            L("snow day nap time", listOf(T.sleepy), 0, null),
            L("the snow looks like sugar", listOf(T.foodie), 0, null),
        )),
        l(LineCategory.cloudTalk, listOf(
            L("lots of clouds up there", listOf(), 0, null),
            L("that cloud looks like a slime", listOf(T.curious), 0, null),
            L("grey sky, cozy goo", listOf(T.sleepy), 0, null),
            L("clouds are just sky slimes", listOf(T.playful), 0, null),
            L("the sun is hiding. i'll be your sun.", listOf(T.cuddly), 0, 7..18),
            L("that cloud looks like a pancake", listOf(T.foodie), 0, null),
        )),
    )
}
