package dev.persona.watch

import android.app.Activity
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import dev.persona.watch.data.*
import dev.persona.watch.ui.WatchTheme as T
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import dev.persona.watch.pet.PetBridge
import dev.persona.watch.pet.PetActivity
import dev.persona.watch.extensions.ExtensionWidgetsActivity

/** A small, foreground-only REST client, mirroring PersonaWatch's wrist workflow. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private val refreshCoordinator = Executors.newSingleThreadExecutor()
    private val mutations = Executors.newSingleThreadExecutor()
    private val groupNetwork = Executors.newFixedThreadPool(4)
    private var additionalHubs = emptyList<SavedHub>()
    private var groupCache = emptyMap<String, WatchSnapshot>()
    private var failedGroups = emptySet<String>()
    private lateinit var credentials: CredentialStore
    private var config: HubConfig? = null
    private var client: HubClient? = null
    private var snapshot: WatchSnapshot? = null
    private val transcripts = mutableMapOf<String, List<WatchMessage>>()
    private val picks = mutableMapOf<String, MutableSet<String>>()
    private val drafts by lazy { getSharedPreferences("watch_drafts", MODE_PRIVATE) }
    private var page = "home"
    private var selectedSession: WatchSession? = null
    private var selectedInteraction: WatchInteraction? = null
    private var selectedInbox: InboxItem? = null
    private var previousPage = "home"
    private var notice = ""
    private var error = ""
    private var busy = false
    private var refreshing = false
    private var foreground = false
    private var generation = 0
    private var composer: EditText? = null
    private var dictationDraftKey: String? = null
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var confirmation: (() -> Unit)? = null
    private var confirmationText = ""
    private var confirmationLabel = ""
    private var confirmationDanger = false
    private var fullText = ""
    private var pendingDebugPage: String? = null
    private var pendingDebugId: String? = null
    private var sessionUiNeedsRefresh = false
    private var unregisterSystemBack: (() -> Unit)? = null
    private val poll = object : Runnable {
        override fun run() {
            if (!foreground) return
            if (getSystemService(PowerManager::class.java).isInteractive) refresh()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = T.background
        window.navigationBarColor = T.background
        credentials = CredentialStore(this)
        config = runCatching { credentials.load() }.getOrNull()
        additionalHubs = HubDirectory(this).additional()
        applyDebugIntent(intent)
        client = runCatching { config?.let { HubClient(it, language()) } }.getOrElse {
            config = null
            error = getString(R.string.invalid_connection)
            null
        }
        page = if (config == null && additionalHubs.isEmpty()) "settings" else "home"
        render()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { navigateBack() }
            val dispatcher = onBackInvokedDispatcher
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            unregisterSystemBack = { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDebugIntent(intent)
        client = config?.let { HubClient(it, language()) }
        applyDebugPage()
        render()
        refresh()
    }

    private fun applyDebugIntent(value: Intent?) {
        if (!BuildConfig.DEBUG || value == null) return
        if (value.getBooleanExtra("import_pairing", false)) {
            val file = File(filesDir, "debug-pairing.json")
            try {
                if (file.isFile) {
                    val json = JSONObject(file.readText())
                    val imported = HubConfig(json.getString("baseUrl"), json.getString("token"),
                        json.optString("fingerprint").takeIf { it.isNotBlank() },
                        json.optString("deviceId").takeIf { it.isNotBlank() }).validated()
                    credentials.save(imported)
                    WidgetUpdates.request(this)
                    config = imported
                    generation++
                    refreshing = false
                    snapshot = null
                    groupCache = emptyMap()
                    transcripts.clear()
                    clearSelection()
                }
            } catch (_: Exception) { error = getString(R.string.invalid_connection) }
            finally { file.delete() }
        }
        pendingDebugPage = value.getStringExtra("screen")
        pendingDebugId = value.getStringExtra("session_id") ?: value.getStringExtra("interaction_id")
    }

    private fun applyDebugPage() {
        if (!BuildConfig.DEBUG) return
        when (pendingDebugPage) {
            "home", "agents", "assistant", "inbox", "settings" -> page = pendingDebugPage!!
            "session" -> {
                val found = snapshot?.sessions?.firstOrNull { pendingDebugId == null || it.sessionId == pendingDebugId }
                if (found == null) return
                selectedSession = found; page = "session"; loadMessages(found)
            }
            "interaction" -> {
                val found = snapshot?.interactions?.firstOrNull { pendingDebugId == null || it.id == pendingDebugId }
                if (found == null) return
                selectedInteraction = found; page = "interaction"
            }
        }
        pendingDebugPage = null
    }

    override fun onResume() {
        super.onResume()
        val currentGroups = HubDirectory(this).additional()
        if (currentGroups != additionalHubs) {
            additionalHubs = currentGroups
            groupCache = groupCache.filterKeys { it.isEmpty() || currentGroups.any { group -> group.id == it } }
            generation++; refreshing = false
        }
        foreground = true
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        foreground = false
        handler.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterSystemBack?.invoke()
        unregisterSystemBack = null
        handler.removeCallbacksAndMessages(null)
        network.shutdownNow()
        refreshCoordinator.shutdownNow()
        mutations.shutdown()
        groupNetwork.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Legacy system back; Android 13 and later use OnBackInvokedDispatcher")
    override fun onBackPressed() = navigateBack()

    private fun navigateBack() {
        if (isFinishing || isDestroyed) return
        when (page) {
            "home" -> finish()
            "confirm", "full_text" -> { page = previousPage; confirmation = null; render() }
            "session", "interaction", "inbox_item" -> { page = "home"; render() }
            else -> { page = "home"; render() }
        }
    }

    private fun language() = when (resources.configuration.locales[0].language) {
        "zh" -> "zh-Hans"; "ja" -> "ja"; else -> "en"
    }

    private fun refresh() {
        val hub = client
        if (hub == null && additionalHubs.isEmpty()) return
        if (refreshing || busy || isDestroyed) return
        refreshing = true
        updateStatus()
        val requestGeneration = generation
        val groups = additionalHubs.toList()
        val previousCache = groupCache.toMap()
        refreshCoordinator.execute {
            val result = runCatching { loadGroups(hub, groups, previousCache) }
            handler.post {
                if (isDestroyed || generation != requestGeneration) return@post
                refreshing = false
                result.fold({ loaded ->
                    val fresh = loaded.snapshot
                    groupCache = loaded.cache
                    failedGroups = loaded.failed
                    val oldIds = snapshot?.interactions?.map { it.key }?.toSet().orEmpty()
                    val isFirst = snapshot == null
                    val allFailed = failedGroups.size == groups.size + if (hub != null) 1 else 0
                    snapshot = if (isFirst && allFailed) null else if (fresh.inboxUnavailable) fresh.copy(inbox = snapshot?.inbox.orEmpty()) else fresh
                    error = when { allFailed -> getString(R.string.connection_failed); failedGroups.isNotEmpty() -> getString(R.string.hub_partial); else -> "" }
                    config?.let { PetBridge.refresh(this, it, snapshot, allFailed, failedGroups.isNotEmpty()) }
                    val openRequest = selectedInteraction
                    if (openRequest != null && (page == "interaction" || page == "confirm" && previousPage == "interaction")) {
                        val current = fresh.interactions.firstOrNull { it.key == openRequest.key }
                        if (current != openRequest) {
                            confirmation = null
                            selectedInteraction = current
                            page = if (current == null) "home" else "interaction"
                            notice = getString(if (current == null) R.string.request_gone else R.string.request_changed)
                            render(true)
                        }
                    }
                    if (!isFirst && fresh.interactions.any { it.key !in oldIds }) {
                        scroll.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }
                    val beforeDebugPage = page
                    applyDebugPage()
                    val previousSession = selectedSession
                    if (previousSession != null && (page == "session" || page == "confirm" && previousPage == "session")) {
                        val latest = fresh.sessions.firstOrNull { it.key == previousSession.key }
                            ?: previousSession.copy(state = "offline", canSend = false, canInterrupt = false, canApprove = false)
                        if (latest != previousSession) {
                            selectedSession = latest
                            sessionUiNeedsRefresh = true
                            if (page == "confirm" && !latest.canInterrupt) { confirmation = null; page = "session" }
                        }
                        if (notice == getString(R.string.processing) && latest.state !in setOf("working", "needs_you")) notice = ""
                    }
                    if (page != beforeDebugPage || page in setOf("home", "agents", "assistant", "inbox")) render(preserveScroll = true)
                    else if (page == "session" && sessionUiNeedsRefresh && composer?.hasFocus() != true) render(true)
                    else updateStatus()
                    if (page == "session") selectedSession?.let { loadMessages(it) }
                }, { error = errorText(it); config?.let { c -> PetBridge.refresh(this, c, snapshot, true) }; updateStatus() })
            }
        }
    }

    private fun loadGroups(primary: HubClient?, extras: List<SavedHub>, cached: Map<String, WatchSnapshot>): HubGroupRead =
        HubGroupReader.read(primary?.config, extras, cached, language(), groupNetwork)

    private fun hubFor(group: String): HubClient? = if (group.isEmpty()) client
        else additionalHubs.firstOrNull { it.id == group }?.config?.let { HubClient(it, language()) }

    private fun render(preserveScroll: Boolean = false) {
        if (isDestroyed) return
        val position = if (preserveScroll && ::scroll.isInitialized) scroll.scrollY else T.zero
        composer = null
        scroll = ScrollView(this).apply {
            setBackgroundColor(T.background)
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            clipToPadding = false
        }
        body = T.column(this).apply {
            setPadding(dp(T.screenInset), dp(T.roundEndInset), dp(T.screenInset), dp(T.roundEndInset))
        }
        scroll.addView(body)
        setContentView(scroll)
        val clock = T.text(this, SimpleDateFormat(T.clockPattern, Locale.getDefault()).format(Date()), T.caption, T.secondary)
        clock.gravity = Gravity.CENTER
        body.addView(clock)
        status = T.text(this, "", T.caption, T.secondary)
        status.gravity = Gravity.CENTER
        when (page) {
            "settings" -> renderSettings()
            "agents" -> renderSessions(false)
            "assistant" -> renderSessions(true)
            "session" -> renderSession()
            "interaction" -> renderInteraction()
            "inbox" -> renderInbox()
            "inbox_item" -> renderInboxItem()
            "confirm" -> renderConfirmation()
            "full_text" -> { heading(getString(R.string.long_text)); paragraph(fullText) }
            else -> renderHome()
        }
        if (page != "home") action(R.string.back) { navigateBack() }
        updateStatus()
        if (preserveScroll) scroll.post { scroll.scrollTo(T.zero, position) }
    }

    private fun heading(value: String, color: Int = T.ink) {
        body.addView(T.text(this, value, T.title, color, true).apply {
            gravity = Gravity.CENTER
            setPadding(T.zero, dp(T.lineGap), T.zero, dp(T.gap))
            isAccessibilityHeading = true
        })
        if (status.parent == null) body.addView(status)
    }

    private fun section(value: String) {
        body.addView(T.text(this, value, T.heading, T.ink, true).apply {
            setPadding(T.zero, dp(T.gap), T.zero, dp(T.lineGap))
            isAccessibilityHeading = true
        })
    }

    private fun paragraph(value: String, color: Int = T.secondary, compact: Boolean = false) {
        if (value.isBlank()) return
        body.addView(T.text(this, value, if (compact) T.caption else T.body, color).apply {
            setPadding(T.zero, dp(T.lineGap), T.zero, dp(T.lineGap))
            setTextIsSelectable(true)
        })
    }

    private fun action(id: Int, primary: Boolean = false, destructive: Boolean = false, enabled: Boolean = true, call: () -> Unit) =
        action(getString(id), primary, destructive, enabled, call)

    private fun action(label: String, primary: Boolean = false, destructive: Boolean = false, enabled: Boolean = true, call: () -> Unit): View {
        val view = T.button(this, label, primary, destructive) { if (!busy) call() }
        view.contentDescription = label
        view.isEnabled = enabled && !busy
        view.alpha = if (view.isEnabled) 1f else 0.45f
        body.addView(view)
        return view
    }

    private fun card(title: String, subtitle: String, color: Int = T.ink, call: () -> Unit) {
        val row = T.column(this).apply {
            background = T.surface(this@MainActivity)
            minimumHeight = dp(T.touch)
            setPadding(dp(T.padding), dp(T.gap), dp(T.padding), dp(T.gap))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(T.gap) }
            isClickable = true
            isFocusable = true
            setOnClickListener { if (!busy) call() }
            contentDescription = listOf(title, subtitle).filter { it.isNotBlank() }.joinToString(". ")
        }
        row.addView(T.text(this, title, T.heading, color, true).apply { maxLines = T.cardPreviewLines })
        if (subtitle.isNotBlank()) row.addView(T.text(this, subtitle, T.caption, T.secondary).apply { maxLines = T.cardPreviewLines })
        body.addView(row)
    }

    private fun renderHome() {
        heading(getString(R.string.app_name), T.brand)
        if (config == null && additionalHubs.isEmpty()) {
            paragraph(getString(R.string.pair_body))
            action(R.string.connect, primary = true) { navigate("settings") }
            return
        }
        val requests = snapshot?.interactions.orEmpty()
        if (snapshot != null && requests.isNotEmpty()) section(getString(R.string.pending_count, requests.size))
        if (snapshot != null && requests.isEmpty()) {
            paragraph(getString(R.string.all_clear), compact = true)
        } else requests.forEach { request ->
            card(request.title.ifBlank { kindText(request.kind) }, listOf(request.groupName, request.summary.ifBlank { kindText(request.kind) }).filter { it.isNotBlank() }.joinToString(" · "), T.accent) {
                selectedInteraction = request; navigate("interaction")
            }
        }
        action(R.string.assistant) { navigate("assistant") }
        action(R.string.agents) { navigate("agents") }
        action(R.string.inbox) { navigate("inbox") }
        config?.takeIf { PetBridge.hasCached(this, it) }?.let { action(R.string.pet_title) { startActivity(Intent(this, PetActivity::class.java)) } }
        action(R.string.extension_widgets) {
            if (additionalHubs.isEmpty()) startActivity(Intent(this, ExtensionWidgetsActivity::class.java))
            else {
                val groups = (if (config != null) listOf("" to getString(R.string.hub_primary)) else emptyList()) + additionalHubs.map { it.id to it.name }
                android.app.AlertDialog.Builder(this).setTitle(R.string.extension_widgets)
                    .setItems(groups.map { it.second }.toTypedArray()) { _, index ->
                        startActivity(Intent(this, ExtensionWidgetsActivity::class.java).putExtra("group", groups[index].first))
                    }.show()
            }
        }
        action(R.string.refresh) { notice = ""; refresh() }
        action(R.string.settings) { navigate("settings") }
    }

    private fun renderSessions(assistant: Boolean) {
        heading(getString(if (assistant) R.string.assistant else R.string.agents))
        val sessions = snapshot?.sessions.orEmpty().filter { it.assistant == assistant }
        if (sessions.isEmpty()) {
            paragraph(getString(if (snapshot == null) R.string.loading else if (assistant) R.string.no_assistant else R.string.no_agents))
            if (assistant && snapshot != null) paragraph(getString(R.string.no_assistant_body), compact = true)
        }
        sessions.forEach { session ->
            card(session.title, listOf(session.groupName, stateText(session.state)).filter { it.isNotBlank() }.joinToString(" · "), stateColor(session.state)) { openSession(session) }
        }
        action(R.string.refresh) { refresh() }
    }

    private fun openSession(session: WatchSession) {
        selectedSession = session
        navigate("session")
        if (session.groupId !in failedGroups) hubFor(session.groupId)?.let { hub -> network.execute { runCatching { hub.markSeen(session) } } }
        loadMessages(session)
    }

    private fun renderSession() {
        sessionUiNeedsRefresh = false
        val original = selectedSession ?: return paragraph(getString(R.string.unknown_item))
        val session = snapshot?.sessions?.firstOrNull { it.key == original.key } ?: original
        selectedSession = session
        heading(session.title)
        if (session.groupName.isNotBlank()) paragraph(session.groupName, compact = true)
        paragraph(stateText(session.state), stateColor(session.state), true)
        snapshot?.interactions.orEmpty().filter { it.sessionKey == session.key }.forEach { request ->
            card(kindText(request.kind), request.summary, T.accent) { selectedInteraction = request; navigate("interaction") }
        }
        val messages = transcripts[session.key]
        if (messages == null) paragraph(getString(R.string.loading))
        else if (messages.isEmpty()) paragraph(getString(R.string.no_messages))
        messages?.forEach { message ->
            val container = T.column(this).apply {
                background = T.surface(this@MainActivity, if (message.own) T.primaryFill else T.card)
                setPadding(dp(T.padding), dp(T.gap), dp(T.padding), dp(T.gap))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(T.gap) }
            }
            val sender = if (message.own) getString(R.string.you) else if (message.role == "user")
                message.sourceTitle.takeIf { it.isNotBlank() }?.let { "@$it" } ?: getString(R.string.message_system_source)
                else getString(R.string.assistant)
            container.addView(T.text(this, sender, T.caption, if (message.own) T.ink else T.secondary))
            container.addView(T.text(this, message.text, T.body).apply {
                maxLines = MESSAGE_PREVIEW_LINES
                setTextIsSelectable(true)
            })
            if (message.text.length > TEXT_PREVIEW_LENGTH || message.text.count { it == '\n' } >= MESSAGE_PREVIEW_LINES) {
                container.addView(T.button(this, getString(R.string.long_text)) { openFullText(message.text) })
            }
            if (message.work.isNotEmpty()) {
                container.addView(T.button(this, getString(R.string.work_count, message.work.sumOf { it.count })) {
                    loadWork(session, message)
                })
            }
            body.addView(container)
        }
        if (session.canSend) {
            compose("session:${session.key}") { text, id ->
                val current = snapshot?.sessions?.firstOrNull { it.key == session.key }
                if (current?.canSend != true) { error = getString(R.string.permission_failed); updateStatus(); return@compose }
                perform(R.string.processing, owner = current.groupId, task = { it.send(current, text, id) }) {
                    clearDraft("session:${session.key}")
                    loadMessages(session)
                }
            }
        } else paragraph(getString(R.string.read_only), compact = true)
        if (session.canSend) action(R.string.continue_action) {
            val current = snapshot?.sessions?.firstOrNull { it.key == session.key }
            if (current?.canSend == true) perform(R.string.processing, owner = current.groupId, task = {
                it.send(current, getString(R.string.continue_action), operationId("continue:${session.key}"))
            }) {
                drafts.edit().remove("operation:${hash("continue:${session.key}")}").apply()
                loadMessages(current)
            }
        }
        if (session.canInterrupt && session.state in setOf("working", "needs_you")) {
            action(R.string.stop, destructive = true) {
                confirm(getString(R.string.stop_body), getString(R.string.stop), true) {
                    perform(R.string.stopped, owner = session.groupId, task = { it.interrupt(session) })
                }
            }
        }
        action(R.string.refresh) { loadMessages(session); refresh() }
    }

    private fun loadMessages(session: WatchSession) {
        val hub = hubFor(session.groupId) ?: return
        val requestGeneration = generation
        network.execute {
            val result = runCatching { hub.messages(session, textLabels()) }
            handler.post {
                if (isDestroyed || generation != requestGeneration) return@post
                result.fold({ messages ->
                    val changed = transcripts[session.key] != messages
                    transcripts[session.key] = messages
                    if (changed && page == "session" && selectedSession?.key == session.key && composer?.hasFocus() != true) render(true)
                }, { error = errorText(it); updateStatus() })
            }
        }
    }

    private fun textLabels() = WatchTextLabels(photo = getString(R.string.text_photo), video = getString(R.string.text_video),
        file = getString(R.string.text_file), card = getString(R.string.text_card), contexts = { getString(R.string.text_contexts, it) },
        untitledContext = getString(R.string.text_context))

    private fun loadWork(session: WatchSession, message: WatchMessage) {
        val hub = hubFor(session.groupId) ?: return
        val requestGeneration = generation
        notice = getString(R.string.refreshing)
        updateStatus()
        network.execute {
            val result = runCatching { hub.work(session, message) }
            handler.post {
                if (isDestroyed || requestGeneration != generation) return@post
                notice = ""
                result.fold({ lines ->
                    if (page == "session" && selectedSession?.key == session.key) openFullText(lines.joinToString("\n\n"))
                }, { error = errorText(it); updateStatus() })
            }
        }
    }

    private fun renderInteraction() {
        val item = selectedInteraction ?: return paragraph(getString(R.string.unknown_item))
        heading(kindText(item.kind))
        paragraph(item.title, T.ink)
        if (item.groupName.isNotBlank()) paragraph(item.groupName, compact = true)
        if (!item.canAnswer) paragraph(getString(R.string.requires_phone), T.working)
        when (item.kind) {
            "question" -> {
                item.questions.forEach { question ->
                    section(question.question)
                    val key = "${item.key}:${question.question}"
                    question.options.forEach { option ->
                        choice(option.label, option.description, picks[key]?.contains(option.label) == true, item.canAnswer) {
                            val current = picks.getOrPut(key) { mutableSetOf() }
                            if (question.multiSelect) {
                                if (!current.add(option.label)) current.remove(option.label)
                            } else { current.clear(); current.add(option.label) }
                            render(true)
                        }
                    }
                }
                action(R.string.submit, primary = true, enabled = item.canAnswer && item.questions.isNotEmpty()) {
                    val answers = item.questions.associate { it.question to picks["${item.key}:${it.question}"].orEmpty().toSet() }
                    if (answers.values.any { it.isEmpty() }) { notice = getString(R.string.select_answer); updateStatus() }
                    else resolve(item, WatchResolution.question(item, answers))
                }
            }
            "plan" -> {
                paragraph(WatchParser.plainText(item.plan.ifBlank { item.summary }), T.ink)
                action(R.string.approve, primary = true, enabled = item.canAnswer) {
                    confirm(getString(R.string.approve_body), getString(R.string.approve)) { resolve(item, WatchResolution.plan(item, true)) }
                }
                action(R.string.send_back, enabled = item.canAnswer) { resolve(item, WatchResolution.plan(item, false, getString(R.string.plan_feedback))) }
            }
            "permission" -> {
                paragraph(item.summary, T.ink)
                if (item.requiresInput) paragraph(getString(R.string.requires_phone), T.working)
                else action(R.string.allow, primary = true, enabled = item.canAnswer) {
                    confirm(item.summary + "\n\n" + getString(R.string.allow_body), getString(R.string.allow)) { resolve(item, WatchResolution.permission(item, true)) }
                }
                action(R.string.deny, enabled = item.canAnswer) { resolve(item, WatchResolution.permission(item, false)) }
            }
            "screen" -> {
                paragraph(item.screen.ifBlank { item.summary }, T.ink)
                val key = item.key
                if (key !in picks) picks[key] = item.options.filter { it.checked }.map { it.key }.toMutableSet()
                item.options.forEach { option ->
                    choice(option.label, "", picks[key]?.contains(option.key) == true, item.canAnswer) {
                        val current = picks.getOrPut(key) { mutableSetOf() }
                        if (item.multiSelect) { if (!current.add(option.key)) current.remove(option.key) }
                        else { current.clear(); current.add(option.key) }
                        render(true)
                    }
                }
                action(R.string.submit, primary = true, enabled = item.canAnswer && item.options.isNotEmpty()) {
                    val selected = picks[key].orEmpty()
                    if (selected.isEmpty()) { notice = getString(R.string.select_answer); updateStatus() }
                    else resolve(item, WatchResolution.screen(item, selected))
                }
                if (item.options.isEmpty()) paragraph(getString(R.string.requires_phone), T.working)
                action(R.string.screen_escape, enabled = item.canAnswer) {
                    resolve(item, JSONObject().put("keys", org.json.JSONArray().put("esc")))
                }
                action(R.string.screen_enter, enabled = item.canAnswer) {
                    confirm(getString(R.string.screen_enter_body), getString(R.string.screen_enter)) {
                        resolve(item, JSONObject().put("keys", org.json.JSONArray().put("enter")))
                    }
                }
            }
            else -> paragraph(item.summary, T.ink)
        }
        if (item.canAnswer && item.kind in setOf("question", "plan", "permission", "screen")) {
            action(R.string.handle_elsewhere) { resolve(item, WatchResolution.dismiss(item)) }
        }
    }

    private fun choice(label: String, description: String, selected: Boolean, enabled: Boolean, call: () -> Unit) {
        val box = CheckBox(this).apply {
            text = label
            textSize = T.body
            setTextColor(T.ink)
            buttonTintList = android.content.res.ColorStateList.valueOf(T.accent)
            isChecked = selected
            isEnabled = enabled && !busy
            minimumHeight = dp(T.touch)
            setPadding(T.zero, dp(T.lineGap), T.zero, dp(T.lineGap))
            setOnClickListener { call() }
        }
        body.addView(box)
        if (description.isNotBlank()) paragraph(description, compact = true)
    }

    private fun resolve(item: WatchInteraction, resolution: JSONObject) {
        val current = snapshot?.interactions?.firstOrNull { it.key == item.key }
        if (current != item) {
            selectedInteraction = current
            confirmation = null
            page = if (current == null) "home" else "interaction"
            notice = getString(if (current == null) R.string.request_gone else R.string.request_changed)
            render()
            return
        }
        val opKey = "resolve:${item.key}:${resolution}"
        var outcome = ResolveResult()
        perform(R.string.handled, owner = item.groupId, task = { outcome = it.resolve(item, resolution, operationId(opKey)) }) {
            if (outcome.already || outcome.gone) notice = getString(R.string.request_gone)
            drafts.edit().remove("operation:${hash(opKey)}").apply()
            selectedInteraction = null
            page = "home"
        }
    }

    private fun renderInbox() {
        heading(getString(R.string.inbox))
        if (snapshot?.inboxUnavailable == true) paragraph(getString(
            if (snapshot?.inbox.isNullOrEmpty()) R.string.inbox_unavailable else R.string.inbox_stale), T.working)
        else if (snapshot?.inbox.isNullOrEmpty()) paragraph(getString(if (snapshot == null) R.string.loading else R.string.no_inbox))
        snapshot?.inbox.orEmpty().forEach { item -> card(item.title, item.preview) { selectedInbox = item; navigate("inbox_item") } }
        action(R.string.refresh) { refresh() }
    }

    private fun renderInboxItem() {
        val item = selectedInbox ?: return paragraph(getString(R.string.unknown_item))
        heading(item.title)
        if (item.accountName.isNotBlank()) paragraph(item.accountName, compact = true)
        paragraph(item.preview, T.ink)
        val draftKey = "inbox:${item.manager.orEmpty()}:${item.id}"
        val storageKey = "draft:${hash(draftKey)}"
        val prefilledKey = "prefilled:${hash(draftKey + item.suggestedReply)}"
        if (item.canReply && item.suggestedReply.isNotBlank() && !drafts.contains(storageKey) && !drafts.getBoolean(prefilledKey, false))
            drafts.edit().putString(storageKey, item.suggestedReply.take(16000)).putBoolean(prefilledKey, true).apply()
        val canReply = item.canReply && "" !in failedGroups
        if (!canReply && item.canReply) paragraph(getString(R.string.read_only), compact = true)
        if (canReply) compose(draftKey) { text, id ->
            perform(R.string.saved, { it.reply(item, text, id) }) { clearDraft(draftKey) }
        }
        if (canReply) action(R.string.inbox_ai_reply) {
            val makeDraft = {
                var suggestion = ""
                perform(R.string.inbox_draft_ready, { suggestion = it.draftReply(item) }) {
                    drafts.edit().putString(storageKey, suggestion).apply()
                }
            }
            if (drafts.getString(storageKey, "").isNullOrBlank()) makeDraft()
            else confirm(getString(R.string.inbox_replace_draft_body), getString(R.string.inbox_ai_reply), call = makeDraft)
        }
        action(R.string.mark_done, enabled = "" !in failedGroups) {
            confirm(getString(R.string.mark_done_body), getString(R.string.mark_done)) {
                perform(R.string.saved, { it.markDone(item) }) { page = "inbox" }
            }
        }
    }

    private fun compose(key: String, send: (String, String) -> Unit) {
        section(getString(R.string.reply))
        val field = input(getString(R.string.reply_hint), drafts.getString("draft:${hash(key)}", "").orEmpty())
        field.setSingleLine(false)
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                drafts.edit().putString("draft:${hash(key)}", s.toString()).apply()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        composer = field
        action(R.string.dictate) { dictate(key) }
        action(R.string.send, primary = true) {
            val text = field.text.toString().trim()
            if (text.isNotEmpty()) send(text, operationId("send:$key:$text"))
        }
    }

    // A self-hosted hub's one-time pairing credential, not an account password. Keep it masked;
    // normal deployment provisions it through the private debug file without typing on the watch.
    @SuppressLint("WearPasswordInput")
    private fun input(hint: String, value: String = "", secret: Boolean = false): EditText {
        val field = EditText(this).apply {
            setText(value)
            this.hint = hint
            textSize = T.body
            setTextColor(T.ink)
            setHintTextColor(T.secondary)
            backgroundTintList = android.content.res.ColorStateList.valueOf(T.accent)
            minimumHeight = dp(T.touch)
            setPadding(dp(T.lineGap), dp(T.lineGap), dp(T.lineGap), dp(T.lineGap))
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        body.addView(field, LinearLayout.LayoutParams(-1, -2))
        return field
    }

    private fun renderSettings() {
        action(R.string.hub_groups) { startActivity(Intent(this, ConnectionsActivity::class.java)) }
        heading(getString(R.string.pair_title))
        paragraph(getString(R.string.pair_body), compact = true)
        section(getString(R.string.pair_link_or_address))
        val address = input(getString(R.string.hub_hint), config?.baseUrl.orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        section(getString(R.string.pair_token))
        val token = input(getString(if (config == null) R.string.pair_token else R.string.token_saved), secret = true)
        action(R.string.show_token) {
            val show = token.transformationMethod != null
            token.transformationMethod = if (show) null else PasswordTransformationMethod.getInstance()
            token.setSelection(token.text.length)
        }
        action(R.string.connect, primary = true) {
            val old = config
            val enteredToken = token.text.toString().trim()
            val enteredAddress = address.text.toString().trim()
            val pairingLink = enteredAddress.startsWith("persona:")
            if (!pairingLink && old != null && enteredToken.isBlank() && enteredAddress.trimEnd('/') != old.baseUrl) {
                error = getString(R.string.new_address_token); updateStatus(); return@action
            }
            val usedToken = enteredToken.ifBlank { old?.token.orEmpty() }
            val candidate = runCatching { if (pairingLink) PairingInput.parse(enteredAddress, enteredToken) else HubConfig(enteredAddress, usedToken,
                if (enteredAddress.trimEnd('/') == old?.baseUrl) old.fingerprint else null,
                if (enteredToken.isBlank()) old?.deviceId else null).validated() }.getOrElse {
                error = getString(R.string.invalid_connection); updateStatus(); return@action
            }
            busy = true
            notice = getString(R.string.pairing)
            error = ""
            updateStatus()
            network.execute {
                var accepted: HubConfig? = null
                val result = runCatching {
                    val paired = if (!pairingLink && enteredToken.isBlank() && old?.baseUrl == candidate.baseUrl) candidate else HubClient(candidate, language()).pair()
                    credentials.save(paired)
                    WidgetUpdates.request(this)
                    accepted = paired
                    val fresh = HubClient(paired, language()).refresh()
                    paired to fresh
                }
                handler.post {
                    if (isDestroyed) return@post
                    busy = false
                    result.fold({ (paired, fresh) ->
                        groupCache = emptyMap(); failedGroups = emptySet()
                        config = paired; client = HubClient(paired, language()); snapshot = fresh
                        generation++; refreshing = false; transcripts.clear(); clearSelection(); page = "home"
                        notice = getString(R.string.connection_saved); error = ""; render()
                    }, {
                        accepted?.let { paired ->
                            groupCache = emptyMap(); failedGroups = emptySet()
                            config = paired; client = HubClient(paired, language()); snapshot = null
                            generation++; refreshing = false; transcripts.clear(); clearSelection(); page = "home"
                        }
                        error = errorText(it); notice = ""
                        if (accepted != null) render() else updateStatus()
                    })
                }
            }
        }
        if (config != null) action(R.string.disconnect, destructive = true) {
            confirm(getString(R.string.disconnect_body), getString(R.string.disconnect), true) {
                credentials.clear(); config = null; client = null; snapshot = null
                WidgetUpdates.request(this)
                groupCache = emptyMap(); failedGroups = emptySet()
                generation++; refreshing = false; transcripts.clear(); clearSelection()
                error = ""; notice = ""; page = "settings"; render()
            }
        }
        paragraph(getString(R.string.watch_scope), compact = true)
        paragraph(getString(R.string.watch_version, BuildConfig.VERSION_NAME), compact = true)
    }

    private fun perform(success: Int, task: (HubClient) -> Any?, owner: String = "", done: () -> Unit = {}) {
        if (owner in failedGroups) { error = getString(R.string.connection_failed); updateStatus(); return }
        val hub = hubFor(owner) ?: return
        if (busy) return
        busy = true
        notice = getString(R.string.sending)
        error = ""
        render(true)
        val requestGeneration = generation
        mutations.execute {
            val result = runCatching { task(hub) }
            handler.post {
                if (isDestroyed || requestGeneration != generation) return@post
                busy = false
                result.fold({
                    notice = getString(success)
                    done()
                    scroll.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }, {
                    error = if ((it as? HubException)?.code in setOf("timeout", "unreachable", "hub_unavailable")) getString(R.string.action_failed) else errorText(it)
                    notice = ""
                })
                render(true)
                refresh()
            }
        }
    }

    private fun confirm(message: String, label: String, dangerous: Boolean = false, call: () -> Unit) {
        previousPage = page
        confirmation = call
        confirmationText = message
        confirmationLabel = label
        confirmationDanger = dangerous
        page = "confirm"
        render()
    }

    private fun renderConfirmation() {
        heading(confirmationLabel)
        paragraph(confirmationText, T.ink)
        action(confirmationLabel, primary = !confirmationDanger, destructive = confirmationDanger) {
            val operation = confirmation
            confirmation = null
            page = previousPage
            operation?.invoke()
        }
        action(R.string.cancel) { page = previousPage; confirmation = null; render() }
    }

    private fun openFullText(text: String) { fullText = text; previousPage = page; navigate("full_text") }
    private fun navigate(destination: String) { notice = ""; page = destination; render() }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        val value = when {
            error.isNotBlank() -> error + (snapshot?.let {
                "\n" + getString(R.string.updated_ago, (System.currentTimeMillis() - it.updatedAt).coerceAtLeast(0) / 60_000)
            } ?: "")
            notice.isNotBlank() -> notice
            busy -> getString(R.string.sending)
            snapshot == null && refreshing -> getString(R.string.loading)
            snapshot != null -> {
                val age = (System.currentTimeMillis() - snapshot!!.updatedAt).coerceAtLeast(0) / 60_000
                if (age == 0L) getString(R.string.connected) else getString(R.string.updated_ago, age)
            }
            else -> ""
        }
        status.text = value
        status.setTextColor(if (error.isNotEmpty()) T.working else T.secondary)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun errorText(cause: Throwable): String = getString(when ((cause as? HubException)?.code) {
        "unauthorized", "invalid_token", "authentication_failed", "pairing_expired", "invalid_watch_credential" -> R.string.authentication_failed
        "forbidden", "unsupported_action", "cannot_answer", "requires_input", "requires_phone", "read_only", "unsupported_interaction" -> R.string.permission_failed
        "gone", "not_found" -> R.string.request_gone
        "invalid_address", "https_required", "invalid_fingerprint" -> R.string.invalid_connection
        "unanswered_question", "invalid_answer" -> R.string.select_answer
        "delivery_unconfirmed", "changed", "request_failed", "invalid_operation" -> R.string.action_failed
        else -> if (busy) R.string.action_failed else R.string.connection_failed
    })

    private fun stateText(value: String) = getString(when (value) {
        "needs_you", "blocked" -> R.string.needs_you
        "working" -> R.string.working
        "unread" -> R.string.unread
        "done" -> R.string.done
        "offline" -> R.string.offline
        else -> R.string.idle
    })
    private fun stateColor(value: String) = when (value) {
        "needs_you", "blocked" -> T.accent
        "working" -> T.working
        "unread", "done" -> T.positive
        else -> T.secondary
    }
    private fun kindText(value: String) = getString(when (value) {
        "question" -> R.string.question; "plan" -> R.string.plan
        "permission" -> R.string.approval; "screen" -> R.string.screen; else -> R.string.request
    })
    private fun dp(value: Int) = T.dp(this, value)
    private fun hash(value: String) = DraftScope.hash(config?.baseUrl.orEmpty(), additionalHubs.map { it.id }.toSet(), value)
    private fun clearSelection() {
        selectedSession = null
        selectedInteraction = null
        selectedInbox = null
        confirmation = null
        picks.clear()
    }
    private fun operationId(key: String): String {
        val stored = "operation:${hash(key)}"
        return drafts.getString(stored, null) ?: UUID.randomUUID().toString().also { drafts.edit().putString(stored, it).apply() }
    }
    private fun clearDraft(key: String) {
        val old = drafts.getString("draft:${hash(key)}", "").orEmpty().trim()
        drafts.edit().remove("draft:${hash(key)}").remove("operation:${hash("send:$key:$old")}").apply()
    }

    private fun dictate(key: String) {
        dictationDraftKey = key
        try {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, resources.configuration.locales[0].toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.dictate_prompt))
            }, DICTATION_REQUEST)
        } catch (_: ActivityNotFoundException) {
            notice = getString(R.string.dictation_unavailable); updateStatus()
        }
    }

    @Deprecated("Platform speech result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DICTATION_REQUEST || resultCode != RESULT_OK) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        val key = dictationDraftKey ?: return
        drafts.edit().putString("draft:${hash(key)}", text).apply()
        render(true)
        notice = getString(R.string.draft_unsent)
        updateStatus()
    }

    companion object {
        private const val POLL_MS = 8_000L
        private const val DICTATION_REQUEST = 10
        private const val MESSAGE_PREVIEW_LINES = 8
        private const val TEXT_PREVIEW_LENGTH = 350
    }
}
