import XCTest
@testable import Slimy

private let berlin: Calendar = {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "Europe/Berlin")!
    return c
}()

private func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int = 12, _ minute: Int = 0) -> Date {
    berlin.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
}

/// 4 May 2026, noon in Berlin: no season, holiday or birthday.
private let noon = date(2026, 5, 4)

private func hatchedState(at now: Date = noon) -> GameState {
    var state = GameState()
    state.hatch(name: "Testi", skin: .lime, seed: 42, now: now)
    state.settings.sleep.auto = false
    return state
}

final class EmotionModelTests: XCTestCase {
    func testStartsCalm() {
        let e = Emotions()
        XCTAssertEqual(e[.joy], 0.3)
        for emotion in Emotion.allCases where emotion != .joy {
            XCTAssertEqual(e[emotion], 0, emotion.rawValue)
        }
        XCTAssertEqual(e.dominant, .joy)
        XCTAssertEqual(e.intensity, 0.3, accuracy: 1e-9)
    }

    func testValuesStayInRange() {
        var e = Emotions()
        e[.anger] = 7
        e[.fear] = -2
        e[.love] = .nan
        e[.joy] = .infinity
        XCTAssertEqual(e[.anger], 1)
        XCTAssertEqual(e[.fear], 0)
        XCTAssertEqual(e[.love], 0)
        XCTAssertEqual(e[.joy], 0)
    }

    func testFeelingRisesAndSoftensItsOpposites() {
        var e = Emotions()
        e[.sadness] = 0.5
        e.feel(.joy, 0.4)
        XCTAssertEqual(e[.joy], 0.7, accuracy: 1e-9)
        XCTAssertEqual(e[.sadness], 0.5 - 0.4 * Emotions.damping, accuracy: 1e-9)
        // Taking a feeling away leaves the others alone.
        e.feel(.joy, -0.2)
        XCTAssertEqual(e[.joy], 0.5, accuracy: 1e-9)
        XCTAssertEqual(e[.sadness], 0.5 - 0.4 * Emotions.damping, accuracy: 1e-9)
        e.feel(.anger, .nan)
        XCTAssertEqual(e[.anger], 0)
        for emotion in Emotion.allCases {
            XCTAssertFalse(emotion.opposites.contains(emotion), emotion.rawValue)
            XCTAssertFalse(emotion.title.isEmpty)
            XCTAssertEqual(emotion.adjective, emotion.adjective.lowercased())
        }
    }

    func testDominantAndStrongest() {
        var e = Emotions()
        e[.curiosity] = 0.8
        e[.excitement] = 0.5
        XCTAssertEqual(e.dominant, .curiosity)
        XCTAssertEqual(e.intensity, 0.8, accuracy: 1e-9)
        XCTAssertEqual(e.strongest(2), [.curiosity, .excitement])
        XCTAssertEqual(e.strongest(5, atLeast: 0.6), [.curiosity])
    }

    func testWireLeavesOutFaintFeelingsAndRounds() {
        var e = Emotions()
        e[.joy] = 0.456
        e[.fear] = 0.04
        e[.love] = 0.05
        XCTAssertEqual(e.wire, ["joy": 0.46, "love": 0.05])
    }

    func testBlending() {
        var a = Emotions()
        a[.sadness] = 0.8
        var b = Emotions()
        b[.sadness] = 0.2
        XCTAssertEqual(a.blended(with: b, weight: 0.5)[.sadness], 0.5, accuracy: 1e-9)
        XCTAssertEqual(a.blended(with: b, weight: 0)[.sadness], 0.8, accuracy: 1e-9)
        XCTAssertEqual(a.blended(with: b, weight: 7)[.sadness], 0.2, accuracy: 1e-9)
    }

    func testCodingAndSavesFromBeforeFeelings() throws {
        var e = Emotions()
        e[.love] = 0.7
        let back = try JSONDecoder().decode(Emotions.self, from: JSONEncoder().encode(e))
        XCTAssertEqual(back, e)

        let partial = try JSONDecoder().decode(Emotions.self, from: Data(#"{"fear": 2, "anger": "lots"}"#.utf8))
        XCTAssertEqual(partial[.fear], 1)
        XCTAssertEqual(partial[.anger], 0)
        XCTAssertEqual(partial[.joy], 0.3)

        let slime = try GameStorage.decoder.decode(Slime.self, from: Data(#"{"name": "Old"}"#.utf8))
        XCTAssertEqual(slime.name, "Old")
        XCTAssertEqual(slime.emotions, Emotions())
        XCTAssertTrue(slime.colours.isEmpty)
    }
}

final class EmotionTimeTests: XCTestCase {
    func testSettlesHalfwayInThirtyMinutes() {
        var e = Emotions()
        e[.excitement] = 1
        e.settle(toward: Emotions(), over: 30 * 60)
        XCTAssertEqual(e[.excitement], 0.5, accuracy: 1e-9)
        e.settle(toward: Emotions(), over: -100)
        e.settle(toward: Emotions(), over: .nan)
        XCTAssertEqual(e[.excitement], 0.5, accuracy: 1e-9)
    }

    func testTickSettlesTowardTheBaseline() {
        var state = hatchedState()
        state.slime.emotions[.excitement] = 1
        let start = state.slime.lastUpdate
        state.tick(now: start.addingTimeInterval(30 * 60), calendar: berlin)
        XCTAssertLessThan(state.slime.emotions[.excitement], 0.6)
        XCTAssertGreaterThan(state.slime.emotions[.excitement], 0.45)

        state.tick(now: start.addingTimeInterval(6 * 3600), calendar: berlin)
        let base = Emotions.baseline(needs: state.slime.needs, personality: state.slime.personality,
                                     bond: state.slime.bond, asleep: state.slime.isAsleep)
        XCTAssertEqual(state.slime.emotions[.excitement], base[.excitement], accuracy: 0.05)
    }

    func testClockGoingBackChangesNoFeelings() {
        var state = hatchedState()
        state.slime.emotions[.excitement] = 1
        state.tick(now: state.slime.lastUpdate.addingTimeInterval(-3600), calendar: berlin)
        XCTAssertEqual(state.slime.emotions[.excitement], 1)
        // And a huge jump ahead just lands on the baseline.
        state.tick(now: state.slime.lastUpdate.addingTimeInterval(400 * 86400), calendar: berlin)
        XCTAssertLessThan(state.slime.emotions[.excitement], 0.2)
        for emotion in Emotion.allCases {
            XCTAssertTrue((0...1).contains(state.slime.emotions[emotion]), emotion.rawValue)
        }
    }

    func testNeedsDriveSadnessBoredomAndSleepiness() {
        let personality = Personality(playful: 0.5, cuddly: 0.5, foodie: 0.5, sleepy: 0.5, curious: 0.5)
        let happy = Emotions.baseline(needs: Needs(), personality: personality, bond: 30, asleep: false)
        var low = Needs()
        low.love = 10
        low.fun = 10
        low.energy = 10
        let neglected = Emotions.baseline(needs: low, personality: personality, bond: 30, asleep: false)
        XCTAssertGreaterThan(neglected[.sadness], happy[.sadness] + 0.2)
        XCTAssertGreaterThan(neglected[.boredom], happy[.boredom] + 0.2)
        XCTAssertGreaterThan(neglected[.sleepiness], happy[.sleepiness] + 0.2)
        XCTAssertLessThan(neglected[.joy], happy[.joy])
        XCTAssertEqual(happy.dominant, .joy)

        var hungry = Needs()
        hungry.hunger = 5
        XCTAssertGreaterThan(Emotions.baseline(needs: hungry, personality: personality, bond: 30, asleep: false)[.anger], 0.2)

        let asleep = Emotions.baseline(needs: Needs(), personality: personality, bond: 30, asleep: true)
        XCTAssertEqual(asleep.dominant, .sleepiness)
        XCTAssertEqual(asleep[.excitement], 0)
    }

    func testStrongSadnessShowsAsASadMood() {
        var slime = hatchedState().slime
        XCTAssertEqual(slime.mood, .idle)
        slime.emotions[.sadness] = 0.7
        XCTAssertEqual(slime.mood, .sad)
        slime.isAsleep = true
        XCTAssertEqual(slime.mood, .sleep)
    }
}

final class EmotionRuleTests: XCTestCase {
    func testFoodBringsJoyAndTheFavouriteLove() {
        var state = hatchedState()
        state.slime.favorite = .berry
        state.slime.needs.hunger = 20
        state.slime.emotions = Emotions()
        XCTAssertTrue(state.feed(.carrot, now: noon.addingTimeInterval(60)).ok)
        XCTAssertGreaterThan(state.slime.emotions[.joy], 0.4)
        XCTAssertLessThan(state.slime.emotions[.love], 0.05)

        let before = state.slime.emotions
        XCTAssertTrue(state.feed(.berry, now: noon.addingTimeInterval(120)).ok)
        XCTAssertGreaterThan(state.slime.emotions[.love], before[.love] + 0.15)
        XCTAssertGreaterThan(state.slime.emotions[.joy], before[.joy])
    }

    func testPettingBringsLove() {
        var state = hatchedState()
        state.slime.emotions = Emotions()
        _ = state.pet(now: noon.addingTimeInterval(60))
        XCTAssertGreaterThan(state.slime.emotions[.love], 0.15)
    }

    func testBathsCheerUpButSomeSlimesGrumble() {
        var state = hatchedState()
        state.slime.personality = Personality(playful: 0.6, cuddly: 0.5, foodie: 0.5, sleepy: 0.3, curious: 0.7)
        XCTAssertFalse(state.slime.personality.dislikesBaths)
        state.slime.needs.clean = 30
        state.slime.emotions = Emotions()
        XCTAssertTrue(state.bath(now: noon.addingTimeInterval(60)).ok)
        XCTAssertGreaterThan(state.slime.emotions[.joy], 0.5)
        XCTAssertEqual(state.slime.emotions[.anger], 0, accuracy: 0.01)

        var grumpy = hatchedState()
        grumpy.slime.personality = Personality(playful: 0.2, cuddly: 0.5, foodie: 0.5, sleepy: 0.8, curious: 0.2)
        XCTAssertTrue(grumpy.slime.personality.dislikesBaths)
        grumpy.slime.needs.clean = 30
        grumpy.slime.emotions = Emotions()
        XCTAssertTrue(grumpy.bath(now: noon.addingTimeInterval(60)).ok)
        XCTAssertGreaterThan(grumpy.slime.emotions[.anger], 0.2)
        XCTAssertTrue(grumpy.memory.recentLines.last?.hasPrefix("bathGrumpy.") ?? false)
        // It doesn't last.
        grumpy.tick(now: noon.addingTimeInterval(2 * 3600), calendar: berlin)
        XCTAssertLessThan(grumpy.slime.emotions[.anger], 0.1)
    }

    func testGamesExciteAndRecordsDelight() {
        var state = hatchedState()
        state.slime.emotions = Emotions()
        _ = state.finish(GameResult(game: .bubblePop, score: 100, session: UUID(), finishedAt: noon.addingTimeInterval(60)))
        XCTAssertGreaterThan(state.slime.emotions[.excitement], 0.25)

        let before = state.slime.emotions
        let record = state.finish(GameResult(game: .bubblePop, score: 900, session: UUID(),
                                             finishedAt: noon.addingTimeInterval(120)))
        XCTAssertTrue(record.events.contains(.record(.bubblePop, 900)))
        XCTAssertGreaterThan(state.slime.emotions[.joy], before[.joy] + 0.2)
        XCTAssertGreaterThan(state.slime.emotions[.excitement], before[.excitement] + 0.2)
    }

    func testFriendsCheerItUp() {
        var state = hatchedState()
        state.slime.emotions = Emotions()
        _ = state.friendMoment(.friendVisit, friend: "Mochi", now: noon.addingTimeInterval(60))
        XCTAssertGreaterThan(state.slime.emotions[.joy], 0.45)
        XCTAssertGreaterThan(state.slime.emotions[.excitement], 0.2)
        XCTAssertEqual(state.memory.last(.visit)?.detail, "Mochi")
        XCTAssertEqual(state.progress.stats.visits, 1)

        state.slime.emotions = Emotions()
        _ = state.friendMoment(.friendHug, friend: "Mochi", now: noon.addingTimeInterval(120))
        XCTAssertGreaterThan(state.slime.emotions[.love], 0.15)
        XCTAssertEqual(state.progress.stats.hugs, 1)
    }

    func testPhoneEventsStirFeelings() {
        var state = hatchedState()
        state.slime.emotions = Emotions()
        _ = state.handle(.batteryCritical, now: noon.addingTimeInterval(60))
        XCTAssertEqual(state.slime.emotions[.fear], 0.3, accuracy: 1e-9)
        for event in PhoneEvent.allCases {
            if let feeling = event.feeling { XCTAssertGreaterThan(feeling.amount, 0, event.rawValue) }
        }
    }
}

final class ChatterTests: XCTestCase {
    func testNeverRepeatsOneOfItsLast12Lines() {
        var state = hatchedState()
        var said: [String] = []
        for i in 0..<300 {
            let text = state.chatter(now: noon.addingTimeInterval(Double(i) * 11), calendar: berlin)
            XCTAssertFalse(text.isEmpty)
            XCTAssertFalse(text.contains("{"), text)
            let id = state.memory.recentLines.last ?? ""
            XCTAssertFalse(said.suffix(GameState.chatterMemory).contains(id), "\(id) at \(i)")
            XCTAssertEqual(state.slime.say?.lineID, id)
            said.append(id)
        }
        XCTAssertGreaterThan(Set(said).count, 40)
    }

    func testAsleepItOnlyMumbles() {
        var state = hatchedState()
        state.slime.isAsleep = true
        for i in 0..<40 {
            _ = state.chatter(now: noon.addingTimeInterval(Double(i) * 11), calendar: berlin)
            XCTAssertTrue(state.memory.recentLines.last?.hasPrefix("sleeping.") ?? false)
        }
    }

    func testAStrongFeelingComesUpOften() {
        var state = hatchedState()
        var excited = 0
        for i in 0..<60 {
            state.slime.emotions = Emotions()
            state.slime.emotions[.excitement] = 1
            _ = state.chatter(now: noon.addingTimeInterval(Double(i) * 11), calendar: berlin)
            if state.memory.recentLines.last?.hasPrefix("feelExcited.") == true { excited += 1 }
        }
        XCTAssertGreaterThan(excited, 10)
    }

    func testLowNeedsComeUpGently() {
        var state = hatchedState()
        state.slime.needs.hunger = 5
        var hungry = 0
        for i in 0..<40 {
            _ = state.chatter(now: noon.addingTimeInterval(Double(i) * 11), calendar: berlin)
            if state.memory.recentLines.last?.hasPrefix("wishHunger.") == true { hungry += 1 }
        }
        XCTAssertGreaterThan(hungry, 4)
    }

    func testTopicsFollowTheWorldAndWhatJustHappened() {
        var state = hatchedState()
        state.inventory.habitats.insert(.beach)
        state.inventory.habitat = .beach
        let summerEvening = state.chatterTopics(now: date(2026, 7, 4, 21), calendar: berlin).map(\.category)
        XCTAssertTrue(summerEvening.contains(.beachTalk))
        XCTAssertTrue(summerEvening.contains(.summer))
        XCTAssertTrue(summerEvening.contains(.evening))
        XCTAssertFalse(summerEvening.contains(.afterFood))

        XCTAssertTrue(state.feed(.apple, now: noon.addingTimeInterval(60)).ok)
        XCTAssertTrue(state.chatterTopics(now: noon.addingTimeInterval(5 * 60), calendar: berlin)
            .map(\.category).contains(.afterFood))
        XCTAssertFalse(state.chatterTopics(now: noon.addingTimeInterval(60 * 60), calendar: berlin)
            .map(\.category).contains(.afterFood))

        state.slime.isAsleep = true
        XCTAssertEqual(state.chatterTopics(now: noon, calendar: berlin).map(\.category), [.sleeping])
    }

    func testItTalksAboutTheWeatherItCanSee() {
        // Every three-hour block of two weeks in July and two in December.
        let starts = [date(2026, 7, 1, 0), date(2026, 12, 1, 0)]
        let moments = starts.flatMap { start in (0..<(14 * 8)).map { start.addingTimeInterval(Double($0) * 3 * 3600) } }
        let weatherTalk: Set<LineCategory> = [.rainTalk, .snowTalk, .cloudTalk]
        var seen: Set<Sky> = []
        var state = hatchedState()
        for habitat in Habitat.allCases {
            state.inventory.habitats.insert(habitat)
            state.inventory.habitat = habitat
            for moment in moments {
                let sky = Sky.at(moment, calendar: berlin)
                seen.insert(sky)
                let talk = Set(state.chatterTopics(now: moment, calendar: berlin).map(\.category)).intersection(weatherTalk)
                if habitat == .space || habitat == .crystal || sky == .clear {
                    XCTAssertTrue(talk.isEmpty, "\(habitat.rawValue) \(sky.rawValue)")
                } else {
                    XCTAssertEqual(talk, Set([sky.talk].compactMap { $0 }), "\(habitat.rawValue) \(sky.rawValue)")
                }
            }
        }
        XCTAssertEqual(seen, Set(Sky.allCases))
    }

    func testNoLineGuiltTrips() {
        let guilt = ["you forgot", "you left me", "abandon", "neglect", "your fault", "why didn't you", "you never",
                     "all alone", "you don't care", "ignored"]
        for (category, lines) in Dialogue.lines {
            for line in lines {
                let text = line.text.lowercased()
                for phrase in guilt {
                    XCTAssertFalse(text.contains(phrase), "\(category.rawValue): \(line.text)")
                }
            }
        }
    }
}
