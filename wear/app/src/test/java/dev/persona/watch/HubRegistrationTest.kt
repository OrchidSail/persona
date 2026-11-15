package dev.persona.watch

import dev.persona.watch.data.HubConfig
import org.junit.Assert.*
import org.junit.Test

class HubRegistrationTest {
    private val input = HubConfig("http://hub.local", "sample-one-time-token")
    private val issued = input.copy(token = "sample-issued-watch-token", deviceId = "sample-device")
    private val id = "11111111-1111-1111-1111-111111111111"

    private class Store : HubRegistrationStore {
        var rows = emptyList<SavedHub>()
        var durableRows = emptyList<SavedHub>()
        var journal: PendingHubRegistration? = null
        var durableJournal: PendingHubRegistration? = null
        val credentials = mutableMapOf<String, HubConfig>()
        var primary: String? = null
        var failPublish = false
        var failPrepare = false
        var failCleanup = false
        var failCredential = false
        var unreadableCredential = false
        var saves = 0
        var publications = 0
        val events = mutableListOf<String>()
        override fun groups() = rows
        override fun primaryUrl() = primary
        override fun pending() = journal
        override fun writePending(pending: PendingHubRegistration?) {
            journal = pending // Like SharedPreferences, memory changes even if disk commit fails.
            events += if (pending == null) "cleanup" else "prepare"
            check(if (pending == null) !failCleanup else !failPrepare)
            durableJournal = pending
        }
        override fun credential(id: String): HubConfig? {
            check(!unreadableCredential || id !in credentials)
            return credentials[id]
        }
        override fun saveCredential(id: String, config: HubConfig) {
            events += "credential"
            check(!failCredential)
            saves++
            credentials[id] = config
        }
        override fun writeGroups(groups: List<SavedHub>) {
            rows = groups
            events += "publish"
            publications++
            check(!failPublish)
            durableRows = groups
        }
        fun restart() { rows = durableRows; journal = durableJournal }
    }

    private fun engine(store: Store) = HubRegistration(store) { id }
    private fun fails(action: () -> Unit) {
        try { action(); fail("Expected injected failure") } catch (_: IllegalStateException) { }
    }

    @Test fun directoryFailureIsRecoveredAfterRestartWithoutTheOneTimeToken() {
        val store = Store().apply { failPublish = true }
        var exchanges = 0
        fails { engine(store).add("Sample Hub", input) { exchanges++; store.events += "exchange"; issued } }
        assertTrue(store.durableRows.isEmpty())
        assertEquals(id, store.durableJournal?.id)
        assertEquals(issued, store.credentials[id])
        assertEquals(listOf("prepare", "exchange", "credential", "publish"), store.events)
        store.restart()
        store.failPublish = false
        assertTrue(engine(store).recover())
        assertEquals(listOf(SavedHub(id, "Sample Hub", issued)), store.durableRows)
        assertNull(store.durableJournal)
        assertEquals(1, exchanges)
        assertEquals(1, store.saves)
        assertFalse(engine(store).recover())
    }

    @Test fun retryingAddFinishesTheSameRegistrationWithoutRedeemingAgain() {
        val store = Store().apply { failPublish = true }
        fails { engine(store).add("Sample Hub", input) { issued } }
        store.failPublish = false
        engine(store).add("Sample Hub", input) { throw AssertionError("Consumed token must not be redeemed") }
        assertEquals(1, store.durableRows.size)
        assertEquals(id, store.durableRows.single().id)
        assertEquals(1, store.saves)
        assertEquals(2, store.publications) // Recommit even when the failed write changed the memory view.
    }

    @Test fun repeatedPublicationFailuresKeepTheEncryptedCredentialReachable() {
        val store = Store().apply { failPublish = true }
        fails { engine(store).add("Sample Hub", input) { issued } }
        repeat(3) {
            store.restart()
            fails { engine(store).recover() }
            assertEquals(issued, store.credentials[store.durableJournal?.id])
        }
        store.failPublish = false
        assertTrue(engine(store).recover())
        assertEquals(1, store.durableRows.size)
        assertEquals(1, store.saves)
    }

    @Test fun cleanupFailureReplaysIdempotentlyAfterRestart() {
        val store = Store().apply { failCleanup = true }
        fails { engine(store).add("Sample Hub", input) { issued } }
        assertEquals(1, store.durableRows.size)
        assertNotNull(store.durableJournal)
        store.restart()
        store.failCleanup = false
        assertTrue(engine(store).recover())
        assertEquals(1, store.durableRows.size)
        assertEquals(1, store.saves)
        assertNull(store.durableJournal)
    }

    @Test fun failureToPrepareJournalDoesNotConsumeTheToken() {
        val store = Store().apply { failPrepare = true }
        fails { engine(store).add("Sample Hub", input) { throw AssertionError("Must prepare before exchange") } }
        assertTrue(store.credentials.isEmpty())
        assertNull(store.durableJournal)
        store.failPrepare = false
        var exchanges = 0
        engine(store).add("Sample Hub", input) { exchanges++; issued }
        assertEquals(1, exchanges)
        assertEquals(1, store.durableRows.size)
    }

    @Test fun missingIssuedCredentialNeverTriggersAutomaticRedemptionOrFakeRecovery() {
        val store = Store().apply { failCredential = true }
        fails { engine(store).add("Sample Hub", input) { issued } }
        store.restart()
        assertFalse(engine(store).recover())
        assertTrue(store.durableRows.isEmpty())
        assertNotNull(store.durableJournal)
        // A crash/write failure before issued credentials become durable cannot be locally recovered.
    }

    @Test fun validatesCapacityAndDuplicatesBeforeTokenExchange() {
        val store = Store()
        store.primary = input.baseUrl
        assertThrows(IllegalArgumentException::class.java) {
            engine(store).add("Sample Hub", input) { throw AssertionError("Duplicate must not exchange") }
        }
        store.primary = null
        store.rows = (1..3).map { SavedHub("sample-$it", "Sample", input.copy(baseUrl = "http://hub$it.local")) }
        assertThrows(IllegalArgumentException::class.java) {
            engine(store).add("Sample Hub", input) { throw AssertionError("Full directory must not exchange") }
        }
        assertTrue(store.events.isEmpty())
    }

    @Test fun recoveryPreservesUnrelatedConnections() {
        val store = Store()
        val other = SavedHub("other", "Other", input.copy(baseUrl = "http://other.local"))
        store.rows = listOf(other)
        store.durableRows = store.rows
        store.failPublish = true
        fails { engine(store).add("Sample Hub", input) { issued } }
        store.restart()
        store.failPublish = false
        engine(store).recover()
        assertEquals(listOf(other, SavedHub(id, "Sample Hub", issued)), store.durableRows)
    }

    @Test fun unreadableSavedCredentialKeepsItsJournalAndPreventsAnotherExchange() {
        val store = Store().apply { failPublish = true }
        fails { engine(store).add("Sample Hub", input) { issued } }
        store.restart()
        store.failPublish = false
        store.unreadableCredential = true
        fails { engine(store).recover() }
        fails {
            engine(store).add("Other", input.copy(baseUrl = "http://other.local")) {
                throw AssertionError("Unreadable saved credential must not be abandoned")
            }
        }
        assertEquals(id, store.durableJournal?.id)
        assertEquals(1, store.saves)
        store.unreadableCredential = false
        assertTrue(engine(store).recover())
        assertEquals(listOf(SavedHub(id, "Sample Hub", issued)), store.durableRows)
    }
}
