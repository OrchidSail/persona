package persona.feature.settings

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Message
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import persona.core.contracts.JsonCodec
import persona.core.contracts.api.ExtensionViewItem
import persona.core.designsystem.GroupPosition
import persona.core.designsystem.JButton
import persona.core.designsystem.JButtonKind
import persona.core.designsystem.JButtonSize
import persona.core.designsystem.JDialog
import persona.core.designsystem.JGroupItem
import persona.core.designsystem.JIcon
import persona.core.designsystem.PersonaTheme
import persona.core.designsystem.Tokens
import persona.core.designsystem.icons.PersonaIcons
import persona.core.designsystem.rowPadding
import persona.core.domain.views.AssistantCard
import persona.core.domain.views.AssistantCards
import persona.core.domain.views.AssistantOpen

// An assistant's `html` component (docs/assistant/assistant-views.md "Free-form HTML rendering"):
//
// Self-contained offline HTML (HTML / inline CSS / SVG / events / 2D Canvas / inline data: images) running inside an
// isolated WebView with network loads disabled, subresources and navigations intercepted, file/content/DOM/DB storage
// disabled, per-profile/per-view cookie isolation (never clearing the app's global cookie store), autofill and password
// input collection blocked, and a frozen non-configurable `window.persona` bridge bound to a per-mount random nonce.
//
// Honest platform boundaries (docs/assistant/assistant-views.md "Do not overstate execution-time limits"):
// A standard Android WebView does not provide a hard CPU or memory quota — an endless loop inside a card can hold the
// shared WebView renderer until the card leaves visibility, is stopped/destroyed, or the renderer process terminates
// (`onRenderProcessGone`). Concurrency is bounded to [AssistantHtmlSandbox.MostRunning] visible pages, every page is
// destroyed on leaving visibility / backgrounding / disposal, and every `persona.reply` / `persona.open` shows its full
// verbatim payload in a native confirmation dialog outside the WebView before reaching the hub.

/** What a card gives its HTML pages: their bridge's two calls, each after native confirmation by the user. */
internal class HtmlBridgeHost(
    /** Null: the card cannot answer now (answered, busy, not registered, read-only); a page's reply is refused. */
    val reply: (suspend (componentKey: String, text: String, expectedVersion: Long) -> Boolean)?,
    val open: suspend (componentKey: String, url: String, expectedVersion: Long) -> Boolean,
)

/**
 * Platform seam for the isolated HTML WebView. Production delegates to AndroidX WebKit and Android WebView; tests
 * supply a recording runtime to verify visibility lifecycle, bridge dispatch, security settings, and teardown on JVM.
 */
internal interface AssistantHtmlRuntime {
    fun supportsWebMessageListener(): Boolean
    fun supportsMultiProfile(): Boolean
    fun createWebView(context: Context): WebView?
    fun isolateProfile(view: WebView)
    fun configureCookies(view: WebView)
    fun attachBridge(view: WebView, onMessage: (data: String?, isMainFrame: Boolean) -> Unit)
    fun detachBridge(view: WebView)
    fun loadDocument(view: WebView, document: String)
    fun destroyWebView(view: WebView)
    fun newNonce(): String = AssistantHtmlSandbox.generateNonce()
    fun nowMs(): Long = SystemClock.uptimeMillis()
}

internal object PlatformAssistantHtmlRuntime : AssistantHtmlRuntime {
    override fun supportsWebMessageListener(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }.getOrDefault(false)

    override fun supportsMultiProfile(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    override fun createWebView(context: Context): WebView? = runCatching { WebView(context) }.getOrNull()

    override fun isolateProfile(view: WebView) {
        if (supportsMultiProfile() && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                WebViewCompat.setProfile(view, AssistantHtmlSandbox.SandboxProfile)
                ProfileStore.getInstance().getProfile(AssistantHtmlSandbox.SandboxProfile)?.let { profile ->
                    profile.cookieManager.setAcceptCookie(false)
                    profile.cookieManager.removeAllCookies(null)
                    profile.webStorage.deleteAllData()
                }
            } catch (_: Throwable) {
                // Best-effort per-profile isolation on devices whose WebView provider lacks profile storage.
            }
        }
    }

    override fun configureCookies(view: WebView) {
        // Per-view setting only; never call global CookieManager.setAcceptCookie(false) or removeAllCookies on the
        // default profile, which would disrupt the user's normal in-app browser WebViews.
        try {
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        } catch (_: Throwable) {
            // Robolectric or restricted environments without a Chromium CookieManager provider.
        }
    }

    override fun attachBridge(view: WebView, onMessage: (data: String?, isMainFrame: Boolean) -> Unit) {
        if (supportsWebMessageListener() && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, AssistantHtmlSandbox.BridgeObject, setOf("*")) { _, message, _, isMainFrame, _ ->
                onMessage(message.data, isMainFrame)
            }
        }
    }

    override fun detachBridge(view: WebView) {
        if (supportsWebMessageListener() && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                WebViewCompat.removeWebMessageListener(view, AssistantHtmlSandbox.BridgeObject)
            } catch (_: Throwable) {
                // Already detached or WebView already torn down.
            }
        }
    }

    override fun loadDocument(view: WebView, document: String) {
        val encoded = Base64.encodeToString(document.toByteArray(Charsets.UTF_8), Base64.NO_PADDING or Base64.NO_WRAP)
        view.loadData(encoded, "text/html; charset=utf-8", "base64")
    }

    override fun destroyWebView(view: WebView) {
        detachBridge(view)
        try {
            view.stopLoading()
            view.webViewClient = AssistantHtmlSandbox.InertClient
            view.webChromeClient = null
            view.setDownloadListener(null)
            // Clear DOM, timers, and canvas buffers in this instance without touching global WebView timers.
            view.loadUrl("about:blank")
            view.clearHistory()
            view.onPause()
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeAllViews()
            view.destroy()
        } catch (_: Throwable) {
            // WebView already destroyed by the system or renderer crash.
        }
    }
}

internal val LocalAssistantHtmlRuntime = staticCompositionLocalOf<AssistantHtmlRuntime> { PlatformAssistantHtmlRuntime }

/**
 * Concurrency bound for running HTML cards: at most [maxRunning] visible pages execute at once. Observable so a queued
 * visible card claims a slot automatically once another page leaves visibility or stops; explicit user "Run" may evict
 * the oldest slot. This is a concurrency bound, not a CPU or memory quota.
 */
internal class HtmlExecutionBudget(val maxRunning: Int = AssistantHtmlSandbox.MostRunning) {
    private val _owners = MutableStateFlow<Set<String>>(linkedSetOf())
    val owners: StateFlow<Set<String>> = _owners.asStateFlow()

    @Synchronized
    fun acquire(owner: String, force: Boolean = false): Boolean {
        val current = _owners.value
        if (owner in current) return true
        if (current.size < maxRunning) {
            _owners.update { LinkedHashSet(it).apply { add(owner) } }
            return true
        }
        if (force && current.isNotEmpty()) {
            _owners.update {
                LinkedHashSet(it).apply {
                    remove(first())
                    add(owner)
                }
            }
            return true
        }
        return false
    }

    @Synchronized
    fun release(owner: String) {
        if (owner in _owners.value) {
            _owners.update { LinkedHashSet(it).apply { remove(owner) } }
        }
    }

    @Synchronized
    fun clear() {
        _owners.value = linkedSetOf()
    }
}

internal val LocalHtmlExecutionBudget = staticCompositionLocalOf { AssistantHtmlSandbox.budget }

/** Pure viewport-visibility calculation: true whenever at least 1px in both axes intersects the visible window. */
internal object AssistantHtmlVisibility {
    const val MinVisiblePx = 1f

    fun isVisible(boundsInWindow: Rect, layoutSize: IntSize, windowSize: IntSize): Boolean {
        if (layoutSize.width <= 0 || layoutSize.height <= 0 || windowSize.width <= 0 || windowSize.height <= 0) return false
        val maxW = windowSize.width.toFloat()
        val maxH = windowSize.height.toFloat()
        val left = boundsInWindow.left.coerceIn(0f, maxW)
        val right = boundsInWindow.right.coerceIn(0f, maxW)
        val top = boundsInWindow.top.coerceIn(0f, maxH)
        val bottom = boundsInWindow.bottom.coerceIn(0f, maxH)
        return (right - left) >= MinVisiblePx && (bottom - top) >= MinVisiblePx
    }
}

/** Subresource interception allowlist: only non-main-frame inline `data:image/` images may pass. */
internal object AssistantHtmlRequestPolicy {
    private val safeDataImage = Regex(
        "^data:image/(?:png|jpeg|jpg|gif|webp|svg\\+xml)(?:;charset=[a-z0-9_-]+)?(?:;base64)?,",
        RegexOption.IGNORE_CASE,
    )

    fun allowsSubresource(url: String?, isForMainFrame: Boolean): Boolean {
        if (isForMainFrame || url.isNullOrBlank()) return false
        return safeDataImage.containsMatchIn(url.trim())
    }
}

internal object AssistantHtmlSandbox {
    /** Android isolated WebView sandbox is enabled (W-D01). */
    const val Accepted = true

    /** At most this many pages run at once; the others wait for a slot or an explicit "Run". */
    const val MostRunning = 2

    /** Dedicated WebView profile name when `WebViewFeature.MULTI_PROFILE` is available. */
    const val SandboxProfile = "persona-assistant-html"

    /**
     * Strict CSP placed before all assistant HTML: inline script/style only, inline `data:` images/fonts only, and
     * every network, frame, worker, object, media, form target, and base URI denied.
     */
    const val Policy = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; " +
        "media-src 'none'; connect-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; object-src 'none'; " +
        "manifest-src 'none'; form-action 'none'; base-uri 'none'"

    /** Raw WebMessageListener handle captured and hidden by the prelude script before card code runs. */
    const val BridgeObject = "personaBridge"

    private val nonceRandom = SecureRandom()
    private val validNonce = Regex("^[A-Za-z0-9_-]{16,128}$")

    /** Shared default execution budget for running HTML cards across the app. */
    internal val budget = HtmlExecutionBudget(MostRunning)

    /** 128-bit URL-safe random nonce unique to each mounted HTML card instance. */
    fun generateNonce(): String {
        val bytes = ByteArray(18)
        nonceRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun preludeScript(bridge: Boolean, nonce: String): String {
        val safeNonce = if (validNonce.matches(nonce)) nonce else generateNonce()
        return buildString {
            append("(function(){")
            append("var S=String,J=JSON.stringify,O=Object,D=document;")
            // Prevent password/file inputs and autofill spoofing in assistant-drawn pages.
            append("var h=function(el){if(!el||el.nodeType!==1)return;")
            append("var tag=(el.tagName||'').toLowerCase();")
            append("if(tag==='input'||tag==='textarea'||tag==='select'||tag==='form'){try{el.setAttribute('autocomplete','off');}catch(e){}}")
            append("if(tag==='input'){var t=(el.getAttribute('type')||'').toLowerCase();")
            append("if(t==='password'||t==='file'){try{el.setAttribute('type','text');el.disabled=true;el.readOnly=true;}catch(e){}}}")
            append("if(el.style&&el.style.webkitTextSecurity){try{el.style.webkitTextSecurity='none';}catch(e){}}};")
            append("try{var desc=O.getOwnPropertyDescriptor(HTMLInputElement.prototype,'type');if(desc&&desc.set){")
            append("O.defineProperty(HTMLInputElement.prototype,'type',{configurable:false,get:desc.get,set:function(v){")
            append("var s=S(v).toLowerCase();if(s==='password'||s==='file'){this.disabled=true;this.readOnly=true;desc.set.call(this,'text');return;}")
            append("desc.set.call(this,v);}});}}catch(e){}")
            append("try{var mo=new MutationObserver(function(list){for(var i=0;i<list.length;i++){var r=list[i];")
            append("if(r.type==='attributes'){h(r.target);}else if(r.addedNodes){for(var j=0;j<r.addedNodes.length;j++){")
            append("var nd=r.addedNodes[j];h(nd);if(nd.querySelectorAll){var qs=nd.querySelectorAll('input,textarea,select,form');")
            append("for(var k=0;k<qs.length;k++)h(qs[k]);}}}}});")
            append("mo.observe(D.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['type','style','autocomplete']});}catch(e){}")
            if (bridge) {
                append("var b=window.").append(BridgeObject).append(";if(!b)return;")
                append("var n='").append(safeNonce).append("';")
                append("var post=b.postMessage.bind(b);")
                append("try{delete window.").append(BridgeObject).append(";}catch(e){}")
                append("var s=function(m){try{post(J(m));}catch(e){}};")
                append("O.defineProperty(window,'persona',{value:O.freeze({")
                append("reply:function(t){if(typeof t==='string')s({nonce:n,kind:'reply',text:t});else if(t!=null)s({nonce:n,kind:'reply',text:S(t)});},")
                append("open:function(u){if(typeof u==='string')s({nonce:n,kind:'open',url:u});else if(u!=null)s({nonce:n,kind:'open',url:S(u)});}")
                append("}),writable:false,configurable:false,enumerable:false});")
            }
            append("})();")
        }
    }

    /**
     * Builds the offline HTML document: CSP meta tag first, light/dark color-scheme and base typography, password/bridge
     * prelude script next, and the assistant's HTML body last.
     */
    fun document(html: String, bridge: Boolean, nonce: String = generateNonce(), dark: Boolean = false): String {
        val scheme = if (dark) "dark" else "light"
        val ink = if (dark) "#f2f2f4" else "#1c1c1e"
        return buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"").append(Policy).append("\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<meta name=\"color-scheme\" content=\"").append(scheme).append("\">")
            append("<style>:root{color-scheme:").append(scheme)
            append("}html,body{background:transparent;margin:0;color:").append(ink)
            append(";font:14px/1.45 system-ui,-apple-system,sans-serif}body{padding:12px;box-sizing:border-box;overflow-wrap:break-word}")
            append("input[type=\"password\"]{-webkit-text-security:none!important}</style>")
            append("<script>").append(preludeScript(bridge, nonce)).append("</script>")
            append("</head><body>").append(html).append("</body></html>")
        }
    }

    /** Whether an HTML card may execute on this device: sandbox accepted and `WEB_MESSAGE_LISTENER` supported. */
    fun runnable(runtime: AssistantHtmlRuntime = PlatformAssistantHtmlRuntime): Boolean =
        Accepted && runtime.supportsWebMessageListener()

    /**
     * Locks a WebView down to strictly offline, zero-permission, zero-storage execution: no network loads, no file or
     * content access, no DOM/database storage, no cache, no mixed content, no autofill, no popups, no JS dialogs, no
     * permissions, no downloads, and every non-inline request or navigation intercepted.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(
        view: WebView,
        blocked: () -> Unit,
        gone: () -> Unit,
        runtime: AssistantHtmlRuntime = PlatformAssistantHtmlRuntime,
    ) {
        runtime.isolateProfile(view)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            @Suppress("DEPRECATION") databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            blockNetworkImage = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            @Suppress("DEPRECATION") setGeolocationEnabled(false)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            @Suppress("DEPRECATION") saveFormData = false
        }
        runtime.configureCookies(view)
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        view.setBackgroundColor(0)
        view.isLongClickable = false
        view.setDownloadListener { _, _, _, _, _ -> blocked() }
        view.webViewClient = SandboxClient(blocked, gone)
        view.webChromeClient = SandboxChrome
    }

    /** Loads the document as an opaque-origin `data:` URL (no cookies, storage, or app origin). */
    fun load(
        view: WebView,
        html: String,
        bridge: Boolean,
        nonce: String = generateNonce(),
        dark: Boolean = false,
        runtime: AssistantHtmlRuntime = PlatformAssistantHtmlRuntime,
    ) {
        runtime.loadDocument(view, document(html, bridge, nonce, dark))
    }

    /** Neutral client attached during teardown so late callbacks never reach disposed Compose state. */
    internal object InertClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
            blockedResponse()

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean = true
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    internal class SandboxClient(private val blocked: () -> Unit, private val gone: () -> Unit) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url?.toString()
            val mainFrame = request?.isForMainFrame ?: true
            if (AssistantHtmlRequestPolicy.allowsSubresource(url, mainFrame)) return null
            blocked()
            return blockedResponse()
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            blocked()
            return true
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            blocked()
            return true
        }

        /** The renderer died (or was killed for memory): this page stops cleanly; the app goes on. */
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            gone()
            return true
        }
    }

    internal object SandboxChrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
            result?.cancel()
            return true
        }
        override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.cancel(); return true }
        override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            return true
        }
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
    }
}

/** A validated request a page made through its JS bridge. */
internal sealed interface HtmlBridgeRequest {
    data class Reply(val text: String) : HtmlBridgeRequest
    data class Open(val url: String) : HtmlBridgeRequest
}

/** Pending native confirmation bound to the card version the user saw when the bridge fired. */
internal data class PendingHtmlBridgeRequest(val request: HtmlBridgeRequest, val version: Long)

/**
 * Validates and rate-limits bridge messages for a single mounted HTML card instance:
 * - main frame only, matching active instance generation, matching per-mount nonce, matching card version
 * - one native confirmation open at a time (flooding dropped immediately)
 * - strict payload validation for `reply` (non-blank, <= 2000 code points, no control chars except `\n`/`\t`, no
 *   `persona-action` tag spoofing, refused when card can no longer reply) and `open` (<= 2048 chars and validated by
 *   [AssistantOpen.target])
 * - post-refusal cooldown ([RefusedQuietMs]) and mute after [MostDeclines] user declines until explicit retry.
 */
internal class HtmlBridgePolicy {
    companion object {
        const val MostDeclines = 2
        const val RefusedQuietMs = 10_000L
        const val MaxMessageLength = 16_384
    }

    var declines = 0
        private set
    var asking = false
        private set
    var quietUntilMs = 0L
        private set
    val stopped: Boolean get() = declines >= MostDeclines

    fun read(
        message: String?,
        mainFrame: Boolean,
        instance: Int,
        current: Int,
        expectedNonce: String,
        cardVersion: Long,
        currentVersion: Long,
        canReply: Boolean = true,
        nowMs: Long = 0L,
    ): HtmlBridgeRequest? {
        if (!mainFrame || instance != current || cardVersion != currentVersion) return null
        if (asking || stopped || nowMs < quietUntilMs) return null
        if (message == null || message.length > MaxMessageLength) return null
        val o = runCatching { JsonCodec.parseToJsonElement(message) }.getOrNull() as? JsonObject ?: return null
        fun string(name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (string("nonce") != expectedNonce) return null
        val request = when (string("kind")) {
            "reply" -> if (!canReply) null else string("text")?.takeIf(::validReplyText)?.let { HtmlBridgeRequest.Reply(it) }
            "open" -> string("url")?.takeIf { it.length <= AssistantOpen.HtmlMaxUrlLength && AssistantOpen.target(it) != null }
                ?.let { HtmlBridgeRequest.Open(it) }
            else -> null
        } ?: return null
        asking = true
        return request
    }

    fun answered(confirmed: Boolean, nowMs: Long = 0L) {
        asking = false
        if (!confirmed) {
            declines++
            quietUntilMs = nowMs + RefusedQuietMs
        }
    }

    /** Dismisses an open confirmation due to card update, external answer, or leaving screen without penalizing. */
    fun dismiss() {
        asking = false
    }

    /** Clears decline throttling when the user explicitly restarts/retries the card. */
    fun reset() {
        asking = false
        declines = 0
        quietUntilMs = 0L
    }

    private fun validReplyText(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.codePointCount(0, text.length) > AssistantCards.ReplyMost) return false
        if (text.any { it.isISOControl() && it != '\n' && it != '\t' }) return false
        if (text.contains("persona-action", ignoreCase = true)) return false
        return true
    }
}

/**
 * An `html` item of an assistant card (Glass / Brief / Board share identical state, lifecycle, and security rules):
 * renders its plain-text fallback when the hub or runtime cannot run HTML; otherwise auto-runs on entering the visible
 * viewport (up to [AssistantHtmlSandbox.MostRunning] concurrent pages), destroys the WebView completely on leaving
 * visibility / backgrounding / disposal, and gates every `persona.reply` / `persona.open` behind native confirmation.
 */
@Composable
internal fun AssistantHtmlItem(
    item: ExtensionViewItem,
    card: AssistantCard,
    modifier: Modifier,
    bridge: HtmlBridgeHost?,
    cardKey: String = "",
    version: Long = 1L,
    pageName: String? = null,
    answered: Boolean = false,
    enabled: Boolean = true,
    onVersionChanged: () -> Unit = {},
) {
    val runtime = LocalAssistantHtmlRuntime.current
    val palette = PersonaTheme.palette
    val type = PersonaTheme.type
    val title = item.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings_card_html_title)
    val canRun = enabled && AssistantHtmlSandbox.runnable(runtime)
    JGroupItem(GroupPosition.Single, modifier.testTag("assistant_html_card")) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = rowPadding, vertical = Tokens.Space.m),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
                JIcon(PersonaIcons.agents, null, size = Tokens.Space.l, tint = palette.ink3)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.xxs)) {
                    Text(title, style = type.caption, color = palette.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.settings_card_html_badge),
                        style = type.micro,
                        color = palette.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!canRun) {
                Text(stringResource(R.string.settings_card_html_fallback), style = type.caption, color = palette.ink3)
                Text(card.fallback, style = type.body, color = palette.ink)
            } else {
                HtmlPage(
                    item = item,
                    fallback = card.fallback,
                    height = (item.height ?: 320).coerceIn(160, 640),
                    bridge = bridge.takeIf { !item.key.isNullOrBlank() },
                    cardKey = cardKey,
                    version = version,
                    pageName = pageName,
                    answered = answered,
                    onVersionChanged = onVersionChanged,
                )
            }
        }
    }
}

private enum class HtmlStopReason { None, User, Crashed, Unavailable }

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun HtmlPage(
    item: ExtensionViewItem,
    fallback: String,
    height: Int,
    bridge: HtmlBridgeHost?,
    cardKey: String,
    version: Long,
    pageName: String?,
    answered: Boolean,
    onVersionChanged: () -> Unit,
) {
    val runtime = LocalAssistantHtmlRuntime.current
    val budget = LocalHtmlExecutionBudget.current
    val scope = rememberCoroutineScope()
    val palette = PersonaTheme.palette
    val type = PersonaTheme.type
    val dark = PersonaTheme.darkTheme
    val windowSize = LocalWindowInfo.current.containerSize
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val appActive = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    val ownerId = remember(cardKey, pageName, item.key) { "$cardKey|${pageName.orEmpty()}|${item.key.orEmpty()}|${UUID.randomUUID()}" }
    val budgetOwners by budget.owners.collectAsState()
    var onScreen by remember(ownerId) { mutableStateOf(false) }
    var stopReason by remember(cardKey, pageName, item.key, item.html) { mutableStateOf(HtmlStopReason.None) }
    var runGeneration by remember(cardKey, pageName, item.key) { mutableIntStateOf(0) }
    var pending by remember(cardKey, pageName, item.key) { mutableStateOf<PendingHtmlBridgeRequest?>(null) }
    val policy = remember(cardKey, pageName, item.key, version, runGeneration) { HtmlBridgePolicy() }

    val currentBridge by rememberUpdatedState(bridge)
    val currentVersion by rememberUpdatedState(version)
    val currentRun by rememberUpdatedState(runGeneration)
    val currentOnVersionChanged by rememberUpdatedState(onVersionChanged)
    val hasBridge = bridge != null

    val eligible = onScreen && appActive && stopReason == HtmlStopReason.None
    val hasSlot = ownerId in budgetOwners
    val running = eligible && hasSlot

    // Claim a concurrency slot whenever visible & active (and re-check when another card releases its slot).
    LaunchedEffect(eligible, budgetOwners, ownerId, budget) {
        if (eligible) budget.acquire(ownerId, force = false)
        else budget.release(ownerId)
    }

    // Release concurrency slot unconditionally when this composable leaves composition.
    DisposableEffect(ownerId, budget) {
        onDispose {
            budget.release(ownerId)
        }
    }

    // Leaving visibility / stopping / backgrounding immediately invalidates active instance and any open confirmation.
    LaunchedEffect(running) {
        if (!running) {
            runGeneration++
            if (pending != null) {
                policy.dismiss()
                pending = null
            }
        }
    }

    // A live-card version update under an open confirmation dialog revokes it immediately; never submit stale version.
    LaunchedEffect(version) {
        val open = pending
        if (open != null && open.version != version) {
            policy.dismiss()
            pending = null
            currentOnVersionChanged()
        }
    }

    // Answering the card elsewhere (or via a native declarative button) revokes any pending reply dialog immediately.
    LaunchedEffect(answered) {
        if (answered && pending?.request is HtmlBridgeRequest.Reply) {
            policy.dismiss()
            pending = null
        }
    }

    val visibilityModifier = Modifier
        .fillMaxWidth()
        .onGloballyPositioned { coords ->
            onScreen = coords.isAttached && AssistantHtmlVisibility.isVisible(
                boundsInWindow = coords.boundsInWindow(),
                layoutSize = coords.size,
                windowSize = windowSize,
            )
        }

    Column(visibilityModifier, verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        if (running) {
            key(cardKey, version, pageName, item.key, item.html, hasBridge, runGeneration, dark) {
                val instance = runGeneration
                val mountedVersion = version
                val nonce = remember { runtime.newNonce() }
                val destroyed = remember { AtomicBoolean(false) }
                val mountedView = remember { mutableStateOf<WebView?>(null) }

                fun teardown(view: WebView?) {
                    if (view != null && destroyed.compareAndSet(false, true)) {
                        mountedView.value = null
                        runtime.destroyWebView(view)
                    }
                }

                DisposableEffect(nonce) {
                    onDispose {
                        teardown(mountedView.value)
                    }
                }

                AndroidView(
                    factory = { context ->
                        val webView = runtime.createWebView(context)
                        if (webView == null) {
                            stopReason = HtmlStopReason.Unavailable
                            budget.release(ownerId)
                            View(context)
                        } else {
                            mountedView.value = webView
                            AssistantHtmlSandbox.configure(
                                view = webView,
                                blocked = {},
                                gone = {
                                    stopReason = HtmlStopReason.Crashed
                                    budget.release(ownerId)
                                },
                                runtime = runtime,
                            )
                            val keyName = item.key
                            if (hasBridge && !keyName.isNullOrBlank()) {
                                runtime.attachBridge(webView) { data, isMainFrame ->
                                    if (destroyed.get()) return@attachBridge
                                    val request = policy.read(
                                        message = data,
                                        mainFrame = isMainFrame,
                                        instance = instance,
                                        current = currentRun,
                                        expectedNonce = nonce,
                                        cardVersion = mountedVersion,
                                        currentVersion = currentVersion,
                                        canReply = currentBridge?.reply != null,
                                        nowMs = runtime.nowMs(),
                                    )
                                    if (request != null) {
                                        pending = PendingHtmlBridgeRequest(request, mountedVersion)
                                    }
                                }
                            }
                            AssistantHtmlSandbox.load(
                                view = webView,
                                html = item.html.orEmpty(),
                                bridge = hasBridge,
                                nonce = nonce,
                                dark = dark,
                                runtime = runtime,
                            )
                            webView
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height.dp)
                        .clip(PersonaTheme.shapes.controlShape)
                        .testTag("assistant_html_webview"),
                    onRelease = { view ->
                        teardown(view as? WebView)
                    },
                )
            }
        } else {
            val statusText = when {
                stopReason == HtmlStopReason.Unavailable -> stringResource(R.string.settings_card_html_fallback)
                stopReason == HtmlStopReason.Crashed -> stringResource(R.string.settings_card_html_crashed)
                stopReason == HtmlStopReason.User -> stringResource(R.string.settings_card_html_stopped)
                eligible && !hasSlot -> stringResource(R.string.settings_card_html_too_many)
                else -> null
            }
            if (statusText != null) {
                Text(statusText, style = type.caption, color = palette.ink3, modifier = Modifier.testTag("assistant_html_status"))
            }
            Text(fallback, style = type.body, color = palette.ink, modifier = Modifier.testTag("assistant_html_fallback"))
        }

        val queued = eligible && !hasSlot
        val showRetry = !running && stopReason != HtmlStopReason.None
        if (running || queued || showRetry) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    JButton(
                        text = stringResource(R.string.settings_card_html_stop),
                        onClick = {
                            stopReason = HtmlStopReason.User
                            budget.release(ownerId)
                            runGeneration++
                        },
                        modifier = Modifier.heightIn(min = Tokens.MinTouch.android).testTag("assistant_html_stop"),
                        kind = JButtonKind.Quiet,
                        size = JButtonSize.Medium,
                        icon = PersonaIcons.interrupt,
                    )
                }
                if (queued || showRetry) {
                    JButton(
                        text = stringResource(if (queued) R.string.settings_card_html_run else R.string.settings_card_html_retry),
                        onClick = {
                            stopReason = HtmlStopReason.None
                            policy.reset()
                            runGeneration++
                            if (onScreen && appActive) budget.acquire(ownerId, force = true)
                        },
                        modifier = Modifier.heightIn(min = Tokens.MinTouch.android).testTag(if (queued) "assistant_html_run" else "assistant_html_retry"),
                        kind = JButtonKind.Quiet,
                        size = JButtonSize.Medium,
                        icon = if (queued) PersonaIcons.run else PersonaIcons.retry,
                    )
                }
            }
        }
    }

    pending?.let { active ->
        val componentKey = item.key.orEmpty()
        fun finish(confirmed: Boolean) {
            policy.answered(confirmed, runtime.nowMs())
            pending = null
        }
        when (val request = active.request) {
            is HtmlBridgeRequest.Reply -> {
                val replyAction = currentBridge?.reply
                JDialog(
                    onDismiss = { finish(false) },
                    title = stringResource(R.string.settings_card_html_reply_title),
                    text = stringResource(R.string.settings_card_html_reply_text, request.text),
                    confirm = if (replyAction != null) {
                        stringResource(R.string.settings_card_html_send) to {
                            val seen = active.version
                            finish(true)
                            scope.launch {
                                val _ = replyAction.invoke(componentKey, request.text, seen)
                            }
                            Unit
                        }
                    } else null,
                )
            }
            is HtmlBridgeRequest.Open -> {
                val openAction = currentBridge?.open
                JDialog(
                    onDismiss = { finish(false) },
                    title = stringResource(R.string.settings_card_html_open_title),
                    text = stringResource(R.string.settings_card_html_open_text, request.url),
                    confirm = if (openAction != null) {
                        stringResource(R.string.settings_card_html_open) to {
                            val seen = active.version
                            finish(true)
                            scope.launch {
                                val _ = openAction.invoke(componentKey, request.url, seen)
                            }
                            Unit
                        }
                    } else null,
                )
            }
        }
    }
}
