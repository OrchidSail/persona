import XCTest

/// A chat item's explicit extension page must open its native source with the page's own View-in-chat action. Merely
/// opening the detail keeps the reader collapsed and reports nothing; the one explicit action expands it, shows the
/// existing conversation and reports that conversation read without a second tap on the source disclosure.
/// Run against scripts/mock/mock_hub.py with MOCK_EXTENSIONS=1 MOCK_INBOX_ITEM_UI=1 and without MOCK_INBOX_EVENTS
/// (PERSONA_MOCK_HUB). TEST_RUNNER_PERSONA_SHOTS saves the opened source; TEST_RUNNER_PERSONA_SHOTS_DEVICE and
/// TEST_RUNNER_PERSONA_SHOTS_APPEARANCE name the device and appearance in its file.
final class InboxDirectSourceTests: XCTestCase {
    private static let item = "lark:f1:100"
    private static let feed = "f1"
    private static let nativeMessage = "下午我要和客户确认时间"

    private var app: XCUIApplication!
    private var hub: String!
    private var regularWidth = false

    override func setUpWithError() throws {
        continueAfterFailure = false
        guard let hub = ProcessInfo.processInfo.environment["PERSONA_MOCK_HUB"] else {
            throw XCTSkip("needs scripts/mock/mock_hub.py with MOCK_EXTENSIONS=1 MOCK_INBOX_ITEM_UI=1, without MOCK_INBOX_EVENTS")
        }
        self.hub = hub
    }

    override func tearDown() {
        if let app, (testRun?.totalFailureCount ?? 0) > 0 {
            let shot = XCTAttachment(screenshot: app.screenshot())
            shot.name = "inbox-direct-source-failure"
            shot.lifetime = .keepAlways
            add(shot)
        }
        app?.terminate()
        super.tearDown()
    }

    @MainActor func testChatViewActionOpensNativeSourceAndReportsRead() throws {
        try post("/api/v1/inbox/done", body: ["ids": [Self.item], "done": false])
        let initialReadCount = try chatReadReports().count
        openDetail()

        let extensionPage = element("inbox-plugin-item")
        XCTAssertTrue(extensionPage.waitForExistence(timeout: 20),
                      "the stock extension did not draw the item page:\n\(app.debugDescription)")
        XCTAssertFalse(app.textFields["lark.box"].exists,
                       "the native chat reader was built before the extension's explicit open action")

        sleep(2) // the native chat reports a read 400 ms after its rows come on screen
        XCTAssertEqual(try chatReadReports().count, initialReadCount,
                       "the extension's detail page reported the chat read before its open action")

        let open = app.buttons["plugin-ui-action-open"].firstMatch
        requireHittable(open, "the extension page's View-in-chat action")
        XCTAssertEqual(try chatReadReports().count, initialReadCount,
                       "the extension page reported the chat read while revealing its open action")
        open.tap()

        XCTAssertTrue(app.textFields["lark.box"].waitForExistence(timeout: 15),
                      "one View-in-chat action did not open the native reader; no source-disclosure tap followed:\n\(app.debugDescription)")
        let message = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", Self.nativeMessage)).firstMatch
        XCTAssertTrue(message.waitForExistence(timeout: 10),
                      "the native reader did not show the existing conversation:\n\(app.debugDescription)")

        let disclosure = element("inbox.detail.source")
        XCTAssertTrue(disclosure.waitForExistence(timeout: 5), "the expanded source lost its disclosure")
        assertLabel("收起", on: disclosure, "the directly opened source disclosure")

        let reports = try waitForReports(after: initialReadCount)
        XCTAssertGreaterThan(reports.count, initialReadCount,
                             "the explicit extension action did not report the \(Self.feed) chat as read")
        shot()
    }

    @MainActor private func openDetail() {
        app = XCUIApplication()
        let pair = "persona://pair?url=" + hub.addingPercentEncoding(withAllowedCharacters: .alphanumerics)!
            + "&token=" + UITestToken.device
        app.launchArguments = ["-pairURL", pair, "-tab", "mail", "-dev.openChat", "", "-openSplitFresh", "YES",
                               "-inbox.kind", "", "-inbox.source", "", "-openInboxItem", Self.item]
        app.launchPinned()
        acceptIconAlert()
        XCTAssertTrue(app.windows.firstMatch.waitForExistence(timeout: 10), "no app window")
        regularWidth = app.windows.firstMatch.frame.width > 600
    }

    @MainActor private func element(_ id: String) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    @MainActor private func requireHittable(_ element: XCUIElement, _ what: String, timeout: TimeInterval = 10) {
        for _ in 0..<8 where !(element.exists && element.isHittable) {
            app.swipeUp(velocity: .slow)
            _ = element.waitForExistence(timeout: 1)
        }
        let predicate = NSPredicate(format: "exists == true AND hittable == true")
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        let met = XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
        XCTAssertTrue(met, "\(what) is not on screen:\n\(app.debugDescription)")
    }

    @MainActor private func assertLabel(_ label: String, on element: XCUIElement, _ what: String,
                                        timeout: TimeInterval = 5) {
        let predicate = NSPredicate(format: "label CONTAINS %@", label)
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        XCTAssertEqual(XCTWaiter().wait(for: [expectation], timeout: timeout), .completed,
                       "\(what) does not say \(label):\n\(app.debugDescription)")
    }

    /// Only this chat's reports: the mock is shared and other runs' chats report too.
    private func chatReadReports() throws -> [[String: Any]] {
        let url = try XCTUnwrap(URL(string: hub + "/mock/lark_reads"))
        let data = try Data(contentsOf: url)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let reads = try XCTUnwrap(object["reads"] as? [[String: Any]])
        return reads.filter { $0["feed"] as? String == Self.feed }
    }

    private func waitForReports(after baseline: Int, timeout: TimeInterval = 10) throws -> [[String: Any]] {
        let deadline = Date.now.addingTimeInterval(timeout)
        var current = try chatReadReports()
        while current.count <= baseline, Date.now < deadline {
            usleep(250_000)
            current = try chatReadReports()
        }
        return current
    }

    private func post(_ path: String, body: [String: Any]) throws {
        let url = try XCTUnwrap(URL(string: hub + path))
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(UITestToken.device)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let answered = expectation(description: "POST " + path)
        URLSession.shared.dataTask(with: request) { _, response, error in
            XCTAssertNil(error)
            XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
            answered.fulfill()
        }.resume()
        wait(for: [answered], timeout: 10)
    }

    @MainActor private func shot() {
        let environment = ProcessInfo.processInfo.environment
        let png = app.screenshot().pngRepresentation
        let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        attachment.name = "inbox-direct-source-chat"
        attachment.lifetime = .keepAlways
        add(attachment)
        guard let directory = environment["PERSONA_SHOTS"] else { return }
        let device = environment["PERSONA_SHOTS_DEVICE"] ?? (regularWidth ? "ipad" : "iphone")
        let appearance = environment["PERSONA_SHOTS_APPEARANCE"] ?? "unknown"
        let file = "inbox-direct-source-chat-\(device)-\(appearance)-\(UITestStyle.name).png"
        try? png.write(to: URL(fileURLWithPath: directory).appendingPathComponent(file))
    }
}
