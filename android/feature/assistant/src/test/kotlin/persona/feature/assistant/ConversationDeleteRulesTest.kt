package persona.feature.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import persona.core.contracts.SessionCompaction
import persona.core.contracts.Session
import persona.core.contracts.SessionKey
import persona.core.contracts.api.AssistantManagement
import persona.core.contracts.api.AssistantNotes
import persona.core.contracts.api.AssistantRefused
import persona.core.contracts.api.AssistantRepository
import persona.core.contracts.api.AssistantSnapshot
import persona.core.contracts.api.MemoryFile
import persona.core.contracts.api.Run
import persona.core.contracts.api.ScheduledTask

/**
 * Pure unit tests for W-C01 permanent conversation deletion rules, hard protections,
 * re-entry prevention, and state refresh handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationDeleteRulesTest {
    private val mainKey = SessionKey("hub", "main")

    @Test fun busyGuardsMatchWebAndServerBoundaries() {
        val idle = Session("hub", "side", title = "Idle", status = "idle", state = "idle")
        assertFalse(conversationBusyForDelete(idle))
        assertFalse(conversationBusyForDelete(idle.copy(archived = true)))
        assertFalse(conversationBusyForDelete(idle.copy(compaction = SessionCompaction(state = "done"))))

        val busyCases = listOf(
            idle.copy(starting = true),
            idle.copy(waiting = true),
            idle.copy(pendingInteractions = 1),
            idle.copy(queued = listOf("next message")),
            idle.copy(compaction = SessionCompaction(state = "running")),
            idle.copy(backgroundWorking = true),
            idle.copy(backgroundWaiting = true),
            idle.copy(status = "working"),
            idle.copy(status = "blocked"),
            idle.copy(state = "working"),
            idle.copy(state = "needs_you"),
        )
        for (session in busyCases) {
            assertTrue("expected busy: $session", conversationBusyForDelete(session))
            assertFalse("busy chat must not be deletable: $session",
                conversationDeleteAllowed(session, supported = true, main = mainKey))
        }
    }

    @Test fun hardProtectionsBlockUnsupportedMainSupervisorTombstonesAndPlaceholders() {
        val side = Session("hub", "side", title = "Side", status = "idle")
        val main = Session("hub", "main", title = "Main", status = "idle")
        val sameIdOtherMachine = Session("other", "main", title = "Main", status = "idle")
        val supervisor = Session("hub", "bg-sup", title = "Supervisor", status = "idle")

        assertFalse(conversationDeleteAllowed(side, supported = false, main = mainKey))
        assertTrue(conversationDeleteAllowed(side, supported = true, main = mainKey))
        assertTrue(conversationDeleteAllowed(side.copy(archived = true), supported = true, main = mainKey))
        assertFalse(conversationDeleteAllowed(main, supported = true, main = mainKey))
        assertFalse(conversationDeleteAllowed(sameIdOtherMachine, supported = true, main = mainKey))
        assertFalse(conversationDeleteAllowed(supervisor, supported = true, main = mainKey, builtIn = "bg-sup"))
        assertFalse(conversationDeleteAllowed(side.copy(deleted = true), supported = true, main = mainKey))
        assertFalse(conversationDeleteAllowed(Session("", "side"), supported = true, main = mainKey))
        assertFalse(conversationDeleteAllowed(Session("hub", ""), supported = true, main = mainKey))
    }

    private class StubHub(
        initial: List<Session>,
        var archivedList: List<Session> = emptyList(),
    ) : AssistantRepository, AssistantManagement {
        override val assistant = MutableStateFlow(AssistantSnapshot(SessionKey("hub", "main"), initial))
        val deletedCalls = mutableListOf<SessionKey>()
        var deleteGate: CompletableDeferred<Unit>? = null
        var deleteFailure: Exception? = null
        var refreshes = 0
        var archivedReads = 0

        override suspend fun refresh() { refreshes++ }
        override suspend fun warm() = Unit
        override suspend fun endConversation(session: SessionKey) = Unit
        override suspend fun archiveConversation(session: SessionKey) = Unit
        override suspend fun archivedConversations(): List<Session> { archivedReads++; return archivedList }
        override suspend fun deleteConversation(session: SessionKey) {
            deletedCalls += session
            deleteGate?.await()
            deleteFailure?.let { throw it }
            assistant.value = assistant.value.copy(conversations = assistant.value.conversations.filterNot { it.key == session })
            archivedList = archivedList.filterNot { it.key == session }
        }
        override suspend fun tasks(): List<ScheduledTask> = emptyList()
        override suspend fun saveTask(task: ScheduledTask) = task
        override suspend fun deleteTask(id: String) = Unit
        override suspend fun runTask(id: String) = error("unused")
        override suspend fun freshTask(id: String) = error("unused")
        override suspend fun runs(): List<Run> = emptyList()
        override suspend fun notes() = AssistantNotes()
        override suspend fun setNotes(text: String) = Unit
        override suspend fun memory(): List<MemoryFile> = emptyList()
        override suspend fun saveMemory(name: String, content: String) = Unit
        override suspend fun deleteMemory(name: String) = Unit
    }

    @Test fun confirmDeletePreventsReentryRechecksLiveStateAndHandlesRefusal() = runTest {
        val main = Session("hub", "main", title = "Main", status = "idle")
        val side = Session("hub", "side", title = "Side", status = "idle")
        val hub = StubHub(listOf(main, side))
        val history = ConversationHistory(hub, hub)

        // If the conversation started working after the dialog opened, confirm refuses locally without calling the hub.
        history.requestDelete(side)
        val becameBusy = history.confirmDelete(
            latestConversations = listOf(main, side.copy(status = "working")),
            supported = true,
            main = mainKey,
            builtIn = null,
            unavailableMessage = "Cannot delete right now",
            errorMessage = { it.message.orEmpty() },
        )
        assertEquals(ConversationDeleteOutcome.Failed("Cannot delete right now"), becameBusy)
        assertEquals("Cannot delete right now", history.deleteError)
        assertTrue(hub.deletedCalls.isEmpty())

        // Server refusal preserves inline error and keeps dialog armed.
        hub.deleteFailure = AssistantRefused("conversation_busy", "Conversation is running")
        val refused = history.confirmDelete(
            latestConversations = listOf(main, side),
            supported = true,
            main = mainKey,
            builtIn = null,
            unavailableMessage = "Cannot delete right now",
            errorMessage = { it.message.orEmpty() },
        )
        assertEquals(ConversationDeleteOutcome.Failed("Conversation is running"), refused)
        assertEquals("Conversation is running", history.deleteError)
        assertEquals(side, history.deleting)
        assertTrue(history.deleted.isEmpty())

        // Re-entry while in flight is ignored; success updates deleted set, triggers onConfirmed, and refreshes.
        hub.deleteFailure = null
        val gate = CompletableDeferred<Unit>()
        hub.deleteGate = gate
        val confirmed = mutableListOf<SessionKey>()
        val first = async {
            history.confirmDelete(
                latestConversations = listOf(main, side),
                supported = true,
                main = mainKey,
                builtIn = null,
                unavailableMessage = "Cannot delete right now",
                errorMessage = { it.message.orEmpty() },
                onConfirmed = { confirmed += it },
            )
        }
        runCurrent()
        assertTrue(history.deleteBusy)
        val duplicate = history.confirmDelete(
            latestConversations = listOf(main, side),
            supported = true,
            main = mainKey,
            builtIn = null,
            unavailableMessage = "Cannot delete right now",
            errorMessage = { it.message.orEmpty() },
        )
        assertEquals(ConversationDeleteOutcome.Ignored, duplicate)
        assertEquals(2, hub.deletedCalls.size) // 1 refusal + 1 in-flight

        gate.complete(Unit)
        assertEquals(ConversationDeleteOutcome.Deleted(side.key), first.await())
        assertFalse(history.deleteBusy)
        assertNull(history.deleting)
        assertNull(history.deleteError)
        assertTrue(side.key in history.deleted)
        assertEquals(listOf(side.key), confirmed)
        assertEquals(1, hub.refreshes)
        assertEquals(1, hub.archivedReads)
    }
}
