import Foundation

// MARK: - Needs

enum Need: String, Codable, CaseIterable, Identifiable, Hashable {
    case hunger, fun, energy, clean, love

    var id: String { rawValue }

    var title: String {
        switch self {
        case .hunger: "Food"
        case .fun: "Fun"
        case .energy: "Energy"
        case .clean: "Clean"
        case .love: "Love"
        }
    }

    var symbol: String {
        switch self {
        case .hunger: "fork.knife"
        case .fun: "gamecontroller.fill"
        case .energy: "bolt.fill"
        case .clean: "bubbles.and.sparkles.fill"
        case .love: "heart.fill"
        }
    }

    /// What the slime says when this is the thing it wants most.
    var wish: String {
        switch self {
        case .hunger: "i'm sooo hungry"
        case .fun: "play with me!!"
        case .energy: "so... sleepy..."
        case .clean: "i feel sticky"
        case .love: "pet me? pls?"
        }
    }

    /// Points lost per hour. Negative means it refills.
    func decay(asleep: Bool) -> Double {
        switch (self, asleep) {
        case (.hunger, false): 6.5
        case (.hunger, true): 2.5
        case (.fun, false): 5.5
        case (.fun, true): 1
        case (.energy, false): 4.5
        case (.energy, true): -14
        case (.clean, false): 3
        case (.clean, true): 1
        case (.love, false): 4.5
        case (.love, true): 1.5
        }
    }
}

struct Needs: Codable, Hashable {
    var hunger = 80.0
    var fun = 70.0
    var energy = 90.0
    var clean = 85.0
    var love = 75.0

    subscript(need: Need) -> Double {
        get {
            switch need {
            case .hunger: hunger
            case .fun: fun
            case .energy: energy
            case .clean: clean
            case .love: love
            }
        }
        set {
            let v = min(100, max(0, newValue))
            switch need {
            case .hunger: hunger = v
            case .fun: fun = v
            case .energy: energy = v
            case .clean: clean = v
            case .love: love = v
            }
        }
    }

    mutating func add(_ need: Need, _ amount: Double) { self[need] += amount }

    var lowest: (need: Need, value: Double) {
        Need.allCases.map { ($0, self[$0]) }.min { $0.1 < $1.1 }!
    }

    var average: Double { Need.allCases.map { self[$0] }.reduce(0, +) / Double(Need.allCases.count) }
}

// MARK: - Animation keys (must match tools/make_slime.py)

/// The looping animation. Picks the font family together with `Reaction`.
enum Mood: String, Codable, Hashable {
    case idle, sad, sleep
}

/// A one-minute animation that plays after something happens.
enum Reaction: String, Codable, Hashable, CaseIterable {
    case none, eat, play, love, bath, melt, zap, confused, wave
}

// MARK: - Food

enum Food: String, Codable, CaseIterable, Identifiable, Hashable {
    case apple, berry, cookie, cake, starJelly

    var id: String { rawValue }

    var title: String {
        switch self {
        case .apple: "Apple"
        case .berry: "Berries"
        case .cookie: "Cookie"
        case .cake: "Cake"
        case .starJelly: "Star Jelly"
        }
    }

    var emoji: String {
        switch self {
        case .apple: "🍎"
        case .berry: "🍓"
        case .cookie: "🍪"
        case .cake: "🍰"
        case .starJelly: "🌟"
        }
    }

    var price: Int {
        switch self {
        case .apple: 0
        case .berry: 3
        case .cookie: 6
        case .cake: 12
        case .starJelly: 30
        }
    }

    var nutrition: Double {
        switch self {
        case .apple: 18
        case .berry: 24
        case .cookie: 28
        case .cake: 45
        case .starJelly: 100
        }
    }

    var funBonus: Double {
        switch self {
        case .apple: 0
        case .berry: 3
        case .cookie: 8
        case .cake: 12
        case .starJelly: 25
        }
    }
}

// MARK: - The slime

struct CareCounters: Codable, Hashable {
    var feeds = 0
    var plays = 0
    var baths = 0
    var pets = 0
    var bestScore = 0
}

enum SlimeAction: Hashable {
    case feed(Food)
    case play(score: Int)
    case bath
    case pet
    case sleep
    case wake
}

struct ActionOutcome {
    var ok: Bool
    var line: String
    var leveledUp = false
}

struct SlimeState: Codable, Hashable {
    var name = "Blob"
    var skin: Skin = .lime
    var birthday = Date.now
    var needs = Needs()
    var xp = 0
    var coins = 20
    var isAsleep = false
    var lastUpdate = Date.now
    var reaction: Reaction = .none
    var reactionAt = Date.distantPast
    var favorite: Food = .berry
    var counters = CareCounters()
    var recentPets: [Date] = []
    /// Last thing the slime said about a phone event, shown in the island.
    var eventLine: String?
    var lastEvent: PhoneEvent?
    var eventAt = Date.distantPast

    static func newborn(named name: String = "Blob") -> SlimeState {
        var s = SlimeState()
        s.name = name
        s.favorite = [Food.berry, .cookie, .cake].randomElement()!
        return s
    }

    // MARK: Time

    /// Runs the clock forward: needs drain, energy refills during sleep,
    /// and the slime wakes up by itself once rested in the morning.
    mutating func advance(to now: Date) {
        let hours = now.timeIntervalSince(lastUpdate) / 3600
        guard hours > 0 else { return }
        // Simulate in chunks so a long sleep wakes up at the right time.
        var remaining = hours
        while remaining > 0 {
            let step = min(remaining, 0.25)
            for need in Need.allCases {
                needs.add(need, -need.decay(asleep: isAsleep) * step)
            }
            remaining -= step
            if isAsleep, needs.energy >= 100 {
                let t = now.addingTimeInterval(-remaining * 3600)
                let hour = Calendar.current.component(.hour, from: t)
                if (7..<22).contains(hour) { isAsleep = false }
            }
        }
        lastUpdate = now
    }

    // MARK: Derived

    var mood: Mood {
        if isAsleep { return .sleep }
        return needs.lowest.value < 25 ? .sad : .idle
    }

    /// The need it is asking for, if anything is getting low.
    var wish: Need? {
        let low = needs.lowest
        return low.value < 55 ? low.need : nil
    }

    var level: Int { Self.level(forXP: xp) }

    static func level(forXP xp: Int) -> Int {
        // 40 xp to level 2, then each level costs 40 more than the last.
        Int((sqrt(1 + 8 * Double(xp) / 40) - 1) / 2) + 1
    }

    static func xpNeeded(for level: Int) -> Int { 40 * level * (level - 1) / 2 }

    var levelProgress: Double {
        let a = Self.xpNeeded(for: level), b = Self.xpNeeded(for: level + 1)
        return Double(xp - a) / Double(b - a)
    }

    var ageDays: Int {
        Calendar.current.dateComponents([.day], from: birthday, to: .now).day ?? 0
    }

    func isUnlocked(_ skin: Skin) -> Bool { level >= skin.unlockLevel }

    /// The reaction still playing, if it started less than a minute ago.
    func activeReaction(at now: Date) -> Reaction {
        now.timeIntervalSince(reactionAt) < 60 ? reaction : .none
    }

    /// One line of chatter for the island and the room.
    /// The phone event it is still talking about, for 15 minutes after it happened.
    func recentEvent(at now: Date) -> PhoneEvent? {
        now.timeIntervalSince(eventAt) < 15 * 60 ? lastEvent : nil
    }

    func line(at now: Date) -> String {
        if let eventLine, recentEvent(at: now) != nil { return eventLine }
        if isAsleep { return "zzz… dreaming of \(favorite.title.lowercased())" }
        if let wish { return wish.wish }
        let hour = Calendar.current.component(.hour, from: now)
        let content = [
            "life is good ✨",
            "i love you, you know",
            "bloop.",
            "feeling extra jiggly",
            hour < 12 ? "good morning!!" : hour < 18 ? "nice afternoon huh" : "cozy evening vibes",
        ]
        return content[(hour + counters.pets) % content.count]
    }

    // MARK: Actions

    @discardableResult
    mutating func apply(_ action: SlimeAction, at now: Date = .now) -> ActionOutcome {
        advance(to: now)
        let levelBefore = level
        var out: ActionOutcome

        switch action {
        case .feed(let food):
            if coins < food.price {
                return ActionOutcome(ok: false, line: "not enough coins for \(food.emoji)")
            }
            if needs.hunger >= 97 {
                return ActionOutcome(ok: false, line: "i'm stuffed!! 🫃")
            }
            isAsleep = false
            coins -= food.price
            let favorite = food == self.favorite
            needs.add(.hunger, food.nutrition * (favorite ? 1.3 : 1))
            needs.add(.fun, food.funBonus)
            if food == .starJelly {
                for need in Need.allCases { needs.add(need, 20) }
            }
            if favorite { needs.add(.love, 6) }
            counters.feeds += 1
            xp += 6
            react(.eat, at: now)
            out = ActionOutcome(ok: true, line: favorite ? "MY FAVORITE \(food.emoji)!!" : "nom nom \(food.emoji)")

        case .play(let score):
            if needs.energy < 12 {
                return ActionOutcome(ok: false, line: "too tired to play… 💤")
            }
            isAsleep = false
            needs.add(.fun, 18 + Double(min(score, 40)) / 2)
            needs.add(.energy, -10)
            needs.add(.clean, -8)
            needs.add(.hunger, -5)
            coins += 2 + score / 5
            xp += 10 + score / 4
            counters.plays += 1
            counters.bestScore = max(counters.bestScore, score)
            react(.play, at: now)
            out = ActionOutcome(ok: true, line: score > 0 ? "wheee! \(score) points!" : "wheee!")

        case .bath:
            if needs.clean > 95 {
                return ActionOutcome(ok: false, line: "already squeaky clean ✨")
            }
            isAsleep = false
            needs.clean = 100
            needs.add(.love, 4)
            xp += 6
            counters.baths += 1
            react(.bath, at: now)
            out = ActionOutcome(ok: true, line: "bubble bath!! 🫧")

        case .pet:
            recentPets = recentPets.filter { now.timeIntervalSince($0) < 600 } + [now]
            let streak = recentPets.count
            needs.add(.love, max(2, 14 - Double(streak - 1) * 4))
            needs.add(.fun, 2)
            if streak <= 3 { xp += 3 }
            counters.pets += 1
            if isAsleep {
                out = ActionOutcome(ok: true, line: "*happy sleepy noises*")
            } else {
                react(.love, at: now)
                out = ActionOutcome(ok: true, line: streak > 5 ? "ok ok i get it, you love me 🥰" : "hehe that tickles ❤️")
            }

        case .sleep:
            if needs.energy > 85 {
                return ActionOutcome(ok: false, line: "not sleepy yet!")
            }
            isAsleep = true
            reaction = .none
            out = ActionOutcome(ok: true, line: "good night… 🌙")

        case .wake:
            isAsleep = false
            if needs.energy < 50 { needs.add(.fun, -5) }
            react(.wave, at: now)
            out = ActionOutcome(ok: true, line: needs.energy < 50 ? "five more minutes…" : "good morning!! ☀️")
        }

        if level > levelBefore {
            coins += 10 * level
            out.leveledUp = true
            out.line = "LEVEL \(level)! +\(10 * level) coins"
        }
        return out
    }

    /// Something happened on the phone. The slime reacts and says something.
    mutating func apply(_ event: PhoneEvent, at now: Date = .now) {
        advance(to: now)
        if let effect = event.effect { needs.add(effect.need, effect.amount) }
        if !isAsleep || event.wakesUp {
            isAsleep = false
            react(event.reaction, at: now)
        }
        eventLine = event.lines.randomElement()
        lastEvent = event
        eventAt = now
    }

    mutating func react(_ r: Reaction, at now: Date) {
        reaction = r
        reactionAt = now
    }
}

// MARK: - Phone events

/// Things the phone does that the slime reacts to. In the app these are
/// detected live; when the app is closed, Shortcuts automations send them.
enum PhoneEvent: String, Codable, CaseIterable, Identifiable, Hashable {
    case batteryLow, batteryCritical, batteryFull
    case chargerConnected, chargerDisconnected
    case wifiConnected, wifiDisconnected, cellular, offline, backOnline
    case airplaneOn, airplaneOff, lowPowerOn, lowPowerOff
    case goodMorning, goodNight, alarm, newHour

    var id: String { rawValue }

    var title: String {
        switch self {
        case .batteryLow: "Battery low"
        case .batteryCritical: "Battery almost empty"
        case .batteryFull: "Battery full"
        case .chargerConnected: "Charger connected"
        case .chargerDisconnected: "Charger disconnected"
        case .wifiConnected: "Wi-Fi connected"
        case .wifiDisconnected: "Wi-Fi disconnected"
        case .cellular: "Switched to mobile data"
        case .offline: "No internet"
        case .backOnline: "Back online"
        case .airplaneOn: "Airplane Mode on"
        case .airplaneOff: "Airplane Mode off"
        case .lowPowerOn: "Low Power Mode on"
        case .lowPowerOff: "Low Power Mode off"
        case .goodMorning: "Good morning"
        case .goodNight: "Good night"
        case .alarm: "Alarm"
        case .newHour: "New hour"
        }
    }

    var symbol: String {
        switch self {
        case .batteryLow: "battery.25percent"
        case .batteryCritical: "battery.0percent"
        case .batteryFull: "battery.100percent"
        case .chargerConnected: "bolt.fill"
        case .chargerDisconnected: "powerplug"
        case .wifiConnected: "wifi"
        case .wifiDisconnected: "wifi.slash"
        case .cellular: "antenna.radiowaves.left.and.right"
        case .offline: "icloud.slash"
        case .backOnline: "checkmark.icloud"
        case .airplaneOn, .airplaneOff: "airplane"
        case .lowPowerOn, .lowPowerOff: "leaf.fill"
        case .goodMorning: "sun.max.fill"
        case .goodNight: "moon.stars.fill"
        case .alarm: "alarm.fill"
        case .newHour: "clock.fill"
        }
    }

    /// The Shortcuts automation trigger that sends this event.
    var shortcutTrigger: String {
        switch self {
        case .batteryLow: "Battery Level → Falls Below 20%"
        case .batteryCritical: "Battery Level → Falls Below 5%"
        case .batteryFull: "Battery Level → Equals 100%"
        case .chargerConnected: "Charger → Is Connected"
        case .chargerDisconnected: "Charger → Is Disconnected"
        case .wifiConnected: "Wi-Fi → Joins Any Network"
        case .wifiDisconnected: "Wi-Fi → Leaves Any Network"
        case .cellular: "Wi-Fi → Leaves Any Network (pick this or Wi-Fi disconnected)"
        case .offline: "Airplane Mode → Is Turned On, or detected live in the app"
        case .backOnline: "Detected live in the app"
        case .airplaneOn: "Airplane Mode → Is Turned On"
        case .airplaneOff: "Airplane Mode → Is Turned Off"
        case .lowPowerOn: "Low Power Mode → Is Turned On"
        case .lowPowerOff: "Low Power Mode → Is Turned Off"
        case .goodMorning: "Time of Day → Sunrise, or Sleep → Wake Up"
        case .goodNight: "Sleep → Wind Down Begins, or Time of Day → 22:00"
        case .alarm: "Alarm → Is Stopped"
        case .newHour: "Time of Day → every hour you like"
        }
    }

    var reaction: Reaction {
        switch self {
        case .batteryLow, .batteryCritical: .melt
        case .batteryFull, .chargerConnected: .zap
        case .chargerDisconnected, .lowPowerOff, .airplaneOff, .backOnline: .wave
        case .wifiConnected: .play
        case .wifiDisconnected, .cellular, .offline, .airplaneOn: .confused
        case .lowPowerOn: .melt
        case .goodMorning, .alarm, .newHour: .wave
        case .goodNight: .love
        }
    }

    var wakesUp: Bool {
        switch self {
        case .batteryCritical, .goodMorning, .alarm: true
        default: false
        }
    }

    var effect: (need: Need, amount: Double)? {
        switch self {
        case .chargerConnected: (.energy, 6)
        case .batteryFull: (.fun, 5)
        case .wifiConnected: (.fun, 4)
        case .goodMorning: (.love, 4)
        default: nil
        }
    }

    /// Whether the island should pop open with a sound.
    var alerts: Bool {
        switch self {
        case .batteryLow, .batteryCritical, .batteryFull, .offline, .goodMorning, .alarm: true
        default: false
        }
    }

    var lines: [String] {
        switch self {
        case .batteryLow: ["i'm melting… 🪫 plug me in?", "battery's low, i feel woozy", "need… juice…"]
        case .batteryCritical: ["HELP i'm a puddle 🫠", "5%!! this is not a drill", "tell my story… 🪫"]
        case .batteryFull: ["100%!! i'm FULL of zap ⚡️", "fully charged & jiggly", "unplug me, i'm buzzing"]
        case .chargerConnected: ["ooh yummy electricity ⚡️", "*slurps power*", "charging… tingly!"]
        case .chargerDisconnected: ["bye cable 👋", "free roaming mode!", "unplugged and ready"]
        case .wifiConnected: ["wi-fi!! 📶 zoom zoom", "ooh fast internet", "connected! hi network"]
        case .wifiDisconnected: ["where did the wi-fi go?", "wi-fi left us… 🥲", "hello? wi-fi?"]
        case .cellular: ["on mobile data now 📡", "switched to the air waves", "careful with the data!"]
        case .offline: ["no internet?? 😵‍💫", "i'm lost… no signal", "the internet is gone"]
        case .backOnline: ["we're back online!", "internet returned 🎉", "signal found!"]
        case .airplaneOn: ["✈️ are we flying??", "airplane mode! seatbelt on", "up up and away"]
        case .airplaneOff: ["landed! 🛬", "hello ground", "back to earth"]
        case .lowPowerOn: ["saving energy… moving slow", "eco slime mode 🍃", "conserving jiggles"]
        case .lowPowerOff: ["full power!!", "eco mode off, let's go", "i can wiggle again"]
        case .goodMorning: ["good morning!! ☀️", "rise and slime!", "new day, new jiggles"]
        case .goodNight: ["sleep well 🌙", "night night, love you", "time for slime dreams"]
        case .alarm: ["WAKE UP WAKE UP ⏰", "the alarm! go go go", "up you get!"]
        case .newHour: ["another hour, another bloop", "tick tock 🕐", "hourly jiggle check ✅"]
        }
    }
}
