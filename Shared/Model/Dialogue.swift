import Foundation

/// When the slime says something.
enum LineCategory: String, CaseIterable {
    case morning, day, evening, night, reunion, longReunion
    case wishHunger, wishFun, wishEnergy, wishClean, wishLove
    case fed, fedFavorite, favoriteFound, stuffed, noCoins
    case played, highScore, lowScore, tooTired
    case bath, bathGrumpy, alreadyClean
    case pet, petAgain, petAsleep
    case sleep, notSleepy, wake, wakeTired, sleeping
    case levelUp, stageUp, achievement, bought, equipped
    case gift, giftReceived, friendHug, friendVisit, friendNew, missFriend
    case idle, idleClose, curious
    case dailyGift, giftWeek, streak, streakFresh
    case spooky, cozy, newYear, birthday
    // How it feels, for small talk.
    case feelJoy, feelSad, feelAngry, feelScared, feelLove, feelCurious, feelBored, feelExcited, feelSleepy
    // What just happened, for small talk.
    case afterFood, afterGame, afterFriend, afterCrate, partnerCared
    // Caring together and crates.
    case careStarted, careEnded, crateOpened, crateDuplicate, crateLegendary
    // The seasons, where it lives and the weather.
    case spring, summer, autumn, winter
    case roomTalk, gardenTalk, beachTalk, spaceTalk, crystalTalk
    case rainTalk, snowTalk, cloudTalk
}

struct Line {
    let id: String
    let text: String
    var traits: [Personality.Trait] = []
    var minBond = 0
    var hours: ClosedRange<Int>? = nil
}

/// Values filled into {placeholders}.
struct LineContext {
    var food = ""
    var favorite = ""
    var friend = ""
    var score = 0
    var game = ""
    var level = 0
    var days = 0
    var item = ""
    var streak = 0
    var years = 0
}

enum Dialogue {
    /// Picks a fitting line the slime hasn't said lately, weighted by
    /// personality, records it, and returns the text.
    static func say(_ category: LineCategory, in state: inout GameState, context: LineContext = .init(),
                    at now: Date = .now, calendar: Calendar = .current) -> String {
        let line = pick(category, state: state, at: now, calendar: calendar)
        state.memory.used(line: line.id, at: now)
        return fill(line.text, context: context, state: state)
    }

    static func pick(_ category: LineCategory, state: GameState, at now: Date, calendar: Calendar = .current) -> Line {
        let hour = calendar.component(.hour, from: now)
        let bank = lines[category] ?? [Line(id: "fallback", text: "blub.")]
        let fitting = bank.filter { line in
            Double(line.minBond) <= state.slime.bond && (line.hours?.contains(hour) ?? true)
        }
        let pool = fitting.isEmpty ? bank : fitting
        let fresh = pool.filter { !state.memory.recentLines.contains($0.id) }
        let options = fresh.isEmpty ? pool : fresh

        // Deterministic per moment, so tests are stable and a redraw doesn't reshuffle.
        var rng = SplitMix(seed: SplitMix.seed("\(category.rawValue)\(Int(now.timeIntervalSince1970 / 7))\(state.memory.recentLines.count)"))
        let weights = options.map { line in
            1 + 2 * line.traits.map { state.slime.personality[$0] }.reduce(0, +)
        }
        var roll = rng.unit() * weights.reduce(0, +)
        for (line, weight) in zip(options, weights) {
            roll -= weight
            if roll <= 0 { return line }
        }
        return options[options.count - 1]
    }

    static func fill(_ text: String, context: LineContext, state: GameState) -> String {
        var out = text
        let values: [String: String] = [
            "{name}": state.slime.name,
            "{owner}": state.player.name.isEmpty ? "friend" : state.player.name,
            "{food}": context.food,
            "{fav}": context.favorite.isEmpty ? state.slime.favorite.title.lowercased() : context.favorite,
            "{friend}": context.friend,
            "{score}": "\(context.score)",
            "{game}": context.game,
            "{level}": "\(context.level)",
            "{days}": "\(context.days)",
            "{item}": context.item,
            "{streak}": "\(context.streak)",
            "{years}": context.years == 1 ? "1 year" : "\(context.years) years",
        ]
        for (key, value) in values { out = out.replacingOccurrences(of: key, with: value) }
        return out
    }

    /// Small talk that fits the hour.
    static func timeOfDay(hour: Int) -> LineCategory {
        switch hour {
        case 5..<11: .morning
        case 11..<17: .day
        case 17..<22: .evening
        default: .night
        }
    }

    static func category(for need: Need) -> LineCategory {
        switch need {
        case .hunger: .wishHunger
        case .fun: .wishFun
        case .energy: .wishEnergy
        case .clean: .wishClean
        case .love: .wishLove
        }
    }

    /// Small talk for a feeling.
    static func category(for emotion: Emotion) -> LineCategory {
        switch emotion {
        case .joy: .feelJoy
        case .sadness: .feelSad
        case .anger: .feelAngry
        case .fear: .feelScared
        case .love: .feelLove
        case .curiosity: .feelCurious
        case .boredom: .feelBored
        case .excitement: .feelExcited
        case .sleepiness: .feelSleepy
        }
    }

    /// Small talk about where it lives.
    static func category(for habitat: Habitat) -> LineCategory {
        switch habitat {
        case .room: .roomTalk
        case .garden: .gardenTalk
        case .beach: .beachTalk
        case .space: .spaceTalk
        case .crystal: .crystalTalk
        }
    }

    /// A line of the category that fits the bond and the hour and isn't in
    /// `recent`, weighted by personality; lines said in the last half hour
    /// count less. Nil if every fitting line is recent.
    static func fresh(_ category: LineCategory, state: GameState, at now: Date, calendar: Calendar = .current,
                      avoiding recent: Set<String>, using rng: inout SplitMix) -> Line? {
        let hour = calendar.component(.hour, from: now)
        let options = (lines[category] ?? []).filter { line in
            !recent.contains(line.id) && Double(line.minBond) <= state.slime.bond && (line.hours?.contains(hour) ?? true)
        }
        guard !options.isEmpty else { return nil }
        let weights = options.map { line -> Double in
            let character = 1 + 2 * line.traits.map { state.slime.personality[$0] }.reduce(0, +)
            let saidAt = state.memory.lineUsedAt[line.id] ?? .distantPast
            let lately = now.timeIntervalSince(saidAt) < 30 * 60
            return character * (lately ? 0.3 : 1)
        }
        var roll = rng.unit() * weights.reduce(0, +)
        for (line, weight) in zip(options, weights) {
            roll -= weight
            if roll < 0 { return line }
        }
        return options[options.count - 1]
    }

    // MARK: - Lines

    static let lines: [LineCategory: [Line]] = {
        func l(_ c: LineCategory, _ items: [(String, [Personality.Trait], Int, ClosedRange<Int>?)]) -> (LineCategory, [Line]) {
            (c, items.enumerated().map { i, item in
                Line(id: "\(c.rawValue).\(i)", text: item.0, traits: item.1, minBond: item.2, hours: item.3)
            })
        }
        func s(_ c: LineCategory, _ texts: [String]) -> (LineCategory, [Line]) {
            l(c, texts.map { ($0, [], 0, nil) })
        }
        let table: [(LineCategory, [Line])] = [
            l(.morning, [
                ("morning.", [], 0, nil),
                ("good morning. i'm awake. almost.", [.sleepy], 0, nil),
                ("morning! what are we doing today?", [.playful, .curious], 0, nil),
                ("breakfast would be nice", [.foodie], 0, nil),
                ("morning, {owner}. glad you're here.", [.cuddly], 30, nil),
                ("the sun is up. so am i. mostly.", [.sleepy], 0, nil),
                ("i dreamed we went to the beach", [.curious], 0, nil),
                ("morning stretch. boing.", [.playful], 0, nil),
                ("breakfast idea: {fav}", [.foodie], 0, nil),
            ]),
            l(.day, [
                ("hello.", [], 0, nil),
                ("i wobbled a little while you were gone", [], 0, nil),
                ("there you are", [.cuddly], 10, nil),
                ("i've been thinking. about {fav}.", [.foodie], 0, nil),
                ("i studied a speck of dust. fascinating.", [.curious], 0, nil),
                ("wanna play something?", [.playful], 0, nil),
                ("busy day? i'll keep the wobble warm.", [.cuddly], 0, nil),
                ("i counted the floorboards. there are a lot.", [.curious], 0, nil),
                ("midday bounce check: very bouncy", [.playful], 0, nil),
                ("is it snack o'clock?", [.foodie], 0, nil),
            ]),
            l(.evening, [
                ("evening. cozy.", [], 0, nil),
                ("today was good, right?", [.cuddly], 20, nil),
                ("i'm getting sleepy", [.sleepy], 0, nil),
                ("one more game before bed?", [.playful], 0, nil),
                ("the light is all orange. pretty.", [], 0, nil),
                ("let's take it slow tonight", [.sleepy], 0, nil),
                ("tell me about your day?", [.curious], 10, nil),
                ("evening snack? just asking.", [.foodie], 0, nil),
            ]),
            l(.night, [
                ("psst. it's night.", [], 0, nil),
                ("you're still up too?", [.curious], 0, nil),
                ("counting slime sheep", [.sleepy], 0, nil),
                ("the moon is wobbling too", [], 0, nil),
                ("shh. the room is sleeping.", [.sleepy], 0, nil),
                ("night thought: you're nice", [.cuddly], 30, nil),
                ("i can see one star from here", [.curious], 0, nil),
            ]),
            l(.reunion, [
                ("you're back", [], 0, nil),
                ("hi! i waited for you. a little.", [.cuddly], 0, nil),
                ("finally. i have so much to tell you. not really.", [.curious], 0, nil),
                ("good to see you", [], 20, nil),
                ("hi! i kept everything wobbly for you", [], 0, nil),
                ("oh good, it's you", [.cuddly], 10, nil),
            ]),
            l(.longReunion, [
                ("{days} days! i missed you. are you okay?", [], 0, nil),
                ("there you are. i spent the time wobbling.", [], 0, nil),
                ("welcome back. i saved you a spot.", [.cuddly], 0, nil),
            ]),
            l(.wishHunger, [
                ("hungry. very hungry.", [], 0, nil),
                ("my tummy is making noises", [], 0, nil),
                ("all i can think about is {fav}", [.foodie], 0, nil),
                ("an apple would be nice right now", [], 0, nil),
                ("a little snack would be perfect", [], 0, nil),
                ("snack time soon?", [], 0, nil),
                ("i could eat a whole {fav}", [.foodie], 0, nil),
                ("my tummy says hello", [], 0, nil),
            ]),
            l(.wishFun, [
                ("i'm bored", [], 0, nil),
                ("play? please?", [.playful], 0, nil),
                ("i keep thinking about {game}", [.playful], 0, nil),
                ("i'm staring at the wall. the wall stares back.", [.curious], 0, nil),
                ("one round of {game}?", [.playful], 0, nil),
                ("a game would be fun", [], 0, nil),
                ("let's play something together?", [.playful], 0, nil),
                ("i've got bounce to spare", [.playful], 0, nil),
            ]),
            l(.wishEnergy, [
                ("eyes. so. heavy.", [], 0, nil),
                ("i could sleep standing up. i don't have legs.", [.sleepy], 0, nil),
                ("bed?", [], 0, nil),
                ("i might nap right here", [.sleepy], 0, nil),
                ("a nap sounds lovely", [], 0, nil),
                ("sleepy goo, sleepy me", [.sleepy], 0, nil),
                ("my wobble is getting slow", [], 0, nil),
            ]),
            l(.wishClean, [
                ("i'm sticking to myself", [], 0, nil),
                ("something is stuck inside me. i think.", [.curious], 0, nil),
                ("a bath would be nice", [], 0, nil),
                ("bubbles? i love bubbles.", [], 0, nil),
                ("a little splash would be nice", [], 0, nil),
                ("i'm a bit dusty", [], 0, nil),
                ("bath time soon? just a thought.", [], 0, nil),
            ]),
            l(.wishLove, [
                ("pet me a little?", [], 0, nil),
                ("a little company would be nice", [.cuddly], 0, nil),
                ("cuddle break?", [.cuddly], 0, nil),
                ("a quick pat would make my day", [], 0, nil),
                ("a pat would be nice", [], 0, nil),
                ("come say hi?", [.cuddly], 0, nil),
                ("i saved you a cuddle", [.cuddly], 0, nil),
            ]),
            l(.fed, [
                ("nom. {food}.", [], 0, nil),
                ("{food}. good.", [], 0, nil),
                ("thanks. that was tasty.", [], 0, nil),
                ("*chews very loudly*", [.foodie], 0, nil),
                ("{food}! tastes like more.", [.foodie], 0, nil),
            ]),
            s(.fedFavorite, [
                "{food}! MY FAVOURITE",
                "you just know what i like",
                "best {food} in the world",
            ]),
            s(.favoriteFound, [
                "{food}! that's it. my favourite food.",
            ]),
            s(.stuffed, [
                "i'm about to burst",
                "no room left. really.",
                "later. i'm full.",
            ]),
            s(.noCoins, [
                "not enough coins",
                "we can't afford that. playing helps.",
            ]),
            l(.played, [
                ("wheee. {score} points.", [], 0, nil),
                ("that was fun", [], 0, nil),
                ("again?", [.playful], 0, nil),
                ("{score}. not bad.", [], 0, nil),
            ]),
            s(.highScore, [
                "new record! {score}!",
                "{score}! i've never been this good",
                "a record. i'm a little proud.",
            ]),
            s(.lowScore, [
                "next time for sure",
                "practice, practice",
                "it was fun anyway",
            ]),
            s(.tooTired, [
                "too tired to play",
                "sleep first, then play",
            ]),
            s(.bath, [
                "squeaky clean",
                "blub. clean.",
                "i'm shiny",
            ]),
            s(.bathGrumpy, [
                "brr. wet. i'll forgive you.",
                "baths. not my favourite. but i'm shiny.",
                "hmph. clean. fine.",
                "i'm soggy and a bit grumpy",
                "okay, the bubbles were nice. a little.",
            ]),
            s(.alreadyClean, [
                "i'm already clean",
                "can't get any cleaner",
            ]),
            l(.pet, [
                ("hehe. tickles.", [], 0, nil),
                ("more of that", [.cuddly], 0, nil),
                ("*purrs. i think.*", [.cuddly], 20, nil),
                ("love you, {owner}", [], 60, nil),
                ("that's nice", [], 0, nil),
            ]),
            s(.petAgain, [
                "okay okay, you like me. got it.",
                "i'm all flat from petting",
            ]),
            s(.petAsleep, [
                "*snores happily*",
                "*smiles in its sleep*",
            ]),
            s(.sleep, [
                "good night",
                "sleep well. me too.",
                "lights out",
            ]),
            s(.notSleepy, [
                "i'm not tired yet",
                "sleep? now? no.",
            ]),
            s(.wake, [
                "good morning",
                "well rested",
                "hello world",
            ]),
            s(.wakeTired, [
                "five more minutes …",
                "who woke me up",
            ]),
            s(.sleeping, [
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
            ]),
            s(.levelUp, [
                "level {level}!",
                "level {level}. i'm getting big.",
            ]),
            s(.stageUp, [
                "i grew. look at me.",
                "i feel different. bigger.",
            ]),
            s(.achievement, [
                "achievement! {item}",
                "we did it: {item}",
            ]),
            s(.bought, [
                "{item}! thank you.",
                "ooh, {item}",
            ]),
            s(.equipped, [
                "suits me, right?",
                "i look good",
                "fancy.",
            ]),
            s(.gift, [
                "a gift for {friend} is on its way",
                "{friend} will love it",
            ]),
            s(.giftReceived, [
                "{friend} sent us something!",
                "mail from {friend}",
            ]),
            s(.friendHug, [
                "*squishes {friend}*",
                "hug with {friend}. soft.",
            ]),
            s(.friendVisit, [
                "visiting {friend}",
                "{friend} has a lovely home",
            ]),
            s(.friendNew, [
                "new friend: {friend}",
                "{friend}! hi!",
            ]),
            s(.missFriend, [
                "i miss {friend}",
                "{friend} hasn't been around in a while",
            ]),
            l(.idle, [
                ("all good here", [], 0, nil),
                ("blub.", [], 0, nil),
                ("just wobbling along", [], 0, nil),
                ("what are you up to?", [.curious], 0, nil),
                ("i saw a fly. it was fast.", [.curious], 0, nil),
                ("not hungry. but {fav} always works.", [.foodie], 0, nil),
                ("practicing my bounce", [.playful], 0, nil),
                ("yawn", [.sleepy], 0, nil),
                ("i'm here if you need me", [.cuddly], 10, nil),
                ("the sun is nice today", [], 0, 9...18),
                ("it's dark outside", [], 0, 19...23),
                ("i'm practicing being round", [], 0, nil),
                ("wobble. wobble. pause. wobble.", [.playful], 0, nil),
                ("do you think clouds are slimes?", [.curious], 0, nil),
                ("i saved you a warm spot", [.cuddly], 20, nil),
                ("thinking about {fav}. again.", [.foodie], 0, nil),
                ("tiny nap later? tiny nap later.", [.sleepy], 0, nil),
                ("i'm thinking about nothing. it's relaxing.", [], 0, nil),
                ("a little wobble for no reason", [], 0, nil),
                ("i like the sound of the room", [], 0, nil),
                ("doing my slime things", [], 0, nil),
                ("i practiced a new face. it's this one.", [.playful], 0, nil),
                ("blub blub", [], 0, nil),
                ("just here. being round.", [], 0, nil),
                ("small thought: you're nice", [.cuddly], 20, nil),
            ]),
            l(.idleClose, [
                ("i'm glad you exist", [], 50, nil),
                ("you're my favourite human", [.cuddly], 70, nil),
                ("remember our first day?", [], 50, nil),
                ("we make a good team", [], 50, nil),
                ("i like it when you're around", [.cuddly], 60, nil),
            ]),
            s(.curious, [
                "what's that?",
                "hm?",
                "let me have a look",
            ]),
            // The daily gift and the streak. Never a word about missed days.
            s(.dailyGift, [
                "a gift! for me? for us.",
                "ooh. it rattles.",
                "unwrapping. carefully. very carefully.",
                "coins! we're rich. a little.",
                "i love surprises",
                "same time tomorrow?",
            ]),
            s(.giftWeek, [
                "day seven. the big one!",
                "a {item}! what's inside?",
                "a whole week of gifts. look at us.",
                "it rattles. it's a {item}!",
            ]),
            l(.streak, [
                ("{streak} days in a row. we're a team.", [], 0, nil),
                ("day {streak}. i've been counting.", [.curious], 0, nil),
                ("you keep coming back. i like that a lot.", [.cuddly], 0, nil),
                ("{streak} days! that's a habit now.", [], 0, nil),
                ("streak buddies", [.playful], 0, nil),
                ("every day with you is a good day", [.cuddly], 20, nil),
                ("{streak} days. my wobble is getting stronger.", [.playful], 0, nil),
            ]),
            s(.streakFresh, [
                "day one. my favourite kind of day.",
                "fresh start. hi!",
                "hi again. so good to see you.",
                "new streak, same slime",
            ]),
            // The seasons.
            l(.spooky, [
                ("boo. did i scare you?", [.playful], 0, nil),
                ("i carved a pumpkin. it looks like me.", [], 0, nil),
                ("the bats are friendly. mostly.", [.curious], 0, nil),
                ("i'm dressing up as a ghost. a green one.", [.playful], 0, nil),
                ("spooky season. i'm only a little scared.", [.cuddly], 0, nil),
                ("something went bump. it was me.", [], 0, nil),
                ("trick or treat? i pick treat.", [.foodie], 0, nil),
                ("the pumpkins are smiling at me", [], 0, nil),
            ]),
            l(.cozy, [
                ("snow! i want to be a snowslime.", [.playful], 0, nil),
                ("it's so cozy in here", [], 0, nil),
                ("the lights keep blinking at me", [.curious], 0, nil),
                ("hot cocoa weather", [.foodie], 0, nil),
                ("i wrapped myself in a blanket. i'm a gift.", [.cuddly], 0, nil),
                ("the tree smells like a forest", [], 0, nil),
                ("warm goo, cold window", [.sleepy], 0, nil),
                ("let's stay in today", [.sleepy], 0, nil),
            ]),
            s(.newYear, [
                "happy new year!",
                "new year. same goo. better goo.",
                "the sky is going pop pop pop",
                "my resolution: more wobbling",
                "did you see the fireworks?",
            ]),
            s(.birthday, [
                "it's my birthday! {years} old today.",
                "{years} of wobbling with you",
                "cake? for me? for me!",
                "best birthday ever",
                "make a wish. i made one too.",
                "thank you for hatching me",
            ]),
        ]
        // How it feels.
        let feelings: [(LineCategory, [Line])] = [
            l(.feelJoy, [
                ("i feel all bouncy inside", [], 0, nil),
                ("today is a good wobble day", [], 0, nil),
                ("everything is nice. i checked.", [], 0, nil),
                ("happy goo, happy me", [], 0, nil),
                ("i might hum. slimes can hum.", [], 0, nil),
                ("i'm so glad i'm me", [], 0, nil),
                ("look at me. very content.", [], 0, nil),
                ("my jelly is doing a little dance", [.playful], 0, nil),
                ("good vibes only. and snacks.", [.foodie], 0, nil),
                ("i like it here. with you.", [.cuddly], 0, nil),
                ("wheee. no reason. just wheee.", [.playful], 0, nil),
                ("i'm happy and i know it", [], 0, nil),
            ]),
            l(.feelSad, [
                ("a bit droopy today", [], 0, nil),
                ("i'm a little blue. not the colour.", [], 0, nil),
                ("feeling soft and squishy. the sad kind.", [], 0, nil),
                ("can i sit here with you for a bit?", [.cuddly], 0, nil),
                ("small rain cloud in my goo", [], 0, nil),
                ("i'll be okay. i'm just a puddle right now.", [], 0, nil),
                ("some days are wobbly", [], 0, nil),
                ("a hug would help. maybe.", [.cuddly], 0, nil),
                ("i'm quiet today. that's okay.", [], 0, nil),
                ("my bounce is resting", [], 0, nil),
                ("sad slime, soft heart", [], 0, nil),
                ("i think i need a cozy minute", [.sleepy], 0, nil),
            ]),
            l(.feelAngry, [
                ("hmph.", [], 0, nil),
                ("i'm grumpy. a tiny bit. okay, medium.", [], 0, nil),
                ("my goo is all bristly", [], 0, nil),
                ("don't poke the jelly. well. maybe softly.", [], 0, nil),
                ("i'm cross. i forgot why.", [.curious], 0, nil),
                ("grr. that's my scary noise.", [.playful], 0, nil),
                ("i'm stomping. you can't tell, no feet.", [], 0, nil),
                ("grumpy mode. it'll pass.", [], 0, nil),
                ("grumpy tummy. a snack would fix it.", [.foodie], 0, nil),
                ("huff. puff. okay, better.", [], 0, nil),
            ]),
            l(.feelScared, [
                ("eep.", [], 0, nil),
                ("did you hear that?", [.curious], 0, nil),
                ("i'm hiding. you didn't see me.", [], 0, nil),
                ("everything's a bit big right now", [], 0, nil),
                ("stay close for a moment?", [.cuddly], 0, nil),
                ("my goo went all wobbly. the scared kind.", [], 0, nil),
                ("it's fine. it's fine. is it fine?", [], 0, nil),
                ("brave slime. brave slime. eep.", [], 0, nil),
                ("i'll just be very small for a bit", [], 0, nil),
                ("you're here. that helps.", [.cuddly], 0, nil),
            ]),
            l(.feelLove, [
                ("i like you a whole lot", [], 0, nil),
                ("you're my favourite person", [.cuddly], 20, nil),
                ("my goo goes warm when you're here", [], 0, nil),
                ("can we stay like this?", [.cuddly], 0, nil),
                ("i saved all my best wobbles for you", [.playful], 0, nil),
                ("heart full. goo full.", [.foodie], 0, nil),
                ("you make the room nicer", [], 0, nil),
                ("i'm so lucky you found my egg", [], 0, nil),
                ("if i had arms i'd hug you", [.cuddly], 0, nil),
                ("you, me, this. perfect.", [], 0, nil),
                ("i love our little life", [], 30, nil),
                ("glad it's you, {owner}", [], 10, nil),
            ]),
            l(.feelCurious, [
                ("what's that over there?", [], 0, nil),
                ("i have a question. i forgot it.", [], 0, nil),
                ("how does the sun stay up?", [], 0, nil),
                ("i wonder what's behind the wall", [], 0, nil),
                ("can slimes learn to whistle?", [.playful], 0, nil),
                ("if i stretch really far, where do i end?", [], 0, nil),
                ("i'm investigating. very serious.", [], 0, nil),
                ("is the moon made of jelly too?", [.foodie], 0, nil),
                ("what do you do all day out there?", [.cuddly], 0, nil),
                ("i found a crumb. studying it.", [.foodie], 0, nil),
                ("why is water wet? asking for me.", [], 0, nil),
                ("what does purple taste like?", [.foodie], 0, nil),
            ]),
            l(.feelBored, [
                ("hmm. hmm hmm.", [], 0, nil),
                ("i counted my wobbles. 312.", [], 0, nil),
                ("i'm looking for something to do", [], 0, nil),
                ("game? maybe? if you feel like it.", [.playful], 0, nil),
                ("i invented a new game. it's called waiting.", [], 0, nil),
                ("tap tap tap. that's me drumming.", [.playful], 0, nil),
                ("the ceiling has 4 spots. i named them.", [.curious], 0, nil),
                ("i could go for a little adventure", [.curious], 0, nil),
                ("bored goo. needs a bounce.", [], 0, nil),
                ("let's do something silly", [.playful], 0, nil),
            ]),
            l(.feelExcited, [
                ("something good is happening. i can feel it.", [], 0, nil),
                ("i can't sit still. i'm vibrating.", [], 0, nil),
                ("best. day. ever. maybe.", [], 0, nil),
                ("wheeee! again! again!", [.playful], 0, nil),
                ("my goo is fizzing", [], 0, nil),
                ("boing boing boing", [.playful], 0, nil),
                ("i have so much energy right now", [], 0, nil),
                ("let's do everything!", [.curious], 0, nil),
                ("that was amazing. was it amazing? it was.", [], 0, nil),
                ("i'm a firework. a squishy one.", [], 0, nil),
                ("zoom. zoom. zoom.", [.playful], 0, nil),
                ("can we play? can we? can we?", [.playful], 0, nil),
            ]),
            l(.feelSleepy, [
                ("yaaawn", [], 0, nil),
                ("my eyes are doing the slow blink", [], 0, nil),
                ("just resting my goo for a second", [], 0, nil),
                ("sleepy. so sleepy. very cozy.", [.sleepy], 0, nil),
                ("i could nap on a cloud", [.sleepy], 0, nil),
                ("my bounce went to bed already", [], 0, nil),
                ("five minutes of nap would fix me", [.sleepy], 0, nil),
                ("mm. warm. drowsy.", [], 0, nil),
                ("if i'm quiet, i'm dozing", [], 0, nil),
                ("is it bedtime? it feels like bedtime.", [], 0, nil),
            ]),
        ]
        // What just happened, caring together, crates.
        let moments: [(LineCategory, [Line])] = [
            l(.afterFood, [
                ("still thinking about that {food}", [.foodie], 0, nil),
                ("tummy happy", [], 0, nil),
                ("that hit the spot", [], 0, nil),
                ("{food}. ten out of ten.", [.foodie], 0, nil),
                ("my goo is digesting. very busy.", [], 0, nil),
                ("i can still taste it. mmm.", [.foodie], 0, nil),
                ("full and round. rounder than usual.", [], 0, nil),
                ("thanks for the snack", [.cuddly], 0, nil),
            ]),
            l(.afterGame, [
                ("that round of {game} was fun", [], 0, nil),
                ("my bounce is still going", [.playful], 0, nil),
                ("i'm practicing {game} in my head", [.curious], 0, nil),
                ("rematch later?", [.playful], 0, nil),
                ("we're getting good at {game}", [], 0, nil),
                ("phew. good game.", [], 0, nil),
                ("i liked that one", [], 0, nil),
                ("i'm still a bit out of breath", [.sleepy], 0, nil),
            ]),
            l(.afterFriend, [
                ("i liked seeing {friend}", [], 0, nil),
                ("{friend} is really nice", [.cuddly], 0, nil),
                ("we should see {friend} again soon", [], 0, nil),
                ("friends make the day wobblier", [.playful], 0, nil),
                ("i'm telling everyone about {friend}", [], 0, nil),
                ("{friend} said hi. i said hi back.", [], 0, nil),
                ("my friend list is my treasure", [], 0, nil),
                ("still smiling about {friend}", [], 0, nil),
            ]),
            l(.afterCrate, [
                ("still thinking about that crate", [], 0, nil),
                ("what if the next crate has a surprise?", [.curious], 0, nil),
                ("{item}! i keep looking at it.", [], 0, nil),
                ("unboxing is my new favourite sport", [.playful], 0, nil),
                ("crates are like gifts that rattle", [], 0, nil),
                ("that crate was a good one", [], 0, nil),
                ("i love the sound a crate makes", [], 0, nil),
                ("i'd like to live in a crate. a small one.", [.curious], 0, nil),
            ]),
            l(.partnerCared, [
                ("{friend} looked after me. so nice.", [], 0, nil),
                ("{friend} was here! sort of.", [.playful], 0, nil),
                ("two people care for me. i'm famous.", [], 0, nil),
                ("{friend} says hi. i think.", [], 0, nil),
                ("i got some care from {friend}", [.cuddly], 0, nil),
                ("lucky me. two of you.", [], 0, nil),
                ("{friend} and you make a good team", [], 0, nil),
                ("{friend} was just here. i'm all warm.", [.cuddly], 0, nil),
                ("double the care. double the wobble.", [.playful], 0, nil),
                ("tell {friend} i said thanks", [], 0, nil),
            ]),
            s(.careStarted, [
                "{friend} is looking after me too now",
                "two of you? best slime life.",
                "hi, {friend}! we're a team now.",
                "one slime, two friends. perfect.",
                "a new caretaker. i'll be extra wobbly.",
            ]),
            s(.careEnded, [
                "just us again. that's nice too.",
                "{friend} was lovely. bye for now.",
                "back home. i missed this spot.",
                "hello again. i kept everything wobbly.",
            ]),
            s(.crateOpened, [
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
            ]),
            s(.crateDuplicate, [
                "we have that already. coins it is.",
                "a twin! turned into coins.",
                "already ours. shiny coins instead.",
                "coins! almost as good.",
                "a double. so we get coins.",
            ]),
            s(.crateLegendary, [
                "what. what. look at this!",
                "i'm... i'm an axolotl?!",
                "something legendary. i'm shaking.",
                "frilly gills! i have frilly gills!",
                "the rarest thing. and it's ours.",
            ]),
        ]
        // The seasons and where it lives.
        let world: [(LineCategory, [Line])] = [
            l(.spring, [
                ("the flowers are waking up", [], 0, nil),
                ("spring! everything's green like me.", [], 0, nil),
                ("i smell rain and blossoms", [.curious], 0, nil),
                ("the birds are back. loud ones.", [], 0, nil),
                ("spring cleaning? i'll just wobble.", [.sleepy], 0, nil),
            ]),
            l(.summer, [
                ("it's warm. i'm a bit melty.", [], 0, nil),
                ("summer! ice cream weather.", [.foodie], 0, nil),
                ("the days are so long. more wobble time.", [.playful], 0, nil),
                ("sunny goo, happy goo", [], 0, nil),
                ("let's find some shade", [.sleepy], 0, nil),
            ]),
            l(.autumn, [
                ("the leaves are turning orange", [], 0, nil),
                ("crunchy leaf season", [.playful], 0, nil),
                ("sweater weather. i'm the sweater.", [.cuddly], 0, nil),
                ("the wind is doing loops", [.curious], 0, nil),
                ("cozy autumn wobble", [.sleepy], 0, nil),
            ]),
            l(.winter, [
                ("chilly. i'm staying jiggly.", [], 0, nil),
                ("winter. perfect for blankets.", [.sleepy], 0, nil),
                ("my goo is a bit cold", [.cuddly], 0, nil),
                ("frosty window, warm slime", [], 0, nil),
                ("short days, long naps", [.sleepy], 0, nil),
            ]),
            l(.roomTalk, [
                ("home sweet home", [], 0, nil),
                ("this room is just my size", [], 0, nil),
                ("i rearranged the dust", [.curious], 0, nil),
                ("my corner is the best corner", [], 0, nil),
                ("i like our little room", [.cuddly], 0, nil),
            ]),
            l(.gardenTalk, [
                ("the grass tickles", [], 0, nil),
                ("a bee waved at me. i think.", [.curious], 0, nil),
                ("i'm basically a garden slime now", [], 0, nil),
                ("the flowers smell nice", [], 0, nil),
                ("if it rains, i'll grow", [.playful], 0, nil),
                ("i heard the wind in the trees", [.sleepy], 0, nil),
            ]),
            l(.beachTalk, [
                ("sand gets everywhere. everywhere.", [], 0, nil),
                ("the waves keep saying hi", [], 0, nil),
                ("i'm a beach blob now", [.playful], 0, nil),
                ("salty air. tasty air.", [.foodie], 0, nil),
                ("i found a shell. it's mine now.", [.curious], 0, nil),
                ("the sea is so big", [], 0, nil),
            ]),
            l(.spaceTalk, [
                ("no gravity. i'm floating.", [.playful], 0, nil),
                ("hi, stars", [], 0, nil),
                ("space is very quiet", [.sleepy], 0, nil),
                ("i can see the whole world from here", [.curious], 0, nil),
                ("is that a comet? make a wish.", [], 0, nil),
                ("astro-slime reporting in", [], 0, nil),
            ]),
            l(.crystalTalk, [
                ("the crystals hum a little", [], 0, nil),
                ("everything sparkles in here", [], 0, nil),
                ("i can see a hundred of me", [.curious], 0, nil),
                ("glowy cave, glowy goo", [], 0, nil),
                ("the crystals are warm. who knew.", [], 0, nil),
            ]),
            // Weather: said outside and at the room's window alike.
            l(.rainTalk, [
                ("pitter patter. it's raining.", [], 0, nil),
                ("listen. rain.", [], 0, nil),
                ("rain! my favourite sound.", [], 0, nil),
                ("rainy day. good for naps.", [.sleepy], 0, nil),
                ("i want to count the raindrops", [.curious], 0, nil),
                ("rain makes me extra squishy", [.playful], 0, nil),
                ("rainy days are for staying close", [.cuddly], 0, nil),
                ("soup weather", [.foodie], 0, nil),
            ]),
            l(.snowTalk, [
                ("snow! everything's so quiet.", [], 0, nil),
                ("look, it's snowing", [], 0, nil),
                ("no two snowflakes alike. like slimes.", [.curious], 0, nil),
                ("i want to catch a snowflake", [.playful], 0, nil),
                ("snowy day. stay cozy with me?", [.cuddly], 0, nil),
                ("snow day nap time", [.sleepy], 0, nil),
                ("the snow looks like sugar", [.foodie], 0, nil),
            ]),
            l(.cloudTalk, [
                ("lots of clouds up there", [], 0, nil),
                ("that cloud looks like a slime", [.curious], 0, nil),
                ("grey sky, cozy goo", [.sleepy], 0, nil),
                ("clouds are just sky slimes", [.playful], 0, nil),
                ("the sun is hiding. i'll be your sun.", [.cuddly], 0, 7...18),
                ("that cloud looks like a pancake", [.foodie], 0, nil),
            ]),
        ]
        var out: [LineCategory: [Line]] = [:]
        for (category, items) in table + feelings + moments + world { out[category] = items }
        return out
    }()
}
