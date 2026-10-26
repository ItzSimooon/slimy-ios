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
        ]
        let back = try JSONDecoder().decode([PendingAction].self, from: JSONEncoder().encode(actions))
        XCTAssertEqual(back, actions)
        // The idempotency key stays the same across saves, so a resend counts once.
        XCTAssertEqual(back.map(\.key), actions.map(\.key))
        XCTAssertEqual(back[0].friendID, "dev_a")
        XCTAssertNil(back[4].friendID)
    }
}
