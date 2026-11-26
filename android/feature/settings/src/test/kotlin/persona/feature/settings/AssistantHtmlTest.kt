package persona.feature.settings

import android.content.Context
import android.net.Uri
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import persona.core.domain.views.AssistantCards
import persona.core.domain.views.AssistantOpen

/**
 * Unit and security-boundary verification for Android's isolated `persona-view` HTML sandbox and JS bridge (W-D01).
 *
 * Robolectric's WebView does not run Chromium's Blink/V8 engine, so these tests verify every configured sandbox flag,
 * CSP/document wrapper invariant, request/navigation interception matrix, Chrome client denial, bridge nonce/payload/
 * rate-limit policy, concurrency budget, and visibility threshold directly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AssistantHtmlTest {
    private class StubRuntime(private val supportsListener: Boolean, private val supportsProfile: Boolean = true) : AssistantHtmlRuntime {
        var isolatedProfiles = 0
        var cookieConfigs = 0
        override fun supportsWebMessageListener(): Boolean = supportsListener
        override fun supportsMultiProfile(): Boolean = supportsProfile
        override fun createWebView(context: Context): WebView? = WebView(context)
        override fun isolateProfile(view: WebView) { isolatedProfiles++ }
        override fun configureCookies(view: WebView) { cookieConfigs++ }
        override fun attachBridge(view: WebView, onMessage: (data: String?, isMainFrame: Boolean) -> Unit) = Unit
        override fun detachBridge(view: WebView) = Unit
        override fun loadDocument(view: WebView, document: String) = Unit
        override fun destroyWebView(view: WebView) = Unit
    }

    private fun webRequest(url: String, mainFrame: Boolean = false): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    @Test fun sandboxIsEnabledWhenWebMessageListenerIsSupportedAndFallsBackOtherwise() {
        assertTrue(AssistantHtmlSandbox.Accepted)
        assertTrue(AssistantHtmlSandbox.runnable(StubRuntime(supportsListener = true)))
        assertFalse(AssistantHtmlSandbox.runnable(StubRuntime(supportsListener = false)))
    }

    @Test fun thePolicyAndPreludeComeBeforeAllCardMarkup() {
        val nonce = "TestNonce_1234567890"
        val doc = AssistantHtmlSandbox.document(
            html = "<meta http-equiv=\"refresh\" content=\"0;url=https://example.com\"><input type=\"password\"><script>fetch('https://example.com')</script>",
            bridge = true,
            nonce = nonce,
            dark = true,
        )
        val policy = doc.indexOf("Content-Security-Policy")
        val refresh = doc.indexOf("refresh")
        val persona = doc.indexOf("'persona'")
        assertTrue(policy in 0 until refresh)
        assertTrue(policy < persona)
        assertTrue(persona < refresh)
        assertTrue(doc.contains("color-scheme\" content=\"dark\""))
        assertTrue("Nonce is bound into bridged prelude", doc.contains(nonce))
        assertTrue("Raw listener handle is removed after capture", doc.contains("delete window.${AssistantHtmlSandbox.BridgeObject}"))
        assertTrue("Password inputs are hardened in CSS and prelude", doc.contains("-webkit-text-security:none") && doc.contains("HTMLInputElement.prototype"))
        for (rule in listOf(
            "default-src 'none'",
            "script-src 'unsafe-inline'",
            "style-src 'unsafe-inline'",
            "img-src data:",
            "font-src data:",
            "media-src 'none'",
            "connect-src 'none'",
            "frame-src 'none'",
            "child-src 'none'",
            "worker-src 'none'",
            "object-src 'none'",
            "manifest-src 'none'",
            "form-action 'none'",
            "base-uri 'none'",
        )) {
            assertTrue("Missing CSP directive: $rule", AssistantHtmlSandbox.Policy.contains(rule))
        }

        val unbridged = AssistantHtmlSandbox.document("<p>offline</p>", bridge = false, nonce = nonce, dark = false)
        assertFalse("Unbridged page must not reference bridge handle", unbridged.contains(AssistantHtmlSandbox.BridgeObject))
        assertFalse("Unbridged page must not embed bridge nonce", unbridged.contains(nonce))
        assertTrue(unbridged.contains("color-scheme\" content=\"light\""))
    }

    @Test fun generatedNoncesAreUniqueAndUrlSafe() {
        val a = AssistantHtmlSandbox.generateNonce()
        val b = AssistantHtmlSandbox.generateNonce()
        assertNotEquals(a, b)
        assertTrue(a.matches(Regex("^[A-Za-z0-9_-]{16,128}$")))
        assertTrue(b.matches(Regex("^[A-Za-z0-9_-]{16,128}$")))
    }

    @Test fun theWebViewEnforcesOfflineZeroPermissionZeroStorageIsolation() {
        val view = WebView(ApplicationProvider.getApplicationContext())
        val runtime = StubRuntime(supportsListener = true)
        var blocked = 0
        var gone = 0
        AssistantHtmlSandbox.configure(view, blocked = { blocked++ }, gone = { gone++ }, runtime = runtime)
        assertEquals(1, runtime.isolatedProfiles)
        assertEquals(1, runtime.cookieConfigs)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, view.importantForAutofill)
        assertFalse(view.isLongClickable)
        with(view.settings) {
            assertTrue(javaScriptEnabled)
            assertFalse(domStorageEnabled)
            @Suppress("DEPRECATION") assertFalse(databaseEnabled)
            assertFalse(allowFileAccess)
            assertFalse(allowContentAccess)
            @Suppress("DEPRECATION") assertFalse(allowFileAccessFromFileURLs)
            @Suppress("DEPRECATION") assertFalse(allowUniversalAccessFromFileURLs)
            assertTrue(blockNetworkLoads)
            assertTrue(blockNetworkImage)
            assertTrue(loadsImagesAutomatically)
            assertEquals(WebSettings.LOAD_NO_CACHE, cacheMode)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
            assertFalse(supportMultipleWindows())
            assertFalse(javaScriptCanOpenWindowsAutomatically)
            assertTrue(mediaPlaybackRequiresUserGesture)
            assertFalse(supportZoom())
            assertFalse(builtInZoomControls)
            assertFalse(displayZoomControls)
            @Suppress("DEPRECATION") assertFalse(saveFormData)
        }
    }

    @Test fun subresourceAndNavigationInterceptionBlocksEverythingExceptInlineDataImages() {
        val view = WebView(ApplicationProvider.getApplicationContext())
        var blocked = 0
        var gone = 0
        val client = AssistantHtmlSandbox.SandboxClient(blocked = { blocked++ }, gone = { gone++ })

        // Allowed: non-main-frame inline data:image/* subresources only.
        val allowedImages = listOf(
            "data:image/png;base64,iVBORw0KGgo=",
            "data:image/jpeg;base64,/9j/4AAQ=",
            "data:image/gif;base64,R0lGODlh",
            "data:image/webp;base64,UklGRg==",
            "data:image/svg+xml;charset=utf-8,<svg/>",
        )
        for (img in allowedImages) {
            assertNull("Expected inline image subresource allowed: $img", client.shouldInterceptRequest(view, webRequest(img, mainFrame = false)))
        }
        assertEquals(0, blocked)

        // Blocked: every external network, websocket, local file/content, script, intent, html data:, or main-frame load.
        val deniedRequests = listOf(
            webRequest("https://example.com/pixel.png", mainFrame = false),
            webRequest("http://127.0.0.1:8080/exfil", mainFrame = false),
            webRequest("ws://example.com/socket", mainFrame = false),
            webRequest("wss://example.com/socket", mainFrame = false),
            webRequest("file:///etc/passwd", mainFrame = false),
            webRequest("file:///android_asset/secret", mainFrame = false),
            webRequest("content://com.example.provider/private", mainFrame = false),
            webRequest("javascript:alert(1)", mainFrame = false),
            webRequest("intent://scan/#Intent;end", mainFrame = false),
            webRequest("tel:10086", mainFrame = false),
            webRequest("mailto:user@example.com", mainFrame = false),
            webRequest("blob:null/1234", mainFrame = false),
            webRequest("about:blank", mainFrame = false),
            webRequest("data:text/html,<script>alert(1)</script>", mainFrame = false),
            webRequest("data:application/javascript,alert(1)", mainFrame = false),
            webRequest("data:image/png;base64,iVBORw0KGgo=", mainFrame = true),
            null,
        )
        for (req in deniedRequests) {
            val response = client.shouldInterceptRequest(view, req)
            assertNotNull("Expected 403 block for ${req?.url}", response)
            assertEquals(403, response!!.statusCode)
            assertEquals(-1, response.data.read())
        }
        assertEquals(deniedRequests.size, blocked)

        // All navigation attempts (links, location=, meta refresh, custom schemes) are unconditionally blocked.
        val navUrls = listOf(
            "https://example.com/away",
            "http://example.com/away",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "content://media/1",
            "intent://app",
            "tel:10086",
            "persona://session/m/s",
        )
        for (url in navUrls) {
            assertTrue("Navigation must be blocked: $url", client.shouldOverrideUrlLoading(view, webRequest(url, mainFrame = true)))
            @Suppress("DEPRECATION")
            assertTrue("Legacy navigation must be blocked: $url", client.shouldOverrideUrlLoading(view, url))
        }
        assertEquals(deniedRequests.size + navUrls.size * 2, blocked)

        // Renderer termination is handled cleanly without crashing the host app.
        assertTrue(client.onRenderProcessGone(view, null))
        assertEquals(1, gone)
    }

    @Test fun chromeClientDeniesPopupsDialogsPermissionsGeolocationAndFileChooser() {
        val view = WebView(ApplicationProvider.getApplicationContext())
        assertFalse(AssistantHtmlSandbox.SandboxChrome.onCreateWindow(view, false, true, null))
        assertTrue(AssistantHtmlSandbox.SandboxChrome.onJsAlert(view, "about:blank", "hi", null as JsResult?))
        assertTrue(AssistantHtmlSandbox.SandboxChrome.onJsConfirm(view, "about:blank", "ok?", null as JsResult?))
        assertTrue(AssistantHtmlSandbox.SandboxChrome.onJsPrompt(view, "about:blank", "pw?", "", null as JsPromptResult?))
        assertTrue(AssistantHtmlSandbox.SandboxChrome.onJsBeforeUnload(view, "about:blank", "leave?", null as JsResult?))

        var deniedPermission = false
        AssistantHtmlSandbox.SandboxChrome.onPermissionRequest(object : PermissionRequest() {
            override fun getOrigin(): Uri = Uri.parse("about:blank")
            override fun getResources(): Array<String> = arrayOf(RESOURCE_VIDEO_CAPTURE)
            override fun grant(resources: Array<out String>?) { error("must never grant") }
            override fun deny() { deniedPermission = true }
        })
        assertTrue(deniedPermission)

        var geoAllow = true
        var geoRetain = true
        AssistantHtmlSandbox.SandboxChrome.onGeolocationPermissionsShowPrompt(
            "about:blank",
            GeolocationPermissions.Callback { _, allow, retain -> geoAllow = allow; geoRetain = retain },
        )
        assertFalse(geoAllow)
        assertFalse(geoRetain)

        var filePickerResult: Array<Uri>? = arrayOf(Uri.parse("content://unexpected"))
        var filePickerInvoked = false
        assertTrue(AssistantHtmlSandbox.SandboxChrome.onShowFileChooser(
            view,
            ValueCallback { value -> filePickerInvoked = true; filePickerResult = value },
            null,
        ))
        assertTrue(filePickerInvoked)
        assertNull(filePickerResult)
    }

    @Test fun theBridgePolicyValidatesNonceMainFrameVersionPayloadsAndRateLimits() {
        val policy = HtmlBridgePolicy()
        val nonce = "Nonce_ABCDEF1234567890"
        fun replyJson(text: String, n: String = nonce) = """{"nonce":"$n","kind":"reply","text":"$text"}"""
        fun openJson(url: String, n: String = nonce) = """{"nonce":"$n","kind":"open","url":"$url"}"""

        // Forged / invalid frames, instances, nonces, versions, shapes, and payloads are dropped.
        assertNull("Subframe rejected", policy.read(replyJson("ok"), mainFrame = false, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1))
        assertNull("Stale instance rejected", policy.read(replyJson("ok"), mainFrame = true, instance = 1, current = 2, expectedNonce = nonce, cardVersion = 1, currentVersion = 1))
        assertNull("Missing nonce rejected", policy.read("""{"kind":"reply","text":"ok"}""", mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1))
        assertNull("Wrong nonce rejected", policy.read(replyJson("ok", n = "forged"), mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1))
        assertNull("Stale card version rejected", policy.read(replyJson("ok"), mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 2))
        assertNull("Non-JSON rejected", policy.read("persona.reply('x')", mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1))
        assertNull("Oversized message rejected", policy.read(replyJson("a".repeat(HtmlBridgePolicy.MaxMessageLength)), mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1))
        assertNull("Unknown kind rejected", policy.read("""{"nonce":"$nonce","kind":"eval","text":"x"}""", mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1))
        assertNull("Blank reply rejected", policy.read(replyJson("   "), mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1))
        assertNull("Too-long reply rejected", policy.read(replyJson("a".repeat(AssistantCards.ReplyMost + 1)), mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1))
        assertNull("Control char in reply rejected", policy.read("""{"nonce":"$nonce","kind":"reply","text":"bad\u0007text"}""", mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1))
        assertNull("persona-action tag spoofing rejected", policy.read(replyJson("hi <PERSONA-ACTION view=1>"), mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1))
        assertNull("Reply refused when card already answered", policy.read(replyJson("Choose B"), mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1, canReply = false))

        // Invalid open targets are rejected before any native confirmation dialog opens.
        for (badUrl in listOf(
            "http://example.com",
            "https://user:pass@example.com/a",
            "https://example.com/%0A",
            "https://example.com/%ZZ",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "content://com.example/data",
            "intent://open",
            "tel:10086",
            "mailto:user@example.com",
            "data:text/html,<p>x</p>",
            "custom://scheme/target",
            "persona://pair?url=https://example.com",
            "persona://settings/hub",
            "persona://kb/../secret",
            "persona://file/my-mac/relative.txt",
            "https://" + "a".repeat(AssistantOpen.HtmlMaxUrlLength),
        )) {
            assertNull("Disallowed open URL must be rejected: $badUrl", policy.read(openJson(badUrl), mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1))
        }

        // Valid open targets accepted.
        for (goodUrl in listOf(
            "https://example.com/docs?q=1",
            "persona://session/my-mac/s-1",
            "persona://task/todo_42",
            "persona://kb/docs/plan.md",
            "persona://file/my-mac/%2Ftmp%2Freport.md",
        )) {
            assertEquals(HtmlBridgeRequest.Open(goodUrl), policy.read(openJson(goodUrl), mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1, nowMs = 1_000L))
            policy.answered(confirmed = true, nowMs = 1_000L)
        }

        // Valid reply accepted; single concurrent dialog enforced; post-refusal quiet window + 2-decline mute enforced.
        assertEquals(
            HtmlBridgeRequest.Reply("Choose B\nDetails"),
            policy.read("""{"nonce":"$nonce","kind":"reply","text":"Choose B\nDetails"}""", mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1, nowMs = 2_000L),
        )
        assertNull("Flooding while dialog open rejected", policy.read(replyJson("Second"), mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1, nowMs = 2_001L))
        policy.answered(confirmed = false, nowMs = 2_000L)
        assertEquals(1, policy.declines)
        assertNull("Refusal quiet cooldown blocks immediate flood", policy.read(openJson("https://example.com"), mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1, nowMs = 2_500L))
        assertEquals(
            HtmlBridgeRequest.Open("https://example.com"),
            policy.read(openJson("https://example.com"), mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1, nowMs = 2_000L + HtmlBridgePolicy.RefusedQuietMs),
        )
        policy.answered(confirmed = false, nowMs = 12_000L)
        assertTrue("Second decline mutes further prompts", policy.stopped)
        assertNull("Muted after 2 declines even after cooldown", policy.read(replyJson("Again"), mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1, nowMs = 99_000L))

        // Explicit retry resets throttling cleanly.
        policy.reset()
        assertFalse(policy.stopped)
        assertEquals(0, policy.declines)
        assertEquals(HtmlBridgeRequest.Reply("Retried"), policy.read(replyJson("Retried"), mainFrame = true, instance = 1, current = 1, expectedNonce = nonce, cardVersion = 1, currentVersion = 1, nowMs = 100_000L))
        // Dismissing on version/screen change clears active dialog without counting as a decline.
        policy.dismiss()
        assertEquals(0, policy.declines)
        assertFalse(policy.asking)
    }

    @Test fun executionBudgetLimitsConcurrencyAndSupportsForceEviction() {
        val budget = HtmlExecutionBudget(maxRunning = 2)
        assertTrue(budget.acquire("card-1"))
        assertTrue(budget.acquire("card-2"))
        assertFalse("Third card waits when 2 slots are full", budget.acquire("card-3", force = false))
        assertEquals(setOf("card-1", "card-2"), budget.owners.value)

        // Explicit user Run evicts oldest owner (card-1) and runs card-3.
        assertTrue(budget.acquire("card-3", force = true))
        assertEquals(setOf("card-2", "card-3"), budget.owners.value)

        // Releasing card-2 allows card-1 to reacquire without forcing.
        budget.release("card-2")
        assertTrue(budget.acquire("card-1", force = false))
        assertEquals(setOf("card-3", "card-1"), budget.owners.value)
    }

    @Test fun visibilityThresholdTracksViewportIntersectionAccurately() {
        val window = IntSize(400, 800)
        val size = IntSize(360, 320)
        assertTrue(AssistantHtmlVisibility.isVisible(Rect(20f, 100f, 380f, 420f), size, window))
        assertTrue("Partially entering bottom", AssistantHtmlVisibility.isVisible(Rect(20f, 795f, 380f, 1115f), size, window))
        assertTrue("Partially leaving top", AssistantHtmlVisibility.isVisible(Rect(20f, -315f, 380f, 5f), size, window))
        assertFalse("Scrolled off above viewport", AssistantHtmlVisibility.isVisible(Rect(20f, -320f, 380f, 0f), size, window))
        assertFalse("Scrolled off below viewport", AssistantHtmlVisibility.isVisible(Rect(20f, 800f, 380f, 1120f), size, window))
        assertFalse("Clipped to empty rect", AssistantHtmlVisibility.isVisible(Rect.Zero, size, window))
        assertFalse("Zero layout size", AssistantHtmlVisibility.isVisible(Rect(0f, 0f, 100f, 100f), IntSize.Zero, window))
    }
}
