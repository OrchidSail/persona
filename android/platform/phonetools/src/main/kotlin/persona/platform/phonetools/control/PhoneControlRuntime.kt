package persona.platform.phonetools.control

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
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
import kotlinx.coroutines.launch
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
 * Strictly enforces three zero-overhead states:
 * - State 0 (OFF / default): zero foreground service lease, zero VirtualDisplay, zero overlay,
 *   zero wireless-ADB watchdog observers, and O(1) AtomicBoolean short-circuit in Accessibility
 *   and NotificationListener services.
 * - State 1 (ON, idle): advertises `device_relay_register` on `/api/v1/ws` and activates
 *   [privilegedShell] watchdog when configured; still zero foreground lease, zero VirtualDisplay,
 *   and zero overlay.
 * - State 2 (active task): acquires `HubService` keepalive lease, creates privileged background
 *   virtual display on demand, and mounts fixed-size live mini-screen [TaskStatusOverlay].
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
) : AutoCloseable {

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
    private val inFlightJobs = ConcurrentHashMap<String, Job>()

    private var overlay: TaskStatusOverlay? = null
    private var previewFeedJob: Job? = null
    private var terminalReleaseJob: Job? = null
    private var disconnectGraceJob: Job? = null
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
            )
        },
        masterEnabledProvider = ::isEffectiveEnabled,
        safetyPolicyProvider = ::currentSafetyConfig,
        deviceStateProvider = deviceStateProvider,
        onOverlayUpdate = overlayHandlerOverride ?: ::handleOverlayUpdate,
        onSessionLifecycle = ::handleSessionLifecycle,
        copy = overlayCopy,
        virtualDisplayActiveProvider = { virtualDisplay.isActive },
        onBringTaskToForeground = {
            if (virtualDisplay.isActive) {
                val _ = virtualDisplay.bringTopTaskToMainDisplay()
            }
        },
    )

    init {
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
            overlay?.hide()
            overlayActiveState.value = false
            return
        }
        val current = overlay ?: TaskStatusOverlay(
            context = appContext,
            onTogglePause = { val _ = executor.togglePauseFromOverlay() },
            onTakeoverOrResumePhone = {
                if (executor.controlState.value.takeoverRequested || overlay?.lastSummary?.isWaitingOnPhone == true) {
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
        ).also {
            overlay = it
            PhoneControlGateState.activeOverlay = it
            startPreviewFeed(it)
        }
        overlayActiveState.value = summary.isActive
        current.update(summary)
    }

    private fun startPreviewFeed(target: TaskStatusOverlay) {
        previewFeedJob?.cancel()
        previewFeedJob = scope.launch {
            combine(virtualDisplay.displayId, virtualDisplay.previewFrames) { vdId, frame ->
                (vdId != null) to frame
            }.collect { (isVirtual, frame) ->
                target.updatePreviewFrame(frame, usingVirtualDisplay = isVirtual)
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
        terminalReleaseJob?.cancel()
        terminalReleaseJob = null
        cancelDisconnectGraceTimer()
        stopPreviewFeed()
        inFlightJobs.values.forEach { it.cancel(CancellationException("control_disabled")) }
        inFlightJobs.clear()
        executor.resetSession()
        overlay?.hide()
        overlay = null
        PhoneControlGateState.activeOverlay = null
        overlayActiveState.value = false
        virtualDisplay.release()
        releaseKeepAliveLease()
        if (sendUnregister && transport != null) {
            val _ = transport.unregister(CHANNEL)
            lastRegisteredEnabled = false
        }
    }

    override fun close() {
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
        )
    }
}
