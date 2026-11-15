import SwiftUI
import Testing
@testable import Persona

/// Return Key Sends (#8178): which edits are Return typed, and what a hardware Return does with modifiers held.
struct ReturnSendsTests {
    @Test func oneLineBreakTypedAnywhereIsReturn() {
        #expect(ReturnSends.breakTyped(from: "hello", to: "hello\n"))
        #expect(ReturnSends.breakTyped(from: "hello", to: "he\nllo"))
        #expect(ReturnSends.breakTyped(from: "hello", to: "\nhello"))
        #expect(ReturnSends.breakTyped(from: "", to: "\n"))
        // A line break after another: still the one key.
        #expect(ReturnSends.breakTyped(from: "a\n", to: "a\n\n"))
        #expect(ReturnSends.breakTyped(from: "你好", to: "你好\n"))
    }

    @Test func otherEditsAreNotReturn() {
        #expect(!ReturnSends.breakTyped(from: "hello", to: "hello!"))
        #expect(!ReturnSends.breakTyped(from: "hello", to: "hello"))
        #expect(!ReturnSends.breakTyped(from: "hello\n", to: "hello"))
        // A paste that brings a line break and more, or a break that replaces a selection.
        #expect(!ReturnSends.breakTyped(from: "hello", to: "hello\nworld"))
        #expect(!ReturnSends.breakTyped(from: "hello", to: "hell\n"))
        // The keyboard confirming its composition (Pinyin's letters become characters): no line break.
        #expect(!ReturnSends.breakTyped(from: "ni", to: "你"))
        #expect(!ReturnSends.breakTyped(from: "nihao", to: "nihao "))
    }

    @Test func plainReturnSendsAndModifiedReturnDoesNot() {
        #expect(ReturnSends.keySends([], composing: false))
        #expect(ReturnSends.keySends(.capsLock, composing: false))
        #expect(ReturnSends.keySends(.numericPad, composing: false))
        #expect(!ReturnSends.keySends(.shift, composing: false))
        #expect(!ReturnSends.keySends(.option, composing: false))
        // ⌘Return is the send button's own shortcut.
        #expect(!ReturnSends.keySends(.command, composing: false))
    }

    @Test func returnWhileComposingConfirmsInsteadOfSending() {
        #expect(!ReturnSends.keySends([], composing: true))
    }

    @Test func returnDoesWhatTheSendButtonsPlaceDoes() {
        #expect(ReturnSends.outcome(canSend: true, gated: false) == .send)
        #expect(ReturnSends.outcome(canSend: false, gated: false) == .nothing)
        #expect(ReturnSends.outcome(canSend: true, gated: true) == .gate)
        #expect(ReturnSends.outcome(canSend: false, gated: true) == .gate)
    }

    @MainActor @Test func offByDefault() {
        let defaults = UserDefaults(suiteName: "ReturnSendsTests")!
        defaults.removePersistentDomain(forName: "ReturnSendsTests")
        #expect(StoredValue(wrappedValue: false, ReturnSends.enabledKey, store: defaults).wrappedValue == false)
    }
}
