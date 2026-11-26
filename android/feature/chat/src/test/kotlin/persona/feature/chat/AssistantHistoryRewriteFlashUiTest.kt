package persona.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.FeedItem
import persona.core.contracts.FeedResponse
import persona.core.contracts.Injected
import persona.core.contracts.Interaction
import persona.core.contracts.Item
import persona.core.contracts.ItemsEvent
import persona.core.contracts.ItemsResponse
import persona.core.contracts.Machine
import persona.core.contracts.Resolution
import persona.core.contracts.RevsEvent
import persona.core.contracts.ServerEvent
import persona.core.contracts.Session
import persona.core.contracts.SessionKey
import persona.core.contracts.SessionsEvent
import persona.core.contracts.TaskPlan
import persona.core.contracts.Todo
import persona.core.contracts.api.ChatSnapshot
import persona.core.contracts.api.ChatTransport
import persona.core.contracts.api.ConnectionState
import persona.core.contracts.api.DeliveryState
import persona.core.contracts.api.HubConnection
import persona.core.contracts.api.InteractionCenter
import persona.core.contracts.api.ItemsQuery
import persona.core.contracts.api.MessageSender
import persona.core.contracts.api.OutboxMessage
import persona.core.contracts.api.SessionsRepository
import persona.core.contracts.api.TasksApi
import persona.core.data.CachedChat
import persona.core.data.ChatItemsCache
import persona.core.data.HubChatRepository
import persona.core.data.KotlinContinuityAdapter
import persona.core.designsystem.AppStyle
import persona.core.designsystem.PersonaTheme
import persona.core.domain.tasks.TasksStore

/**
 * End-to-end Compose UI regression tests across Glass, Brief, and Board for assistant chat history
 * rewrites, feed clipping while loading / unknown boundary, and background round stability.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h900dp")
class AssistantHistoryRewriteFlashUiTest {
    @get:Rule val rule = createComposeRule()

    private val key = SessionKey("computer-a", "main-assistant")
    private val session = Session(key.machineId, key.sessionId, title = "Main", status = "working", agent = "agy", assistant = true)

    private class FakeConnection : HubConnection {
        override val state = MutableStateFlow(ConnectionState.Online)
        override val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 32)
        override fun reconnect() = Unit
        override fun send(message: JsonObject) = true
    }

    private class MemoryCache : ChatItemsCache {
        @Volatile var saved: CachedChat = CachedChat()
        override suspend fun load(session: SessionKey) = CachedChat()
        override suspend fun save(session: SessionKey, snapshot: CachedChat) { saved = snapshot }
    }

    private class FakeTasksApi(private val items: List<FeedItem>) : TasksApi {
        override suspend fun todos(): List<Todo> = emptyList()
        override suspend fun feed(conversation: String, since: String?): FeedResponse = FeedResponse(items)
        override suspend fun mainConversation(): String = "main-assistant"
        override suspend fun startPlan(id: String, remember: Boolean): TaskPlan = error("unused")
        override suspend fun dismissPlan(id: String): TaskPlan = error("unused")
        override suspend fun retryTask(todoId: String): Todo = error("unused")
        override suspend fun removeTask(todoId: String) = error("unused")
        override suspend fun route(conversation: String, todoId: String, text: String): FeedItem = error("unused")
        override suspend fun today(conversation: String): FeedItem = error("unused")
        override suspend fun inboxReply(itemId: String, text: String, card: String?) = error("unused")
        override suspend fun acceptTask(todoId: String, askedAt: String) = error("unused")
        override suspend fun patchTodo(id: String, title: String?, notes: String?, done: Boolean?): Todo = error("unused")
        override suspend fun deleteTodo(id: String) = error("unused")
        override suspend fun linkTodoAgent(todoId: String, machineId: String, sessionId: String) = error("unused")
        override suspend fun unlinkTodoAgent(todoId: String, machineId: String, sessionId: String) = error("unused")
    }

    private fun visibleTexts(): List<String> = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

    private fun verifyHistoryRewritePreservesAssistantRepliesAndFeedClip(style: AppStyle) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connection = FakeConnection()
            val cache = MemoryCache()
            val h1Items = listOf(
                Item("u1#0", 9, role = "user", kind = "text", text = "User question one", ts = "2026-11-25T10:00:00Z"),
                Item("a1#0", 10, role = "assistant", kind = "text", text = "Assistant reply visible one", ts = "2026-11-25T10:00:05Z"),
            )
            val h2Items = listOf(
                Item("u1-native#0", 1, role = "user", kind = "text", text = "User question one", ts = "2026-11-25T10:00:00Z"),
                Item("a1-native#0", 2, role = "assistant", kind = "text", text = "Assistant reply visible one", ts = "2026-11-25T10:00:05Z"),
                Item("u2-native#0", 3, role = "user", kind = "text", text = "User question two", ts = "2026-11-25T10:00:09Z"),
                Item("a2-native#0", 4, role = "assistant", kind = "text", text = "Assistant reply after rewrite", ts = "2026-11-25T10:00:10Z"),
            )
            val olderRouted = FeedItem(
                id = "routed-old",
                conversation = key.sessionId,
                at = "2026-11-24T09:00:00Z",
                kind = "routed",
                body = buildJsonObject {
                    put("from", "user")
                    put("title", "Earlier agent task")
                    put("text", "Older routed decision that must stay clipped")
                },
            )
            val currentRouted = FeedItem(
                id = "routed-now",
                conversation = key.sessionId,
                at = "2026-11-25T10:00:08Z",
                kind = "routed",
                body = buildJsonObject {
                    put("from", "user")
                    put("title", "Active agent task")
                    put("text", "Current routed decision")
                },
            )
            val overtakingItem = Item(
                "late#0", 5, role = "user", kind = "text", text = "Follow-up after rewrite",
                ts = "2026-11-25T10:00:12Z",
            )
            val latestCalls = AtomicInteger()
            val reloadRequested = CompletableDeferred<Unit>()
            val reloadResponse = CompletableDeferred<ItemsResponse>()
            val transport = object : ChatTransport {
                override suspend fun items(session: SessionKey, query: ItemsQuery): ItemsResponse = when {
                    query.afterSeq == 10 ->
                        ItemsResponse(emptyList(), hasMore = false, history = "h2", fromSeq = 10, toSeq = 10)
                    query.afterSeq != null ->
                        ItemsResponse(listOf(overtakingItem), hasMore = false, history = "h2", fromSeq = query.afterSeq, toSeq = 5)
                    latestCalls.incrementAndGet() == 1 ->
                        ItemsResponse(h1Items, hasMore = true, history = "h1", toSeq = 10)
                    else -> {
                        reloadRequested.complete(Unit)
                        reloadResponse.await()
                    }
                }
                override suspend fun machines(): List<Machine> = listOf(Machine(key.machineId, "Computer A", online = true))
                override suspend fun sessions(): List<Session> = listOf(session)
                override suspend fun interactions(): List<Interaction> = emptyList()
                override suspend fun resolve(id: String, resolution: Resolution) = Unit
                override suspend fun image(session: SessionKey, itemId: String) = byteArrayOf()
                override suspend fun fullItem(session: SessionKey, itemId: String) = Item(itemId)
            }
            val sessions = object : SessionsRepository {
                override val machines = MutableStateFlow(listOf(Machine(key.machineId, "Computer A", online = true)))
                override val sessions = MutableStateFlow(listOf(session))
                override suspend fun refresh() = Unit
            }
            val chats = HubChatRepository(
                transport, connection, cache, sessions, scope,
                continuityFactory = { chat, _ -> KotlinContinuityAdapter(chat) },
            )
            val outboxMessage = OutboxMessage(
                clientMessageId = "local-unknown-1",
                session = key,
                text = "Local unconfirmed message",
                state = DeliveryState.Unknown,
                createdAt = Instant.parse("2026-11-25T10:00:15Z").toEpochMilli(),
            )
            val sender = object : MessageSender {
                override val outbox = MutableStateFlow(listOf(outboxMessage))
                override suspend fun send(session: SessionKey, text: String, clientMessageId: String, clearDraft: String?) = Unit
            }
            val interactions = object : InteractionCenter {
                override val pending = MutableStateFlow(emptyList<Interaction>())
                override suspend fun refresh() = Unit
                override suspend fun resolve(id: String, resolution: Resolution) = Unit
            }
            val tasks = TasksStore(FakeTasksApi(listOf(olderRouted, currentRouted)), connection, scope)

            rule.setContent {
                PersonaTheme(darkTheme = true, reduceMotion = true, style = style) {
                    Box(Modifier.fillMaxSize().background(PersonaTheme.palette.background)) {
                        ChatScreen(
                            sessionKey = key,
                            repository = chats,
                            sender = sender,
                            interactions = interactions,
                            sessions = sessions,
                            onBack = {},
                            chrome = ChatChromeBindings(
                                connection = connection,
                                tasks = tasks,
                                mainConversation = key.sessionId,
                            ),
                            renderStyle = ChatRenderStyle.Assistant,
                            embedded = true,
                            showTopBar = false,
                        )
                    }
                }
            }

            // Wait for initial load (assistant reply + current routed decision + outbox visible; older routed clipped).
            rule.waitUntil(5_000) {
                val texts = visibleTexts()
                texts.any { it.contains("Assistant reply visible one") } &&
                    texts.any { it.contains("Local unconfirmed message") }
            }
            assertFalse(
                "style=$style: older routed decision must remain clipped while hasOlder=true",
                visibleTexts().any { it.contains("Older routed decision") },
            )

            // Trigger history rewrite via rev catch-up (after_seq -> new history -> FetchLatest held) + WS push + SessionsEvent.
            scope.launch {
                connection.events.emit(RevsEvent(key.machineId, mapOf(key.sessionId to 2L)))
            }
            rule.waitUntil(5_000) { reloadRequested.isCompleted }
            scope.launch {
                connection.events.emit(SessionsEvent(key.machineId, listOf(session.copy(rev = 3))))
                connection.events.emit(ItemsEvent(key.machineId, key.sessionId, listOf(
                    overtakingItem,
                ), "h2", fromSeq = 4, toSeq = 5))
            }
            rule.waitForIdle()

            // During the multi-second reload window, assistant replies MUST NOT disappear and older feed MUST NOT unclip!
            val duringReload = visibleTexts()
            assertTrue(
                "style=$style: assistant reply disappeared during history reload; visible=$duringReload",
                duringReload.any { it.contains("Assistant reply visible one") },
            )
            assertTrue(
                "style=$style: local outbox message must remain visible",
                duringReload.any { it.contains("Local unconfirmed message") },
            )
            assertFalse(
                "style=$style: older routed decision unclipped during reload; visible=$duringReload",
                duringReload.any { it.contains("Older routed decision") },
            )

            // Complete FetchLatest and verify the new history renders cleanly without losing assistant messages.
            reloadResponse.complete(ItemsResponse(h2Items, hasMore = true, history = "h2", toSeq = 4))
            rule.waitUntil(5_000) {
                visibleTexts().any { it.contains("Assistant reply after rewrite") }
            }
            val afterReload = visibleTexts()
            assertTrue("style=$style: initial assistant reply kept after rewrite", afterReload.any { it.contains("Assistant reply visible one") })
            assertTrue("style=$style: new assistant reply displayed after rewrite", afterReload.any { it.contains("Assistant reply after rewrite") })
            assertTrue("style=$style: overtaking push preserved after rewrite", afterReload.any { it.contains("Follow-up after rewrite") })
            assertFalse("style=$style: older routed decision stays clipped after rewrite", afterReload.any { it.contains("Older routed decision") })
        } finally {
            scope.cancel()
        }
    }

    @Test fun glassPreservesAssistantRepliesDuringHistoryRewrite() =
        verifyHistoryRewritePreservesAssistantRepliesAndFeedClip(AppStyle.Glass)

    @Test fun briefPreservesAssistantRepliesDuringHistoryRewrite() =
        verifyHistoryRewritePreservesAssistantRepliesAndFeedClip(AppStyle.Brief)

    @Test fun boardPreservesAssistantRepliesDuringHistoryRewrite() =
        verifyHistoryRewritePreservesAssistantRepliesAndFeedClip(AppStyle.Board)

    @Test fun loadedEarliestHoldsBackFeedWhileLoadingOrBoundaryUnknown() {
        val ts = "2026-11-25T10:00:00Z"
        val items = listOf(Item("u1", 1, role = "user", kind = "text", text = "Hello", ts = ts))
        assertEquals(Instant.MAX, loadedEarliest(emptyList(), hasOlder = false, olderBoundaryKnown = true, loading = true))
        assertEquals(Instant.MAX, loadedEarliest(emptyList(), hasOlder = false, olderBoundaryKnown = false, loading = true))
        assertEquals(Instant.MAX, loadedEarliest(emptyList(), hasOlder = false, olderBoundaryKnown = false, loading = false))
        assertEquals(Instant.parse(ts), loadedEarliest(items, hasOlder = false, olderBoundaryKnown = false, loading = false))
        assertNull(loadedEarliest(emptyList(), hasOlder = false, olderBoundaryKnown = true, loading = false))
        assertNull(loadedEarliest(items, hasOlder = false, olderBoundaryKnown = true, loading = false))
    }

    private fun verifyColdOpenHoldsBackHistoricalRoutedFeed(style: AppStyle) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connection = FakeConnection()
            val firstPage = CompletableDeferred<ItemsResponse>()
            val transport = object : ChatTransport {
                override suspend fun items(session: SessionKey, query: ItemsQuery): ItemsResponse = firstPage.await()
                override suspend fun machines(): List<Machine> = listOf(Machine(key.machineId, "Computer A", online = true))
                override suspend fun sessions(): List<Session> = listOf(session)
                override suspend fun interactions(): List<Interaction> = emptyList()
                override suspend fun resolve(id: String, resolution: Resolution) = Unit
                override suspend fun image(session: SessionKey, itemId: String) = byteArrayOf()
                override suspend fun fullItem(session: SessionKey, itemId: String) = Item(itemId)
            }
            val sessions = object : SessionsRepository {
                override val machines = MutableStateFlow(listOf(Machine(key.machineId, "Computer A", online = true)))
                override val sessions = MutableStateFlow(listOf(session))
                override suspend fun refresh() = Unit
            }
            val chats = HubChatRepository(
                transport, connection, MemoryCache(), sessions, scope,
                continuityFactory = { chat, _ -> KotlinContinuityAdapter(chat) },
            )
            val sender = object : MessageSender {
                override val outbox = MutableStateFlow(emptyList<OutboxMessage>())
                override suspend fun send(session: SessionKey, text: String, clientMessageId: String, clearDraft: String?) = Unit
            }
            val interactions = object : InteractionCenter {
                override val pending = MutableStateFlow(emptyList<Interaction>())
                override suspend fun refresh() = Unit
                override suspend fun resolve(id: String, resolution: Resolution) = Unit
            }
            val oldRouted = FeedItem(
                id = "routed-before-first-page",
                conversation = key.sessionId,
                at = "2026-11-24T09:00:00Z",
                kind = "routed",
                body = buildJsonObject {
                    put("from", "user")
                    put("title", "Past task")
                    put("text", "Historical routed bubble must not flood loading state")
                },
            )
            val tasks = TasksStore(FakeTasksApi(listOf(oldRouted)), connection, scope)

            rule.setContent {
                PersonaTheme(darkTheme = true, reduceMotion = true, style = style) {
                    Box(Modifier.fillMaxSize().background(PersonaTheme.palette.background)) {
                        ChatScreen(
                            sessionKey = key,
                            repository = chats,
                            sender = sender,
                            interactions = interactions,
                            sessions = sessions,
                            onBack = {},
                            chrome = ChatChromeBindings(connection = connection, tasks = tasks, mainConversation = key.sessionId),
                            renderStyle = ChatRenderStyle.Assistant,
                            embedded = true,
                            showTopBar = false,
                        )
                    }
                }
            }
            rule.waitForIdle()
            assertFalse(
                "style=$style: historical routed feed must not flood screen before first page resolves",
                visibleTexts().any { it.contains("Historical routed bubble") },
            )
            firstPage.complete(ItemsResponse(
                listOf(
                    Item("u1#0", 1, role = "user", kind = "text", text = "Latest prompt", ts = "2026-11-25T10:00:00Z"),
                    Item("a1#0", 2, role = "assistant", kind = "text", text = "Latest assistant answer", ts = "2026-11-25T10:00:05Z"),
                ),
                hasMore = true,
                history = "h1",
                toSeq = 2,
            ))
            rule.waitUntil(5_000) { visibleTexts().any { it.contains("Latest assistant answer") } }
            assertFalse(
                "style=$style: older routed bubble remains clipped after first page with hasMore=true",
                visibleTexts().any { it.contains("Historical routed bubble") },
            )
        } finally {
            scope.cancel()
        }
    }

    @Test fun glassColdOpenHoldsBackHistoricalRoutedFeed() =
        verifyColdOpenHoldsBackHistoricalRoutedFeed(AppStyle.Glass)

    @Test fun briefColdOpenHoldsBackHistoricalRoutedFeed() =
        verifyColdOpenHoldsBackHistoricalRoutedFeed(AppStyle.Brief)

    @Test fun boardColdOpenHoldsBackHistoricalRoutedFeed() =
        verifyColdOpenHoldsBackHistoricalRoutedFeed(AppStyle.Board)

    private fun verifyHistorySwapWithQuietSupervisionRoundNeverFlashes(style: AppStyle) {
        val hiddenTrigger = Item(
            "sup-old#0", 1, role = "user", kind = "text", text = "Silent supervision trigger",
            ts = "2026-11-25T09:59:00Z",
            injected = Injected(kind = "system", state = "supervise", level = "info"),
        )
        val hiddenThought = Item("sup-old-work#0", 2, role = "assistant", kind = "thinking", text = "Quiet work")
        val h1 = listOf(
            hiddenTrigger,
            hiddenThought,
            Item("u-old#0", 3, role = "user", kind = "text", text = "Normal user prompt", ts = "2026-11-25T10:00:00Z"),
            Item("a-old#0", 4, role = "assistant", kind = "text", text = "Visible assistant reply h1", ts = "2026-11-25T10:00:05Z"),
        )
        val h2 = listOf(
            hiddenTrigger.copy(id = "sup-new#0"),
            hiddenThought.copy(id = "sup-new-work#0"),
            Item("u-new#0", 3, role = "user", kind = "text", text = "Normal user prompt", ts = "2026-11-25T10:00:00Z"),
            Item("a-new#0", 4, role = "assistant", kind = "text", text = "Visible assistant reply h2", ts = "2026-11-25T10:00:05Z"),
        )
        val flow = MutableStateFlow(ChatSnapshot(items = h1, loading = false, hasOlder = true, history = "h1", verified = true))
        val chats = object : persona.core.contracts.api.ChatRepository {
            override fun observe(session: SessionKey) = flow
            override suspend fun loadOlder(session: SessionKey) = Unit
            override suspend fun expandFold(session: SessionKey, fromSeq: Int, toSeq: Int) = emptyList<Item>()
        }
        val sender = object : MessageSender {
            override val outbox = MutableStateFlow(emptyList<OutboxMessage>())
            override suspend fun send(session: SessionKey, text: String, clientMessageId: String, clearDraft: String?) = Unit
        }
        val interactions = object : InteractionCenter {
            override val pending = MutableStateFlow(emptyList<Interaction>())
            override suspend fun refresh() = Unit
            override suspend fun resolve(id: String, resolution: Resolution) = Unit
        }
        val sessions = object : SessionsRepository {
            override val machines = MutableStateFlow(listOf(Machine(key.machineId, "Computer A", online = true)))
            override val sessions = MutableStateFlow(listOf(session))
            override suspend fun refresh() = Unit
        }

        rule.setContent {
            PersonaTheme(darkTheme = true, reduceMotion = true, style = style) {
                Box(Modifier.fillMaxSize().background(PersonaTheme.palette.background)) {
                    ChatScreen(
                        sessionKey = key,
                        repository = chats,
                        sender = sender,
                        interactions = interactions,
                        sessions = sessions,
                        onBack = {},
                        chrome = ChatChromeBindings(mainConversation = key.sessionId),
                        renderStyle = ChatRenderStyle.Assistant,
                        embedded = true,
                        showTopBar = false,
                    )
                }
            }
        }
        rule.waitUntil(5_000) { visibleTexts().any { it.contains("Visible assistant reply h1") } }
        assertFalse("style=$style: hidden supervision round must stay hidden before swap", visibleTexts().any { it.contains("Silent supervision trigger") })

        flow.value = ChatSnapshot(items = h2, loading = false, hasOlder = true, history = "h2", verified = true)
        assertFalse("style=$style: hidden supervision round must not flash during swap", visibleTexts().any { it.contains("Silent supervision trigger") })
        rule.waitUntil(5_000) { visibleTexts().any { it.contains("Visible assistant reply h2") } }
        assertFalse("style=$style: hidden supervision round must stay hidden after swap", visibleTexts().any { it.contains("Silent supervision trigger") })
    }

    @Test fun glassHistorySwapWithQuietSupervisionRoundNeverFlashes() =
        verifyHistorySwapWithQuietSupervisionRoundNeverFlashes(AppStyle.Glass)

    @Test fun briefHistorySwapWithQuietSupervisionRoundNeverFlashes() =
        verifyHistorySwapWithQuietSupervisionRoundNeverFlashes(AppStyle.Brief)

    @Test fun boardHistorySwapWithQuietSupervisionRoundNeverFlashes() =
        verifyHistorySwapWithQuietSupervisionRoundNeverFlashes(AppStyle.Board)
}
