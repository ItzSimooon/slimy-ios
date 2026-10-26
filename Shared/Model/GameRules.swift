import Foundation

/// Something worth celebrating that came out of an action.
enum GameEvent: Hashable {
    case levelUp(Int)
    case stageUp(Stage)
    case achievement(String)
    case favoriteFound(Food)
    case record(GameID, Int)
    case challenge(String)
    /// The slime turned this many years old today.
    case birthday(Int)
    case holiday(Holiday)
    /// A crate turned up (every fifth level, after a great round).
    case crate(CrateKind, CrateSource)
}

struct Outcome {
    var ok: Bool
    var line: String
    var events: [GameEvent] = []
}

extension GameState {
    // MARK: Time

    /// Moves the world to `now`: needs, sleep and feelings, daily
    /// challenges, special days, achievements.
    @discardableResult
    mutating func tick(now: Date, calendar: Calendar = .current) -> [GameEvent] {
        guard hatched else { return [] }
        slime.advance(to: now, schedule: settings.sleep, calendar: calendar)
        refreshChallenges(now: now, calendar: calendar)
        player.daysActive.insert(Challenges.dayKey(now, calendar: calendar))
        if player.daysActive.count > 400 {
            player.daysActive = Set(player.daysActive.sorted().suffix(400))
        }
        if slime.wish == nil { slime.lastNudged = nil }
        let special = celebrateSpecialDays(now: now, calendar: calendar)
        return special + checkAchievements(now: now)
    }

    /// Birthdays and holidays: a gift and a memory, once a year each.
    private mutating func celebrateSpecialDays(now: Date, calendar: Calendar) -> [GameEvent] {
        player.seasonsSeen.insert(Season.at(now, calendar: calendar).rawValue)
        let year = Calendar.gregorian(like: calendar).component(.year, from: now)
        var events: [GameEvent] = []
        if let years = Birthday.years(born: slime.birthday, at: now, calendar: calendar),
           wallet.claim(Birthday.receipt(year: year), coins: Birthday.coins(years: years), at: now) {
            inventory.owned.insert("furn.cake")
            slime.needs.add(.love, 15)
            slime.needs.add(.fun, 15)
            slime.feel(.joy, 0.5)
            slime.feel(.excitement, 0.4)
            slime.feel(.love, 0.2)
            memory.add(MemoryEntry(kind: .birthday, at: now, detail: "\(years)", keepsake: true))
            // A birthday that starts at midnight doesn't wake it up.
            if !slime.isAsleep {
                slime.react(.celebrate, at: now)
                let line = Dialogue.say(.birthday, in: &self, context: LineContext(years: years), at: now, calendar: calendar)
                slime.say = Utterance(text: line, at: now)
            }
            events.append(.birthday(years))
        }
        if let holiday = Holiday.at(now, calendar: calendar),
           wallet.claim(holiday.receipt(year: year), coins: holiday.coins, at: now) {
            memory.add(MemoryEntry(kind: .holiday, at: now, detail: holiday.rawValue, keepsake: true))
            slime.feel(.joy, 0.3)
            slime.feel(.excitement, 0.3)
            if !slime.isAsleep {
                slime.react(.celebrate, at: now)
                let line = Dialogue.say(holiday.lines, in: &self, at: now, calendar: calendar)
                slime.say = Utterance(text: line, at: now)
            }
            events.append(.holiday(holiday))
        }
        return events
    }

    /// Whether today is the slime's hatch-day anniversary.
    func isBirthday(at now: Date, calendar: Calendar = .current) -> Bool {
        hatched && Birthday.years(born: slime.birthday, at: now, calendar: calendar) != nil
    }

    /// The app was opened. Greets you, without guilt, if you were away.
    mutating func arrive(now: Date, calendar: Calendar = .current) -> String? {
        guard hatched else { return nil }
        let away = now.timeIntervalSince(player.lastSeen)
        player.lastSeen = now
        guard away > 4 * 3600, !slime.isAsleep else { return nil }
        let days = Int(away / 86400)
        // Glad you're back, whatever the time away.
        slime.feel(.joy, days >= 2 ? 0.4 : 0.25)
        slime.feel(.love, days >= 2 ? 0.25 : 0.15)
        let line: String
        var rng = SplitMix(seed: SplitMix.seed("arrive\(Int(now.timeIntervalSince1970 / 60))"))
        if days >= 2 {
            line = Dialogue.say(.longReunion, in: &self, context: .init(days: days), at: now, calendar: calendar)
            memory.add(MemoryEntry(kind: .reunion, at: now, detail: "\(days)"))
        } else if let seasonal = Season.at(now, calendar: calendar).lines, rng.unit() < 0.5 {
            line = Dialogue.say(seasonal, in: &self, at: now, calendar: calendar)
        } else {
            line = Dialogue.say(.reunion, in: &self, at: now, calendar: calendar)
        }
        slime.react(.wave, at: now)
        slime.say = Utterance(text: line, at: now)
        return line
    }

    mutating func hatch(name: String, skin: Skin, seed: UInt64, now: Date) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        slime = .newborn(named: trimmed.isEmpty ? "Blob" : String(trimmed.prefix(16)), skin: skin, seed: seed)
        slime.birthday = now
        slime.lastUpdate = now
        slime.react(.wave, at: now)
        slime.feel(.curiosity, 0.4)
        slime.feel(.joy, 0.2)
        hatched = true
        player.lastSeen = now
        memory.add(MemoryEntry(kind: .hatched, at: now, detail: slime.name, keepsake: true))
        refreshChallenges(now: now)
    }

    // MARK: Care

    /// Feeds the slime. A free serving from the pantry is used first; then
    /// it costs the food's price.
    mutating func feed(_ food: Food, now: Date) -> Outcome {
        tick(now: now)
        let fromPantry = servings(of: food) > 0
        guard fromPantry || slime.level >= food.unlockLevel else {
            return Outcome(ok: false, line: "\(food.title) unlocks at level \(food.unlockLevel).")
        }
        guard fromPantry || wallet.coins >= food.price else {
            return Outcome(ok: false, line: Dialogue.say(.noCoins, in: &self, at: now))
        }
        guard slime.needs.hunger < 97 else {
            return Outcome(ok: false, line: Dialogue.say(.stuffed, in: &self, at: now))
        }
        if fromPantry {
            let left = servings(of: food) - 1
            inventory.pantry[food] = left > 0 ? left : nil
        } else {
            _ = wallet.spend(food.price)
        }
        slime.isAsleep = false
        let isFavorite = food == slime.favorite
        slime.needs.add(.hunger, food.nutrition * (isFavorite ? 1.3 : 1))
        slime.needs.add(.fun, food.funBonus)
        if food == .starJelly {
            for need in Need.allCases { slime.needs.add(need, 20) }
            slime.feel(.excitement, 0.3)
        }
        if isFavorite {
            slime.needs.add(.love, 6)
            slime.feel(.joy, 0.3)
            slime.feel(.love, 0.2)
        } else {
            slime.feel(.joy, 0.15 + 0.1 * slime.personality.foodie)
        }
        inventory.foodsTasted.insert(food)
        progress.stats.feeds += 1
        slime.bond = min(100, slime.bond + 0.5)
        slime.personality.drift(.foodie)
        slime.react(.eat, at: now)
        noteCare(.feed, food: food, now: now)

        var events: [GameEvent] = []
        var line: String
        let ctx = LineContext(food: food.title.lowercased())
        if isFavorite && !inventory.favoriteFound {
            inventory.favoriteFound = true
            events.append(.favoriteFound(food))
            memory.add(MemoryEntry(kind: .favoriteFound, at: now, detail: food.rawValue, keepsake: true))
            line = Dialogue.say(.favoriteFound, in: &self, context: ctx, at: now)
            slime.react(.celebrate, at: now)
        } else if isFavorite {
            line = Dialogue.say(.fedFavorite, in: &self, context: ctx, at: now)
        } else {
            line = Dialogue.say(.fed, in: &self, context: ctx, at: now)
        }
        memory.add(MemoryEntry(kind: .fed, at: now, detail: food.rawValue))
        events += gainXP(6, now: now, line: &line)
        events += checkAchievements(now: now)
        slime.say = Utterance(text: line, at: now)
        return Outcome(ok: true, line: line, events: events)
    }

    mutating func pet(now: Date) -> Outcome {
        tick(now: now)
        slime.recentPets = slime.recentPets.filter { now.timeIntervalSince($0) < 600 } + [now]
        let streak = slime.recentPets.count
        let loveBefore = slime.needs.love
        slime.needs.add(.love, max(2, 14 - Double(streak - 1) * 4))
        let loveGiven = slime.needs.love - loveBefore
        slime.needs.add(.fun, 2)
        progress.stats.pets += 1
        slime.personality.drift(.cuddly, by: 0.002)
        // Each pet in a row means a little less.
        slime.feel(.love, 0.18 * max(0.25, 1 - Double(streak - 1) * 0.2))
        slime.feel(.joy, 0.05)
        noteCare(.pet, amount: loveGiven, now: now)
        var line: String
        var events: [GameEvent] = []
        if slime.isAsleep {
            line = Dialogue.say(.petAsleep, in: &self, at: now)
        } else if streak > 5 {
            line = Dialogue.say(.petAgain, in: &self, at: now)
            slime.react(.blush, at: now)
        } else {
            line = Dialogue.say(.pet, in: &self, at: now)
            slime.react(slime.bond >= 50 && streak == 1 ? .blush : .love, at: now)
        }
        if streak <= 3 {
            slime.bond = min(100, slime.bond + 1)
            events += gainXP(3, now: now, line: &line)
        }
        events += checkAchievements(now: now)
        slime.say = Utterance(text: line, at: now)
        return Outcome(ok: true, line: line, events: events)
    }

    mutating func bath(now: Date) -> Outcome {
        tick(now: now)
        guard slime.needs.clean <= 95 else {
            return Outcome(ok: false, line: Dialogue.say(.alreadyClean, in: &self, at: now))
        }
        slime.isAsleep = false
        slime.needs.clean = 100
        slime.needs.add(.love, 4)
        progress.stats.baths += 1
        slime.bond = min(100, slime.bond + 0.5)
        slime.react(.bath, at: now)
        // Some slimes are grumpy about baths for a little while.
        let grumpy = slime.personality.dislikesBaths
        if grumpy {
            slime.feel(.anger, 0.3)
            slime.feel(.joy, 0.1)
        } else {
            slime.feel(.joy, 0.3)
        }
        noteCare(.bath, now: now)
        memory.add(MemoryEntry(kind: .bath, at: now))
        var line = Dialogue.say(grumpy ? .bathGrumpy : .bath, in: &self, at: now)
        var events = gainXP(6, now: now, line: &line)
        events += checkAchievements(now: now)
        slime.say = Utterance(text: line, at: now)
        return Outcome(ok: true, line: line, events: events)
    }

    mutating func sleep(now: Date) -> Outcome {
        tick(now: now)
        guard slime.needs.energy <= 85 else {
            return Outcome(ok: false, line: Dialogue.say(.notSleepy, in: &self, at: now))
        }
        slime.isAsleep = true
        slime.reaction = .none
        progress.stats.sleeps += 1
        slime.personality.drift(.sleepy, by: 0.002)
        slime.feel(.sleepiness, 0.1)
        slime.feel(.excitement, -0.2)
        noteCare(.sleep, now: now)
        let line = Dialogue.say(.sleep, in: &self, at: now)
        slime.say = Utterance(text: line, at: now)
        return Outcome(ok: true, line: line, events: checkAchievements(now: now))
    }

    mutating func wake(now: Date) -> Outcome {
        tick(now: now)
        slime.isAsleep = false
        let tired = slime.needs.energy < 50
        if tired {
            slime.needs.add(.fun, -5)
            slime.feel(.sleepiness, 0.15)
            slime.feel(.anger, 0.1)
        } else {
            slime.feel(.joy, 0.1)
            slime.feel(.sleepiness, -0.3)
        }
        slime.react(.stretch, at: now)
        noteCare(.wake, now: now)
        let line = Dialogue.say(tired ? .wakeTired : .wake, in: &self, at: now)
        slime.say = Utterance(text: line, at: now)
        return Outcome(ok: true, line: line)
    }

    // MARK: Arcade

    var canPlay: Bool { slime.needs.energy >= 12 }

    func record(_ game: GameID) -> GameRecord { progress.records[game] ?? GameRecord() }

    mutating func markTutorialSeen(_ game: GameID) {
        progress.records[game, default: GameRecord()].tutorialSeen = true
    }

    /// Pays out a finished round once. A second call with the same session
    /// changes nothing. A great round sometimes brings a mystery crate,
    /// decided by the round's session.
    mutating func finish(_ result: GameResult) -> Outcome {
        let now = result.finishedAt
        tick(now: now)
        guard wallet.claim("game:\(result.session.uuidString)", coins: result.coins, at: now) else {
            return Outcome(ok: false, line: "Already collected.")
        }
        let game = result.game
        let favorite = game == slime.favoriteGame
        slime.isAsleep = false
        slime.needs.add(.fun, result.funGain * (favorite ? 1.3 : 1))
        slime.needs.add(.energy, -game.energyCost)
        slime.needs.add(.clean, -5)
        slime.needs.add(.hunger, -4)
        if favorite { slime.bond = min(100, slime.bond + 1) }
        slime.personality.drift(.playful)
        progress.stats.plays += 1
        let great = result.score >= result.goodScore
        if great { progress.stats.goodGames += 1 }
        if game == .daily, let day = result.day, var quest = progress.quest, quest.day == day {
            quest.best = max(quest.best, result.score)
            progress.quest = quest
        }
        let ratio = Double(max(0, result.score)) / Double(result.goodScore)
        noteCare(.play, game: game, amount: ratio * 100, now: now)

        var record = progress.records[game] ?? GameRecord()
        // Every day's game is different, so its scores aren't records.
        let isRecord = game != .daily && result.score > record.best && record.plays > 0
        record.best = max(record.best, result.score)
        record.plays += 1
        record.lastPlayed = now
        progress.records[game] = record

        for i in progress.challenges.daily.indices {
            if case .score(game, _)? = Challenges.template(progress.challenges.daily[i].id).map(\.goal) {
                progress.challenges.daily[i].best = max(progress.challenges.daily[i].best, result.score)
            }
        }

        var events: [GameEvent] = []
        var line: String
        let ctx = LineContext(score: result.score, game: game.title)
        if isRecord {
            events.append(.record(game, result.score))
            memory.add(MemoryEntry(kind: .highScore, at: now, detail: "\(game.rawValue):\(result.score)", keepsake: true))
            slime.feel(.joy, 0.35)
            slime.feel(.excitement, 0.4)
            line = Dialogue.say(.highScore, in: &self, context: ctx, at: now)
            slime.react(.celebrate, at: now)
        } else if result.score < result.goodScore / 4 {
            slime.feel(.excitement, 0.15)
            slime.feel(.joy, 0.05)
            line = Dialogue.say(.lowScore, in: &self, context: ctx, at: now)
            slime.react(.play, at: now)
        } else {
            slime.feel(.excitement, 0.3)
            slime.feel(.joy, great ? 0.15 : 0.08)
            line = Dialogue.say(.played, in: &self, context: ctx, at: now)
            slime.react(.play, at: now)
        }
        if great, Crates.dropsAfterRound(result.session), addCrate(.mystery) {
            events.append(.crate(.mystery, .greatRound))
        }
        memory.add(MemoryEntry(kind: .played, at: now, detail: "\(game.rawValue):\(result.score)"))
        player.xp += result.xp / 2
        events += gainXP(result.xp, now: now, line: &line)
        events += checkAchievements(now: now)
        slime.say = Utterance(text: line, at: now)
        return Outcome(ok: true, line: line, events: events)
    }

    // MARK: Daily plan

    /// Starts tracking the quest of a new day's plan.
    mutating func startQuest(_ plan: DailyPlan) {
        guard progress.quest?.day != plan.day else { return }
        var q = QuestState(day: plan.day)
        if case .stat(let key, _) = plan.quest.goal { q.baseline = progress.stats[key] }
        progress.quest = q
    }

    func questProgress(_ plan: DailyPlan) -> (value: Int, target: Int) {
        guard let q = progress.quest, q.day == plan.day else { return (0, 1) }
        switch plan.quest.goal {
        case .stat(let key, let target): return (min(target, max(0, progress.stats[key] - q.baseline)), target)
        case .dailyScore(let target): return (min(target, q.best), target)
        }
    }

    /// Pays the quest once, when it's done.
    mutating func claimQuest(_ plan: DailyPlan, now: Date) -> Outcome {
        let p = questProgress(plan)
        guard p.value >= p.target else { return Outcome(ok: false, line: "Not done yet.") }
        guard wallet.claim("quest:\(plan.day)", coins: plan.quest.coins, at: now) else {
            return Outcome(ok: false, line: "Already collected.")
        }
        progress.quest?.claimed = true
        slime.react(.celebrate, at: now)
        slime.feel(.joy, 0.2)
        slime.feel(.excitement, 0.15)
        var line = "+\(plan.quest.coins) coins"
        let events = gainXP(15, now: now, line: &line) + checkAchievements(now: now)
        return Outcome(ok: true, line: line, events: events)
    }

    // MARK: Shop and wardrobe

    func isAvailable(_ unlock: Unlock, now: Date = .now, calendar: Calendar = .current) -> Bool {
        switch unlock {
        case .level(let n): slime.level >= n
        case .achievement(let id): progress.achievements[id] != nil
        case .bond(let n): slime.bond >= Double(n)
        case .season(let month): Calendar.gregorian(like: calendar).component(.month, from: now) == month
        }
    }

    func owns(_ id: String) -> Bool { inventory.owned.contains(id) }

    mutating func buy(_ item: CatalogItem, now: Date) -> Outcome {
        guard !owns(item.id) else { return Outcome(ok: false, line: "You already have it.") }
        guard isAvailable(item.unlock, now: now) else { return Outcome(ok: false, line: "Still locked.") }
        guard wallet.spend(item.price) else {
            return Outcome(ok: false, line: Dialogue.say(.noCoins, in: &self, at: now))
        }
        wallet.ledger["buy:\(item.id)"] = now
        inventory.owned.insert(item.id)
        progress.stats.purchases += 1
        slime.react(.celebrate, at: now)
        slime.feel(.joy, 0.15)
        slime.feel(.curiosity, 0.1)
        let line = Dialogue.say(.bought, in: &self, context: .init(item: item.title), at: now)
        slime.say = Utterance(text: line, at: now)
        return Outcome(ok: true, line: line, events: checkAchievements(now: now))
    }

    mutating func buy(_ habitat: Habitat, now: Date) -> Outcome {
        guard !inventory.habitats.contains(habitat) else { return Outcome(ok: false, line: "You already have it.") }
        guard isAvailable(habitat.unlock, now: now) else { return Outcome(ok: false, line: "Still locked.") }
        guard wallet.spend(habitat.price) else {
            return Outcome(ok: false, line: Dialogue.say(.noCoins, in: &self, at: now))
        }
        wallet.ledger["buy:habitat.\(habitat.rawValue)"] = now
        inventory.habitats.insert(habitat)
        inventory.habitat = habitat
        slime.react(.curious, at: now)
        slime.feel(.curiosity, 0.35)
        slime.feel(.excitement, 0.2)
        let line = Dialogue.say(.bought, in: &self, context: .init(item: habitat.title), at: now)
        return Outcome(ok: true, line: line, events: checkAchievements(now: now))
    }

    mutating func equip(_ id: String, now: Date) {
        guard owns(id), let item = Catalog.item(id) else { return }
        switch item.kind {
        case .hat: slime.equipped.hat = id
        case .glasses: slime.equipped.glasses = id
        case .scarf: slime.equipped.scarf = id
        case .effect: slime.equipped.effect = id
        case .furniture:
            if let slot = item.slot { inventory.furniture[slot] = id }
        }
        slime.react(.blush, at: now)
        slime.feel(.joy, 0.1)
        slime.say = Utterance(text: Dialogue.say(.equipped, in: &self, at: now), at: now)
    }

    mutating func unequip(_ id: String) {
        if slime.equipped.hat == id { slime.equipped.hat = nil }
        if slime.equipped.glasses == id { slime.equipped.glasses = nil }
        if slime.equipped.scarf == id { slime.equipped.scarf = nil }
        if slime.equipped.effect == id { slime.equipped.effect = nil }
        for (slot, value) in inventory.furniture where value == id { inventory.furniture[slot] = nil }
    }

    func isEquipped(_ id: String) -> Bool {
        slime.equipped.all.contains(id) || inventory.furniture.values.contains(id)
    }

    mutating func move(to habitat: Habitat) {
        guard inventory.habitats.contains(habitat) else { return }
        inventory.habitat = habitat
    }

    // MARK: Challenges

    mutating func refreshChallenges(now: Date, calendar: Calendar = .current) {
        let day = Challenges.dayKey(now, calendar: calendar)
        if progress.challenges.dayKey != day {
            progress.challenges.dayKey = day
            // Same seed for everyone each day; locked games are left out.
            let pool = Challenges.dailyOptions(forLevel: slime.level)
            progress.challenges.daily = Challenges.pick(pool, count: 3, key: day).map { template in
                var c = ChallengeState(id: template.id)
                if case .stat(let key, _) = template.goal { c.baseline = progress.stats[key] }
                return c
            }
        }
        let week = Challenges.weekKey(now, calendar: calendar)
        if progress.challenges.weekKey != week {
            progress.challenges.weekKey = week
            progress.challenges.weekly = Challenges.pick(Challenges.weeklyPool, count: 2, key: week).map { template in
                var c = ChallengeState(id: template.id)
                if case .stat(let key, _) = template.goal { c.baseline = progress.stats[key] }
                return c
            }
        }
    }

    mutating func claimChallenge(_ id: String, now: Date) -> Outcome {
        let board = progress.challenges
        let isDaily = board.daily.contains { $0.id == id }
        guard let challenge = (board.daily + board.weekly).first(where: { $0.id == id }),
              let template = Challenges.template(id) else { return Outcome(ok: false, line: "This challenge is over.") }
        guard Challenges.isDone(challenge, in: self) else { return Outcome(ok: false, line: "Not done yet.") }
        let key = "challenge:\(isDaily ? board.dayKey : board.weekKey):\(id)"
        guard wallet.claim(key, coins: template.coins, at: now) else { return Outcome(ok: false, line: "Already collected.") }
        if isDaily, let i = progress.challenges.daily.firstIndex(where: { $0.id == id }) {
            progress.challenges.daily[i].claimed = true
        } else if let i = progress.challenges.weekly.firstIndex(where: { $0.id == id }) {
            progress.challenges.weekly[i].claimed = true
        }
        player.xp += isDaily ? 10 : 30
        slime.feel(.joy, isDaily ? 0.15 : 0.25)
        var line = "+\(template.coins) coins"
        var events: [GameEvent] = [.challenge(id)]
        events += gainXP(isDaily ? 8 : 25, now: now, line: &line)
        return Outcome(ok: true, line: line, events: events)
    }

    // MARK: Phone, friends, chatter

    mutating func handle(_ event: PhoneEvent, now: Date) -> String {
        tick(now: now)
        if let effect = event.effect { slime.needs.add(effect.need, effect.amount) }
        if let feeling = event.feeling { slime.feel(feeling.emotion, feeling.amount) }
        if !slime.isAsleep || event.wakesUp {
            slime.isAsleep = false
            slime.react(event.reaction, at: now)
        }
        var rng = SplitMix(seed: SplitMix.seed("\(event.rawValue)\(Int(now.timeIntervalSince1970))"))
        let line = event.lines[Int(rng.next() % UInt64(event.lines.count))]
        slime.lastEvent = event
        slime.eventAt = now
        slime.say = Utterance(text: line, at: now)
        progress.stats.eventsSeen += 1
        memory.add(MemoryEntry(kind: .event, at: now, detail: event.rawValue))
        _ = checkAchievements(now: now)
        return line
    }

    mutating func receiveGift(_ food: Food, from friend: String, now: Date) -> String {
        tick(now: now)
        slime.needs.add(.hunger, food.nutrition)
        slime.needs.add(.love, 8)
        slime.feel(.joy, 0.3)
        slime.feel(.excitement, 0.15)
        slime.feel(.love, 0.15)
        inventory.foodsTasted.insert(food)
        progress.stats.giftsReceived += 1
        slime.react(.celebrate, at: now)
        memory.add(MemoryEntry(kind: .giftReceived, at: now, detail: friend, keepsake: progress.stats.giftsReceived == 1))
        let line = Dialogue.say(.giftReceived, in: &self, context: .init(friend: friend), at: now)
        slime.say = Utterance(text: line, at: now)
        return line
    }

    /// Something done together with a friend's slime: a hug, a visit or a
    /// gift sent. Counts it, cheers the slime up and returns its line.
    mutating func friendMoment(_ category: LineCategory, friend: String, now: Date) -> String {
        tick(now: now)
        slime.needs.add(.love, 10)
        slime.needs.add(.fun, 5)
        var levelLine = ""
        _ = gainXP(4, now: now, line: &levelLine)
        switch category {
        case .gift:
            progress.stats.giftsSent += 1
            memory.add(MemoryEntry(kind: .gift, at: now, detail: friend))
            slime.feel(.joy, 0.2)
            slime.feel(.love, 0.1)
        case .friendVisit:
            progress.stats.visits += 1
            memory.add(MemoryEntry(kind: .visit, at: now, detail: friend))
            slime.feel(.joy, 0.25)
            slime.feel(.excitement, 0.25)
        case .friendHug:
            progress.stats.hugs += 1
            slime.feel(.love, 0.2)
            slime.feel(.joy, 0.15)
        default:
            slime.feel(.joy, 0.15)
        }
        slime.react(category == .friendVisit ? .wave : .love, at: now)
        let line = Dialogue.say(category, in: &self, context: .init(friend: friend), at: now)
        slime.say = Utterance(text: line, at: now)
        _ = checkAchievements(now: now)
        return line
    }

    mutating func madeFriend(_ friend: String, now: Date) {
        progress.stats.friends += 1
        memory.add(MemoryEntry(kind: .friendMade, at: now, detail: friend, keepsake: true))
        slime.feel(.joy, 0.3)
        slime.feel(.excitement, 0.25)
        slime.say = Utterance(text: Dialogue.say(.friendNew, in: &self, context: .init(friend: friend), at: now), at: now)
        _ = checkAchievements(now: now)
    }

    /// Says what it needs once per need, not over and over. Returns the line
    /// if the island should tell you.
    mutating func nudge(now: Date) -> (need: Need, line: String)? {
        tick(now: now)
        guard let wish = slime.wish, !slime.isAsleep else { return nil }
        if slime.lastNudged == wish, now.timeIntervalSince(slime.lastNudgeAt) < 6 * 3600 { return nil }
        slime.lastNudged = wish
        slime.lastNudgeAt = now
        let line = Dialogue.say(Dialogue.category(for: wish), in: &self,
                                context: .init(game: slime.favoriteGame.title), at: now)
        slime.say = Utterance(text: line, at: now)
        return (wish, line)
    }

    /// How many of its most recent lines small talk never repeats.
    static let chatterMemory = 12

    /// Small talk: something that fits right now, picked from what it needs,
    /// how it feels, what just happened (food, a game, a friend, the
    /// partner's care, a crate), the season, where it lives, the weather,
    /// the time of day and its character. Never one of its last 12 lines, and never a
    /// guilt trip.
    mutating func chatter(now: Date, calendar: Calendar = .current) -> String {
        tick(now: now, calendar: calendar)
        let recent = Set(memory.recentLines.suffix(Self.chatterMemory))
        // Deterministic per moment, like the lines themselves.
        var rng = SplitMix(seed: SplitMix.seed(
            "chatter\(Int(now.timeIntervalSince1970))|\(memory.lineUsedAt.count)|\(memory.recentLines.last ?? "")"))
        var topics = chatterTopics(now: now, calendar: calendar)
        while !topics.isEmpty {
            let total = topics.reduce(0.0) { $0 + max(0, $1.weight) }
            var roll = rng.unit() * total
            var index = topics.count - 1
            for (i, topic) in topics.enumerated() {
                roll -= max(0, topic.weight)
                if roll < 0 {
                    index = i
                    break
                }
            }
            let topic = topics.remove(at: index)
            if let line = Dialogue.fresh(topic.category, state: self, at: now, calendar: calendar,
                                         avoiding: recent, using: &rng) {
                return speak(line, context: topic.context, at: now)
            }
        }
        // Everything that fits was said lately: any line of the fallback
        // kind it hasn't said in a while.
        let fallback: LineCategory = slime.isAsleep ? .sleeping : .idle
        let unsaid = (Dialogue.lines[fallback] ?? []).filter { !recent.contains($0.id) }
        let line = unsaid.isEmpty
            ? Dialogue.pick(fallback, state: self, at: now, calendar: calendar)
            : unsaid[Int(rng.next() % UInt64(unsaid.count))]
        return speak(line, context: LineContext(game: slime.favoriteGame.title), at: now)
    }

    /// One thing it could talk about, and how much it wants to.
    struct ChatterTopic {
        let category: LineCategory
        let weight: Double
        var context = LineContext()
    }

    /// What it could talk about right now, weighted.
    func chatterTopics(now: Date, calendar: Calendar = .current) -> [ChatterTopic] {
        let base = LineContext(game: slime.favoriteGame.title,
                               streak: streakInfo(at: now, calendar: calendar).current,
                               years: Birthday.years(born: slime.birthday, at: now, calendar: calendar) ?? 0)
        if slime.isAsleep { return [ChatterTopic(category: .sleeping, weight: 1, context: base)] }
        var topics: [ChatterTopic] = []

        // What it would like, gently, a little more often the lower it gets.
        let low = slime.needs.lowest
        if low.value < 50 {
            topics.append(ChatterTopic(category: Dialogue.category(for: low.need),
                                       weight: 2 + 4 * (50 - low.value) / 50, context: base))
        }

        // How it feels: the leading feeling most, strong others too.
        let feelings = slime.emotions
        for e in Emotion.allCases where feelings[e] >= 0.3 {
            let lead = e == feelings.dominant
            topics.append(ChatterTopic(category: Dialogue.category(for: e),
                                       weight: (lead ? 1.5 : 0.5) + 3.5 * feelings[e], context: base))
        }

        // What just happened.
        func recent(_ kind: MemoryEntry.Kind, within window: TimeInterval) -> MemoryEntry? {
            guard let entry = memory.last(kind) else { return nil }
            let age = now.timeIntervalSince(entry.at)
            return age >= 0 && age < window ? entry : nil
        }
        if let fed = recent(.fed, within: 15 * 60) {
            var ctx = base
            ctx.food = Food(rawValue: fed.detail)?.title.lowercased() ?? "snack"
            topics.append(ChatterTopic(category: .afterFood, weight: 1.5, context: ctx))
        }
        if let played = recent(.played, within: 15 * 60) {
            var ctx = base
            let id = String(played.detail.split(separator: ":").first ?? "")
            ctx.game = GameID(rawValue: id)?.title ?? slime.favoriteGame.title
            topics.append(ChatterTopic(category: .afterGame, weight: 1.5, context: ctx))
        }
        if let cared = recent(.partnerCare, within: 20 * 60), let note = PartnerCareNote(detail: cared.detail) {
            var ctx = base
            ctx.friend = note.person
            topics.append(ChatterTopic(category: .partnerCared, weight: 2, context: ctx))
        }
        let friendKinds: [MemoryEntry.Kind] = [.visit, .gift, .giftReceived, .friendMade]
        if let friendly = memory.entries.last(where: { friendKinds.contains($0.kind) }), !friendly.detail.isEmpty {
            let age = now.timeIntervalSince(friendly.at)
            if age >= 0, age < 20 * 60 {
                var ctx = base
                ctx.friend = friendly.detail
                topics.append(ChatterTopic(category: .afterFriend, weight: 1.5, context: ctx))
            }
        }
        if let crate = recent(.crate, within: 15 * 60) {
            var ctx = base
            ctx.item = CrateFind(detail: crate.detail)?.title.lowercased() ?? "that"
            topics.append(ChatterTopic(category: .afterCrate, weight: 1.5, context: ctx))
        }

        // Special days, the season, where it lives, the time of day.
        if base.years > 0 { topics.append(ChatterTopic(category: .birthday, weight: 2.5, context: base)) }
        let season = Season.at(now, calendar: calendar)
        topics.append(ChatterTopic(category: season.talk, weight: season.lines != nil ? 1.4 : 0.6, context: base))
        topics.append(ChatterTopic(category: Dialogue.category(for: inventory.habitat), weight: 0.7, context: base))
        // The weather, wherever it can see the sky: outside or through the room's window.
        if inventory.habitat.hasWeather || inventory.habitat == .room {
            let sky = Sky.at(now, calendar: calendar)
            if let talk = sky.talk {
                topics.append(ChatterTopic(category: talk, weight: sky == .cloudy ? 0.6 : 1.3, context: base))
            }
        }
        topics.append(ChatterTopic(category: Dialogue.timeOfDay(hour: calendar.component(.hour, from: now)),
                                   weight: 1, context: base))

        // Closeness, the streak and its character.
        if slime.bond >= 50 { topics.append(ChatterTopic(category: .idleClose, weight: 0.8, context: base)) }
        if base.streak >= 3 { topics.append(ChatterTopic(category: .streak, weight: 0.35, context: base)) }
        if slime.personality.curious > 0.5 {
            topics.append(ChatterTopic(category: .curious, weight: 0.4 * slime.personality.curious, context: base))
        }
        topics.append(ChatterTopic(category: .idle, weight: 1, context: base))
        return topics
    }

    /// Says a line now and remembers it.
    private mutating func speak(_ line: Line, context: LineContext, at now: Date) -> String {
        memory.used(line: line.id, at: now)
        let text = Dialogue.fill(line.text, context: context, state: self)
        slime.say = Utterance(text: text, at: now, lineID: line.id)
        return text
    }

    /// The line to show right now without changing anything.
    func line(at now: Date) -> String {
        if let current = slime.currentLine(at: now) { return current }
        if slime.isAsleep { return "zzz" }
        if let wish = slime.wish {
            return Dialogue.fill(Dialogue.lines[Dialogue.category(for: wish)]?.first?.text ?? "", context: .init(), state: self)
        }
        return "blub."
    }

    // MARK: Progress

    /// Adds xp. Every new level pays coins once; every fifth level also
    /// brings a mystery crate.
    mutating func gainXP(_ amount: Int, now: Date, line: inout String) -> [GameEvent] {
        let levelBefore = slime.level
        let stageBefore = slime.stage
        slime.xp += amount
        var events: [GameEvent] = []
        if slime.level > levelBefore {
            for level in (levelBefore + 1)...slime.level {
                let paid = wallet.claim("level:\(level)", coins: 10 * level, at: now)
                events.append(.levelUp(level))
                memory.add(MemoryEntry(kind: .levelUp, at: now, detail: "\(level)", keepsake: level % 5 == 0))
                if paid, level % 5 == 0, addCrate(.mystery) {
                    events.append(.crate(.mystery, .levelUp))
                }
            }
            slime.feel(.joy, 0.3)
            slime.feel(.excitement, 0.35)
            line = Dialogue.say(.levelUp, in: &self, context: .init(level: slime.level), at: now)
            slime.react(.celebrate, at: now)
        }
        if slime.stage > stageBefore {
            events.append(.stageUp(slime.stage))
            memory.add(MemoryEntry(kind: .stageUp, at: now, detail: slime.stage.rawValue, keepsake: true))
            line = Dialogue.say(.stageUp, in: &self, at: now)
        }
        return events
    }

    /// Awards every achievement that is newly done: its coins, xp, a memory
    /// and, for some, a crate.
    @discardableResult
    mutating func checkAchievements(now: Date) -> [GameEvent] {
        var events: [GameEvent] = []
        for achievement in Achievements.all where progress.achievements[achievement.id] == nil && achievement.check(self) {
            progress.achievements[achievement.id] = now
            wallet.claim("achievement:\(achievement.id)", coins: achievement.coins, at: now)
            if let crate = achievement.crate { addCrate(crate) }
            player.xp += 20
            memory.add(MemoryEntry(kind: .achievement, at: now, detail: achievement.id, keepsake: true))
            events.append(.achievement(achievement.id))
        }
        return events
    }
}
