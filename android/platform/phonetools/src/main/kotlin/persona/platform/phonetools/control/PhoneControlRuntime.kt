package persona.platform.phonetools.control

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.view.Display
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import persona.core.contracts.api.ConnectionState
import persona.core.contracts.api.DeviceRelayChannelRegistration
import persona.core.contracts.api.DeviceRelayResult
import persona.core.contracts.api.DeviceRelayTransport
import persona.core.contracts.api.HubConnection
import persona.platform.phonetools.PhoneToolSettings
import persona.platform.phonetools.R
import persona.platform.phonetools.control.shell.PrivilegedShellManager
import persona.platform.phonetools.control.shell.PrivilegedShellRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Independent remote phone control state machine mounted on the paired HubScope,
 * consuming the generic `device.relay` channel [CHANNEL] (`phone_control`).
 *
 * Implements Plan C (Hybrid Non-Intrusive Handover):
 * - State 0 (OFF) / State 1 (ON, idle): zero background overhead.
 * - State 2 (active task): starts quietly as a right-edge mini capsule; NEVER steals main-screen
 *   foreground automatically on `waiting_on_phone` / safety gates.
 * - Mode A (default): centered 60fps interactive [TextureView] overlay with streaming touch injection.
 * - Mode B (protected / biometric / user-selected fullscreen): records [MainScreenReturnToken],
 *   moves target app to Display 0, switches overlay to compact 40dp top bar, and safely returns
 *   the app to virtual display + restores user's original app/Home on handback (or degrades cleanly
 *   with `FLAG_KEEP_SCREEN_ON` and restores on terminal completion).
 */
class PhoneControlRuntime(
    context: Context,
    private val deviceId: String,
    private val settings: PhoneToolSettings,
    private val connection: HubConnection,
    private val transport: DeviceRelayTransport?,
    parentScope: CoroutineScope,
    private val automation: Boolean = false,
    private val deviceName: String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android" },
    private val appVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault(""),
    private val onKeepAliveLeaseChanged: (Boolean) -> Unit = {},
    private val permissionsProvider: () -> PhoneControlPermissions = { PhoneControlPermissionsChecker.check(context.applicationContext) },
    screenFactoryOverride: (() -> ScreenPort)? = null,
    deviceStateProviderOverride: DeviceControlStateProvider? = null,
    overlayHandlerOverride: ((OverlayTaskSummary) -> Unit)? = null,
    private val terminalReleaseDelayMs: Long = TaskStatusOverlay.AUTO_DISMISS_DELAY_MS,
    private val disconnectGraceMs: Long = DISCONNECT_GRACE_MS,
    private val disconnectPauseLingerMs: Long = DISCONNECT_PAUSE_LINGER_MS,
) : AutoCloseable, PhoneControlNotificationHandler {

    private val appContext = context.applicationContext
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.Main.immediate)

    val privilegedShell: PrivilegedShellManager = PrivilegedShellRegistry.get(appContext)

    private val resident = MutableStateFlow(false)
    private val permissionRevision = MutableStateFlow(0)
    private val overlayActiveState = MutableStateFlow(false)
    private val keepAliveActive = MutableStateFlow(false)

    private val virtualDisplay = PhoneControlVirtualDisplay(
        context = appContext,
        shellProvider = { privilegedShell.currentShell() },
    )
    private val notifications = PhoneControlNotifications(appContext)
    private val inFlightJobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var androidController: AndroidScreenController? = null

    @Volatile
    private var returnToken: MainScreenReturnToken? = null

    @Volatile
    private var everUsedMainDisplayForTask: Boolean = false

    private var overlay: TaskStatusOverlay? = null
    private var previewFeedJob: Job? = null
    private var terminalReleaseJob: Job? = null
    private var disconnectGraceJob: Job? = null
    private var handbackJob: Job? = null
    private var lastRegisteredEnabled = false

    val isKeepAliveHeld: StateFlow<Boolean> = keepAliveActive.asStateFlow()

    val isOverlayActive: Boolean
        get() = overlay?.isShowing == true || overlayActiveState.value

    val isVirtualDisplayActive: Boolean
        get() = virtualDisplay.isActive

    private val overlayCopy = OverlayCopy(
        readingScreen = appContext.getString(R.string.phonetools_overlay_reading),
        pausedOnOverlay = appContext.getString(R.string.phonetools_overlay_paused_msg),
        resumedOnOverlay = appContext.getString(R.string.phonetools_overlay_resumed_msg),
        takeoverActive = appContext.getString(R.string.phonetools_overlay_takeover_msg),
        controlReturned = appContext.getString(R.string.phonetools_overlay_returned_msg),
        stoppedOnOverlay = appContext.getString(R.string.phonetools_overlay_stopped_msg),
        connectionLost = appContext.getString(R.string.phonetools_overlay_disconnected_msg),
        returningToBackground = appContext.getString(R.string.phonetools_overlay_returning_msg),
        attentionNeeded = appContext.getString(R.string.phonetools_overlay_attention_msg),
        degradedForegroundHint = appContext.getString(R.string.phonetools_overlay_fullscreen_degraded_hint),
    )

    private val deviceStateProvider: DeviceControlStateProvider = deviceStateProviderOverride ?: object : DeviceControlStateProvider {
        override fun snapshot(): DeviceControlSnapshot {
            val pm = appContext.getSystemService(PowerManager::class.java)
            val km = appContext.getSystemService(KeyguardManager::class.java)
            val um = appContext.getSystemService(UserManager::class.java)
            val perms = permissionsProvider()
            val shellUi = privilegedShell.state.value
            return DeviceControlSnapshot(
                masterEnabled = isEffectiveEnabled(),
                interactive = pm?.isInteractive ?: true,
                keyguardShowing = km?.isKeyguardLocked ?: false,
                deviceLocked = km?.isDeviceLocked ?: false,
                secure = km?.isDeviceSecure ?: false,
                helperAvailable = PhoneControlAccessibilityService.instance.value != null,
                userUnlocked = um?.isUserUnlocked ?: true,
                virtualDisplayActive = virtualDisplay.isActive,
                overlayActive = isOverlayActive,
                residentConnection = resident.value,
                permissions = perms,
                safetyPolicy = currentSafetyConfig(),
                virtualDisplayBackend = shellUi.backend,
                virtualDisplayReady = shellUi.isReady,
                wirelessAdbPaired = shellUi.wirelessPaired,
                shizukuReady = shellUi.shizukuRunning && shellUi.shizukuPermissionGranted,
            )
        }
    }

    val executor: PhoneControlExecutor = PhoneControlExecutor(
        screenFactory = screenFactoryOverride ?: {
            AndroidScreenController(
                context = appContext,
                virtualDisplay = virtualDisplay,
                notifications = PhoneControlGateState.notificationBuffer,
                shellProvider = { privilegedShell.currentShell() },
            ).also { created ->
                androidController = created
                overlay?.let { target ->
                    if (previewFeedJob?.isActive == true) {
                        startPreviewFeed(target)
                    }
                }
            }
        },
        masterEnabledProvider = ::isEffectiveEnabled,
        safetyPolicyProvider = ::currentSafetyConfig,
        deviceStateProvider = deviceStateProvider,
        onOverlayUpdate = overlayHandlerOverride ?: ::handleOverlayUpdate,
        onSessionLifecycle = ::handleSessionLifecycle,
        copy = overlayCopy,
        virtualDisplayActiveProvider = { virtualDisplay.isActive },
        protectedFrameProvider = { virtualDisplay.previewFrameProtected.value },
        onAttentionRequested = ::handleAttentionRequested,
        onAttentionCleared = { notifications.cancelAttentionBanner() },
        onEnterFullscreenHandover = ::enterFullscreenHandover,
        onHandbackControl = ::performHandbackControl,
        onTerminalCleanup = ::performTerminalCleanup,
        appLabelResolver = ::resolveAppLabel,
    )

    init {
        PhoneControlNotificationBridge.register(this)
        PhoneControlGateState.update(
            master = isEffectiveEnabled(),
            otp = settings.allowNotificationOtpAutoLogin.value,
        )

        scope.launch {
            combine(settings.remoteControlEnabled, settings.allowNotificationOtpAutoLogin) { master, otp ->
                (!automation && master) to otp
            }.distinctUntilChanged().collect { (effectiveMaster, otp) ->
                PhoneControlGateState.update(effectiveMaster, otp)
                privilegedShell.setMasterActive(effectiveMaster)
                if (!effectiveMaster) {
                    tearDownActiveSessionAndCalls(sendUnregister = lastRegisteredEnabled)
                }
            }
        }

        scope.launch {
            PhoneControlAccessibilityService.instance.collect { service ->
                overlay?.onAccessibilityServiceChanged(service)
            }
        }

        if (transport != null && !automation) {
            scope.launch {
                connection.state.collectLatest { state ->
                    transport.discardPending(CHANNEL)
                    if (state != ConnectionState.Online) {
                        if (executor.hasActiveSession) {
                            startDisconnectGraceTimer()
                        }
                        return@collectLatest
                    }
                    coroutineScope {
                        launch {
                            combine(
                                listOf(
                                    settings.remoteControlEnabled,
                                    settings.allowNotificationOtpAutoLogin,
                                    settings.blockRealPayment,
                                    resident,
                                    permissionRevision,
                                    overlayActiveState,
                                    virtualDisplay.displayId,
                                    privilegedShell.state,
                                    PhoneControlAccessibilityService.instance,
                                    PhoneControlNotificationListener.isConnected,
                                ),
                            ) { _ -> buildRegistrationOrNull() }
                                .distinctUntilChanged()
                                .collect { reg ->
                                    if (reg != null) {
                                        val _ = transport.register(reg)
                                        lastRegisteredEnabled = true
                                    } else if (lastRegisteredEnabled) {
                                        val _ = transport.unregister(CHANNEL)
                                        lastRegisteredEnabled = false
                                    }
                                }
                        }
                        launch {
                            transport.cancelsFor(CHANNEL).collect { cancel ->
                                inFlightJobs.remove(cancel.callId)?.cancel(CancellationException(cancel.reason))
                            }
                        }
                        transport.callsFor(CHANNEL).collect { relayCall ->
                            cancelDisconnectGraceTimer()
                            val call = PhoneControlCall.fromRelayPayload(
                                callId = relayCall.callId,
                                deadlineMs = relayCall.deadlineMs,
                                payload = relayCall.payload,
                            )
                            if (call == null) {
                                val _ = transport.result(
                                    CHANNEL,
                                    relayCall.callId,
                                    DeviceRelayResult.error("invalid_params", "op must be non-empty and args must be a JSON object"),
                                )
                                return@collect
                            }
                            val callJob = scope.launch {
                                try {
                                    val result = try {
                                        withTimeout(call.deadlineMs) {
                                            executor.execute(call)
                                        }
                                    } catch (_: TimeoutCancellationException) {
                                        PhoneControlResult.error("timeout", "Phone control call timed out")
                                    } catch (cancelled: CancellationException) {
                                        if (job.isActive) {
                                            val _ = transport.result(
                                                CHANNEL,
                                                call.callId,
                                                DeviceRelayResult.error("cancelled", "Phone control call cancelled"),
                                            )
                                        }
                                        throw cancelled
                                    }
                                    val _ = transport.result(CHANNEL, call.callId, result.toRelayResult())
                                } finally {
                                    val _ = inFlightJobs.remove(call.callId)
                                }
                            }
                            val _ = inFlightJobs.put(call.callId, callJob)
                        }
                    }
                }
            }
        }
    }

    fun isEffectiveEnabled(): Boolean = !automation && settings.remoteControlEnabled.value

    fun currentSafetyConfig(): SafetyGateConfig = SafetyGateConfig(
        allowNotificationOtpAutoLogin = settings.allowNotificationOtpAutoLogin.value,
        blockRealPayment = settings.blockRealPayment.value,
    )

    fun currentSnapshot(): DeviceControlSnapshot = deviceStateProvider.snapshot()

    fun setResidentConnection(value: Boolean) {
        resident.value = value
    }

    fun refreshPermissions() {
        permissionRevision.value += 1
        if (isEffectiveEnabled()) {
            privilegedShell.refreshStatus()
        }
    }

    override fun onNotificationOpenInline(taskId: String) {
        scope.launch {
            if (taskId.isBlank() || executor.activeTaskId == taskId) {
                val _ = executor.beginInlineInteractionFromUser()
            }
        }
    }

    override fun onNotificationOpenFullscreen(taskId: String) {
        scope.launch {
            if (taskId.isBlank() || executor.activeTaskId == taskId) {
                val _ = executor.beginFullscreenInteractionFromUser()
            }
        }
    }

    override fun onNotificationCancelTask(taskId: String) {
        scope.launch {
            if (taskId.isBlank() || executor.activeTaskId == taskId) {
                val _ = executor.emergencyStopFromOverlay()
            }
        }
    }

    private fun handleAttentionRequested(request: HandoverRequest) {
        val canInline = virtualDisplay.isActive && !virtualDisplay.previewFrameProtected.value
        val appLabel = resolveAppLabel(request.appPackage)
        val _ = notifications.showAttentionBanner(request, appLabel = appLabel, canInline = canInline)
    }

    private fun enterFullscreenHandover() {
        val _ = virtualDisplay.detachInteractiveSurface()
        val captured = virtualDisplay.captureMainForegroundTask()
        var movedId = -1
        var movedPkg: String? = null
        if (virtualDisplay.isActive) {
            val moved = virtualDisplay.moveTopTaskToMainDisplay()
            if (moved != null) {
                movedId = moved.first
                movedPkg = moved.second
            }
        }
        returnToken = captured.copy(
            movedTaskId = movedId,
            movedPackage = movedPkg ?: executor.currentTargetPackage,
        )
        everUsedMainDisplayForTask = true
        androidController?.syncActiveDisplay(Display.DEFAULT_DISPLAY)
    }

    private fun performHandbackControl(
        prevPhase: HandoverPhase,
        onComplete: (degradedToMainDisplay: Boolean) -> Unit,
    ) {
        handbackJob?.cancel()
        if (prevPhase != HandoverPhase.FULLSCREEN) {
            val _ = virtualDisplay.detachInteractiveSurface()
            onComplete(false)
            return
        }
        val token = returnToken
        handbackJob = scope.launch {
            val degraded = withContext(Dispatchers.IO) {
                val vdId = virtualDisplay.displayId.value
                var movedBack = false
                if (vdId != null && token != null && token.movedTaskId > 0) {
                    movedBack = virtualDisplay.moveTaskBackToVirtualDisplay(token.movedTaskId)
                }
                if (movedBack && token != null) {
                    val _ = virtualDisplay.restoreMainTask(token)
                }
                if (movedBack && vdId != null) {
                    val a11y = PhoneControlAccessibilityService.instance.value
                    var stabilized = false
                    repeat(HANDBACK_SETTLE_POLLS) {
                        delay(HANDBACK_SETTLE_INTERVAL_MS)
                        val hasVdPkg = virtualDisplay.topTaskPackage() != null
                        val hasVdWin = a11y?.let {
                            ScreenTreeCollector.hasRenderableContentWindows(it.windowsOnDisplay(vdId), appContext.packageName)
                        } ?: hasVdPkg
                        if (hasVdPkg && hasVdWin) {
                            stabilized = true
                            return@repeat
                        }
                    }
                    if (stabilized) {
                        androidController?.syncActiveDisplay(vdId)
                        false
                    } else {
                        androidController?.syncActiveDisplay(Display.DEFAULT_DISPLAY)
                        true
                    }
                } else {
                    androidController?.syncActiveDisplay(Display.DEFAULT_DISPLAY)
                    true
                }
            }
            if (!degraded) {
                returnToken = null
                everUsedMainDisplayForTask = false
            }
            onComplete(degraded)
        }
    }

    private fun performTerminalCleanup(status: String, summary: String, targetPackage: String?) {
        handbackJob?.cancel()
        handbackJob = null
        val _ = virtualDisplay.detachInteractiveSurface()
        notifications.cancelAttentionBanner()

        val token = returnToken
        val shouldRestoreMain = everUsedMainDisplayForTask || androidController?.activeOnVirtualDisplay?.value == false
        val pkgToCheck = targetPackage ?: token?.movedPackage
        if (shouldRestoreMain && !pkgToCheck.isNullOrBlank()) {
            scope.launch(Dispatchers.IO) {
                val currentMainTop = virtualDisplay.topMainTaskPackage()
                if (currentMainTop == pkgToCheck) {
                    val _ = virtualDisplay.restoreMainTask(token ?: MainScreenReturnToken(wasHome = true))
                }
            }
        }
        returnToken = null
        everUsedMainDisplayForTask = false

        val isTimeout = summary.contains("超时") || summary.contains("timeout", ignoreCase = true) // i18n-ignore: matches timeout status summary
        val _ = notifications.showResultNotification(status = status, summary = summary, isTimeout = isTimeout)
    }

    private fun resolveAppLabel(packageName: String?): String? {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val pm = appContext.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai)?.toString()?.trim()?.ifEmpty { null }
        }.getOrNull() ?: pkg.substringAfterLast('.')
    }

    private fun buildRegistrationOrNull(): DeviceRelayChannelRegistration? {
        val snap = deviceStateProvider.snapshot()
        if (!snap.masterEnabled) return null
        return DeviceRelayChannelRegistration(
            channel = CHANNEL,
            protocolVersion = 1,
            deviceId = deviceId,
            deviceName = deviceName,
            platform = "android",
            appVersion = appVersion,
            meta = buildPhoneControlRelayMeta(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                androidSdk = Build.VERSION.SDK_INT,
                ready = snap.ready,
                capabilities = CAPABILITIES,
                permissions = snap.permissions,
                safetyPolicy = snap.safetyPolicy.toContract(),
                runtime = snap.toRuntimeInfo(),
            ),
        )
    }

    private fun handleOverlayUpdate(summary: OverlayTaskSummary) {
        if (!isEffectiveEnabled() || (!summary.isActive && summary.status == "idle")) {
            stopPreviewFeed()
            val _ = virtualDisplay.detachInteractiveSurface()
            overlay?.hide()
            overlayActiveState.value = false
            return
        }
        val current = overlay ?: TaskStatusOverlay(
            context = appContext,
            onTogglePause = { val _ = executor.togglePauseFromOverlay() },
            onTakeoverOrResumePhone = {
                val ctrl = executor.controlState.value
                if (ctrl.takeoverRequested || overlay?.lastSummary?.isWaitingOnPhone == true) {
                    val _ = executor.resumeFromPhoneOverlay()
                } else {
                    val _ = executor.requestTakeoverFromOverlay()
                }
            },
            onEmergencyStop = { val _ = executor.emergencyStopFromOverlay() },
            onAutoDismissed = {
                stopPreviewFeed()
                overlayActiveState.value = false
                releaseKeepAliveLease()
            },
            onBringToForeground = {
                val _ = executor.beginFullscreenInteractionFromUser()
            },
            onOpenHandover = {
                val _ = executor.openHandoverFromUser()
            },
            onSwitchToFullscreen = {
                val _ = executor.beginFullscreenInteractionFromUser()
            },
            onCompleteHandback = {
                val _ = executor.resumeFromPhoneOverlay()
            },
            onAttachInteractiveSurface = { surface ->
                virtualDisplay.attachInteractiveSurface(surface)
            },
            onDetachInteractiveSurface = {
                val _ = virtualDisplay.detachInteractiveSurface()
            },
            onInjectInteractiveMotion = { action, downTime, eventTime, x, y, pressure ->
                virtualDisplay.injectInteractiveMotionEvent(action, downTime, eventTime, x, y, pressure)
            },
            virtualDisplaySizeProvider = {
                virtualDisplay.currentWidth to virtualDisplay.currentHeight
            },
        ).also {
            overlay = it
            PhoneControlGateState.activeOverlay = it
        }
        if (summary.isActive && previewFeedJob?.isActive != true) {
            startPreviewFeed(current)
        }
        overlayActiveState.value = summary.isActive
        current.update(summary)
    }

    private fun startPreviewFeed(target: TaskStatusOverlay) {
        previewFeedJob?.cancel()
        val activeVdFlow: StateFlow<Boolean>? = androidController?.activeOnVirtualDisplay
        previewFeedJob = scope.launch {
            val isActuallyVirtualFlow = activeVdFlow ?: virtualDisplay.displayId.map { it != null }
            combine(
                isActuallyVirtualFlow,
                virtualDisplay.previewFrames,
                virtualDisplay.previewFrameProtected,
            ) { isVirtual, frame, isProtected ->
                Triple(isVirtual, frame, isProtected)
            }.collect { (isVirtual, frame, isProtected) ->
                target.updatePreviewFrame(frame, usingVirtualDisplay = isVirtual, protectedFrame = isProtected)
            }
        }
    }

    private fun stopPreviewFeed() {
        previewFeedJob?.cancel()
        previewFeedJob = null
    }

    private fun handleSessionLifecycle(active: Boolean, terminal: Boolean) {
        cancelDisconnectGraceTimer()
        if (active) {
            terminalReleaseJob?.cancel()
            terminalReleaseJob = null
            acquireKeepAliveLease()
            return
        }
        if (terminal) {
            terminalReleaseJob?.cancel()
            terminalReleaseJob = scope.launch {
                delay(terminalReleaseDelayMs)
                if (!executor.hasActiveSession) {
                    stopPreviewFeed()
                    val _ = virtualDisplay.detachInteractiveSurface()
                    overlay?.hide()
                    overlayActiveState.value = false
                    virtualDisplay.release()
                    releaseKeepAliveLease()
                }
            }
        }
    }

    private fun startDisconnectGraceTimer() {
        if (disconnectGraceJob != null) return
        disconnectGraceJob = scope.launch {
            delay(disconnectGraceMs)
            if (connection.state.value == ConnectionState.Online || !executor.hasActiveSession) {
                disconnectGraceJob = null
                return@launch
            }
            executor.pauseForDisconnect()
            delay(disconnectPauseLingerMs)
            if (connection.state.value != ConnectionState.Online) {
                executor.resetSession()
                stopPreviewFeed()
                overlay?.hide()
                overlayActiveState.value = false
                virtualDisplay.release()
                releaseKeepAliveLease()
            }
            disconnectGraceJob = null
        }
    }

    private fun cancelDisconnectGraceTimer() {
        disconnectGraceJob?.cancel()
        disconnectGraceJob = null
    }

    private fun acquireKeepAliveLease() {
        if (!keepAliveActive.value) {
            keepAliveActive.value = true
            onKeepAliveLeaseChanged(true)
        }
    }

    private fun releaseKeepAliveLease() {
        if (keepAliveActive.value) {
            keepAliveActive.value = false
            onKeepAliveLeaseChanged(false)
        }
    }

    private fun tearDownActiveSessionAndCalls(sendUnregister: Boolean) {
        handbackJob?.cancel()
        handbackJob = null
        terminalReleaseJob?.cancel()
        terminalReleaseJob = null
        cancelDisconnectGraceTimer()
        stopPreviewFeed()
        notifications.cancelAttentionBanner()
        inFlightJobs.values.forEach { it.cancel(CancellationException("control_disabled")) }
        inFlightJobs.clear()
        executor.resetSession()
        val _ = virtualDisplay.detachInteractiveSurface()
        overlay?.hide()
        overlay = null
        PhoneControlGateState.activeOverlay = null
        overlayActiveState.value = false
        virtualDisplay.release()
        returnToken = null
        everUsedMainDisplayForTask = false
        releaseKeepAliveLease()
        if (sendUnregister && transport != null) {
            val _ = transport.unregister(CHANNEL)
            lastRegisteredEnabled = false
        }
    }

    override fun close() {
        PhoneControlNotificationBridge.unregister(this)
        tearDownActiveSessionAndCalls(sendUnregister = lastRegisteredEnabled)
        privilegedShell.setMasterActive(false)
        PhoneControlGateState.resetForShutdown()
        job.cancel()
        transport?.discardPending(CHANNEL)
    }

    companion object {
        const val CHANNEL = "phone_control"
        const val DISCONNECT_GRACE_MS = 30_000L
        const val DISCONNECT_PAUSE_LINGER_MS = 10_000L
        private const val HANDBACK_SETTLE_POLLS = 6
        private const val HANDBACK_SETTLE_INTERVAL_MS = 200L
        val CAPABILITIES: List<String> = listOf(
            "device_state",
            "observe",
            "screenshot_a11y_jpeg",
            "action",
            "session",
            "otp_consume",
            "fill_verification_code",
            "virtual_display_shell",
            "mini_screen_overlay",
            "non_intrusive_handover_v2",
            "inline_vd_touch_v1",
        )
    }
}
