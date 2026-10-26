import XCTest
@testable import Slimy

private let berlin: Calendar = {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "Europe/Berlin")!
    return c
}()

/// 4 May 2026, noon in Berlin: no season, holiday or birthday.
private let noon = berlin.date(from: DateComponents(year: 2026, month: 5, day: 4, hour: 12))!

private func hatchedState() -> GameState {
    var state = GameState()
    state.hatch(name: "Testi", skin: .lime, seed: 42, now: noon)
    state.settings.sleep.auto = false
    return state
}

/// Round sessions that are the same on every run.
private func session(_ i: Int) -> UUID {
    UUID(uuidString: String(format: "00000000-0000-4000-8000-%012X", i))!
}

final class CrateRateTests: XCTestCase {
    func testDropRatesAddUpToOneRarestLast() {
        for kind in CrateKind.allCases {
            let rates = Crates.dropRates(kind)
            XCTAssertEqual(rates.reduce(0.0) { $0 + $1.chance }, 1, accuracy: 1e-9, kind.rawValue)
            for row in rates { XCTAssertGreaterThan(row.chance, 0, "\(kind) \(row.rarity)") }
            let order = rates.map { $0.rarity }
            XCTAssertEqual(order, order.sorted(), kind.rawValue)
            XCTAssertEqual(Set(order).count, order.count, kind.rawValue)
        }
    }

    func testLegendaryOnlyFromGoldenAndMysteryCrates() {
        func legendary(_ kind: CrateKind) -> Double {
            Crates.dropRates(kind).first { $0.rarity == .legendary }?.chance ?? 0
        }
        XCTAssertEqual(legendary(.wooden), 0)
        XCTAssertEqual(legendary(.golden), 0.006, accuracy: 1e-12)
        XCTAssertEqual(legendary(.mystery), 0.02, accuracy: 1e-12)
    }

    func testPricesAndNames() {
        XCTAssertEqual(CrateKind.wooden.price, 80)
        XCTAssertEqual(CrateKind.golden.price, 300)
        XCTAssertNil(CrateKind.mystery.price)
        for kind in CrateKind.allCases {
            XCTAssertEqual(kind.sprite, "crate.\(kind.rawValue)")
            XCTAssertFalse(kind.title.isEmpty)
            XCTAssertTrue(kind.blurb.hasSuffix("."))
        }
        XCTAssertLessThan(Rarity.common, .rare)
        XCTAssertLessThan(Rarity.epic, .legendary)
        XCTAssertEqual(Set(Rarity.allCases.map(\.tint)).count, Rarity.allCases.count)
    }

    func testRollsLandOnTheRatesAndOnlyTheAxolotlIsLegendary() {
        let state = hatchedState()
        let rolls = 50_000
        for kind in CrateKind.allCases {
            var rng = SplitMix(seed: SplitMix.seed("rates-\(kind.rawValue)"))
            var counts: [Rarity: Int] = [:]
            for _ in 0..<rolls {
                let reward = Crates.roll(kind, for: state, using: &rng)
                counts[reward.rarity, default: 0] += 1
                if reward.rarity == .legendary {
                    XCTAssertEqual(reward.kind, .skin(.axolotl))
                }
                if case .skin(let skin) = reward.kind, skin.isSecret {
                    XCTAssertEqual(reward.rarity, .legendary)
                }
            }
            for row in Crates.dropRates(kind) {
                let seen = Double(counts[row.rarity] ?? 0) / Double(rolls)
                XCTAssertEqual(seen, row.chance, accuracy: max(0.004, row.chance * 0.1), "\(kind) \(row.rarity)")
            }
            if kind == .wooden { XCTAssertNil(counts[.legendary]) }
        }
    }

    func testTheSameSeedGivesTheSameReward() {
        let state = hatchedState()
        var a = SplitMix(seed: 7)
        var b = SplitMix(seed: 7)
        for _ in 0..<50 {
            XCTAssertEqual(Crates.roll(.golden, for: state, using: &a), Crates.roll(.golden, for: state, using: &b))
        }
        var first = state
        first.addCrate(.mystery)
        var second = first
        let x = first.openCrate(.mystery, now: noon, seed: 99)?.reward
        let y = second.openCrate(.mystery, now: noon, seed: 99)?.reward
        XCTAssertNotNil(x)
        XCTAssertEqual(x, y)
    }

    func testCratesHoldShopItemsAndPlainColoursOnly() {
        for rarity in [Rarity.common, .rare, .epic] {
            XCTAssertFalse(Crates.items(rarity).isEmpty, rarity.rawValue)
            for item in Crates.items(rarity) {
                if case .level = item.unlock {} else { XCTFail("\(item.id) isn't sold by level") }
            }
        }
        XCTAssertTrue(Crates.items(.legendary).isEmpty)
        XCTAssertFalse(Crates.colours.isEmpty)
        XCTAssertFalse(Crates.colours.contains(.axolotl))
        XCTAssertFalse(Crates.colours.contains { Skin.hatchChoices.contains($0) })
        XCTAssertTrue(Crates.foods(.rare, level: 1).isEmpty)
        XCTAssertFalse(Crates.foods(.common, level: 1).isEmpty)
        for food in Crates.foods(.rare, level: 30) { XCTAssertLessThanOrEqual(food.unlockLevel, 30) }
    }
}

final class CrateRewardTests: XCTestCase {
    func testBuyingCostsCoinsAndMysteryCratesAreNotForSale() {
        var state = hatchedState()
        state.wallet.coins = 100
        XCTAssertTrue(state.buyCrate(.wooden, now: noon))
        XCTAssertEqual(state.wallet.coins, 20)
        XCTAssertEqual(state.crateCount(.wooden), 1)
        XCTAssertFalse(state.buyCrate(.wooden, now: noon))
        XCTAssertFalse(state.buyCrate(.golden, now: noon))
        XCTAssertFalse(state.buyCrate(.mystery, now: noon))
        XCTAssertEqual(state.wallet.coins, 20)
        XCTAssertEqual(state.cratesWaiting, 1)
        XCTAssertEqual(state.progress.stats.purchases, 1)
    }

    func testCratesStackUpTo99() {
        var state = hatchedState()
        XCTAssertTrue(state.addCrate(.wooden, 150))
        XCTAssertEqual(state.crateCount(.wooden), Crates.maxStack)
        XCTAssertFalse(state.addCrate(.wooden))
        state.wallet.coins = 10_000
        XCTAssertFalse(state.buyCrate(.wooden, now: noon))
        XCTAssertEqual(state.wallet.coins, 10_000)
    }

    func testOpeningUsesACrateAndRemembersTheFind() throws {
        var state = hatchedState()
        XCTAssertNil(state.openCrate(.wooden, now: noon, seed: 1))
        state.addCrate(.wooden, 3)
        let opened = try XCTUnwrap(state.openCrate(.wooden, now: noon.addingTimeInterval(60), seed: 1))
        XCTAssertTrue(opened.outcome.ok)
        XCTAssertEqual(state.crateCount(.wooden), 2)
        XCTAssertEqual(state.progress.stats.cratesOpened, 1)
        XCTAssertNotNil(state.progress.achievements["firstCrate"])
        XCTAssertTrue(opened.outcome.events.contains(.achievement("firstCrate")))
        let entry = try XCTUnwrap(state.memory.last(.crate))
        let find = try XCTUnwrap(CrateFind(detail: entry.detail))
        XCTAssertEqual(find.title, opened.reward.title)
        XCTAssertEqual(find.rarity, opened.reward.rarity)
        XCTAssertEqual(find.crate, .wooden)
        XCTAssertGreaterThan(state.slime.emotions[.excitement], 0.25)
        XCTAssertNotNil(state.slime.say)

        // A wooden crate can hold another one, so open until none is left.
        state.addCrate(.wooden, 97)
        var opens = 0
        while state.crateCount(.wooden) > 0, opens < 1000 {
            _ = state.openCrate(.wooden, now: noon.addingTimeInterval(120 + Double(opens)), seed: UInt64(opens + 2))
            opens += 1
        }
        XCTAssertGreaterThanOrEqual(opens, 99)
        XCTAssertEqual(state.crateCount(.wooden), 0)
        XCTAssertNil(state.crates[.wooden])
        XCTAssertNotNil(state.progress.achievements["crates50"])
    }

    func testEveryKindOfRewardIsHandedOut() {
        var state = hatchedState()
        let coins = state.wallet.coins
        let earned = state.wallet.earnedTotal
        state.receive(CrateReward(kind: .coins(120), rarity: .rare, title: "120 coins", icon: "coin", convertedCoins: nil))
        XCTAssertEqual(state.wallet.coins, coins + 120)
        XCTAssertEqual(state.wallet.earnedTotal, earned + 120)

        state.receive(CrateReward(kind: .food(.cookie, 3), rarity: .common, title: "Cookie × 3", icon: "cookie",
                                  convertedCoins: nil))
        XCTAssertEqual(state.servings(of: .cookie), 3)

        state.receive(CrateReward(kind: .item("hat.crown"), rarity: .epic, title: "Crown", icon: "hat.crown",
                                  convertedCoins: nil))
        XCTAssertTrue(state.owns("hat.crown"))

        state.receive(CrateReward(kind: .skin(.grape), rarity: .epic, title: "Grape colour", icon: "gem",
                                  convertedCoins: nil))
        XCTAssertTrue(state.slime.isUnlocked(.grape))
        XCTAssertEqual(state.slime.skin, .lime)

        state.receive(CrateReward(kind: .crate(.golden), rarity: .epic, title: "Golden crate", icon: "crate.golden",
                                  convertedCoins: nil))
        XCTAssertEqual(state.crateCount(.golden), 1)
    }

    func testPantryServingsAreFreeAndComeFirst() {
        var state = hatchedState()
        state.progress.achievements["firstMeal"] = noon
        state.slime.needs.hunger = 20
        state.inventory.pantry[.cake] = 2
        let coins = state.wallet.coins
        // Cake opens at level 7 in the kitchen, but a serving can always be eaten.
        XCTAssertTrue(state.feed(.cake, now: noon.addingTimeInterval(60)).ok)
        XCTAssertEqual(state.wallet.coins, coins)
        XCTAssertEqual(state.servings(of: .cake), 1)
        XCTAssertTrue(state.feed(.cake, now: noon.addingTimeInterval(120)).ok)
        XCTAssertEqual(state.servings(of: .cake), 0)
        XCTAssertNil(state.inventory.pantry[.cake])
        state.slime.needs.hunger = 20
        XCTAssertFalse(state.feed(.cake, now: noon.addingTimeInterval(180)).ok)
    }

    func testDuplicatesTurnIntoCoins() {
        var state = hatchedState()
        for item in Crates.items(.common) { state.inventory.owned.insert(item.id) }
        var rng = SplitMix(seed: 3)
        var items = 0
        for _ in 0..<300 {
            let reward = Crates.reward(.wooden, rarity: .common, for: state, using: &rng)
            if case .item(let id) = reward.kind {
                items += 1
                let price = Catalog.item(id)?.price ?? 0
                XCTAssertEqual(reward.convertedCoins, Crates.duplicateCoins(price: price, rarity: .common), id)
                XCTAssertTrue(reward.isDuplicate)
            } else {
                XCTAssertNil(reward.convertedCoins)
            }
        }
        XCTAssertGreaterThan(items, 0)

        state.slime.colours.insert(.grape)
        var colours = 0
        for _ in 0..<300 {
            let reward = Crates.reward(.golden, rarity: .epic, for: state, using: &rng)
            guard case .skin(let skin) = reward.kind else { continue }
            colours += 1
            XCTAssertEqual(reward.convertedCoins, state.slime.isUnlocked(skin) ? Crates.duplicateColourCoins : nil)
        }
        XCTAssertGreaterThan(colours, 0)

        let coins = state.wallet.coins
        let twin = CrateReward(kind: .item("hat.party"), rarity: .common, title: "Party hat", icon: "hat.party",
                               convertedCoins: 15)
        state.receive(twin)
        XCTAssertEqual(state.wallet.coins, coins + 15)
        XCTAssertTrue(twin.detail.contains("15 coins"))
        XCTAssertNil(twin.bonusCrate)
    }

    func testTheAxolotlTransformsTheSlimeOnceThenPaysAndBringsACrate() {
        var state = hatchedState()
        let first = Crates.legendary(for: state)
        XCTAssertEqual(first.kind, .skin(.axolotl))
        XCTAssertEqual(first.rarity, .legendary)
        XCTAssertNil(first.convertedCoins)
        XCTAssertNil(first.bonusCrate)
        state.receive(first)
        XCTAssertEqual(state.slime.skin, .axolotl)
        XCTAssertTrue(state.slime.isUnlocked(.axolotl))

        let again = Crates.legendary(for: state)
        XCTAssertEqual(again.convertedCoins, 500)
        XCTAssertEqual(again.bonusCrate, .golden)
        XCTAssertTrue(again.detail.contains("golden crate"))
        let coins = state.wallet.coins
        let golden = state.crateCount(.golden)
        state.receive(again)
        XCTAssertEqual(state.wallet.coins, coins + 500)
        XCTAssertEqual(state.crateCount(.golden), golden + 1)
    }

    func testFindingTheLegendaryInACrate() throws {
        var state = hatchedState()
        state.addCrate(.mystery)
        let seed = try XCTUnwrap((UInt64(1)...20_000).first { seed in
            var rng = SplitMix(seed: seed)
            return Crates.rarity(.mystery, roll: rng.unit()) == .legendary
        })
        let opened = try XCTUnwrap(state.openCrate(.mystery, now: noon.addingTimeInterval(60), seed: seed))
        XCTAssertEqual(opened.reward.rarity, .legendary)
        XCTAssertEqual(state.slime.skin, .axolotl)
        XCTAssertNotNil(state.progress.achievements["legendary"])
        XCTAssertNotNil(state.progress.achievements["epicFind"])
        XCTAssertEqual(state.slime.emotions[.excitement], 1, accuracy: 0.001)
        XCTAssertTrue(state.memory.last(.crate)?.keepsake ?? false)
        XCTAssertEqual(state.memory.last(.crate)?.summary, "Legendary find: \(Skin.axolotl.title)")
        XCTAssertTrue(state.memory.recentLines.contains { $0.hasPrefix("crateLegendary.") })
    }

    func testWearingTheSecretColourDoesNotEarnTheSecretAchievement() {
        var state = hatchedState()
        // A shared slime can arrive wearing it: the secret is for finding it yourself.
        state.slime.colours.insert(.axolotl)
        state.slime.skin = .axolotl
        state.checkAchievements(now: noon)
        XCTAssertNil(state.progress.achievements["legendary"])
    }

    func testRewardsSurviveCoding() throws {
        let rewards = [
            CrateReward(kind: .food(.cookie, 3), rarity: .common, title: "Cookie × 3", icon: "cookie", convertedCoins: nil),
            CrateReward(kind: .skin(.axolotl), rarity: .legendary, title: "Axolotl", icon: "axolotl", convertedCoins: 500),
            CrateReward(kind: .crate(.golden), rarity: .epic, title: "Golden crate", icon: "crate.golden", convertedCoins: nil),
            CrateReward(kind: .coins(40), rarity: .common, title: "40 coins", icon: "coin", convertedCoins: nil),
            CrateReward(kind: .item("hat.crown"), rarity: .epic, title: "Crown", icon: "hat.crown", convertedCoins: 110),
        ]
        let back = try JSONDecoder().decode([CrateReward].self, from: JSONEncoder().encode(rewards))
        XCTAssertEqual(back, rewards)
    }

    func testCrateMemoriesReadBack() {
        let find = CrateFind(crate: .golden, rarity: .epic, icon: "hat.crown", title: "Crown")
        XCTAssertEqual(CrateFind(detail: find.detail), find)
        XCTAssertNil(CrateFind(detail: "nonsense"))
        XCTAssertNil(CrateFind(detail: "plastic|epic|x|Crown"))
        let entry = MemoryEntry(kind: .crate, at: noon, detail: find.detail, keepsake: true)
        XCTAssertEqual(entry.summary, "Golden crate: Crown")
        XCTAssertEqual(entry.icon, "hat.crown")
    }
}

final class CrateSourceTests: XCTestCase {
    func testEveryFifthLevelBringsAMysteryCrateOnce() {
        var state = hatchedState()
        state.slime.xp = Slime.xpNeeded(for: 5) - 1
        var line = ""
        let events = state.gainXP(1, now: noon, line: &line)
        XCTAssertTrue(events.contains(.levelUp(5)))
        XCTAssertTrue(events.contains(.crate(.mystery, .levelUp)))
        XCTAssertEqual(state.crateCount(.mystery), 1)

        let six = state.gainXP(Slime.xpNeeded(for: 6) - state.slime.xp, now: noon, line: &line)
        XCTAssertTrue(six.contains(.levelUp(6)))
        XCTAssertFalse(six.contains(.crate(.mystery, .levelUp)))

        // Level 5 again (a shared slime's xp changed) pays nothing twice.
        state.slime.xp = Slime.xpNeeded(for: 5) - 1
        _ = state.gainXP(1, now: noon, line: &line)
        XCTAssertEqual(state.crateCount(.mystery), 1)

        let ten = state.gainXP(Slime.xpNeeded(for: 10) - state.slime.xp, now: noon, line: &line)
        XCTAssertTrue(ten.contains(.crate(.mystery, .levelUp)))
        XCTAssertEqual(state.crateCount(.mystery), 2)
    }

    func testGreatRoundsSometimesBringACrateDecidedByTheSession() throws {
        let sessions = (0..<5000).map(session)
        let lucky = sessions.filter(Crates.dropsAfterRound)
        // About 4 in 100.
        XCTAssertGreaterThan(lucky.count, 120)
        XCTAssertLessThan(lucky.count, 290)

        let win = try XCTUnwrap(lucky.first)
        let dry = try XCTUnwrap(sessions.first { !Crates.dropsAfterRound($0) })
        var state = hatchedState()
        let great = GameResult(game: .bubblePop, score: 500, session: win, finishedAt: noon.addingTimeInterval(60))
        let out = state.finish(great)
        XCTAssertTrue(out.events.contains(.crate(.mystery, .greatRound)))
        XCTAssertEqual(state.crateCount(.mystery), 1)
        // The same round again changes nothing.
        XCTAssertFalse(state.finish(great).ok)
        XCTAssertEqual(state.crateCount(.mystery), 1)

        var other = hatchedState()
        // A lucky session but not a great round: nothing.
        _ = other.finish(GameResult(game: .bubblePop, score: 100, session: win, finishedAt: noon.addingTimeInterval(60)))
        XCTAssertEqual(other.crateCount(.mystery), 0)
        // A great round whose session doesn't drop: nothing.
        _ = other.finish(GameResult(game: .bubblePop, score: 500, session: dry, finishedAt: noon.addingTimeInterval(120)))
        XCTAssertEqual(other.crateCount(.mystery), 0)
    }

    func testSomeAchievementsBringACrate() {
        var state = hatchedState()
        state.progress.stats.goodGames = 25
        let events = state.checkAchievements(now: noon)
        XCTAssertTrue(events.contains(.achievement("arcadeStar")))
        XCTAssertEqual(Achievements.achievement("arcadeStar")?.crate, .mystery)
        XCTAssertEqual(state.crateCount(.mystery), 1)
        // Only once.
        state.checkAchievements(now: noon)
        XCTAssertEqual(state.crateCount(.mystery), 1)
    }

    func testCrateSourcesExplainThemselves() {
        for source in [CrateSource.shop, .dailyGift, .levelUp, .greatRound, .achievement, .crate] {
            XCTAssertTrue(source.reason.hasSuffix("."), source.rawValue)
        }
    }
}
