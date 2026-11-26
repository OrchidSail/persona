package persona.platform.phonetools.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Localized strings supplied to [PhoneControlExecutor] for floating overlay status updates.
 */
data class OverlayCopy(
    val readingScreen: String = "Reading current screen",
    val pausedOnOverlay: String = "Paused on phone",
    val resumedOnOverlay: String = "Resumed",
    val takeoverActive: String = "Manual takeover active; tap Return control when done",
    val controlReturned: String = "Control returned; resuming task",
    val stoppedOnOverlay: String = "Stopped on phone",
    val connectionLost: String = "Connection lost; paused",
)

/**
 * Executes the 5 atomic phone control operations (`device_state`, `observe`, `action`, `session`, `otp_consume`)
 * for the thin controlled shell while enforcing:
 * - Master switch enabled check (`control_disabled` when OFF).
 * - Single-device serialization via [Mutex] on `observe`/`action` while `device_state`/`session`/`otp_consume` stay fast.
 * - Bounded in-memory idempotency deduplication via [PhoneControlCallGate] on `action` and `otp_consume`.
 * - Deterministic local [SafetyGatePolicy] BEFORE every action & OTP read, including `tap_xy` coordinate node lookup.
 * - Unconditional `://` URI scheme rejection.
 * - Preserved step numbers across overlay Pause/Resume/Takeover/Return/Stop callbacks.
 */
class PhoneControlExecutor(
    private val screenFactory: () -> ScreenPort,
    private val masterEnabledProvider: () -> Boolean,
    private val safetyPolicyProvider: () -> SafetyGateConfig,
    private val deviceStateProvider: DeviceControlStateProvider,
    private val onOverlayUpdate: (OverlayTaskSummary) -> Unit = {},
    private val onSessionLifecycle: (active: Boolean, terminal: Boolean) -> Unit = { _, _ -> },
    private val copy: OverlayCopy = OverlayCopy(),
    private val callGate: PhoneControlCallGate = PhoneControlCallGate(),
    private val virtualDisplayActiveProvider: () -> Boolean = { false },
    private val onBringTaskToForeground: () -> Unit = {},
) {
    private val gate = SafetyGatePolicy(safetyPolicyProvider)
    private val screenRef = AtomicReference<ScreenPort?>(null)
    private val executionMutex = Mutex()
    private val snapshotsById = ConcurrentHashMap<String, ScreenSnapshot>()

    @Volatile
    private var lastSnapshot: ScreenSnapshot? = null

    @Volatile
    private var lastKnownStep: Int = 0

    private val _controlState = MutableStateFlow(OverlayControlState())
    val controlState: StateFlow<OverlayControlState> = _controlState.asStateFlow()

    val activeTaskId: String
        get() = _controlState.value.taskId.orEmpty()

    val hasActiveSession: Boolean
        get() = _controlState.value.taskId != null

    private fun screen(): ScreenPort {
        screenRef.get()?.let { return it }
        val created = screenFactory()
        return if (screenRef.compareAndSet(null, created)) created else screenRef.get()!!
    }

    suspend fun execute(call: PhoneControlCall): PhoneControlResult {
        if (!masterEnabledProvider()) {
            return PhoneControlResult.error("control_disabled", "Remote phone control is turned off on phone")
        }
        return try {
            val payload = when (call.op) {
                "device_state" -> deviceState()
                "observe" -> executionMutex.withLock { observe(call.args) }
                "action" -> executeIdempotent(call) { executionMutex.withLock { executeAction(call.args) } }
                "session" -> syncSession(call.args)
                "otp_consume" -> executeIdempotent(call) { consumeOtp(call.args) }
                else -> throw PhoneControlException("invalid_params", "Unsupported op: ${call.op}")
            }
            PhoneControlResult.ok(payload)
        } catch (err: PhoneControlException) {
            PhoneControlResult.error(err.code, err.message)
        }
    }

    private suspend fun executeIdempotent(
        call: PhoneControlCall,
        block: suspend () -> JsonObject,
    ): JsonObject {
        val key = call.idempotencyKey.ifBlank { call.callId }
        return when (val begin = callGate.begin(key)) {
            is PhoneControlCallGate.Begin.Cached -> begin.value
            is PhoneControlCallGate.Begin.Follower -> begin.deferred.await()
            PhoneControlCallGate.Begin.Leader -> {
                try {
                    val result = block()
                    callGate.complete(key, result)
                    result
                } catch (t: Throwable) {
                    callGate.fail(key, t)
                    throw t
                }
            }
        }
    }

    private fun ensureAccessibilityReady() {
        val snap = deviceStateProvider.snapshot()
        if (!snap.masterEnabled) {
            throw PhoneControlException("control_disabled", "Remote phone control is turned off on phone")
        }
        if (!snap.permissions.accessibility || !snap.helperAvailable) {
            throw PhoneControlException("accessibility_offline", "Accessibility service is not enabled on phone")
        }
    }

    fun deviceState(): JsonObject =
        deviceStateProvider.snapshot().toResultJson(activeTaskId, _controlState.value)

    suspend fun observe(body: JsonObject): JsonObject {
        ensureAccessibilityReady()
        val taskId = body["task_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val step = body["step"]?.jsonPrimitive?.intOrNull ?: lastKnownStep
        val narration = body["narration"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val includeScreenshot = body["include_screenshot"]?.jsonPrimitive?.booleanOrNull ?: false
        val maxEdge = (body["screenshot_max_edge"]?.jsonPrimitive?.intOrNull ?: ScreenshotEncoder.DEFAULT_MAX_EDGE)
            .coerceIn(ScreenshotEncoder.MIN_MAX_EDGE, ScreenshotEncoder.MAX_MAX_EDGE)
        val quality = (body["screenshot_quality"]?.jsonPrimitive?.intOrNull ?: ScreenshotEncoder.DEFAULT_QUALITY)
            .coerceIn(ScreenshotEncoder.MIN_QUALITY, ScreenshotEncoder.MAX_QUALITY)
        val includeOtpMeta = body["include_otp_meta"]?.jsonPrimitive?.booleanOrNull
            ?: body["include_notifications"]?.jsonPrimitive?.booleanOrNull
            ?: true
        val appHint = body["app_hint"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val sinceMs = body["since_ms"]?.jsonPrimitive?.longOrNull ?: 0L

        if (taskId != null) {
            ensureSessionTaskId(taskId)
            rememberStep(step)
            onSessionLifecycle(true, false)
            val ctrl = _controlState.value
            if (!ctrl.paused && !ctrl.takeoverRequested && !ctrl.stopRequested) {
                publishOverlay(
                    taskId = taskId,
                    status = "running",
                    step = lastKnownStep,
                    summary = narration.ifBlank { copy.readingScreen },
                    isActive = true,
                )
            }
        }

        val snap = screen().captureSnapshot(
            includeScreenshot = includeScreenshot,
            screenshotMaxEdge = maxEdge,
            screenshotQuality = quality,
        )
        snapshotsById.clear()
        val _ = snapshotsById.put(snap.snapshotId, snap)
        lastSnapshot = snap

        val policy = safetyPolicyProvider()
        val otpMeta = if (includeOtpMeta && policy.allowNotificationOtpAutoLogin) {
            screen().findLatestOtp(windowMs = 180_000L, sinceMs = sinceMs, appHint = appHint)?.toMeta()
                ?: OtpMeta(available = false)
        } else {
            OtpMeta(available = false)
        }

        val passiveVerdict = gate.evaluate(
            ActionRiskContext(
                actionName = "observe",
                currentPackage = snap.topPackage.orEmpty(),
                visibleTexts = snap.visibleTexts,
                hasPasswordField = snap.hasPasswordField,
            ),
        )

        return buildJsonObject {
            put("snapshot_id", snap.snapshotId)
            put("display_id", snap.displayId)
            put("width", snap.width)
            put("height", snap.height)
            snap.topPackage?.let { put("top_package", it) }
            put("using_virtual_display", snap.usingVirtualDisplay)
            put("has_password_field", snap.hasPasswordField)
            put("ui_hash", snap.uiHash)
            put("prompt_summary", snap.toPromptSummary())
            putJsonArray("elements") {
                snap.elements.take(100).forEach { e ->
                    addJsonObject {
                        put("ref", e.ref)
                        put("role", e.role)
                        put("name", if (e.secret) "[secret]" else e.name)
                        put("center_x", e.centerX)
                        put("center_y", e.centerY)
                        putJsonArray("bounds") { e.bounds.forEach { add(it) } }
                        e.resourceId?.let { put("resource_id", it) }
                        put("clickable", e.clickable)
                        put("editable", e.editable)
                        put("scrollable", e.scrollable)
                        put("focused", e.focused)
                        put("secret", e.secret)
                        if (e.placeholder != null) put("placeholder", e.placeholder) else put("placeholder", JsonNull)
                        putJsonArray("states") { e.states.forEach { add(it) } }
                    }
                }
            }
            putJsonArray("visible_texts") {
                snap.visibleTexts.take(100).forEach { add(it) }
            }
            put("safety_policy", policy.toJson())
            put("otp_allowed", policy.allowNotificationOtpAutoLogin)
            // Never return plaintext otp.code in observe; return redacted otp_meta only.
            put("otp_meta", otpMeta.toJson())
            put("screenshot", JsonNullOrObject(snap.screenshot?.toJson()))
            if (snap.screenshotOmittedReason != null) {
                put("screenshot_omitted_reason", snap.screenshotOmittedReason)
            } else {
                put("screenshot_omitted_reason", JsonNull)
            }
            if (passiveVerdict is GateDecision.RequirePhone) {
                putJsonObject("screen_gate") {
                    put("gated", true)
                    put("waiting_for", passiveVerdict.waitingFor)
                    put("reason", passiveVerdict.reason)
                    passiveVerdict.app?.let { put("app", it) }
                }
            } else {
                put("screen_gate", JsonNull)
            }
            put("overlay_control", _controlState.value.toJson())
        }
    }

    suspend fun executeAction(body: JsonObject): JsonObject {
        ensureAccessibilityReady()
        val taskId = body["task_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val step = body["step"]?.jsonPrimitive?.intOrNull ?: lastKnownStep
        val narration = body["narration"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val snapshotId = body["snapshot_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val action = body["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
        val params = (body["params"] as? JsonObject) ?: (body["args"] as? JsonObject) ?: JsonObject(emptyMap())

        if (action.isEmpty()) {
            throw PhoneControlException("invalid_params", "action is required")
        }
        if (taskId != null) {
            ensureSessionTaskId(taskId)
            rememberStep(step)
            onSessionLifecycle(true, false)
        }

        val ctrl = _controlState.value
        if (ctrl.stopRequested) {
            return actionResult(
                ok = false,
                cancelledOnPhone = true,
                detail = copy.stoppedOnOverlay,
                error = copy.stoppedOnOverlay,
            )
        }
        if (ctrl.takeoverRequested) {
            publishOverlay(
                taskId = taskId ?: ctrl.taskId,
                status = "waiting_on_phone",
                step = lastKnownStep,
                summary = copy.takeoverActive,
                isWaitingOnPhone = true,
                isActive = true,
            )
            return actionResult(
                ok = false,
                gated = true,
                waitingFor = "manual_takeover",
                reason = copy.takeoverActive,
            )
        }
        if (ctrl.paused) {
            return actionResult(
                ok = false,
                pausedOnPhone = true,
                detail = copy.pausedOnOverlay,
                error = copy.pausedOnOverlay,
            )
        }

        val snap = snapshotsById[snapshotId] ?: lastSnapshot
        val ref = params["ref"]?.jsonPrimitive?.contentOrNull
        val tapX = params["x"]?.jsonPrimitive?.intOrNull
        val tapY = params["y"]?.jsonPrimitive?.intOrNull
        // Security hardening: resolve hit UiElement at (x, y) on tap_xy so coordinate taps cannot bypass payment gates.
        val targetEl = when {
            ref != null -> snap?.findRef(ref)
            action == "tap_xy" && tapX != null && tapY != null -> snap?.findElementAt(tapX, tapY)
            else -> null
        }
        val explicitReason = params["reason"]?.jsonPrimitive?.contentOrNull
            ?: params["message"]?.jsonPrimitive?.contentOrNull
        val targetLabel = listOfNotNull(
            targetEl?.name?.takeIf { it.isNotBlank() },
            params["text"]?.jsonPrimitive?.contentOrNull?.takeIf { action != "input_text" },
            explicitReason,
        ).joinToString(" ")

        val riskCtx = ActionRiskContext(
            actionName = action,
            targetLabel = targetLabel,
            targetResourceId = targetEl?.resourceId.orEmpty(),
            currentPackage = snap?.topPackage.orEmpty(),
            visibleTexts = snap?.visibleTexts.orEmpty(),
            hasPasswordField = snap?.hasPasswordField ?: false,
        )
        when (val verdict = gate.evaluate(riskCtx)) {
            is GateDecision.RequirePhone -> {
                onBringTaskToForeground()
                _controlState.value = _controlState.value.copy(
                    taskId = taskId ?: _controlState.value.taskId,
                    takeoverRequested = false,
                    resumedFromPhone = false,
                )
                publishOverlay(
                    taskId = taskId ?: _controlState.value.taskId,
                    status = "waiting_on_phone",
                    step = lastKnownStep,
                    summary = verdict.reason,
                    isWaitingOnPhone = true,
                    isActive = true,
                )
                return actionResult(
                    ok = false,
                    gated = true,
                    waitingFor = verdict.waitingFor,
                    reason = verdict.reason,
                    app = verdict.app ?: snap?.topPackage,
                )
            }
            GateDecision.Allow -> Unit
        }

        val label = narration.ifBlank { "$action ${ref.orEmpty()}".trim() }
        publishOverlay(
            taskId = taskId ?: _controlState.value.taskId,
            status = "running",
            step = lastKnownStep,
            summary = label,
            isActive = true,
        )

        val port = screen()
        var extraApps: JsonArray? = null
        var extraOtp: ExtractedOtp? = null

        val outcome: ActionOutcome = when (action) {
            "tap" -> {
                val r = ref ?: return actionFailed("tap requires params.ref", snap?.topPackage)
                port.tapRef(r, snapshotId.ifBlank { snap?.snapshotId.orEmpty() })
            }
            "tap_xy" -> {
                val x = tapX ?: return actionFailed("tap_xy requires params.x", snap?.topPackage)
                val y = tapY ?: return actionFailed("tap_xy requires params.y", snap?.topPackage)
                port.tapXY(x, y)
            }
            "double_tap" -> {
                val r = ref ?: return actionFailed("double_tap requires params.ref", snap?.topPackage)
                port.doubleTapRef(r, snapshotId.ifBlank { snap?.snapshotId.orEmpty() })
            }
            "long_press" -> {
                val r = ref ?: return actionFailed("long_press requires params.ref", snap?.topPackage)
                port.longPressRef(r, snapshotId.ifBlank { snap?.snapshotId.orEmpty() })
            }
            "swipe" -> {
                val x1 = params["x1"]?.jsonPrimitive?.intOrNull ?: return actionFailed("swipe requires params.x1", snap?.topPackage)
                val y1 = params["y1"]?.jsonPrimitive?.intOrNull ?: return actionFailed("swipe requires params.y1", snap?.topPackage)
                val x2 = params["x2"]?.jsonPrimitive?.intOrNull ?: return actionFailed("swipe requires params.x2", snap?.topPackage)
                val y2 = params["y2"]?.jsonPrimitive?.intOrNull ?: return actionFailed("swipe requires params.y2", snap?.topPackage)
                val dur = (params["duration_ms"]?.jsonPrimitive?.intOrNull ?: 320).coerceIn(50, 2000).toLong()
                port.swipe(x1, y1, x2, y2, dur)
            }
            "scroll" -> {
                val down = !params["direction"]?.jsonPrimitive?.contentOrNull.equals("up", ignoreCase = true)
                port.scroll(ref, snapshotId.ifBlank { snap?.snapshotId }, down)
            }
            "input_text" -> {
                val text = params["text"]?.jsonPrimitive?.contentOrNull ?: return actionFailed("input_text requires params.text", snap?.topPackage)
                val clearFirst = params["clear_first"]?.jsonPrimitive?.booleanOrNull ?: true
                port.inputText(ref, snapshotId.ifBlank { snap?.snapshotId }, text, clearFirst)
            }
            "press_back" -> port.pressBack()
            "press_home" -> port.pressHome()
            "press_recents" -> port.pressRecents()
            "launch_app" -> {
                val app = params["app"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (app.isEmpty()) return actionFailed("launch_app requires params.app", snap?.topPackage)
                if (app.contains("://")) {
                    return actionFailed("URI scheme deep links are forbidden; launch apps by package or app name only", snap?.topPackage)
                }
                val preferVirtual = params["prefer_virtual_display"]?.jsonPrimitive?.booleanOrNull
                    ?: deviceStateProvider.snapshot().virtualDisplayReady
                port.launchApp(app, preferVirtualDisplay = preferVirtual)
            }
            "list_apps" -> {
                val apps = port.listLaunchableApps().take(80)
                extraApps = buildJsonArray {
                    apps.forEach { a ->
                        addJsonObject {
                            put("package_name", a.packageName)
                            put("label", a.label)
                        }
                    }
                }
                ActionOutcome.Ok("Listed ${apps.size} launchable apps", uiChanged = false)
            }
            "wait" -> {
                val ms = (params["ms"]?.jsonPrimitive?.intOrNull ?: 1500).coerceIn(200, 10_000).toLong()
                port.waitMs(ms)
                ActionOutcome.Ok("Waited ${ms}ms", uiChanged = false)
            }
            "fill_verification_code" -> {
                val otpId = params["otp_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                val appHint = params["app_hint"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                val sinceMs = params["since_ms"]?.jsonPrimitive?.longOrNull ?: 0L
                val clearFirst = params["clear_first"]?.jsonPrimitive?.booleanOrNull ?: true
                port.fillVerificationCode(
                    ref = ref,
                    snapshotId = snapshotId.ifBlank { snap?.snapshotId },
                    otpId = otpId,
                    appHint = appHint,
                    sinceMs = sinceMs,
                    clearFirst = clearFirst,
                )
            }
            "read_verification_code" -> {
                val otpId = params["otp_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                val hint = params["app_hint"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                val sinceMs = params["since_ms"]?.jsonPrimitive?.longOrNull ?: 0L
                val found = port.consumeOtp(windowMs = 180_000L, sinceMs = sinceMs, appHint = hint, otpId = otpId, consume = true)
                if (found == null) {
                    ActionOutcome.Failed("No recent unconsumed verification code found in notifications")
                } else {
                    extraOtp = found
                    ActionOutcome.Ok("Read verification code from ${found.senderOrTitle} (${found.packageName})", uiChanged = false)
                }
            }
            else -> return actionFailed("Unsupported action: $action", snap?.topPackage)
        }

        return when (outcome) {
            is ActionOutcome.Ok -> actionResult(
                ok = true,
                app = snap?.topPackage,
                detail = outcome.detail,
                uiChanged = outcome.uiChanged,
                apps = extraApps,
                otp = extraOtp,
            )
            is ActionOutcome.Failed -> actionResult(
                ok = false,
                app = snap?.topPackage,
                detail = outcome.error,
                error = outcome.error,
                apps = extraApps,
            )
        }
    }

    suspend fun consumeOtp(body: JsonObject): JsonObject {
        val policy = safetyPolicyProvider()
        if (!policy.allowNotificationOtpAutoLogin) {
            return buildJsonObject {
                put("ok", false)
                put("gated", true)
                put("waiting_for", "login_verification")
                put("reason", "Notification OTP auto-login is disabled on phone")
            }
        }
        val otpId = body["otp_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val appHint = body["app_hint"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val sinceMs = body["since_ms"]?.jsonPrimitive?.longOrNull ?: 0L
        val windowMs = (body["window_ms"]?.jsonPrimitive?.longOrNull ?: 180_000L).coerceIn(1_000L, 600_000L)
        val consume = body["consume"]?.jsonPrimitive?.booleanOrNull ?: true

        val found = screen().consumeOtp(
            windowMs = windowMs,
            sinceMs = sinceMs,
            appHint = appHint,
            otpId = otpId,
            consume = consume,
        ) ?: return buildJsonObject {
            put("ok", false)
            put("available", false)
            put("gated", false)
            put("reason", "No matching unconsumed OTP found")
        }

        return buildJsonObject {
            put("ok", true)
            put("available", true)
            put("gated", false)
            put("otp_id", found.otpId)
            put("code", found.code)
            put("package_name", found.packageName)
            put("sender", found.senderOrTitle)
            put("posted_at_ms", found.postedAtMs)
            put("redacted_preview", found.redactedPreview)
            put("consumed", consume)
        }
    }

    fun syncSession(body: JsonObject): JsonObject {
        val taskId = body["task_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw PhoneControlException("invalid_params", "task_id is required")
        val status = body["status"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
        val step = body["step"]?.jsonPrimitive?.intOrNull ?: lastKnownStep
        val summary = body["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val clearFlags = body["clear_flags"]?.jsonPrimitive?.booleanOrNull ?: false

        ensureSessionTaskId(taskId)
        rememberStep(step)
        if (clearFlags) {
            _controlState.value = OverlayControlState(taskId = taskId)
        }

        val terminal = status in TERMINAL_STATUSES
        val ctrlSnapshot = _controlState.value

        if (status.isNotEmpty()) {
            publishOverlay(
                taskId = taskId,
                status = status,
                step = lastKnownStep,
                summary = summary.ifBlank { status },
                isPaused = status == "paused",
                isWaitingOnPhone = status == "waiting_on_phone",
                isActive = !terminal,
            )
        }

        if (terminal) {
            clearSessionResources()
            onSessionLifecycle(false, true)
        } else {
            onSessionLifecycle(true, false)
        }

        return buildJsonObject {
            put("task_id", taskId)
            put("overlay_control", ctrlSnapshot.toJson())
        }
    }

    // ---- On-phone Floating Overlay Button Handlers (preserve lastKnownStep) ----

    fun togglePauseFromOverlay(): Boolean {
        val cur = _controlState.value
        if (cur.taskId == null) return false
        val nextPaused = !cur.paused
        _controlState.value = cur.copy(paused = nextPaused)
        publishOverlay(
            taskId = cur.taskId,
            status = if (nextPaused) "paused" else "running",
            step = lastKnownStep,
            summary = if (nextPaused) copy.pausedOnOverlay else copy.resumedOnOverlay,
            isPaused = nextPaused,
            isActive = true,
        )
        return true
    }

    fun requestTakeoverFromOverlay(): Boolean {
        val cur = _controlState.value
        if (cur.taskId == null) return false
        onBringTaskToForeground()
        _controlState.value = cur.copy(takeoverRequested = true, resumedFromPhone = false)
        publishOverlay(
            taskId = cur.taskId,
            status = "waiting_on_phone",
            step = lastKnownStep,
            summary = copy.takeoverActive,
            isWaitingOnPhone = true,
            isActive = true,
        )
        return true
    }

    fun resumeFromPhoneOverlay(): Boolean {
        val cur = _controlState.value
        if (cur.taskId == null) return false
        _controlState.value = cur.copy(takeoverRequested = false, paused = false, resumedFromPhone = true)
        publishOverlay(
            taskId = cur.taskId,
            status = "running",
            step = lastKnownStep,
            summary = copy.controlReturned,
            isWaitingOnPhone = false,
            isActive = true,
        )
        return true
    }

    fun emergencyStopFromOverlay(): Boolean {
        val cur = _controlState.value
        if (cur.taskId == null) return false
        val stoppedStep = lastKnownStep
        _controlState.value = cur.copy(stopRequested = true, paused = false, takeoverRequested = false)
        publishOverlay(
            taskId = cur.taskId,
            status = "cancelled",
            step = stoppedStep,
            summary = copy.stoppedOnOverlay,
            isActive = false,
        )
        clearCachesAndVirtualDisplay()
        onSessionLifecycle(false, true)
        return true
    }

    fun pauseForDisconnect() {
        val cur = _controlState.value
        if (cur.taskId == null) return
        _controlState.value = cur.copy(paused = true)
        publishOverlay(
            taskId = cur.taskId,
            status = "paused",
            step = lastKnownStep,
            summary = copy.connectionLost,
            isPaused = true,
            isActive = true,
        )
    }

    fun resetSession() {
        clearSessionResources()
        publishOverlay(taskId = null, status = "idle", step = 0, summary = "", isActive = false)
    }

    private fun clearSessionResources() {
        _controlState.value = OverlayControlState()
        lastKnownStep = 0
        clearCachesAndVirtualDisplay()
    }

    private fun clearCachesAndVirtualDisplay() {
        snapshotsById.clear()
        lastSnapshot = null
        screenRef.get()?.clearCaches()
        screenRef.get()?.releaseVirtualDisplay()
    }

    private fun ensureSessionTaskId(taskId: String) {
        val cur = _controlState.value
        if (cur.taskId != taskId) {
            _controlState.value = OverlayControlState(taskId = taskId)
            lastKnownStep = 0
        }
    }

    private fun rememberStep(step: Int) {
        if (step > 0) {
            lastKnownStep = step
        }
    }

    private fun publishOverlay(
        taskId: String?,
        status: String,
        step: Int,
        summary: String,
        isPaused: Boolean = false,
        isWaitingOnPhone: Boolean = false,
        isActive: Boolean = true,
    ) {
        onOverlayUpdate(
            OverlayTaskSummary(
                taskId = taskId,
                status = status,
                step = step,
                actionSummary = summary,
                isPaused = isPaused,
                isWaitingOnPhone = isWaitingOnPhone,
                isActive = isActive,
                usingVirtualDisplay = virtualDisplayActiveProvider(),
            ),
        )
    }

    private fun actionFailed(msg: String, app: String? = null): JsonObject =
        actionResult(ok = false, app = app, detail = msg, error = msg)

    private fun actionResult(
        ok: Boolean,
        gated: Boolean = false,
        waitingFor: String? = null,
        reason: String? = null,
        app: String? = null,
        detail: String? = null,
        uiChanged: Boolean = false,
        pausedOnPhone: Boolean = false,
        cancelledOnPhone: Boolean = false,
        apps: JsonArray? = null,
        otp: ExtractedOtp? = null,
        error: String? = null,
    ): JsonObject = buildJsonObject {
        put("ok", ok)
        put("gated", gated)
        if (waitingFor != null) put("waiting_for", waitingFor) else put("waiting_for", JsonNull)
        if (reason != null) put("reason", reason) else put("reason", JsonNull)
        if (app != null) put("app", app) else put("app", JsonNull)
        if (detail != null) put("detail", detail) else put("detail", JsonNull)
        put("ui_changed", uiChanged)
        put("paused_on_phone", pausedOnPhone)
        put("cancelled_on_phone", cancelledOnPhone)
        put("apps", apps ?: JsonArray(emptyList()))
        if (otp != null) {
            putJsonObject("otp") {
                put("available", true)
                put("otp_id", otp.otpId)
                put("code", otp.code)
                put("sender", otp.senderOrTitle)
                put("package_name", otp.packageName)
                put("posted_at_ms", otp.postedAtMs)
                put("redacted_preview", otp.redactedPreview)
            }
        } else {
            put("otp", JsonNull)
        }
        error?.let { put("error", it) }
        put("overlay_control", _controlState.value.toJson())
    }

    private companion object {
        val TERMINAL_STATUSES = setOf("done", "failed", "cancelled")
    }
}
