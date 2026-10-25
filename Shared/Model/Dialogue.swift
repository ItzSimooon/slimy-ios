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
            "{owner}": state.player.name.isEmpty ? "du" : state.player.name,
            "{food}": context.food,
            "{fav}": context.favorite.isEmpty ? state.slime.favorite.title.lowercased() : context.favorite,
            "{friend}": context.friend,
            "{score}": "\(context.score)",
            "{game}": context.game,
            "{level}": "\(context.level)",
            "{days}": "\(context.days)",
            "{item}": context.item,
        ]
        for (key, value) in values { out = out.replacingOccurrences(of: key, with: value) }
        return out
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
                ("morgen.", [], 0, nil),
                ("guten morgen. ich bin wach. fast.", [.sleepy], 0, nil),
                ("morgen! was machen wir heute?", [.playful, .curious], 0, nil),
                ("frühstück wäre schön", [.foodie], 0, nil),
                ("morgen, {owner}. schön, dass du da bist.", [.cuddly], 30, nil),
            ]),
            l(.day, [
                ("hallo.", [], 0, nil),
                ("ich hab ein bisschen gewackelt, während du weg warst", [], 0, nil),
                ("da bist du ja", [.cuddly], 10, nil),
                ("ich hab nachgedacht. über {fav}.", [.foodie], 0, nil),
                ("ich hab einen staubkrümel untersucht. spannend.", [.curious], 0, nil),
                ("spielen wir was?", [.playful], 0, nil),
            ]),
            l(.evening, [
                ("abend. gemütlich.", [], 0, nil),
                ("der tag war gut, oder?", [.cuddly], 20, nil),
                ("ich werd langsam müde", [.sleepy], 0, nil),
                ("noch ein spiel vorm schlafen?", [.playful], 0, nil),
            ]),
            l(.night, [
                ("psst. es ist nacht.", [], 0, nil),
                ("bist du auch noch wach?", [.curious], 0, nil),
                ("ich zähl schleimschafe", [.sleepy], 0, nil),
            ]),
            l(.reunion, [
                ("du bist wieder da", [], 0, nil),
                ("hallo! ich hab auf dich gewartet. ein bisschen.", [.cuddly], 0, nil),
                ("na endlich. ich hab so viel zu erzählen. eigentlich nicht.", [.curious], 0, nil),
                ("schön, dich zu sehen", [], 20, nil),
            ]),
            l(.longReunion, [
                ("{days} tage! ich hab dich vermisst. alles gut bei dir?", [], 0, nil),
                ("da bist du. ich hab die zeit mit wackeln verbracht.", [], 0, nil),
                ("willkommen zurück. ich hab dir einen platz freigehalten.", [.cuddly], 0, nil),
            ]),
            l(.wishHunger, [
                ("hunger. großer hunger.", [], 0, nil),
                ("mein bauch macht geräusche", [], 0, nil),
                ("ich denk nur noch an {fav}", [.foodie], 0, nil),
                ("ein apfel wär jetzt was", [], 0, nil),
            ]),
            l(.wishFun, [
                ("mir ist langweilig", [], 0, nil),
                ("spielen? bitte?", [.playful], 0, nil),
                ("ich hab {game} schon ewig nicht gespielt", [.playful], 0, nil),
                ("ich starr die wand an. die wand starrt zurück.", [.curious], 0, nil),
            ]),
            l(.wishEnergy, [
                ("augen. so. schwer.", [], 0, nil),
                ("ich könnte im stehen schlafen. ich hab keine beine.", [.sleepy], 0, nil),
                ("bett?", [], 0, nil),
            ]),
            l(.wishClean, [
                ("ich kleb an mir selbst", [], 0, nil),
                ("da ist was in mir drin. glaub ich.", [.curious], 0, nil),
                ("ein bad wär schön", [], 0, nil),
            ]),
            l(.wishLove, [
                ("kraul mich mal", [], 0, nil),
                ("ich bin ein bisschen allein", [.cuddly], 0, nil),
                ("streichel-pause?", [.cuddly], 0, nil),
            ]),
            l(.fed, [
                ("mampf. {food}.", [], 0, nil),
                ("{food}. gut.", [], 0, nil),
                ("danke. war lecker.", [], 0, nil),
                ("*kaut sehr laut*", [.foodie], 0, nil),
                ("{food}! schmeckt nach mehr.", [.foodie], 0, nil),
            ]),
            s(.fedFavorite, [
                "{food}! MEIN LIEBLINGSESSEN",
                "du weißt einfach, was ich mag",
                "das ist das beste {food} der welt",
            ]),
            s(.favoriteFound, [
                "{food}! das ist es. mein lieblingsessen.",
            ]),
            s(.stuffed, [
                "ich platze gleich",
                "kein platz mehr. wirklich.",
                "später. ich bin voll.",
            ]),
            s(.noCoins, [
                "zu wenig münzen",
                "dafür reichen die münzen nicht. spielen hilft.",
            ]),
            l(.played, [
                ("wiii. {score} punkte.", [], 0, nil),
                ("das war gut", [], 0, nil),
                ("nochmal?", [.playful], 0, nil),
                ("{score}. nicht schlecht.", [], 0, nil),
            ]),
            s(.highScore, [
                "neuer rekord! {score}!",
                "{score}! so gut war ich noch nie",
                "rekord. ich bin ein bisschen stolz.",
            ]),
            s(.lowScore, [
                "nächstes mal klappts",
                "üben, üben",
                "war trotzdem lustig",
            ]),
            s(.tooTired, [
                "zu müde zum spielen",
                "erst schlafen, dann spielen",
            ]),
            s(.bath, [
                "frisch gewaschen",
                "blubb. sauber.",
                "ich glänze",
            ]),
            s(.alreadyClean, [
                "bin schon sauber",
                "noch sauberer geht nicht",
            ]),
            l(.pet, [
                ("hihi. kitzelt.", [], 0, nil),
                ("mehr davon", [.cuddly], 0, nil),
                ("*schnurrt. glaub ich.*", [.cuddly], 20, nil),
                ("hab dich lieb, {owner}", [], 60, nil),
                ("das ist schön", [], 0, nil),
            ]),
            s(.petAgain, [
                "ok ok, du magst mich. verstanden.",
                "ich bin schon ganz plattgekrault",
            ]),
            s(.petAsleep, [
                "*schnarcht zufrieden*",
                "*lächelt im schlaf*",
            ]),
            s(.sleep, [
                "gute nacht",
                "schlaf gut. ich auch.",
                "licht aus",
            ]),
            s(.notSleepy, [
                "bin noch nicht müde",
                "schlafen? jetzt? nein.",
            ]),
            s(.wake, [
                "guten morgen",
                "ausgeschlafen",
                "hallo welt",
            ]),
            s(.wakeTired, [
                "noch fünf minuten …",
                "wer hat mich geweckt",
            ]),
            s(.sleeping, [
                "zzz … träumt von {fav}",
                "zzz",
                "*schläft tief*",
            ]),
            s(.levelUp, [
                "level {level}!",
                "level {level}. ich werde groß.",
            ]),
            s(.stageUp, [
                "ich bin gewachsen. sieh mich an.",
                "ich fühl mich anders. größer.",
            ]),
            s(.achievement, [
                "erfolg! {item}",
                "das haben wir geschafft: {item}",
            ]),
            s(.bought, [
                "{item}! danke.",
                "ooh, {item}",
            ]),
            s(.equipped, [
                "steht mir, oder?",
                "ich seh gut aus",
                "schick.",
            ]),
            s(.gift, [
                "geschenk an {friend} ist unterwegs",
                "{friend} wird sich freuen",
            ]),
            s(.giftReceived, [
                "{friend} hat uns was geschickt!",
                "post von {friend}",
            ]),
            s(.friendHug, [
                "*quetscht {friend}*",
                "umarmung mit {friend}. weich.",
            ]),
            s(.friendVisit, [
                "zu besuch bei {friend}",
                "{friend} hat ein schönes zuhause",
            ]),
            s(.friendNew, [
                "neuer freund: {friend}",
                "{friend}! hallo!",
            ]),
            s(.inLove, [
                "ich glaub, ich bin verliebt. in {friend}.",
                "{friend} und ich. für immer.",
            ]),
            s(.missFriend, [
                "ich vermisse {friend}",
                "{friend} war lange nicht da",
            ]),
            l(.idle, [
                ("alles gut hier", [], 0, nil),
                ("blub.", [], 0, nil),
                ("ich wackel so vor mich hin", [], 0, nil),
                ("was machst du gerade?", [.curious], 0, nil),
                ("ich hab eine fliege gesehen. sie war schnell.", [.curious], 0, nil),
                ("hunger hab ich nicht. aber {fav} ginge immer.", [.foodie], 0, nil),
                ("ich übe hüpfen", [.playful], 0, nil),
                ("gähn", [.sleepy], 0, nil),
                ("bin da, falls du mich brauchst", [.cuddly], 10, nil),
                ("die sonne ist schön heute", [], 0, 9...18),
                ("draußen ist es dunkel", [], 0, 19...23),
            ]),
            l(.idleClose, [
                ("schön, dass es dich gibt", [], 50, nil),
                ("du bist mein lieblingsmensch", [.cuddly], 70, nil),
                ("weißt du noch, unser erster tag?", [], 50, nil),
            ]),
            s(.curious, [
                "was ist das?",
                "hm?",
                "ich schau mal nach",
            ]),
        ]
        var out: [LineCategory: [Line]] = [:]
        for (category, items) in table { out[category] = items }
        return out
    }()
}
