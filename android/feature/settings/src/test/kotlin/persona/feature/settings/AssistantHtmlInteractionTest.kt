package persona.feature.settings

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.contracts.FeedItem
import persona.core.contracts.FeedResponse
import persona.core.contracts.JsonCodec
import persona.core.contracts.TaskPlan
import persona.core.contracts.Todo
import persona.core.contracts.api.AssistantViewAnswer
import persona.core.contracts.api.TasksApi
import persona.core.contracts.generated.ExtensionAssistantHTMLActionInput
import persona.core.contracts.generated.ExtensionAssistantHTMLActionKind
import persona.core.contracts.generated.ExtensionViewAction
import persona.core.contracts.generated.ExtensionViewActionInput
import persona.core.contracts.generated.ExtensionViewActionKind
import persona.core.designsystem.AppFeedback
import persona.core.designsystem.AppStyle
import persona.core.designsystem.LocalAppFeedback
import persona.core.designsystem.LocalAssistantCards
import persona.core.designsystem.PersonaTheme
import persona.core.domain.views.AssistantCardActions
import persona.core.domain.views.AssistantCardsHost
import persona.core.domain.views.AssistantViewStore

/**
 * End-to-end Compose UI interaction tests for Android `persona-view` isolated HTML cards and JS bridge (W-D01):
 * visibility-driven auto-run and complete WebView destruction, lifecycle ON_STOP teardown, Stop/Retry/crash/unavailable
 * fallbacks, concurrency budget, shared single-submit `persona.reply` + native confirmation, strict `persona.open`
 * validation + in-app opening (no external browser), Glass/Brief/Board + light/dark consistency, >=48dp touch targets,
 * and EN/ZH/JA localization completeness.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w400dp-h800dp-xhdpi")
class AssistantHtmlInteractionTest {
    @get:Rule val rule = createComposeRule()

    private class TestLifecycleOwner(initial: Lifecycle.State = Lifecycle.State.RESUMED) : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = initial }
        override val lifecycle: Lifecycle get() = registry
    }

    private class RecordingRuntime(
        var listenerSupported: Boolean = true,
        var failCreate: Boolean = false,
    ) : AssistantHtmlRuntime {
        var now: Long = 10_000L
        var createdCount = 0
        var destroyedCount = 0
        var detachedCount = 0
        val activeViews = mutableListOf<WebView>()
        val loadedDocuments = mutableListOf<String>()
        val nonces = mutableListOf<String>()
        val listeners = linkedMapOf<WebView, (String?, Boolean) -> Unit>()

        override fun supportsWebMessageListener(): Boolean = listenerSupported
        override fun supportsMultiProfile(): Boolean = true
        override fun createWebView(context: Context): WebView? {
            if (failCreate) return null
            val view = WebView(context)
            createdCount++
            activeViews += view
            return view
        }
        override fun isolateProfile(view: WebView) = Unit
        override fun configureCookies(view: WebView) = Unit
        override fun attachBridge(view: WebView, onMessage: (data: String?, isMainFrame: Boolean) -> Unit) {
            listeners[view] = onMessage
        }
        override fun detachBridge(view: WebView) {
            if (listeners.remove(view) != null) detachedCount++
        }
        override fun loadDocument(view: WebView, document: String) {
            loadedDocuments += document
        }
        override fun destroyWebView(view: WebView) {
            detachBridge(view)
            val _ = activeViews.remove(view)
            destroyedCount++
        }
        override fun newNonce(): String = "TestNonce_${nonces.size + 1}_1234567890".also { nonces += it }
        override fun nowMs(): Long = now

        fun postFromLatest(payload: String, mainFrame: Boolean = true) {
            val view = activeViews.last()
            val listener = listeners[view] ?: error("No bridge listener attached")
            listener(payload, mainFrame)
        }

        fun postReply(text: String, nonce: String = nonces.last(), mainFrame: Boolean = true) {
            val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            postFromLatest("""{"nonce":"$nonce","kind":"reply","text":"$escaped"}""", mainFrame)
        }

        fun postOpen(url: String, nonce: String = nonces.last(), mainFrame: Boolean = true) {
            val escaped = url.replace("\\", "\\\\").replace("\"", "\\\"")
            postFromLatest("""{"nonce":"$nonce","kind":"open","url":"$escaped"}""", mainFrame)
        }

        fun crashLatestRenderer() {
            val view = activeViews.last()
            view.webViewClient.onRenderProcessGone(view, null)
        }
    }

    private class FakeTasksApi(
        initialFeatures: Set<String> = setOf("assistant_views", "assistant_html_bridge"),
    ) : TasksApi {
        override val features = MutableStateFlow(initialFeatures)
        val viewActions = mutableListOf<Pair<String, ExtensionViewActionInput>>()
        val htmlActions = mutableListOf<Pair<String, ExtensionAssistantHTMLActionInput>>()

        override suspend fun todos(): List<Todo> = emptyList()
        override suspend fun feed(conversation: String, since: String?): FeedResponse = FeedResponse()
        override suspend fun startPlan(id: String, remember: Boolean): TaskPlan = error("unused")
        override suspend fun dismissPlan(id: String): TaskPlan = error("unused")
        override suspend fun retryTask(todoId: String): Todo = error("unused")
        override suspend fun removeTask(todoId: String) = Unit
        override suspend fun route(conversation: String, todoId: String, text: String): FeedItem = error("unused")
        override suspend fun today(conversation: String): FeedItem = error("unused")
        override suspend fun mainConversation(): String? = null
        override suspend fun inboxReply(itemId: String, text: String, card: String?) = Unit

        override suspend fun viewAction(conversation: String, key: String, input: ExtensionViewActionInput): AssistantViewAnswer {
            viewActions += key to input
            return AssistantViewAnswer.Recorded(
                ExtensionViewAction(
                    key = key,
                    action = input.action,
                    page = input.page,
                    values = input.values,
                    clientMsgId = input.clientMsgId,
                    status = "sent",
                    revision = 1,
                    at = "2026-11-25T10:00:00Z",
                    kind = ExtensionViewActionKind.ACTION,
                    version = input.expectedVersion,
                ),
            )
        }

        override suspend fun viewHtmlAction(conversation: String, key: String, input: ExtensionAssistantHTMLActionInput): AssistantViewAnswer {
            htmlActions += key to input
            return if (input.kind == ExtensionAssistantHTMLActionKind.OPEN) {
                AssistantViewAnswer.Open(input.url.orEmpty(), input.expectedVersion)
            } else {
                AssistantViewAnswer.Recorded(
                    ExtensionViewAction(
                        key = key,
                        action = input.componentKey,
                        page = input.page,
                        clientMsgId = input.clientMsgId,
                        status = "sent",
                        revision = 1,
                        at = "2026-11-25T10:00:00Z",
                        kind = ExtensionViewActionKind.HTML,
                        version = input.expectedVersion,
                        text = input.text,
                    ),
                )
            }
        }
    }

    private class RecordingFeedback : AppFeedback {
        val notices = mutableListOf<String>()
        override fun failure(error: Throwable, operation: Boolean): Boolean = true
        override fun notice(message: String): Boolean {
            notices += message
            return true
        }
    }

    private fun json(text: String): JsonElement = JsonCodec.parseToJsonElement(text)

    private fun htmlCardJson(
        title: String = "Interactive Guide",
        fallback: String = "Plain text fallback summary",
        htmlKey: String = "guide",
        height: Int = 240,
        includeNativeButton: Boolean = true,
    ): String {
        val button = if (includeNativeButton) {
            """,{"type":"button","title":"Native Reply","action":{"id":"native-reply","reply":"Native choice"}}"""
        } else ""
        return """{
            "title":"$title",
            "fallback":"$fallback",
            "sections":[{"items":[
                {"type":"html","key":"$htmlKey","title":"$title","height":$height,"html":"<main><canvas width='120' height='60'></canvas><button onclick=\"persona.reply('Choose B')\">Choose B</button></main>"}
                $button
            ]}]
        }"""
    }

    private fun host(
        api: FakeTasksApi = FakeTasksApi(),
        interactive: Boolean = true,
        openFile: ((String, String) -> Unit)? = null,
    ): AssistantCardsHost {
        val store = AssistantViewStore()
        return AssistantCardsHost("c1", store, AssistantCardActions(api, store, reload = {}), interactive, openFile)
    }

    @Test fun autoRunsOnEnteringVisibilityAndDestroysCompletelyOnScrollOutBackgroundAndDispose() {
        val runtime = RecordingRuntime()
        val budget = HtmlExecutionBudget(2)
        val lifecycleOwner = TestLifecycleOwner(Lifecycle.State.RESUMED)
        val api = FakeTasksApi()
        val h = host(api)
        h.store.block("c1", "i1/0")
        var showCard by mutableStateOf(true)
        lateinit var scrollState: androidx.compose.foundation.ScrollState

        rule.setContent {
            scrollState = rememberScrollState()
            CompositionLocalProvider(
                LocalLifecycleOwner provides lifecycleOwner,
                LocalAssistantHtmlRuntime provides runtime,
                LocalHtmlExecutionBudget provides budget,
                LocalAssistantCards provides h,
            ) {
                PersonaTheme(darkTheme = false, reduceMotion = true, style = AppStyle.Glass) {
                    Box(Modifier.fillMaxWidth().height(360.dp)) {
                        Column(Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                            Spacer(Modifier.height(900.dp))
                            if (showCard) AssistantBlock("i1/0", htmlCardJson(), complete = true)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        // Initially below the 360dp viewport: no WebView created, fallback shown.
        assertEquals(0, runtime.createdCount)
        assertEquals(0, runtime.activeViews.size)
        assertTrue(budget.owners.value.isEmpty())
        rule.onNodeWithText("Plain text fallback summary").assertExists()

        // Scroll into viewport: WebView auto-runs and attaches bridge with nonce.
        rule.runOnUiThread { val _ = scrollState.dispatchRawDelta(scrollState.maxValue.toFloat()) }
        rule.waitForIdle()
        assertEquals(1, runtime.createdCount)
        assertEquals(1, runtime.activeViews.size)
        assertEquals(1, budget.owners.value.size)
        assertEquals(1, runtime.loadedDocuments.size)
        assertTrue(runtime.loadedDocuments.last().contains(runtime.nonces.last()))

        // Scroll back out of viewport: WebView destroyed immediately and slot released.
        rule.runOnUiThread { val _ = scrollState.dispatchRawDelta(-scrollState.maxValue.toFloat()) }
        rule.waitForIdle()
        assertEquals(0, runtime.activeViews.size)
        assertEquals(1, runtime.destroyedCount)
        assertEquals(1, runtime.detachedCount)
        assertTrue(budget.owners.value.isEmpty())

        // Scroll back in: fresh WebView and fresh nonce.
        rule.runOnUiThread { val _ = scrollState.dispatchRawDelta(scrollState.maxValue.toFloat()) }
        rule.waitForIdle()
        assertEquals(2, runtime.createdCount)
        assertEquals(1, runtime.activeViews.size)
        assertNotEquals(runtime.nonces[0], runtime.nonces[1])

        // App backgrounded (ON_STOP): destroys WebView so no background execution persists.
        rule.runOnUiThread { lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        rule.waitForIdle()
        assertEquals(0, runtime.activeViews.size)
        assertEquals(2, runtime.destroyedCount)
        assertTrue(budget.owners.value.isEmpty())

        // Returning to foreground (ON_START -> ON_RESUME): auto-resumes visible card.
        rule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        rule.waitForIdle()
        assertEquals(3, runtime.createdCount)
        assertEquals(1, runtime.activeViews.size)

        // Leaving composition: destroys WebView and releases budget unconditionally.
        showCard = false
        rule.waitForIdle()
        assertEquals(0, runtime.activeViews.size)
        assertEquals(3, runtime.destroyedCount)
        assertTrue(budget.owners.value.isEmpty())
    }

    @Test fun personaReplySharesSingleSubmitStateWithNativeButtonsAndRequiresConfirmation() {
        val runtime = RecordingRuntime()
        val budget = HtmlExecutionBudget(2)
        val api = FakeTasksApi()
        val feedback = RecordingFeedback()
        val h = host(api)
        h.store.block("c1", "i1/0")

        rule.setContent {
            CompositionLocalProvider(
                LocalAssistantHtmlRuntime provides runtime,
                LocalHtmlExecutionBudget provides budget,
                LocalAssistantCards provides h,
                LocalAppFeedback provides feedback,
            ) {
                PersonaTheme(darkTheme = false, reduceMotion = true, style = AppStyle.Glass) {
                    AssistantBlock("i1/0", htmlCardJson(includeNativeButton = true), complete = true)
                }
            }
        }
        rule.waitForIdle()
        assertEquals(1, runtime.activeViews.size)

        // Trigger persona.reply: confirmation dialog shows verbatim text + password warning; nothing sent yet.
        rule.runOnUiThread { runtime.postReply("Choose B") }
        rule.waitForIdle()
        assertTrue(api.htmlActions.isEmpty())
        rule.onNodeWithText("The page wants to reply for you").assertIsDisplayed()
        rule.onNodeWithText("Choose B", substring = true).assertIsDisplayed()
        rule.onNodeWithText("passwords or sensitive credentials", substring = true).assertIsDisplayed()

        // Cancel first: nothing sent and refusal cooldown blocks immediate flood.
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(api.htmlActions.isEmpty())
        rule.runOnUiThread { runtime.postReply("Flood immediately") }
        rule.waitForIdle()
        rule.onAllNodesWithText("The page wants to reply for you").let { assertEquals(0, it.fetchSemanticsNodes().size) }

        // After quiet cooldown expires, user confirms Send -> submits once and locks BOTH HTML and native replies.
        runtime.now += HtmlBridgePolicy.RefusedQuietMs
        rule.runOnUiThread { runtime.postReply("Choose B") }
        rule.waitForIdle()
        rule.onNodeWithText("Send").performClick()
        rule.waitForIdle()

        assertEquals(1, api.htmlActions.size)
        val (key, input) = api.htmlActions.single()
        assertEquals("i1/0", key)
        assertEquals(ExtensionAssistantHTMLActionKind.REPLY, input.kind)
        assertEquals("guide", input.componentKey)
        assertEquals("Choose B", input.text)
        assertEquals(1L, input.expectedVersion)
        assertTrue(input.confirmed)
        // Submitted reply text and Sent status are visible; native button is locked/removed.
        rule.onNodeWithText("Sent").assertIsDisplayed()
        rule.onNodeWithText("Choose B").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Native Reply").fetchSemanticsNodes().size)

        // Subsequent persona.reply from HTML is rejected because card is already answered.
        rule.runOnUiThread { runtime.postReply("Second attempt") }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("The page wants to reply for you").fetchSemanticsNodes().size)
        assertEquals(1, api.htmlActions.size)
    }

    @Test fun nativeReplyLocksHtmlReplyWhilePersonaOpenRemainsUsableInApp() {
        val runtime = RecordingRuntime()
        val budget = HtmlExecutionBudget(2)
        val api = FakeTasksApi()
        val openedUris = mutableListOf<String>()
        val openedFiles = mutableListOf<Pair<String, String>>()
        val h = host(api, openFile = { m, p -> openedFiles += m to p })
        h.store.block("c1", "i1/0")

        rule.setContent {
            CompositionLocalProvider(
                LocalAssistantHtmlRuntime provides runtime,
                LocalHtmlExecutionBudget provides budget,
                LocalAssistantCards provides h,
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { openedUris += uri }
                },
            ) {
                PersonaTheme(darkTheme = false, reduceMotion = true, style = AppStyle.Brief) {
                    AssistantBlock("i1/0", htmlCardJson(includeNativeButton = true), complete = true)
                }
            }
        }
        rule.waitForIdle()

        // Answer the card using the native declarative button first.
        rule.onNodeWithText("Native Reply").performClick()
        rule.waitForIdle()
        assertEquals(1, api.viewActions.size)

        // HTML persona.reply is now blocked (shared single-submit state machine).
        rule.runOnUiThread { runtime.postReply("Should be blocked") }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("The page wants to reply for you").fetchSemanticsNodes().size)
        assertTrue(api.htmlActions.isEmpty())

        // Disallowed schemes never even open a confirmation dialog.
        for (bad in listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "content://media/1",
            "intent://app",
            "tel:10086",
            "http://example.com",
            "persona://pair?url=https://example.com",
            "custom://bad",
        )) {
            rule.runOnUiThread { runtime.postOpen(bad) }
            rule.waitForIdle()
            assertEquals("Dialog must not open for $bad", 0, rule.onAllNodesWithText("The page wants to open a link").fetchSemanticsNodes().size)
        }
        assertTrue(openedUris.isEmpty())
        assertTrue(openedFiles.isEmpty())

        // Valid https + session + task + kb + file links all require confirmation and open inside the app.
        val targets = listOf(
            "https://example.com/guide",
            "persona://session/my-mac/s-1",
            "persona://task/todo_42",
            "persona://kb/docs/notes/plan.md",
            "persona://kb/people",
            "persona://file/my-mac/%2Ftmp%2Freport.md",
        )
        for (url in targets) {
            rule.runOnUiThread { runtime.postOpen(url) }
            rule.waitForIdle()
            rule.onNodeWithText("The page wants to open a link").assertIsDisplayed()
            rule.onNodeWithText(url, substring = true).assertIsDisplayed()
            rule.onNodeWithText("Open").performClick()
            rule.waitForIdle()
        }

        assertEquals(targets.size, api.htmlActions.size)
        assertTrue(api.htmlActions.all { it.second.kind == ExtensionAssistantHTMLActionKind.OPEN && it.second.confirmed })
        assertEquals(
            listOf(
                "https://example.com/guide",
                "persona://session/my-mac/s-1",
                "persona://task/todo_42",
                "persona://kb/docs/notes/plan.md",
                "persona://open/kb/people",
            ),
            openedUris,
        )
        assertEquals(listOf("my-mac" to "/tmp/report.md"), openedFiles)
    }

    @Test fun liveCardVersionUpdateRevokesOpenConfirmationDialog() {
        val runtime = RecordingRuntime()
        val budget = HtmlExecutionBudget(2)
        val api = FakeTasksApi()
        val feedback = RecordingFeedback()
        val h = host(api)
        fun liveView(version: Long, label: String) =
            json("""{"id":"view_1","version":$version,"view":${htmlCardJson(title = label, includeNativeButton = false)}}""")
        h.store.view("c1", liveView(1, "V1"))

        rule.setContent {
            CompositionLocalProvider(
                LocalAssistantHtmlRuntime provides runtime,
                LocalHtmlExecutionBudget provides budget,
                LocalAssistantCards provides h,
                LocalAppFeedback provides feedback,
            ) {
                PersonaTheme(darkTheme = false, reduceMotion = true, style = AppStyle.Board) {
                    AssistantLiveCard("view_1")
                }
            }
        }
        rule.waitForIdle()
        rule.runOnUiThread { runtime.postReply("Confirm V1") }
        rule.waitForIdle()
        rule.onNodeWithText("The page wants to reply for you").assertIsDisplayed()

        // Hub pushes view_update v2 while v1 dialog is open -> dialog revoked, stale v1 never submitted.
        h.store.view("c1", liveView(2, "V2"))
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("The page wants to reply for you").fetchSemanticsNodes().size)
        assertTrue(api.htmlActions.isEmpty())
        assertTrue(feedback.notices.isNotEmpty())
        // WebView recreated cleanly for v2 with a fresh nonce.
        assertEquals(2, runtime.createdCount)
        assertEquals(1, runtime.destroyedCount)
        assertEquals(1, runtime.activeViews.size)
    }

    @Test fun externalBrowserHandlerIsRefusedForPersonaOpen() {
        val runtime = RecordingRuntime()
        val budget = HtmlExecutionBudget(2)
        val api = FakeTasksApi()
        val feedback = RecordingFeedback()
        val h = host(api)
        h.store.block("c1", "i1/0")
        val context = ApplicationProvider.getApplicationContext<Context>()

        rule.setContent {
            CompositionLocalProvider(
                LocalAssistantHtmlRuntime provides runtime,
                LocalHtmlExecutionBudget provides budget,
                LocalAssistantCards provides h,
                LocalAppFeedback provides feedback,
                LocalUriHandler provides AndroidUriHandler(context),
            ) {
                PersonaTheme(darkTheme = false, reduceMotion = true, style = AppStyle.Glass) {
                    AssistantBlock("i1/0", htmlCardJson(includeNativeButton = false), complete = true)
                }
            }
        }
        rule.waitForIdle()
        rule.runOnUiThread { runtime.postOpen("https://example.com/in-app-only") }
        rule.waitForIdle()
        rule.onNodeWithText("Open").performClick()
        rule.waitForIdle()
        // Outside ChatLinkHost (AndroidUriHandler), refuses external browser fallback and notifies user.
        assertTrue(feedback.notices.any { it.contains("can't open", ignoreCase = true) })
    }

    @Test fun stopRetryCrashUnavailableAndMissingHubFeatureDegradeGracefullyToFallback() {
        val runtime = RecordingRuntime()
        val budget = HtmlExecutionBudget(2)
        val api = FakeTasksApi()
        val h = host(api)
        h.store.block("c1", "i1/0")

        rule.setContent {
            CompositionLocalProvider(
                LocalAssistantHtmlRuntime provides runtime,
                LocalHtmlExecutionBudget provides budget,
                LocalAssistantCards provides h,
            ) {
                PersonaTheme(darkTheme = false, reduceMotion = true, style = AppStyle.Glass) {
                    AssistantBlock("i1/0", htmlCardJson(includeNativeButton = false), complete = true)
                }
            }
        }
        rule.waitForIdle()
        assertEquals(1, runtime.activeViews.size)

        // Stop destroys WebView and shows fallback + Retry button.
        rule.onNodeWithTag("assistant_html_stop").performClick()
        rule.waitForIdle()
        assertEquals(0, runtime.activeViews.size)
        assertEquals(1, runtime.destroyedCount)
        rule.onNodeWithTag("assistant_html_fallback").assertIsDisplayed()
        rule.onNodeWithTag("assistant_html_retry").assertIsDisplayed()

        // Retry creates fresh WebView.
        rule.onNodeWithTag("assistant_html_retry").performClick()
        rule.waitForIdle()
        assertEquals(2, runtime.createdCount)
        assertEquals(1, runtime.activeViews.size)

        // Renderer process crash triggers graceful degradation to fallback + Retry.
        rule.runOnUiThread { runtime.crashLatestRenderer() }
        rule.waitForIdle()
        assertEquals(0, runtime.activeViews.size)
        assertEquals(2, runtime.destroyedCount)
        rule.onNodeWithText("The page stopped unexpectedly.").assertIsDisplayed()
        rule.onNodeWithTag("assistant_html_fallback").assertIsDisplayed()

        // WebView unavailable on create degrades cleanly without crashing.
        runtime.failCreate = true
        rule.onNodeWithTag("assistant_html_retry").performClick()
        rule.waitForIdle()
        assertEquals(0, runtime.activeViews.size)
        rule.onNodeWithTag("assistant_html_fallback").assertIsDisplayed()

        // Hub without assistant_html_bridge degrades directly to fallback without creating a WebView.
        runtime.failCreate = false
        api.features.value = setOf("assistant_views")
        rule.waitForIdle()
        assertEquals(0, runtime.activeViews.size)
        assertEquals(0, rule.onAllNodesWithTag("assistant_html_webview").fetchSemanticsNodes().size)
        rule.onNodeWithText("Plain text fallback summary").assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "en-w400dp-h1200dp-xhdpi")
    @Test fun concurrencyBudgetCapsRunningCardsAtTwoAndRunButtonEvictsOldest() {
        val runtime = RecordingRuntime()
        val budget = HtmlExecutionBudget(2)
        val api = FakeTasksApi()
        val h = host(api)
        h.store.snapshot("c1", emptyMap(), emptyMap(), listOf("i1/0", "i1/1", "i1/2"))

        rule.setContent {
            CompositionLocalProvider(
                LocalAssistantHtmlRuntime provides runtime,
                LocalHtmlExecutionBudget provides budget,
                LocalAssistantCards provides h,
            ) {
                PersonaTheme(darkTheme = false, reduceMotion = true, style = AppStyle.Glass) {
                    Column(Modifier.fillMaxWidth()) {
                        AssistantBlock("i1/0", htmlCardJson(title = "Card 1", htmlKey = "k1", height = 160, includeNativeButton = false), complete = true)
                        AssistantBlock("i1/1", htmlCardJson(title = "Card 2", htmlKey = "k2", height = 160, includeNativeButton = false), complete = true)
                        AssistantBlock("i1/2", htmlCardJson(title = "Card 3", htmlKey = "k3", height = 160, includeNativeButton = false), complete = true)
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(2, runtime.activeViews.size)
        assertEquals(2, budget.owners.value.size)
        rule.onNodeWithTag("assistant_html_run").assertIsDisplayed()

        // Clicking Run on 3rd card evicts 1st card cleanly and starts 3rd card.
        rule.onNodeWithTag("assistant_html_run").performClick()
        rule.waitForIdle()
        assertEquals(2, runtime.activeViews.size)
        assertEquals(3, runtime.createdCount)
        assertEquals(1, runtime.destroyedCount)
    }

    private data class StyleCase(
        val id: Int,
        val style: AppStyle,
        val dark: Boolean,
        val runtime: RecordingRuntime,
        val budget: HtmlExecutionBudget,
        val api: FakeTasksApi,
        val host: AssistantCardsHost,
        val opened: MutableList<String>,
    )

    @Test fun glassBriefAndBoardShareIdenticalStateSecurityAndTouchTargetsInLightAndDark() {
        val cases = AppStyle.entries.flatMap { style ->
            listOf(false, true).map { dark -> style to dark }
        }
        fun newCase(index: Int, style: AppStyle, dark: Boolean): StyleCase {
            val api = FakeTasksApi()
            val h = host(api)
            h.store.block("c1", "i$index/0")
            return StyleCase(index, style, dark, RecordingRuntime(), HtmlExecutionBudget(2), api, h, mutableListOf())
        }
        var current by mutableStateOf(newCase(0, cases[0].first, cases[0].second))
        var density = 1f

        rule.setContent {
            density = LocalDensity.current.density
            val active = current
            key(active.id) {
                CompositionLocalProvider(
                    LocalAssistantHtmlRuntime provides active.runtime,
                    LocalHtmlExecutionBudget provides active.budget,
                    LocalAssistantCards provides active.host,
                    LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) { active.opened += uri }
                    },
                ) {
                    PersonaTheme(darkTheme = active.dark, reduceMotion = true, style = active.style) {
                        AssistantBlock("i${active.id}/0", htmlCardJson(includeNativeButton = false), complete = true)
                    }
                }
            }
        }

        cases.forEachIndexed { index, (style, dark) ->
            if (index > 0) {
                current = newCase(index, style, dark)
            }
            val active = current
            rule.waitForIdle()
            assertEquals("$style dark=$dark must auto-run", 1, active.runtime.activeViews.size)
            assertTrue(
                "$style dark=$dark must inject matching color-scheme",
                active.runtime.loadedDocuments.last().contains("color-scheme\" content=\"${if (dark) "dark" else "light"}\""),
            )

            // Verify Stop button touch target >= 48dp in every style and appearance.
            val stopHeightDp = rule.onNodeWithTag("assistant_html_stop").fetchSemanticsNode().boundsInRoot.height / density
            assertTrue("$style dark=$dark Stop button height $stopHeightDp dp < 48dp", stopHeightDp >= 48f)

            // Bridge open and reply behave identically in Glass, Brief, and Board.
            rule.runOnUiThread { active.runtime.postOpen("https://example.com/${style.name.lowercase()}") }
            rule.waitForIdle()
            rule.onNodeWithText("Open").performClick()
            rule.waitForIdle()
            assertEquals(listOf("https://example.com/${style.name.lowercase()}"), active.opened)

            rule.runOnUiThread { active.runtime.postReply("Reply in ${style.name}") }
            rule.waitForIdle()
            rule.onNodeWithText("Send").performClick()
            rule.waitForIdle()
            assertEquals(2, active.api.htmlActions.size)

            // Stop + verify Retry button touch target >= 48dp.
            rule.onNodeWithTag("assistant_html_stop").performClick()
            rule.waitForIdle()
            val retryHeightDp = rule.onNodeWithTag("assistant_html_retry").fetchSemanticsNode().boundsInRoot.height / density
            assertTrue("$style dark=$dark Retry button height $retryHeightDp dp < 48dp", retryHeightDp >= 48f)
            assertEquals(0, active.runtime.activeViews.size)
        }
    }

    @Test fun trilingualHtmlCardStringsExistAndFormatInEnZhAndJa() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val locales = listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.JAPANESE)
        val plainIds = listOf(
            R.string.settings_card_html_title,
            R.string.settings_card_html_badge,
            R.string.settings_card_html_fallback,
            R.string.settings_card_html_stopped,
            R.string.settings_card_html_crashed,
            R.string.settings_card_html_too_many,
            R.string.settings_card_html_stop,
            R.string.settings_card_html_run,
            R.string.settings_card_html_retry,
            R.string.settings_card_html_reply_title,
            R.string.settings_card_html_send,
            R.string.settings_card_html_open_title,
            R.string.settings_card_html_open,
        )
        for (locale in locales) {
            val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
            val res = base.createConfigurationContext(config).resources
            for (id in plainIds) {
                assertTrue("Empty string for $locale id=$id", res.getString(id).isNotBlank())
            }
            val replyPrompt = res.getString(R.string.settings_card_html_reply_text, "Choose B")
            assertTrue("Reply prompt missing payload in $locale", replyPrompt.contains("Choose B"))
            val openPrompt = res.getString(R.string.settings_card_html_open_text, "https://example.com")
            assertTrue("Open prompt missing URL in $locale", openPrompt.contains("https://example.com"))
        }
    }
}
