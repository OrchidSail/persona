package persona.core.data

import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import persona.core.contracts.Item
import persona.core.contracts.ItemsEvent
import persona.core.contracts.ItemsResponse
import persona.core.contracts.RevsEvent
import persona.core.contracts.Session
import persona.core.contracts.SessionKey
import persona.core.contracts.SessionsEvent
import persona.core.contracts.DeltaEvent
import persona.core.contracts.api.ChatSnapshot
import persona.core.contracts.api.ItemsQuery
import persona.core.rustcore.RustCore

/**
 * Verifies that history rewrites with pending replacement fetches hold the last committed transcript
 * on screen and in disk cache across both Kotlin (sync) and Rust (async) continuity adapters, matching
 * web `useTranscript.ts` `replacing`.
 */
class ChatHistoryReplacementHoldTest {
    private val key = SessionKey("computer-a", "main-assistant")

    private enum class Adapter { Kotlin, Rust }

    private fun user(seq: Int, text: String, id: String = "$seq#0") =
        Item(id, seq, role = "user", kind = "text", text = text, ts = "2026-11-25T10:00:${"%02d".format(seq.coerceIn(0, 59))}Z")

    private fun assistant(seq: Int, text: String, id: String = "$seq#0") =
        Item(id, seq, role = "assistant", kind = "text", text = text, ts = "2026-11-25T10:01:${"%02d".format(seq.coerceIn(0, 59))}Z")

    private class Frames : ContinuityFrameScheduler {
        private val frames = ConcurrentLinkedQueue<() -> Unit>()
        override fun afterFirstFrame(start: () -> Unit) = start()
        override fun schedule(frame: () -> Unit) { frames.add(frame) }
        override suspend fun submitted(core: RustCore) { check(core.flush(5_000)) }
        fun run() {
            val count = frames.size
            repeat(count) { frames.poll()?.invoke() }
        }
    }

    private inner class Fixture(
        val repository: HubChatRepository,
        val connection: FakeConnection,
        val transport: FakeChatTransport,
        val cache: RecordingCache,
        val foreground: MutableStateFlow<Boolean>,
        val frames: Frames?,
        val snapshot: AtomicReference<ChatSnapshot>,
        val emissions: CopyOnWriteArrayList<ChatSnapshot>,
    ) {
        suspend fun pump(done: () -> Boolean) = withTimeout(5_000) {
            while (!done()) {
                frames?.run()
                delay(1)
            }
        }
    }

    private class RecordingCache(private val initial: CachedChat = CachedChat()) : ChatItemsCache {
        val saves = CopyOnWriteArrayList<CachedChat>()
        override suspend fun load(session: SessionKey) = initial
        override suspend fun save(session: SessionKey, snapshot: CachedChat) {
            saves.add(snapshot)
        }
    }

    private suspend fun withFixture(
        adapter: Adapter,
        initialItems: List<Item>,
        initialHistory: String = "h1",
        initialHasMore: Boolean = true,
        test: suspend Fixture.() -> Unit,
    ) {
        val frames = if (adapter == Adapter.Rust) Frames() else null
        val driver = frames?.let(::RustContinuityDriver)
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val connection = FakeConnection()
        val cache = RecordingCache()
        val foreground = MutableStateFlow(true)
        val transport = FakeChatTransport().apply {
            fetch = { ItemsResponse(initialItems, hasMore = initialHasMore, history = initialHistory, toSeq = initialItems.maxOfOrNull { it.seq } ?: 0) }
        }
        val repository = HubChatRepository(
            transport, connection, cache, FakeSessions(), scope,
            foreground = foreground,
            continuityFactory = { chat, applied ->
                if (driver == null) KotlinContinuityAdapter(chat) else RustContinuityAdapter(chat, driver, applied)
            },
        )
        val snapshot = AtomicReference(ChatSnapshot(verified = false))
        val emissions = CopyOnWriteArrayList<ChatSnapshot>()
        val observer = scope.launch {
            repository.observe(key).collect { next ->
                snapshot.set(next)
                emissions.add(next)
            }
        }
        val fixture = Fixture(repository, connection, transport, cache, foreground, frames, snapshot, emissions)
        try {
            fixture.pump { snapshot.get().verified && snapshot.get().items == initialItems }
            // Drain any remaining creation frames and reset recording right after initial load.
            frames?.run()
            emissions.clear()
            cache.saves.clear()
            fixture.test()
        } finally {
            observer.cancelAndJoin()
            job.cancelAndJoin()
            driver?.close()
        }
    }

    private suspend fun wsHistoryRewriteHoldsLoadedMessages(adapter: Adapter) {
        val h1Items = listOf(user(9, "Question one"), assistant(10, "Assistant reply one"))
        val h2Items = listOf(user(1, "Question one"), assistant(2, "Assistant reply one"), assistant(3, "Assistant reply two"))
        val overtaking = assistant(4, "Overtaking reply")
        withFixture(adapter, h1Items, initialHistory = "h1", initialHasMore = true) {
            val reloadRequested = CompletableDeferred<Unit>()
            val reloadResponse = CompletableDeferred<ItemsResponse>()
            val fetchQueries = CopyOnWriteArrayList<ItemsQuery>()
            transport.fetch = { query ->
                fetchQueries.add(query)
                if (query.afterSeq == null) {
                    reloadRequested.complete(Unit)
                    reloadResponse.await()
                } else {
                    ItemsResponse(listOf(overtaking), hasMore = false, history = "h2", fromSeq = query.afterSeq, toSeq = 4)
                }
            }

            // A live WS push carrying a new history with fromSeq > 0 triggers ContinuityReducer Gap.Reload.
            connection.events.emit(ItemsEvent(key.machineId, key.sessionId, listOf(assistant(11, "Late item")), "h2", fromSeq = 10, toSeq = 11))
            pump { reloadRequested.isCompleted }

            // Intervening session updates and an overtaking push in the new history must NOT wipe or replace the held view.
            connection.events.emit(SessionsEvent(key.machineId, listOf(Session(key.machineId, key.sessionId, status = "working", rev = 99))))
            connection.events.emit(ItemsEvent(key.machineId, key.sessionId, listOf(overtaking), "h2", fromSeq = 3, toSeq = 4))
            repeat(5) { frames?.run(); delay(2) }

            assertEquals("history rewrite must fetch latest page first", null, fetchQueries.firstOrNull()?.afterSeq)
            assertEquals("loaded assistant messages must remain visible while reload is in flight", h1Items, snapshot.get().items)
            assertTrue("hasOlder must remain true while reload is in flight", snapshot.get().hasOlder)
            assertTrue("olderBoundaryKnown must remain true while reload is in flight", snapshot.get().olderBoundaryKnown)
            assertEquals("history must not flicker before replacement commits", "h1", snapshot.get().history)
            assertFalse("snapshot must stay unverified while reload is in flight", snapshot.get().verified)
            assertFalse("loading spinner must not flash over held messages", snapshot.get().loading)
            assertTrue("no emitted snapshot may wipe assistant messages", emissions.all { emitted ->
                emitted.items.any { it.role == "assistant" && it.text == "Assistant reply one" }
            })
            assertTrue("disk cache must never be overwritten with empty items during reload",
                cache.saves.none { it.items.isEmpty() })

            // Now complete FetchLatest and verify atomic swap to h2 (keeping overtaking seq 4).
            reloadResponse.complete(ItemsResponse(h2Items, hasMore = true, history = "h2", toSeq = 3))
            pump { snapshot.get().verified && snapshot.get().history == "h2" && snapshot.get().items.any { it.text == "Overtaking reply" } }

            assertTrue("replacement assistant reply must appear", snapshot.get().items.any { it.text == "Assistant reply two" })
            assertTrue("overtaking push in new history must be preserved", snapshot.get().items.any { it.text == "Overtaking reply" })
            assertTrue("hasOlder from replacement page must apply", snapshot.get().hasOlder)
            assertTrue("disk cache must persist replacement history", cache.saves.any { it.history == "h2" && it.items.isNotEmpty() })
            assertTrue("every emission across the entire rewrite kept assistant messages visible", emissions.all { emitted ->
                emitted.items.any { it.role == "assistant" }
            })
        }
    }

    private suspend fun httpCatchUpHistoryRewriteHoldsLoadedMessages(adapter: Adapter) {
        val h1Items = listOf(user(9, "Question one"), assistant(10, "Assistant reply one"))
        val h2Items = listOf(user(1, "Question one"), assistant(2, "Assistant reply one"), assistant(3, "Assistant reply two"))
        withFixture(adapter, h1Items, initialHistory = "h1", initialHasMore = true) {
            val afterCount = AtomicInteger()
            val reloadRequested = CompletableDeferred<Unit>()
            val reloadResponse = CompletableDeferred<ItemsResponse>()
            transport.fetch = { query ->
                if (query.afterSeq != null) {
                    afterCount.incrementAndGet()
                    // Mirrors agy native export: after_seq=10 returns empty items with new history and fromSeq=10.
                    ItemsResponse(emptyList(), hasMore = false, history = "h2", fromSeq = query.afterSeq, toSeq = query.afterSeq)
                } else {
                    reloadRequested.complete(Unit)
                    reloadResponse.await()
                }
            }

            // Trigger catch-up via foreground resume / revision bump.
            foreground.value = false
            foreground.value = true
            connection.events.emit(RevsEvent(key.machineId, mapOf(key.sessionId to 2L)))
            pump { reloadRequested.isCompleted }
            repeat(5) { frames?.run(); delay(2) }

            assertTrue("catch-up after_seq should have run first", afterCount.get() >= 1)
            assertEquals("loaded assistant messages must stay on screen during FetchAfter->FetchLatest", h1Items, snapshot.get().items)
            assertTrue("hasOlder must stay true during FetchAfter->FetchLatest", snapshot.get().hasOlder)
            assertTrue("no emission may ever drop assistant messages", emissions.all { emitted ->
                emitted.items.any { it.role == "assistant" }
            })

            reloadResponse.complete(ItemsResponse(h2Items, hasMore = true, history = "h2", toSeq = 3))
            pump { snapshot.get().verified && snapshot.get().history == "h2" }
            assertEquals(h2Items, snapshot.get().items)
            assertTrue(snapshot.get().hasOlder)
        }
    }

    private suspend fun chunkedZeroOriginHistoryResetHoldsRecentMessagesUntilComplete(adapter: Adapter) {
        val h1Items = listOf(user(99, "Recent question"), assistant(100, "Recent assistant reply"))
        val ancientChunk = listOf(user(1, "Ancient question"), assistant(2, "Ancient reply"))
        val latestChunk = listOf(user(3, "Recent question"), assistant(4, "Updated assistant reply"))
        withFixture(adapter, h1Items, initialHistory = "h1", initialHasMore = true) {
            val secondPageRequested = CompletableDeferred<Unit>()
            val secondPageResponse = CompletableDeferred<ItemsResponse>()
            val fetchQueries = CopyOnWriteArrayList<ItemsQuery>()
            transport.fetch = { query ->
                fetchQueries.add(query)
                secondPageRequested.complete(Unit)
                secondPageResponse.await()
            }

            connection.events.emit(ItemsEvent(key.machineId, key.sessionId, ancientChunk, "h2", fromSeq = 0, toSeq = 2, moreAfter = true))
            pump { secondPageRequested.isCompleted }
            repeat(5) { frames?.run(); delay(2) }

            assertEquals("chunked reset must continue from seq 2", 2, fetchQueries.firstOrNull()?.afterSeq)
            assertEquals("recent messages must stay held while chunked history reset is still filling", h1Items, snapshot.get().items)
            assertEquals("h1", snapshot.get().history)

            secondPageResponse.complete(ItemsResponse(latestChunk, history = "h2", fromSeq = 2, toSeq = 4, moreAfter = false))
            pump { snapshot.get().history == "h2" && snapshot.get().items.any { it.text == "Updated assistant reply" } }
            assertEquals(ancientChunk + latestChunk, snapshot.get().items)
        }
    }

    private suspend fun failedReloadPreservesVisibleMessagesAndRecovers(adapter: Adapter) {
        val h1Items = listOf(user(9, "Question one"), assistant(10, "Assistant reply one"))
        val h2Items = listOf(user(1, "Question one"), assistant(2, "Recovered assistant reply"))
        withFixture(adapter, h1Items, initialHistory = "h1", initialHasMore = true) {
            val attempts = AtomicInteger()
            transport.fetch = { query ->
                if (attempts.incrementAndGet() == 1) throw IOException("Synthetic mobile drop")
                if (query.afterSeq != null) ItemsResponse(h2Items, history = "h2", fromSeq = 0, toSeq = 2, moreAfter = false)
                else ItemsResponse(h2Items, hasMore = false, history = "h2", toSeq = 2)
            }

            connection.events.emit(ItemsEvent(key.machineId, key.sessionId, listOf(assistant(11, "Trigger")), "h2", fromSeq = 10, toSeq = 11))
            pump { attempts.get() == 1 && snapshot.get().error != null }

            // Even after a session push arrives post-failure, visible messages must remain held rather than wiping to empty.
            connection.events.emit(SessionsEvent(key.machineId, listOf(Session(key.machineId, key.sessionId, status = "idle", rev = 5))))
            repeat(5) { frames?.run(); delay(2) }

            assertEquals("failed history reload must keep previously loaded messages visible", h1Items, snapshot.get().items)
            assertFalse(snapshot.get().verified)
            assertTrue("no emission may ever become empty", emissions.all { it.items.isNotEmpty() })

            // Next revision triggers catch-up and recovers cleanly to h2.
            connection.events.emit(RevsEvent(key.machineId, mapOf(key.sessionId to 6L)))
            pump { snapshot.get().verified && snapshot.get().history == "h2" }
            assertEquals(h2Items, snapshot.get().items)
            assertEquals(null, snapshot.get().error)
        }
    }

    private suspend fun authoritativeEmptyReplacementCommitsWhenConfirmed(adapter: Adapter) {
        val h1Items = listOf(user(1, "Old prompt"), assistant(2, "Old reply"))
        withFixture(adapter, h1Items, initialHistory = "h1", initialHasMore = true) {
            val reloadRequested = CompletableDeferred<Unit>()
            val reloadResponse = CompletableDeferred<ItemsResponse>()
            transport.fetch = {
                reloadRequested.complete(Unit)
                reloadResponse.await()
            }

            connection.events.emit(ItemsEvent(key.machineId, key.sessionId, emptyList(), "h2", fromSeq = 2, toSeq = 2))
            pump { reloadRequested.isCompleted }
            repeat(3) { frames?.run(); delay(2) }
            assertEquals("old items remain visible until authoritative empty page arrives", h1Items, snapshot.get().items)

            reloadResponse.complete(ItemsResponse(emptyList(), hasMore = false, history = "h2", toSeq = 0))
            pump { snapshot.get().verified && snapshot.get().history == "h2" && snapshot.get().items.isEmpty() }
            assertFalse(snapshot.get().hasOlder)
            assertTrue(snapshot.get().olderBoundaryKnown)
        }
    }

    @Test fun kotlinWsHistoryRewriteHoldsLoadedAssistantMessages() = runBlocking {
        wsHistoryRewriteHoldsLoadedMessages(Adapter.Kotlin)
    }

    @Test fun rustWsHistoryRewriteHoldsLoadedAssistantMessages() = runBlocking {
        wsHistoryRewriteHoldsLoadedMessages(Adapter.Rust)
    }

    @Test fun kotlinHttpCatchUpHistoryRewriteHoldsLoadedAssistantMessages() = runBlocking {
        httpCatchUpHistoryRewriteHoldsLoadedMessages(Adapter.Kotlin)
    }

    @Test fun rustHttpCatchUpHistoryRewriteHoldsLoadedAssistantMessages() = runBlocking {
        httpCatchUpHistoryRewriteHoldsLoadedMessages(Adapter.Rust)
    }

    @Test fun kotlinChunkedZeroOriginHistoryResetHoldsRecentMessages() = runBlocking {
        chunkedZeroOriginHistoryResetHoldsRecentMessagesUntilComplete(Adapter.Kotlin)
    }

    @Test fun rustChunkedZeroOriginHistoryResetHoldsRecentMessages() = runBlocking {
        chunkedZeroOriginHistoryResetHoldsRecentMessagesUntilComplete(Adapter.Rust)
    }

    @Test fun kotlinFailedReloadPreservesVisibleMessagesAndRecovers() = runBlocking {
        failedReloadPreservesVisibleMessagesAndRecovers(Adapter.Kotlin)
    }

    @Test fun rustFailedReloadPreservesVisibleMessagesAndRecovers() = runBlocking {
        failedReloadPreservesVisibleMessagesAndRecovers(Adapter.Rust)
    }

    @Test fun kotlinAuthoritativeEmptyReplacementCommitsWhenConfirmed() = runBlocking {
        authoritativeEmptyReplacementCommitsWhenConfirmed(Adapter.Kotlin)
    }

    @Test fun rustAuthoritativeEmptyReplacementCommitsWhenConfirmed() = runBlocking {
        authoritativeEmptyReplacementCommitsWhenConfirmed(Adapter.Rust)
    }

    @Test fun streamingDeltaDuringHistoryRewriteIsBufferedAndReplayedOnCommit() = runBlocking {
        val h1Items = listOf(user(1, "Question one"), assistant(2, "Assistant reply one"))
        val h2Items = listOf(user(1, "Question one"), assistant(2, "Assistant reply one"), user(3, "Question two"))
        withFixture(Adapter.Kotlin, h1Items, initialHistory = "h1", initialHasMore = true) {
            val reloadRequested = CompletableDeferred<Unit>()
            val reloadResponse = CompletableDeferred<ItemsResponse>()
            transport.fetch = {
                reloadRequested.complete(Unit)
                reloadResponse.await()
            }

            connection.events.emit(ItemsEvent(key.machineId, key.sessionId, listOf(user(3, "Question two")), "h2", fromSeq = 2, toSeq = 3))
            pump { reloadRequested.isCompleted }

            connection.events.emit(DeltaEvent(key.machineId, key.sessionId, "Streaming reply in new history"))
            delay(10)
            // Held snapshot stays untouched while replacement fetch is still in flight.
            assertEquals(h1Items, snapshot.get().items)
            assertEquals("h1", snapshot.get().history)

            reloadResponse.complete(ItemsResponse(h2Items, hasMore = true, history = "h2", toSeq = 3))
            pump { snapshot.get().verified && snapshot.get().history == "h2" }

            val streamed = snapshot.get().items.lastOrNull { it.role == "assistant" }
            assertNotNull("buffered delta must replay onto replacement history", streamed)
            assertEquals("Streaming reply in new history", streamed!!.text)
            assertTrue("buffered streaming item must remain active", snapshot.get().streamingItemIds.contains(streamed.id))
        }
    }
}
