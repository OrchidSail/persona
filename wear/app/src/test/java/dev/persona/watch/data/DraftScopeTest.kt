package dev.persona.watch.data

import org.junit.Assert.*
import org.junit.Test

class DraftScopeTest {
    @Test fun changingPrimaryDoesNotMoveAnotherHubsDraftOrPendingOperation() {
        for (prefix in listOf("session:", "send:session:", "continue:", "resolve:")) {
            val key = prefix + "group-a\u0000computer\u0000conversation"
            assertEquals(DraftScope.hash("http://first.local", setOf("group-a"), key), DraftScope.hash("http://second.local", setOf("group-a"), key))
        }
    }
    @Test fun primaryDraftsRemainScopedToTheirOwnHub() {
        val key = "session:computer\u0000conversation"
        assertNotEquals(DraftScope.hash("http://first.local", emptySet(), key), DraftScope.hash("http://second.local", emptySet(), key))
    }
    @Test fun sameServerIdsCannotCollideAcrossGroups() {
        val groups = setOf("one", "two")
        assertNotEquals(DraftScope.hash("http://hub.local", groups, "session:one\u0000same"), DraftScope.hash("http://hub.local", groups, "session:two\u0000same"))
    }
}
