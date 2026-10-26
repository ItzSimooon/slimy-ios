import Foundation

/// When the slime says something.
enum LineCategory: String, CaseIterable {
    case morning, day, evening, night, reunion, longReunion
    case wishHunger, wishFun, wishEnergy, wishClean, wishLove
    case fed, fedFavorite, favoriteFound, stuffed, noCoins
    case played, highScore, lowScore, tooTired
    case bath, alreadyClean
    case pet, petAgain, petAsleep
    case sleep, notSleepy, wake, wakeTired, sleeping
    case levelUp, stageUp, achievement, bought, equipped
    case gift, giftReceived, friendHug, friendVisit, friendNew, inLove, missFriend
    case idle, idleClose, curious
    case dailyGift, giftWeek, streak, streakFresh
    case spooky, cozy, newYear, birthday
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
            ]),
            l(.wishFun, [
                ("i'm bored", [], 0, nil),
                ("play? please?", [.playful], 0, nil),
                ("we haven't played {game} in ages", [.playful], 0, nil),
                ("i'm staring at the wall. the wall stares back.", [.curious], 0, nil),
                ("one round of {game}?", [.playful], 0, nil),
            ]),
            l(.wishEnergy, [
                ("eyes. so. heavy.", [], 0, nil),
                ("i could sleep standing up. i don't have legs.", [.sleepy], 0, nil),
                ("bed?", [], 0, nil),
                ("i might nap right here", [.sleepy], 0, nil),
            ]),
            l(.wishClean, [
                ("i'm sticking to myself", [], 0, nil),
                ("something is stuck inside me. i think.", [.curious], 0, nil),
                ("a bath would be nice", [], 0, nil),
                ("bubbles? i love bubbles.", [], 0, nil),
            ]),
            l(.wishLove, [
                ("pet me a little?", [], 0, nil),
                ("i'm a bit lonely", [.cuddly], 0, nil),
                ("cuddle break?", [.cuddly], 0, nil),
                ("a quick pat would make my day", [], 0, nil),
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
            s(.inLove, [
                "i think i'm in love. with {friend}.",
                "{friend} and me. forever.",
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
                "{item}! for our home?",
                "a whole week of gifts. look at us.",
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
        var out: [LineCategory: [Line]] = [:]
        for (category, items) in table { out[category] = items }
        return out
    }()
}
