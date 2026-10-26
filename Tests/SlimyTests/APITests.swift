import XCTest
@testable import Slimy

/// The app must read exactly what server/ sends (see server/README.md).
final class APIDecodingTests: XCTestCase {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try HyprnodeAPI.decoder.decode(T.self, from: Data(json.utf8))
    }

    private func json<T: Encodable>(_ value: T) throws -> [String: Any] {
        let data = try HyprnodeAPI.encoder.encode(value)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testFriendWithHiddenMood() throws {
        let friend = try decode(Friend.self, """
        {"id": "dev_abc", "ownerName": "Anna", "love": 42, "inLove": false, "since": "2026-10-25T12:00:00Z",
         "slime": {"ownerName": "Anna", "name": "Blob", "skin": "ocean", "level": 5, "stage": "young",
                   "mood": "idle", "happiness": null, "equipped": {"hat": "hat.party"}, "updatedAt": "2026-10-25T12:00:00Z"}}
        """)
        XCTAssertEqual(friend.slime.name, "Blob")
        XCTAssertNil(friend.slime.happiness)
        XCTAssertEqual(friend.stage, .young)
        XCTAssertEqual(friend.slime.equipped?.hat, "hat.party")
    }

    func testFriendFromAnOlderServerWithoutStage() throws {
        let friend = try decode(Friend.self, """
        {"id": "dev_abc", "ownerName": "Anna", "love": 0, "inLove": false, "since": "2026-10-25T12:00:00Z",
         "slime": {"ownerName": "Anna", "name": "Blob", "skin": "lime", "level": 13, "mood": "sad",
                   "happiness": 30, "updatedAt": "2026-10-25T12:00:00Z"}}
        """)
        XCTAssertEqual(friend.stage, .grown)
    }

    func testFriendWithLooksFromANewerApp() throws {
        let friend = try decode(Friend.self, """
        {"id": "dev_abc", "ownerName": "Anna", "love": 5, "inLove": false, "since": "2026-10-25T12:00:00Z",
         "slime": {"ownerName": "Anna", "name": "Blob", "skin": "aSkinFromTheFuture", "level": 3, "stage": "ancient",
                   "mood": "ecstatic", "happiness": 70, "equipped": {"hat": 5}, "updatedAt": "2026-10-25T12:00:00Z"}}
        """)
        XCTAssertEqual(friend.slime.name, "Blob")
        XCTAssertEqual(friend.slime.skin, .lime)
        XCTAssertEqual(friend.slime.mood, .idle)
        XCTAssertNil(friend.slime.stage)
        XCTAssertNil(friend.slime.equipped)
        XCTAssertEqual(friend.stage, Stage.forLevel(3))
        XCTAssertEqual(friend.slime.happiness, 70)
    }

    func testFriendDatesRoundTripWithoutMilliseconds() throws {
        let friend = try decode(Friend.self, """
        {"id": "dev_abc", "ownerName": "Anna", "love": 10, "inLove": false, "since": "2026-10-25T12:00:00Z",
         "slime": {"ownerName": "Anna", "name": "Blob", "skin": "lime", "level": 2, "stage": "baby",
                   "mood": "idle", "happiness": 80, "updatedAt": "2026-10-25T12:30:05Z"}}
        """)
        let text = String(decoding: try HyprnodeAPI.encoder.encode(friend), as: UTF8.self)
        XCTAssertTrue(text.contains("\"2026-10-25T12:00:00Z\""), text)
        XCTAssertTrue(text.contains("\"2026-10-25T12:30:05Z\""), text)
        XCTAssertEqual(try HyprnodeAPI.decoder.decode(Friend.self, from: Data(text.utf8)), friend)
    }

    func testInboxToleratesUnknownKindsAndValues() throws {
        let page = try decode(InboxPage.self, """
        {"cursor": "18", "events": [
          {"id": "17", "friendId": "dev_a", "friendName": "Mochi", "kind": "gift", "food": "cake",
           "preset": null, "challengeId": null, "requestId": null, "createdAt": "2026-10-25T12:00:00Z"},
          {"id": "18", "friendId": "dev_a", "kind": "somethingNew", "food": "pizza", "preset": "shout",
           "createdAt": "2026-10-25T12:00:01Z"}
        ]}
        """)
        XCTAssertEqual(page.events.count, 2)
        XCTAssertEqual(page.events[0].food, .cake)
        XCTAssertNil(page.events[1].food)
        XCTAssertNil(page.events[1].preset)
        XCTAssertEqual(page.cursor, "18")
    }

    func testChallenge() throws {
        let c = try decode(Challenge.self, """
        {"id": "ch_1", "game": "bubblePop", "seed": "12345678901234", "friendId": "dev_b", "friendName": "Mochi",
         "sentByMe": false, "myScore": null, "theirScore": 300, "createdAt": "2026-10-25T12:00:00Z",
         "expiresAt": "2999-10-28T12:00:00Z", "answeredAt": null}
        """)
        XCTAssertEqual(c.seedValue, 12345678901234)
        XCTAssertTrue(c.isOpenForMe)
    }

    func testJoinResults() throws {
        let pending = try decode(JoinResult.self, """
        {"status": "pending", "request": {"id": "req_1", "deviceId": "dev_a", "ownerName": "Anna", "slime": null,
         "createdAt": "2026-10-25T12:00:00Z"}}
        """)
        XCTAssertEqual(pending.status, "pending")
        XCTAssertNil(pending.friend)
        XCTAssertEqual(pending.request?.id, "req_1")
    }

    func testDatesAreSentWithoutMilliseconds() throws {
        let data = try HyprnodeAPI.encoder.encode(["at": Date(timeIntervalSince1970: 1_761_393_600.123)])
        let text = String(decoding: data, as: UTF8.self)
        XCTAssertFalse(text.contains(".123"))
        XCTAssertTrue(text.contains("2025-10-25T12:00:00Z"), text)
    }

    func testPairCode() throws {
        let code = try decode(PairCode.self, #"{"code": "ABC234", "expiresAt": "2026-10-25T12:10:00Z"}"#)
        XCTAssertEqual(code.code, "ABC234")
        XCTAssertEqual(code.expiresAt, Date(timeIntervalSince1970: 1_761_394_200 + 365 * 86400))
    }

    func testTransientErrors() {
        XCTAssertTrue(APIError.offline.isTransient)
        XCTAssertTrue(APIError.server(status: 503, message: nil).isTransient)
        XCTAssertTrue(APIError.server(status: 429, message: nil).isTransient)
        XCTAssertFalse(APIError.server(status: 404, message: "weg").isTransient)
    }

    func testDailyLimitsAreShownNotQueued() {
        let capped = APIError.server(status: 429, message: "That's enough gifts for today. More tomorrow.")
        XCTAssertTrue(capped.isRateLimited)
        XCTAssertFalse(capped.shouldQueue)
        XCTAssertTrue(APIError.offline.shouldQueue)
        XCTAssertTrue(APIError.notReady.shouldQueue)
        XCTAssertTrue(APIError.server(status: 502, message: nil).shouldQueue)
        XCTAssertFalse(APIError.server(status: 409, message: nil).shouldQueue)
    }

    func testBannedAndUnknownDevices() {
        let banned = APIError.server(status: 403, message: "This device can't use online features anymore.")
        XCTAssertTrue(banned.isBanned)
        XCTAssertFalse(banned.isTransient)
        XCTAssertEqual(banned.localizedDescription, "This device can't use online features anymore.")
        XCTAssertTrue(APIError.server(status: 401, message: nil).isUnauthorized)
        XCTAssertFalse(APIError.server(status: 401, message: nil).isBanned)
        XCTAssertNil(APIError.offline.status)
        XCTAssertFalse(APIError.offline.isBanned)
        XCTAssertEqual(APIError.server(status: 400, message: "Please choose a different name.").status, 400)
        XCTAssertFalse(NotOnlineError().localizedDescription.isEmpty)
    }

    func testRefusedNamesAreFinal() {
        let refused = APIError.server(status: 400, message: "Please choose a different name.")
        XCTAssertTrue(refused.isRefused)
        XCTAssertFalse(refused.isTransient)
        XCTAssertFalse(refused.shouldQueue)
        XCTAssertEqual(refused.localizedDescription, "Please choose a different name.")
        XCTAssertFalse(APIError.offline.isRefused)
        XCTAssertFalse(APIError.server(status: 403, message: nil).isRefused)
    }

    func testSlowAnswersCanBeTriedAgain() {
        // A request that timed out becomes a 504 with a readable message.
        let slow = APIError.server(status: 504, message: HyprnodeAPI.slowServerAnswer)
        XCTAssertTrue(slow.isTransient)
        XCTAssertTrue(slow.shouldQueue)
        XCTAssertEqual(slow.localizedDescription, HyprnodeAPI.slowServerAnswer)
        XCTAssertGreaterThan(HyprnodeAPI.chatTimeout, 36)
        XCTAssertEqual(HyprnodeAPI.defaultTimeout, 20)
    }

    // MARK: Reports

    func testReportReasonsMatchTheServer() {
        XCTAssertEqual(ReportReason.allCases.map(\.rawValue), ["name", "harassment", "spam", "chat", "other"])
        XCTAssertFalse(ReportReason.forPeople.contains(.chat))
        XCTAssertEqual(ReportReason.forPeople.count, 4)
        for reason in ReportReason.allCases {
            XCTAssertFalse(reason.title.isEmpty)
        }
    }

    func testPersonReportLeavesOutEmptyFields() throws {
        let body = try json(HyprnodeAPI.ReportBody(deviceId: "dev_a", reason: .harassment, note: "Keeps sending requests", text: nil))
        XCTAssertEqual(body["deviceId"] as? String, "dev_a")
        XCTAssertEqual(body["reason"] as? String, "harassment")
        XCTAssertEqual(body["note"] as? String, "Keeps sending requests")
        XCTAssertNil(body["text"])
        XCTAssertEqual(body.count, 3)
    }

    func testChatReportHasNoDevice() throws {
        let body = try json(HyprnodeAPI.ReportBody(deviceId: nil, reason: .chat, note: nil, text: "a reply"))
        XCTAssertEqual(body["reason"] as? String, "chat")
        XCTAssertEqual(body["text"] as? String, "a reply")
        XCTAssertNil(body["deviceId"])
        XCTAssertNil(body["note"])
    }

    // MARK: Records and leaderboard

    func testRecordsAreSentByGameIDWithoutTheDailyGame() throws {
        let wire = HyprnodeAPI.wireRecords([
            .bubblePop: 120, .daily: 50, .colorMatch: 0, .slimeJump: 2_000_000, .basketball: -3,
        ])
        XCTAssertEqual(wire, ["bubblePop": 120, "slimeJump": HyprnodeAPI.recordCap])

        let body = try json(HyprnodeAPI.RecordsBody(records: wire))
        let records = try XCTUnwrap(body["records"] as? [String: Int])
        XCTAssertEqual(records, ["bubblePop": 120, "slimeJump": 1_000_000])
    }

    func testLeaderboard() throws {
        let games = try decode(Leaderboard.self, """
        {"games": [
          {"game": "bubblePop", "entries": [
            {"deviceId": "dev_a", "ownerName": "Anna", "slimeName": "Blob", "skin": "ocean", "stage": "young", "best": 300, "me": false},
            {"deviceId": "dev_me", "ownerName": "Sam", "slimeName": "Mochi", "skin": "lime", "stage": "baby", "best": 250, "me": true},
            {"deviceId": "dev_c", "ownerName": "Chris", "slimeName": "Jelly", "skin": "rainbow", "stage": "huge", "best": 250, "me": false}
          ]},
          {"game": "aGameFromTheFuture", "entries": [
            {"deviceId": "dev_a", "ownerName": "Anna", "slimeName": "Blob", "skin": "ocean", "stage": "young", "best": 9, "me": false}
          ]},
          {"game": "colorMatch", "entries": []}
        ]}
        """).games

        XCTAssertEqual(games.map(\.game), [.bubblePop])
        let board = games[0]
        XCTAssertEqual(board.entries.count, 3)
        XCTAssertEqual(board.entries.first?.deviceId, "dev_a")
        let mine = try XCTUnwrap(board.mine)
        XCTAssertEqual(mine.deviceId, "dev_me")
        XCTAssertEqual(board.rank(of: mine), 2)
        let chris = try XCTUnwrap(board.entries.first { $0.deviceId == "dev_c" })
        // Unknown looks fall back instead of hiding the row.
        XCTAssertEqual(chris.skin, .lime)
        XCTAssertEqual(chris.stage, .young)
        XCTAssertEqual(board.rank(of: chris), 2)
    }

    func testLeaderboardSurvivesSaving() throws {
        let board = Leaderboard(games: [
            LeaderboardGame(game: .treasureDig, entries: [
                LeaderboardEntry(deviceId: "dev_me", ownerName: "Sam", slimeName: "Mochi", skin: .grape, stage: .grown,
                                 best: 42, me: true),
            ]),
        ])
        let back = try HyprnodeAPI.decoder.decode(Leaderboard.self, from: HyprnodeAPI.encoder.encode(board))
        XCTAssertEqual(back.games, board.games)
    }

    func testEmptyLeaderboard() throws {
        XCTAssertTrue(try decode(Leaderboard.self, #"{"games": []}"#).games.isEmpty)
    }

    // MARK: Names and invites

    func testNamesAreCleanedLikeTheServerDoes() {
        XCTAssertEqual(HyprnodeAPI.cleanedName("  Anna   Lena \n"), "Anna Lena")
        XCTAssertEqual(HyprnodeAPI.cleanedName(String(repeating: "a", count: 40)).count, HyprnodeAPI.nameLimit)
        XCTAssertEqual(HyprnodeAPI.cleanedName("   "), "")
    }

    func testInviteMessage() {
        XCTAssertEqual(FriendInvite.message(for: "ABC234"),
                       "Be my slime friend in Slimy! Code: ABC234 https://slime.hyprnode.de/f/ABC234")
        XCTAssertEqual(FriendInvite.url(for: "ABC234").absoluteString, "https://slime.hyprnode.de/f/ABC234")
    }

    func testInviteLinks() throws {
        XCTAssertEqual(FriendInvite.code(from: try XCTUnwrap(URL(string: "https://slime.hyprnode.de/f/ABC234"))), "ABC234")
        XCTAssertEqual(FriendInvite.code(from: try XCTUnwrap(URL(string: "https://slime.hyprnode.de/f/abc234"))), "ABC234")
        XCTAssertEqual(FriendInvite.code(from: try XCTUnwrap(URL(string: "slimy://f/XYZ789"))), "XYZ789")
        XCTAssertNil(FriendInvite.code(from: try XCTUnwrap(URL(string: "https://example.com/f/ABC234"))))
        XCTAssertNil(FriendInvite.code(from: try XCTUnwrap(URL(string: "https://slime.hyprnode.de/f/ABC"))))
        XCTAssertNil(FriendInvite.code(from: try XCTUnwrap(URL(string: "https://slime.hyprnode.de/f/ABC0I1"))))
        XCTAssertNil(FriendInvite.code(from: try XCTUnwrap(URL(string: "slimy://home"))))
    }

    func testTypedCodesAreSanitized() {
        XCTAssertEqual(FriendInvite.sanitize(" abc-234 "), "ABC234")
        XCTAssertEqual(FriendInvite.sanitize("ABC2345678"), "ABC234")
        XCTAssertEqual(FriendInvite.sanitize("äöü"), "")
    }
}

final class OfflineQueueTests: XCTestCase {
    func testPendingActionsSurviveSaving() throws {
        let actions = [
            PendingAction(kind: .interaction("dev_a", .gift, .cake), coins: 30),
            PendingAction(kind: .message("dev_a", .goodNight)),
            PendingAction(kind: .coop("dev_b", 120)),
            PendingAction(kind: .challenge("dev_b", .colorMatch, "99", 25)),
            PendingAction(kind: .answer("ch_1", 40)),
            PendingAction(kind: .care(.feed, .apple, nil, nil, Date(timeIntervalSince1970: 1_761_393_600))),
            PendingAction(kind: .care(.play, nil, .bubblePop, 42, Date(timeIntervalSince1970: 1_761_393_660))),
        ]
        let back = try JSONDecoder().decode([PendingAction].self, from: JSONEncoder().encode(actions))
        XCTAssertEqual(back, actions)
        // The idempotency key stays the same across saves, so a resend counts once.
        XCTAssertEqual(back.map(\.key), actions.map(\.key))
        XCTAssertEqual(back[0].friendID, "dev_a")
        XCTAssertNil(back[4].friendID)
        XCTAssertNil(back[5].friendID)
        XCTAssertEqual(back.map(\.isCare), [false, false, false, false, false, true, true])
    }

    func testAQueueFromAnOlderVersionKeepsWhatThisOneStillSends() throws {
        // 1.0 could queue a kiss; 1.1 doesn't send kisses anymore.
        let hug = PendingAction(kind: .interaction("dev_a", .hug, nil))
        let gift = PendingAction(kind: .interaction("dev_b", .gift, .cake), coins: 30)
        var text = String(decoding: try JSONEncoder().encode([hug, gift]), as: UTF8.self)
        text = text.replacingOccurrences(of: "\"hug\"", with: "\"kiss\"")
        XCTAssertTrue(text.contains("\"kiss\""), text)

        let back = try JSONDecoder().decode([LossyElement<PendingAction>].self, from: Data(text.utf8))
            .compactMap { $0.value }
        XCTAssertEqual(back, [gift])
    }
}

/// Love between slimes is gone from the app; the server keeps it for older
/// builds, so its fields must still decode (and be ignored).
final class LoveRemovalTests: XCTestCase {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try HyprnodeAPI.decoder.decode(T.self, from: Data(json.utf8))
    }

    func testThisAppNeverSendsKisses() {
        XCTAssertEqual(InteractionKind.allCases.map(\.rawValue), ["hug", "visit", "gift"])
        XCTAssertEqual(FriendQuickAction.allCases.map(\.rawValue), ["hug", "visit", "gift", "greet"])
    }

    func testFriendWithoutLoveFields() throws {
        let friend = try decode(Friend.self, """
        {"id": "dev_abc", "ownerName": "Anna", "since": "2026-10-25T12:00:00Z",
         "slime": {"ownerName": "Anna", "name": "Blob", "skin": "ocean", "level": 5, "stage": "young",
                   "mood": "idle", "happiness": 80, "updatedAt": "2026-10-25T12:00:00Z"}}
        """)
        XCTAssertEqual(friend.slime.name, "Blob")
        XCTAssertEqual(friend.love, 0)
        XCTAssertFalse(friend.inLove)
    }

    func testInteractionResultsWithAndWithoutLove() throws {
        let old = try decode(InteractionResult.self, #"{"love": 60, "inLove": false, "gained": 4, "fellInLove": false, "capped": false, "message": null}"#)
        XCTAssertEqual(old.capped, false)
        let capped = try decode(InteractionResult.self, #"{"capped": true, "message": "That's enough hugs for today. More tomorrow."}"#)
        XCTAssertEqual(capped.capped, true)
        XCTAssertEqual(capped.message, "That's enough hugs for today. More tomorrow.")
    }

    func testLoveMemoriesStayHidden() {
        let at = Date(timeIntervalSince1970: 1_761_393_600)
        let shown = ["met", "first.hug", "first.visit", "first.gift", "firstChallengeWin", "firstCoop", "somethingNew"]
        for kind in shown {
            XCTAssertTrue(FriendMemory(kind: kind, detail: nil, at: at).isShown, kind)
        }
        for kind in ["first.kiss", "love.25", "love.50", "love.75", "inLove"] {
            XCTAssertFalse(FriendMemory(kind: kind, detail: nil, at: at).isShown, kind)
        }
        XCTAssertEqual(FriendMemory(kind: "somethingNew", detail: nil, at: at).title, "A moment together")
    }

    @MainActor
    func testAFriendInLoveOnAnOlderAppIsJustAFriendInTheIsland() throws {
        let friend = try decode(Friend.self, """
        {"id": "dev_abc", "ownerName": "Anna", "love": 100, "inLove": true, "since": "2026-10-25T12:00:00Z",
         "slime": {"ownerName": "Anna", "name": "Blob", "skin": "ocean", "level": 5, "stage": "young",
                   "mood": "idle", "happiness": 80, "updatedAt": "2026-10-25T12:00:00Z"}}
        """)
        let island = FriendsStore.islandSlime(friend)
        XCTAssertEqual(island.id, "dev_abc")
        XCTAssertEqual(island.name, "Blob")
        XCTAssertEqual(island.owner, "Anna")
        XCTAssertEqual(island.skin, .ocean)
        XCTAssertEqual(island.stage, .young)
    }
}

/// Care Together: the wire format of server/ (see server/README.md).
final class CareAPITests: XCTestCase {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try HyprnodeAPI.decoder.decode(T.self, from: Data(json.utf8))
    }

    private func json<T: Encodable>(_ value: T) throws -> [String: Any] {
        let data = try HyprnodeAPI.encoder.encode(value)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testCareKindsMatchTheServer() {
        XCTAssertEqual(CareKind.allCases.map(\.rawValue), ["feed", "play", "bath", "pet", "sleep", "wake", "gift"])
    }

    func testCareCode() throws {
        let code = try decode(PairCode.self, #"{"code": "K7P2QX", "expiresAt": "2026-10-26T12:10:00Z"}"#)
        XCTAssertEqual(code.code, "K7P2QX")
    }

    func testJoiningGivesThePartnerAsHost() throws {
        let joined = try decode(CareJoinResult.self, """
        {"partner": {"deviceId": "dev_host", "ownerName": "Anna"}, "snapshot": null, "version": 0}
        """)
        XCTAssertEqual(joined.partner.deviceId, "dev_host")
        XCTAssertEqual(joined.partner.ownerName, "Anna")
        XCTAssertEqual(joined.partner.role, CareRole.host)
        XCTAssertEqual(joined.partner.myRole, CareRole.joiner)
        XCTAssertNil(joined.snapshot)
        XCTAssertNil(joined.snapshotName)
        XCTAssertEqual(joined.version, 0)
    }

    func testStatusWhileNotCaringTogether() throws {
        // Exactly what the server answers without a pair.
        let status = try decode(CareStatus.self, """
        {"partner": null, "snapshot": null, "version": 0, "events": [], "cursor": "0"}
        """)
        XCTAssertNil(status.partner)
        XCTAssertNil(status.snapshot)
        XCTAssertEqual(status.version, 0)
        XCTAssertTrue(status.events.isEmpty)
        XCTAssertEqual(status.cursor, "0")
    }

    func testStatusWithThePartnersActions() throws {
        let status = try decode(CareStatus.self, """
        {"partner": {"deviceId": "dev_j", "ownerName": "Sam", "role": "joiner"},
         "snapshot": {"name": "Blob", "someFieldFromTheFuture": 1}, "version": 7,
         "events": [
           {"id": "41", "deviceId": "dev_j", "ownerName": "Sam", "kind": "feed", "food": "cake", "game": null,
            "amount": null, "at": "2026-10-26T09:00:00Z", "createdAt": "2026-10-26T09:00:02Z"},
           {"id": 42, "deviceId": "dev_j", "ownerName": "Sam", "kind": "play", "food": null, "game": "jellyDash",
            "amount": 35.5, "at": "2026-10-26T09:05:00Z", "createdAt": "2026-10-26T09:05:01Z"},
           {"id": "43", "deviceId": "dev_j", "ownerName": "Sam", "kind": "dance", "food": null, "game": null,
            "amount": null, "at": "2026-10-26T09:06:00Z", "createdAt": "2026-10-26T09:06:00Z"},
           {"id": "44", "deviceId": "dev_j", "ownerName": "Sam", "kind": "gift", "food": "aFoodFromTheFuture",
            "game": "aGameFromTheFuture", "amount": 3, "at": "2026-10-26T09:07:00Z", "createdAt": "2026-10-26T09:07:00Z"}
         ],
         "cursor": 44}
        """)
        XCTAssertEqual(status.partner?.role, CareRole.joiner)
        XCTAssertEqual(status.partner?.myRole, CareRole.host)
        XCTAssertEqual(status.version, 7)
        XCTAssertEqual(status.snapshotName, "Blob")
        XCTAssertEqual(status.cursor, "44")
        // The unknown kind is skipped, the rest kept in order.
        XCTAssertEqual(status.events.map(\.id), ["41", "42", "44"])
        XCTAssertEqual(status.events[0].food, .cake)
        XCTAssertEqual(status.events[1].game, .jellyDash)
        XCTAssertEqual(status.events[1].amount, 35.5)
        // Food and games a newer app knows fall back to none.
        XCTAssertNil(status.events[2].food)
        XCTAssertNil(status.events[2].game)

        let event = status.events[0].event
        XCTAssertEqual(event.id, "41")
        XCTAssertEqual(event.kind, .feed)
        XCTAssertEqual(event.food, .cake)
        XCTAssertEqual(event.by, "Sam")
        XCTAssertEqual(event.at, Date(timeIntervalSince1970: 1_793_005_200))
    }

    func testCareEventBody() throws {
        let at = Date(timeIntervalSince1970: 1_761_393_600.4)
        let body = try json(CareEventBody(kind: .feed, food: .apple, game: nil, amount: nil, at: at))
        XCTAssertEqual(body["kind"] as? String, "feed")
        XCTAssertEqual(body["food"] as? String, "apple")
        XCTAssertEqual(body["at"] as? String, "2025-10-25T12:00:00Z")
        XCTAssertNil(body["game"])
        XCTAssertNil(body["amount"])
        XCTAssertEqual(body.count, 3)

        let played = try json(CareEventBody(kind: .play, food: nil, game: .daily, amount: 500, at: at))
        XCTAssertEqual(played["game"] as? String, "daily")
        XCTAssertEqual(played["amount"] as? Double, CareEventBody.amountLimit)
        XCTAssertNil(CareEventBody(kind: .pet, food: nil, game: nil, amount: Double.nan, at: at).amount)
        XCTAssertEqual(CareEventBody(kind: .bath, food: nil, game: nil, amount: -3, at: at).amount, 0)
    }

    func testCareEventBodyFromAGameEvent() {
        let at = Date(timeIntervalSince1970: 1_761_393_600)
        let event = CareEvent(id: "local-1", kind: .gift, food: .honey, game: nil, amount: 12, by: "Me", at: at)
        let body = CareEventBody(event)
        XCTAssertEqual(body, CareEventBody(kind: .gift, food: .honey, game: nil, amount: 12, at: at))
    }

    func testCarePathEncodesTheCursor() {
        XCTAssertEqual(HyprnodeAPI.carePath(after: nil), "care")
        XCTAssertEqual(HyprnodeAPI.carePath(after: ""), "care")
        XCTAssertEqual(HyprnodeAPI.carePath(after: "120"), "care?after=120")
        XCTAssertEqual(HyprnodeAPI.carePath(after: "a b&c"), "care?after=a%20b%26c")
    }

    func testSnapshotConflicts() throws {
        let conflict = try XCTUnwrap(HyprnodeAPI.careConflict(from: Data("""
        {"error": "The slime changed on the other phone first. Here is the newest version.", "version": 12, "snapshot": null}
        """.utf8)))
        XCTAssertEqual(conflict.version, 12)
        XCTAssertNil(conflict.snapshot)

        // Not caring together anymore: a 409 without a version.
        let gone = try XCTUnwrap(HyprnodeAPI.careConflict(from: Data("""
        {"error": "You're not caring for a slime together right now."}
        """.utf8)))
        XCTAssertNil(gone.version)
        XCTAssertEqual(gone.error, "You're not caring for a slime together right now.")
    }

    @MainActor
    func testTheSharedSlimeSurvivesTheServer() throws {
        let snapshot = GameStore.shared.sharedSnapshot()
        let first = try HyprnodeAPI.encoder.encode(snapshot)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: first) as? [String: Any])
        // The server filters the slime's name in this field.
        XCTAssertEqual(object["name"] as? String, GameStore.shared.state.slime.name)

        // Dates travel in whole seconds, so compare from the first trip on.
        let back = try HyprnodeAPI.decoder.decode(SlimeSnapshot.self, from: first)
        let again = try HyprnodeAPI.decoder.decode(SlimeSnapshot.self, from: HyprnodeAPI.encoder.encode(back))
        XCTAssertEqual(again, back)
        XCTAssertEqual(back.name, snapshot.name)
        XCTAssertEqual(back.updatedAt.timeIntervalSince1970, snapshot.updatedAt.timeIntervalSince1970, accuracy: 1)

        // As the server hands it back inside GET /care.
        let wrapped = #"{"partner": {"deviceId": "dev_h", "ownerName": "Anna", "role": "host"}, "snapshot": "#
            + String(decoding: first, as: UTF8.self) + #", "version": 3, "events": [], "cursor": "0"}"#
        let status = try decode(CareStatus.self, wrapped)
        XCTAssertNotNil(status.snapshot)
        XCTAssertEqual(status.snapshotName, GameStore.shared.state.slime.name)
        XCTAssertEqual(status.version, 3)
    }

    func testShareMessage() {
        XCTAssertEqual(CareInvite.message(for: "ABC234", slimeName: "Blob"),
                       "Care for my slime Blob with me in Slimy! Code: ABC234 — get Slimy at https://slime.hyprnode.de")
    }

    func testPastedInvitesGiveTheirCode() {
        let message = CareInvite.message(for: "XYZ789", slimeName: "Mochi")
        XCTAssertEqual(CareInvite.code(in: message), "XYZ789")
        XCTAssertEqual(CareInvite.code(in: "code: abc234"), "ABC234")
        XCTAssertNil(CareInvite.code(in: "ABC234"))
        XCTAssertNil(CareInvite.code(in: "Code: AB0I1L"))
    }

    // MARK: Activity

    func testActivityLines() {
        let now = Date(timeIntervalSince1970: 1_761_393_600)
        let fed = CareActivity(id: "1", kind: .feed, food: .apple, by: "Anna", slimeName: "Blob",
                               at: now.addingTimeInterval(-5 * 60))
        XCTAssertEqual(fed.line(now: now), "Anna fed Blob · 5 min ago")
        XCTAssertEqual(fed.detail, "Apple")
        XCTAssertEqual(fed.sprite, "apple")

        let played = CareActivity(id: "2", kind: .play, game: .bubblePop, by: "Anna", slimeName: "Blob", at: now)
        XCTAssertEqual(played.text, "Anna played with Blob")
        XCTAssertEqual(played.detail, GameID.bubblePop.title)

        for kind in CareKind.allCases {
            let entry = CareActivity(id: kind.rawValue, kind: kind, by: "Anna", slimeName: "Blob", at: now)
            XCTAssertTrue(entry.text.hasPrefix("Anna "), entry.text)
            XCTAssertTrue(entry.text.contains("Blob"), entry.text)
            XCTAssertFalse(entry.symbol.isEmpty)
        }
    }

    func testHowLongAgo() {
        let now = Date(timeIntervalSince1970: 1_761_393_600)
        XCTAssertEqual(CareActivity.ago(now, now: now), "just now")
        XCTAssertEqual(CareActivity.ago(now.addingTimeInterval(30), now: now), "just now")
        XCTAssertEqual(CareActivity.ago(now.addingTimeInterval(-59 * 60), now: now), "59 min ago")
        XCTAssertEqual(CareActivity.ago(now.addingTimeInterval(-3600), now: now), "1 hour ago")
        XCTAssertEqual(CareActivity.ago(now.addingTimeInterval(-5 * 3600), now: now), "5 hours ago")
        XCTAssertEqual(CareActivity.ago(now.addingTimeInterval(-30 * 3600), now: now), "yesterday")
        XCTAssertEqual(CareActivity.ago(now.addingTimeInterval(-4 * 86400), now: now), "4 days ago")
    }

    func testActivityKeepsAWeekNewestFirstWithoutDuplicates() {
        let now = Date(timeIntervalSince1970: 1_761_393_600)
        func entry(_ id: String, hoursAgo: Double) -> CareActivity {
            CareActivity(id: id, kind: .pet, by: "Anna", slimeName: "Blob", at: now.addingTimeInterval(-hoursAgo * 3600))
        }
        let old = [entry("a", hoursAgo: 2), entry("b", hoursAgo: 8 * 24)]
        let merged = CareActivity.merged([entry("c", hoursAgo: 1), entry("a", hoursAgo: 2)], into: old, now: now)
        XCTAssertEqual(merged.map(\.id), ["c", "a"])

        let many = (0..<150).map { entry("e\($0)", hoursAgo: Double($0) / 10) }
        XCTAssertEqual(CareActivity.merged(many, into: [], now: now).count, CareActivity.limit)
    }

    func testActivitySurvivesSaving() throws {
        let entries = [CareActivity(id: "9", kind: .sleep, by: "Anna", slimeName: "Blob",
                                    at: Date(timeIntervalSince1970: 1_761_393_600))]
        let back = try HyprnodeAPI.decoder.decode([CareActivity].self, from: HyprnodeAPI.encoder.encode(entries))
        XCTAssertEqual(back, entries)
    }
}

/// What the chat sends and gets back about the slime's feelings.
final class ChatEmotionWireTests: XCTestCase {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try HyprnodeAPI.decoder.decode(T.self, from: Data(json.utf8))
    }

    private func context(emotions: [String: Double]?) -> ChatContext {
        ChatContext(name: "Blob", ageDays: 3, level: 2, mood: .idle, needs: Needs(), favorite: .cake,
                    localTime: "9:41 AM", emotions: emotions)
    }

    func testEmotionsAreSentOnlyWhenThere() throws {
        let with = try HyprnodeAPI.encoder.encode(context(emotions: ["joy": 0.6, "sleepiness": 0.2]))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: with) as? [String: Any])
        let emotions = try XCTUnwrap(object["emotions"] as? [String: Double])
        XCTAssertEqual(emotions, ["joy": 0.6, "sleepiness": 0.2])

        let without = try HyprnodeAPI.encoder.encode(context(emotions: nil))
        let plain = try XCTUnwrap(JSONSerialization.jsonObject(with: without) as? [String: Any])
        XCTAssertNil(plain["emotions"])
        XCTAssertNil(plain["partnerName"])
    }

    func testReplyWithAnEmotion() throws {
        let reply = try decode(ChatReply.self, #"{"reply": "yay!", "reaction": "celebrate", "emotion": {"name": "Joy", "intensity": 0.8}}"#)
        XCTAssertEqual(reply.reply, "yay!")
        XCTAssertEqual(reply.reaction, .celebrate)
        XCTAssertEqual(reply.emotion?.name, "joy")
        XCTAssertEqual(reply.emotion?.intensity, 0.8)
    }

    func testRepliesFromOlderServersAndBrokenEmotions() throws {
        let old = try decode(ChatReply.self, #"{"reply": "hi", "reaction": "wave"}"#)
        XCTAssertNil(old.emotion)
        XCTAssertEqual(old.reaction, .wave)

        let loud = try decode(ChatReply.self, #"{"reply": "hi", "reaction": null, "emotion": {"name": "excitement", "intensity": 7}}"#)
        XCTAssertEqual(loud.emotion?.intensity, 1)

        let vague = try decode(ChatReply.self, #"{"reply": "hi", "emotion": {"name": "curiosity", "intensity": "a lot"}}"#)
        XCTAssertEqual(vague.emotion?.intensity, 0.5)

        let broken = try decode(ChatReply.self, #"{"reply": "hi", "reaction": "aReactionFromTheFuture", "emotion": "joy"}"#)
        XCTAssertEqual(broken.reply, "hi")
        XCTAssertNil(broken.reaction)
        XCTAssertNil(broken.emotion)
    }
}
