import XCTest
@testable import Slimy

/// The app must read exactly what server/ sends (see server/README.md).
final class APIDecodingTests: XCTestCase {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try HyprnodeAPI.decoder.decode(T.self, from: Data(json.utf8))
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
    }

    func testTransientErrors() {
        XCTAssertTrue(APIError.offline.isTransient)
        XCTAssertTrue(APIError.server(status: 503, message: nil).isTransient)
        XCTAssertTrue(APIError.server(status: 429, message: nil).isTransient)
        XCTAssertFalse(APIError.server(status: 404, message: "weg").isTransient)
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
