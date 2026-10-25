import Foundation

/// Something worth celebrating that came out of an action.
enum GameEvent: Hashable {
    case levelUp(Int)
    case stageUp(Stage)
    case achievement(String)
    case favoriteFound(Food)
    case record(GameID, Int)
    case challenge(String)
}

struct Outcome {
    var ok: Bool
    var line: String
    var events: [GameEvent] = []
}

extension GameState {
    // MARK: Time

    /// Moves the world to `now`: needs, sleep, daily challenges, achievements.
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
        return checkAchievements(now: now)
    }

    /// The app was opened. Greets you, without guilt, if you were away.
    mutating func arrive(now: Date, calendar: Calendar = .current) -> String? {
        guard hatched else { return nil }
        let away = now.timeIntervalSince(player.lastSeen)
        player.lastSeen = now
        guard away > 4 * 3600, !slime.isAsleep else { return nil }
        let days = Int(away / 86400)
        let line: String
        if days >= 2 {
            line = Dialogue.say(.longReunion, in: &self, context: .init(days: days), at: now, calendar: calendar)
            memory.add(MemoryEntry(kind: .reunion, at: now, detail: "\(days)"))
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
        hatched = true
        player.lastSeen = now
        memory.add(MemoryEntry(kind: .hatched, at: now, detail: slime.name, keepsake: true))
        refreshChallenges(now: now)
    }

    // MARK: Care

    mutating func feed(_ food: Food, now: Date) -> Outcome {
        tick(now: now)
        guard slime.level >= food.unlockLevel else {
            return Outcome(ok: false, line: "\(food.title) unlocks at level \(food.unlockLevel)")
        }
        guard wallet.coins >= food.price else {
            return Outcome(ok: false, line: Dialogue.say(.noCoins, in: &self, at: now))
        }
        guard slime.needs.hunger < 97 else {
            return Outcome(ok: false, line: Dialogue.say(.stuffed, in: &self, at: now))
        }
        _ = wallet.spend(food.price)
        slime.isAsleep = false
        let isFavorite = food == slime.favorite
        slime.needs.add(.hunger, food.nutrition * (isFavorite ? 1.3 : 1))
        slime.needs.add(.fun, food.funBonus)
        if food == .starJelly {
            for need in Need.allCases { slime.needs.add(need, 20) }
        }
        if isFavorite { slime.needs.add(.love, 6) }
        inventory.foodsTasted.insert(food)
        progress.stats.feeds += 1
        slime.bond = min(100, slime.bond + 0.5)
        slime.personality.drift(.foodie)
        slime.react(.eat, at: now)

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
        slime.needs.add(.love, max(2, 14 - Double(streak - 1) * 4))
        slime.needs.add(.fun, 2)
        progress.stats.pets += 1
        slime.personality.drift(.cuddly, by: 0.002)
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
        memory.add(MemoryEntry(kind: .bath, at: now))
        var line = Dialogue.say(.bath, in: &self, at: now)
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
        let line = Dialogue.say(.sleep, in: &self, at: now)
        slime.say = Utterance(text: line, at: now)
        return Outcome(ok: true, line: line, events: checkAchievements(now: now))
    }

    mutating func wake(now: Date) -> Outcome {
        tick(now: now)
        slime.isAsleep = false
        let tired = slime.needs.energy < 50
        if tired { slime.needs.add(.fun, -5) }
        slime.react(.stretch, at: now)
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
    /// changes nothing.
    mutating func finish(_ result: GameResult) -> Outcome {
        let now = result.finishedAt
        tick(now: now)
        guard wallet.claim("game:\(result.session.uuidString)", coins: result.coins, at: now) else {
            return Outcome(ok: false, line: "already collected")
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
        if result.score >= game.goodScore { progress.stats.goodGames += 1 }

        var record = progress.records[game] ?? GameRecord()
        let isRecord = result.score > record.best && record.plays > 0
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
            line = Dialogue.say(.highScore, in: &self, context: ctx, at: now)
            slime.react(.celebrate, at: now)
        } else if result.score < game.goodScore / 4 {
            line = Dialogue.say(.lowScore, in: &self, context: ctx, at: now)
            slime.react(.play, at: now)
        } else {
            line = Dialogue.say(.played, in: &self, context: ctx, at: now)
            slime.react(.play, at: now)
        }
        memory.add(MemoryEntry(kind: .played, at: now, detail: "\(game.rawValue):\(result.score)"))
        player.xp += result.xp / 2
        events += gainXP(result.xp, now: now, line: &line)
        events += checkAchievements(now: now)
        slime.say = Utterance(text: line, at: now)
        return Outcome(ok: true, line: line, events: events)
    }

    // MARK: Shop and wardrobe

    func isAvailable(_ unlock: Unlock, now: Date = .now, calendar: Calendar = .current) -> Bool {
        switch unlock {
        case .level(let n): slime.level >= n
        case .achievement(let id): progress.achievements[id] != nil
        case .bond(let n): slime.bond >= Double(n)
        case .season(let month): calendar.component(.month, from: now) == month
        }
    }

    func owns(_ id: String) -> Bool { inventory.owned.contains(id) }

    mutating func buy(_ item: CatalogItem, now: Date) -> Outcome {
        guard !owns(item.id) else { return Outcome(ok: false, line: "you already have it") }
        guard isAvailable(item.unlock, now: now) else { return Outcome(ok: false, line: "still locked") }
        guard wallet.spend(item.price) else {
            return Outcome(ok: false, line: Dialogue.say(.noCoins, in: &self, at: now))
        }
        wallet.ledger["buy:\(item.id)"] = now
        inventory.owned.insert(item.id)
        progress.stats.purchases += 1
        slime.react(.celebrate, at: now)
        let line = Dialogue.say(.bought, in: &self, context: .init(item: item.title), at: now)
        slime.say = Utterance(text: line, at: now)
        return Outcome(ok: true, line: line, events: checkAchievements(now: now))
    }

    mutating func buy(_ habitat: Habitat, now: Date) -> Outcome {
        guard !inventory.habitats.contains(habitat) else { return Outcome(ok: false, line: "you already have it") }
        guard isAvailable(habitat.unlock, now: now) else { return Outcome(ok: false, line: "still locked") }
        guard wallet.spend(habitat.price) else {
            return Outcome(ok: false, line: Dialogue.say(.noCoins, in: &self, at: now))
        }
        wallet.ledger["buy:habitat.\(habitat.rawValue)"] = now
        inventory.habitats.insert(habitat)
        inventory.habitat = habitat
        slime.react(.curious, at: now)
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
            progress.challenges.daily = Challenges.pick(Challenges.dailyPool, count: 3, key: day).map { template in
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
              let template = Challenges.template(id) else { return Outcome(ok: false, line: "doesn't exist") }
        guard Challenges.isDone(challenge, in: self) else { return Outcome(ok: false, line: "not done yet") }
        let key = "challenge:\(isDaily ? board.dayKey : board.weekKey):\(id)"
        guard wallet.claim(key, coins: template.coins, at: now) else { return Outcome(ok: false, line: "already collected") }
        if isDaily, let i = progress.challenges.daily.firstIndex(where: { $0.id == id }) {
            progress.challenges.daily[i].claimed = true
        } else if let i = progress.challenges.weekly.firstIndex(where: { $0.id == id }) {
            progress.challenges.weekly[i].claimed = true
        }
        player.xp += isDaily ? 10 : 30
        var line = "+\(template.coins) coins"
        var events: [GameEvent] = [.challenge(id)]
        events += gainXP(isDaily ? 8 : 25, now: now, line: &line)
        return Outcome(ok: true, line: line, events: events)
    }

    // MARK: Phone, friends, chatter

    mutating func handle(_ event: PhoneEvent, now: Date) -> String {
        tick(now: now)
        if let effect = event.effect { slime.needs.add(effect.need, effect.amount) }
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
        inventory.foodsTasted.insert(food)
        progress.stats.giftsReceived += 1
        slime.react(.celebrate, at: now)
        memory.add(MemoryEntry(kind: .giftReceived, at: now, detail: friend, keepsake: progress.stats.giftsReceived == 1))
        let line = Dialogue.say(.giftReceived, in: &self, context: .init(friend: friend), at: now)
        slime.say = Utterance(text: line, at: now)
        return line
    }

    mutating func friendMoment(_ category: LineCategory, friend: String, fellInLove: Bool, now: Date) -> String {
        tick(now: now)
        slime.needs.add(.love, fellInLove ? 40 : 10)
        slime.needs.add(.fun, 5)
        var levelLine = ""
        _ = gainXP(fellInLove ? 50 : 4, now: now, line: &levelLine)
        switch category {
        case .gift: progress.stats.giftsSent += 1
        case .friendVisit: progress.stats.visits += 1
        case .friendHug: progress.stats.hugs += 1
        default: break
        }
        if fellInLove {
            memory.add(MemoryEntry(kind: .inLove, at: now, detail: friend, keepsake: true))
        }
        slime.react(fellInLove ? .celebrate : .love, at: now)
        let line = Dialogue.say(fellInLove ? .inLove : category, in: &self, context: .init(friend: friend), at: now)
        slime.say = Utterance(text: line, at: now)
        _ = checkAchievements(now: now)
        return line
    }

    mutating func madeFriend(_ friend: String, now: Date) {
        progress.stats.friends += 1
        memory.add(MemoryEntry(kind: .friendMade, at: now, detail: friend, keepsake: true))
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

    /// What it says when nothing in particular happened.
    mutating func chatter(now: Date, calendar: Calendar = .current) -> String {
        tick(now: now, calendar: calendar)
        let hour = calendar.component(.hour, from: now)
        let category: LineCategory
        if slime.isAsleep {
            category = .sleeping
        } else if let wish = slime.wish {
            category = Dialogue.category(for: wish)
        } else if slime.bond >= 50, hour % 3 == 0 {
            category = .idleClose
        } else {
            category = .idle
        }
        let line = Dialogue.say(category, in: &self, context: .init(game: slime.favoriteGame.title), at: now, calendar: calendar)
        slime.say = Utterance(text: line, at: now)
        return line
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

    private mutating func gainXP(_ amount: Int, now: Date, line: inout String) -> [GameEvent] {
        let levelBefore = slime.level
        let stageBefore = slime.stage
        slime.xp += amount
        var events: [GameEvent] = []
        if slime.level > levelBefore {
            for level in (levelBefore + 1)...slime.level {
                wallet.claim("level:\(level)", coins: 10 * level, at: now)
                events.append(.levelUp(level))
                memory.add(MemoryEntry(kind: .levelUp, at: now, detail: "\(level)", keepsake: level % 5 == 0))
            }
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

    @discardableResult
    mutating func checkAchievements(now: Date) -> [GameEvent] {
        var events: [GameEvent] = []
        for achievement in Achievements.all where progress.achievements[achievement.id] == nil && achievement.check(self) {
            progress.achievements[achievement.id] = now
            wallet.claim("achievement:\(achievement.id)", coins: achievement.coins, at: now)
            player.xp += 20
            memory.add(MemoryEntry(kind: .achievement, at: now, detail: achievement.id, keepsake: true))
            events.append(.achievement(achievement.id))
        }
        return events
    }
}
