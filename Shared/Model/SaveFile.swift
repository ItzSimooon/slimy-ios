import Foundation

/// An exported save: a `.slimysave` file the player can keep in Files or
/// move to another iPhone. Plain JSON with a small header, so a file can be
/// checked before it replaces anything.
struct SaveFile: Codable {
    static let format = "de.hyprnode.slimy.save"
    /// Version of this file layout (not of the game state inside).
    static let schema = 1
    static let fileExtension = "slimysave"
    /// A real save is a few hundred kilobytes at most.
    static let maxBytes = 8 * 1024 * 1024

    var format: String
    var schema: Int
    var exportedAt: Date
    var appVersion: String
    var state: GameState

    /// Why a file can't be imported. The messages are shown as they are.
    enum Problem: LocalizedError, Equatable {
        case unreadable, notASave, newerVersion, noSlime, tooLarge

        var errorDescription: String? {
            switch self {
            case .unreadable: return "This file couldn't be read. It may be damaged."
            case .notASave: return "This isn't a Slimy save file."
            case .newerVersion: return "This save comes from a newer version of Slimy. Update the app and try again."
            case .noSlime: return "There's no slime in this save yet."
            case .tooLarge: return "This file is too large to be a Slimy save."
            }
        }
    }

    // MARK: Writing

    static func encode(_ state: GameState, at now: Date, appVersion: String) throws -> Data {
        let file = SaveFile(format: format, schema: schema, exportedAt: now, appVersion: appVersion, state: state)
        return try encoder.encode(file)
    }

    /// "Slimy-Mochi-2026-10-26.slimysave"
    static func fileName(for state: GameState, at now: Date, calendar: Calendar = .current) -> String {
        let allowed = CharacterSet.alphanumerics
        let cleaned = String(state.slime.name.unicodeScalars.map { allowed.contains($0) ? Character($0) : "-" })
            .split(separator: "-").joined(separator: "-")
        let name = cleaned.isEmpty ? "Slime" : String(cleaned.prefix(24))
        return "Slimy-\(name)-\(Challenges.dayKey(now, calendar: .gregorian(like: calendar))).\(fileExtension)"
    }

    /// Writes the file into `directory` and returns where it is.
    static func write(_ state: GameState, to directory: URL, at now: Date, appVersion: String,
                      calendar: Calendar = .current) throws -> URL {
        let data = try encode(state, at: now, appVersion: appVersion)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(fileName(for: state, at: now, calendar: calendar))
        try data.write(to: url, options: [.atomic])
        return url
    }

    // MARK: Reading

    /// Checks a file and returns the game in it, repaired and ready to use.
    static func decode(_ data: Data, now: Date) throws -> GameState {
        guard data.count <= maxBytes else { throw Problem.tooLarge }
        guard let header = try? GameStorage.decoder.decode(Header.self, from: data) else { throw Problem.unreadable }
        guard header.format == format else { throw Problem.notASave }
        guard let version = header.schema, version >= 1 else { throw Problem.unreadable }
        guard version <= schema else { throw Problem.newerVersion }
        guard let body = try? GameStorage.decoder.decode(Body.self, from: data) else { throw Problem.unreadable }
        var state = body.state
        guard state.version <= GameState.currentVersion else { throw Problem.newerVersion }
        guard state.hatched else { throw Problem.noSlime }
        state.version = GameState.currentVersion
        state.sanitize(now: now)
        return state
    }

    /// Reads and checks a file, for example one picked in Files.
    static func read(from url: URL, now: Date) throws -> GameState {
        if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > maxBytes {
            throw Problem.tooLarge
        }
        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            throw Problem.unreadable
        }
        return try decode(data, now: now)
    }

    private struct Header: Decodable {
        var format: String?
        var schema: Int?
    }

    private struct Body: Decodable {
        var state: GameState
    }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .secondsSince1970
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return e
    }()
}
