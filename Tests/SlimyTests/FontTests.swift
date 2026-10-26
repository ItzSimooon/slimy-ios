import UIKit
import XCTest
@testable import Slimy

/// The slime's voice and the wordmark need Pixelify Sans; without it SwiftUI
/// quietly draws SF instead.
final class FontTests: XCTestCase {
    func testPixelFontIsInTheBundle() {
        for name in AppFonts.names {
            XCTAssertNotNil(Bundle.main.url(forResource: name, withExtension: "ttf"), "\(name).ttf is not in the app bundle")
        }
    }

    func testPixelFontIsRegistered() {
        AppFonts.register()
        for name in AppFonts.names {
            XCTAssertNotNil(UIFont(name: name, size: 16), "\(name) can't be loaded by name")
        }
    }
}
