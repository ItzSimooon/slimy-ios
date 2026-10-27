package de.hyprnode.slimy.model

import java.time.Instant

/**
 * Makes the day's plan on the phone: game blocks picked by a generator
 * seeded with the date. Everyone gets the same game on the same day (on
 * iPhone and Android alike), and no two days in a row feel alike.
 */
object DailyPlanner {
    fun dayKey(date: Instant, calendar: SlimyCalendar = SlimyCalendar.current): String =
        Challenges.dayKey(date, calendar)

    fun plan(day: String): DailyPlan {
        val rng = SplitMix(SplitMix.seed("slimy.daily.$day"))
        // Rotate controls so neighbouring days differ; the seed picks the rest.
        val controls = GameControl.entries
        val dayNumber = (SplitMix.seed(day) % 9973uL).toInt()
        val control = controls[(dayNumber + rng.int(0..1)) % controls.size]
        val theme = GameTheme.entries[rng.int(0..GameTheme.entries.size - 1)]
        val goal = if (rng.unit() < 0.65) GameGoal.timed else GameGoal.survive

        val pack = packs[rng.int(0..packs.size - 1)]
        val entities = mutableListOf<EntitySpec>()
        val goodCount = rng.int(1..2)
        for (i in 0 until goodCount) {
            val sprite = pack.good[(i + rng.int(0..pack.good.size - 1)) % pack.good.size]
            val motion = pick(
                rng,
                if (control == GameControl.lanes) listOf(EntityMotion.straight)
                else listOf(EntityMotion.straight, EntityMotion.straight, EntityMotion.wave, EntityMotion.zigzag, EntityMotion.bounce),
            )
            val speed = 0.8 + rng.unit() * 0.5
            val rate = if (i == 0) 0.9 + rng.unit() * 0.5 else 0.35 + rng.unit() * 0.3
            val points = if (i == 0) rng.int(1..3) else rng.int(4..8)
            entities.add(
                EntitySpec(sprite = sprite, role = EntityRole.collect, power = null, motion = motion,
                    speed = speed, rate = rate, points = points, size = if (i == 0) 1.0 else 0.85),
            )
        }
        val badCount = rng.int(1..2)
        for (i in 0 until badCount) {
            val sprite = pack.bad[(i + rng.int(0..pack.bad.size - 1)) % pack.bad.size]
            val motions = if (control == GameControl.lanes) listOf(EntityMotion.straight, EntityMotion.straight, EntityMotion.homing)
            else listOf(EntityMotion.straight, EntityMotion.zigzag, EntityMotion.homing, EntityMotion.wave, EntityMotion.bounce)
            val motion = pick(rng, motions)
            val speed = 0.9 + rng.unit() * 0.6
            val rate = 0.45 + rng.unit() * 0.4
            val points = rng.int(2..5)
            val size = 0.9 + rng.unit() * 0.4
            entities.add(
                EntitySpec(sprite = sprite, role = EntityRole.avoid, power = null, motion = motion,
                    speed = speed, rate = rate, points = points, size = size),
            )
        }
        val power = pick(
            rng,
            if (control == GameControl.jump || control == GameControl.flap)
                listOf(PowerKind.shield, PowerKind.double, PowerKind.slow, PowerKind.life)
            else PowerKind.entries,
        )
        entities.add(
            EntitySpec(sprite = powerSprite(power), role = EntityRole.power, power = power, motion = EntityMotion.wave,
                speed = 0.8, rate = 0.12, points = 0, size = 1.0),
        )

        val adjective = pick(rng, adjectives(theme))
        val noun = pick(rng, nouns(control))
        val title = "$adjective $noun"
        val duration = if (goal == GameGoal.timed) (rng.int(4..8) * 10).toDouble() else 60.0
        val lives = if (goal == GameGoal.survive) 3 else (if (rng.unit() < 0.5) 3 else 0)
        val ramp = 0.08 + rng.unit() * 0.17
        var game = GameSpec(
            id = day, title = title, blurb = "", tutorial = emptyList(), control = control, goal = goal,
            duration = duration, lives = lives, theme = theme, entities = entities, ramp = ramp,
        )
        game.blurb = blurb(game, pack)
        game = game.sanitized()
        game.tutorial = tutorial(game)

        val quest = makeQuest(rng, game)
        val story = pick(rng, stories).replace("{game}", game.title)
        return DailyPlan(day = day, game = game, quest = quest, story = story, source = "local").sanitized()
    }

    // MARK: Words and sets

    private class Pack(val good: List<String>, val bad: List<String>, val flavor: String)

    private val packs = listOf(
        Pack(listOf("apple", "berry", "carrot", "cookie"), listOf("rotten", "cactus"), "snacks"),
        Pack(listOf("coin", "gem"), listOf("rock", "cactus"), "treasure"),
        Pack(listOf("bubbleItem", "starJelly"), listOf("rock", "puddle"), "bubbles"),
        Pack(listOf("flower", "seed"), listOf("mushroom", "rock"), "garden things"),
        Pack(listOf("noteItem", "heart"), listOf("rock", "rotten"), "music"),
        Pack(listOf("cake", "pudding", "honey"), listOf("cactus", "rotten"), "sweets"),
        Pack(listOf("chest", "coin"), listOf("puddle", "rock"), "loot"),
        Pack(listOf("ball", "starJelly"), listOf("cactus", "mushroom"), "toys"),
    )

    private fun powerSprite(p: PowerKind): String = when (p) {
        PowerKind.shield -> "bubbleItem"
        PowerKind.magnet -> "gem"
        PowerKind.slow -> "starJelly"
        PowerKind.double -> "coin"
        PowerKind.life -> "heart"
        PowerKind.shrink -> "mushroom"
    }

    private val stories = listOf(
        "i dreamed about {game} all night. let's play it.",
        "today smells like adventure. and a little like snacks.",
        "i counted the clouds this morning. there were many.",
        "new day, new game. i practiced my wobble for it.",
        "i feel lucky today. maybe we beat yesterday's score?",
        "a bird looked at me. i looked back. we're friends now.",
        "i made up a game called {game}. you'll like it.",
        "i'm full of bounce today. careful.",
        "the island is cozy today. i rearranged my pixels.",
        "i found a shiny thing. then i lost it. now we find more.",
    )

    private fun <T> pick(rng: SplitMix, list: List<T>): T = list[rng.int(0..list.size - 1)]

    private fun makeQuest(rng: SplitMix, game: GameSpec): DailyQuest = when (rng.int(0..4)) {
        0, 1 -> {
            val target = max(5, (game.goodScore.toDouble() * 0.8 / 5).toInt() * 5)
            DailyQuest(title = "Score $target in ${game.title}", goal = DailyQuest.Goal.dailyScore(target), coins = 25)
        }
        2 -> {
            val n = rng.int(2..4)
            DailyQuest(title = "Feed your slime $n times", goal = DailyQuest.Goal.stat(StatKey.feeds, n), coins = 15)
        }
        3 -> {
            val n = rng.int(6..10)
            DailyQuest(title = "Pet your slime $n times", goal = DailyQuest.Goal.stat(StatKey.pets, n), coins = 15)
        }
        else -> {
            val n = rng.int(2..4)
            DailyQuest(title = "Play $n rounds in the arcade", goal = DailyQuest.Goal.stat(StatKey.plays, n), coins = 20)
        }
    }

    private fun max(a: Int, b: Int): Int = if (a >= b) a else b

    private fun blurb(game: GameSpec, pack: Pack): String = when (game.control) {
        GameControl.drag -> "It's raining ${pack.flavor}. Catch the good stuff."
        GameControl.lanes -> "Three lanes, lots of ${pack.flavor}. Switch fast."
        GameControl.steer -> "Float around, grab ${pack.flavor}, dodge the rest."
        GameControl.jump -> "Run, jump and collect ${pack.flavor} on the way."
        GameControl.flap -> "Flap through the sky and collect ${pack.flavor}."
    }

    /** Plain rules from the blocks, so every game explains itself. */
    fun tutorial(game: GameSpec): List<String> {
        val lines = mutableListOf<String>()
        lines.add(
            when (game.control) {
                GameControl.drag -> "Drag left and right to move."
                GameControl.lanes -> "Tap left or right to switch lanes."
                GameControl.steer -> "Touch and drag; the slime floats after your finger."
                GameControl.jump -> "Tap to jump. Tap again in the air for a double jump."
                GameControl.flap -> "Tap to flap up. Don't touch the ground."
            },
        )
        val good = game.entities.filter { it.role == EntityRole.collect }.map { spriteName(it.sprite) }
        val bad = game.entities.filter { it.role == EntityRole.avoid }.map { spriteName(it.sprite) }
        var collect = "Collect ${list(good)}"
        if (bad.isNotEmpty()) collect += ", avoid ${list(bad)}"
        lines.add("$collect.")
        val power = game.entities.firstOrNull { it.role == EntityRole.power }?.power
        if (power != null) {
            lines.add(
                if (game.goal == GameGoal.survive) "Grab the glowing one for ${power.title.lowercase()}. Last as long as you can."
                else "Grab the glowing one for ${power.title.lowercase()}.",
            )
        } else if (game.goal == GameGoal.survive) {
            lines.add("Last as long as you can.")
        }
        return lines
    }

    fun spriteName(sprite: String): String = when (sprite) {
        "bubbleItem" -> "bubbles"
        "noteItem" -> "notes"
        "starJelly" -> "star jellies"
        "rotten" -> "rotten food"
        "cactus" -> "cacti"
        "berry" -> "strawberries"
        "chest" -> "chests"
        "puddle" -> "puddles"
        "honey" -> "honey"
        "pudding" -> "pudding"
        "soup" -> "soup"
        else -> sprite + "s"
    }

    private fun list(items: List<String>): String = when (items.size) {
        0 -> "everything"
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private fun adjectives(theme: GameTheme): List<String> = when (theme) {
        GameTheme.dawn -> listOf("Dawn", "Early", "Peachy")
        GameTheme.day -> listOf("Sunny", "Breezy", "Bright")
        GameTheme.dusk -> listOf("Dusk", "Golden", "Twilight")
        GameTheme.night -> listOf("Moonlight", "Midnight", "Sleepy")
        GameTheme.ocean -> listOf("Deep-Sea", "Splashy", "Coral")
        GameTheme.forest -> listOf("Mossy", "Leafy", "Forest")
        GameTheme.candy -> listOf("Candy", "Sugar", "Bubblegum")
        GameTheme.lava -> listOf("Lava", "Toasty", "Ember")
        GameTheme.space -> listOf("Cosmic", "Starry", "Orbit")
        GameTheme.snow -> listOf("Frosty", "Snowy", "Chilly")
    }

    private fun nouns(control: GameControl): List<String> = when (control) {
        GameControl.drag -> listOf("Munch", "Catch", "Snack Rain")
        GameControl.lanes -> listOf("Rush", "Dash", "Lane Run")
        GameControl.steer -> listOf("Drift", "Dodge", "Float")
        GameControl.jump -> listOf("Hop", "Run", "Bounce")
        GameControl.flap -> listOf("Flight", "Flutter", "Glide")
    }
}
