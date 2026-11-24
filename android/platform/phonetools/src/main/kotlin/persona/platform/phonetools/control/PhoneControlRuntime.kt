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
import persona.core.contracts.api.HubConnection
import persona.core.contracts.api.PhoneControlPermissions
import persona.core.contracts.api.PhoneControlRegistration
import persona.core.contracts.api.PhoneControlResult
import persona.core.contracts.api.PhoneControlTransport
import persona.platform.phonetools.PhoneToolSettings
import persona.platform.phonetools.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Independent remote phone control state machine mounted on the paired HubScope.
 *
 * Strictly enforces three zero-overhead states:
 * - State 0 (OFF / default): zero foreground service lease, zero VirtualDisplay, zero overlay,
 *   O(1) AtomicBoolean short-circuit in Accessibility and NotificationListener services.
 * - State 1 (ON, idle): advertises `register_phone_control` on `/api/v1/ws`; still zero foreground
 *   lease, zero VirtualDisplay, and zero overlay.
 * - State 2 (active task): acquires `HubService` keepalive lease and lazily mounts [TaskStatusOverlay].
 *   Terminal status releases lease and destroys overlay after 4s; prolonged disconnect (>30s) pauses
 *   the overlay and cleans up all resources 10s later.
 */
class PhoneControlRuntime(
    context: Context,
    private val deviceId: String,
    private val settings: PhoneToolSettings,
    private val connection: HubConnection,
    private val transport: PhoneControlTransport?,
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

    private val resident = MutableStateFlow(false)
    private val permissionRevision = MutableStateFlow(0)
    private val overlayActiveState = MutableStateFlow(false)
    private val keepAliveActive = MutableStateFlow(false)

    private val virtualDisplay = PhoneControlVirtualDisplay(appContext)
    private val inFlightJobs = ConcurrentHashMap<String, Job>()

    private var overlay: TaskStatusOverlay? = null
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
            )
        }
    }

    val executor: PhoneControlExecutor = PhoneControlExecutor(
        screenFactory = screenFactoryOverride ?: {
            AndroidScreenController(appContext, virtualDisplay, PhoneControlGateState.notificationBuffer)
        },
        masterEnabledProvider = ::isEffectiveEnabled,
        safetyPolicyProvider = ::currentSafetyConfig,
        deviceStateProvider = deviceStateProvider,
        onOverlayUpdate = overlayHandlerOverride ?: ::handleOverlayUpdate,
        onSessionLifecycle = ::handleSessionLifecycle,
        copy = overlayCopy,
    )

    init {
        PhoneControlGateState.update(
            master = isEffectiveEnabled(),
            otp = settings.allowNotificationOtpAutoLogin.value,
        )

        // Sync process-wide O(1) gate state and clean up immediately whenever master switch turns OFF.
        scope.launch {
            combine(settings.remoteControlEnabled, settings.allowNotificationOtpAutoLogin) { master, otp ->
                (!automation && master) to otp
            }.distinctUntilChanged().collect { (effectiveMaster, otp) ->
                PhoneControlGateState.update(effectiveMaster, otp)
                if (!effectiveMaster) {
                    tearDownActiveSessionAndCalls(sendDisabledFrame = lastRegisteredEnabled)
                }
            }
        }

        if (transport != null && !automation) {
            scope.launch {
                connection.state.collectLatest { state ->
                    transport.discardPending()
                    if (state != ConnectionState.Online) {
                        if (executor.hasActiveSession) {
                            startDisconnectGraceTimer()
                        }
                        return@collectLatest
                    }
                    // Reconnected: cancel grace timer if active session survived the brief drop, and re-register.
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
                                    PhoneControlAccessibilityService.instance,
                                    PhoneControlNotificationListener.isConnected,
                                ),
                            ) { _ -> buildRegistration() }
                                .distinctUntilChanged()
                                .collect { reg ->
                                    if (reg.enabled) {
                                        val _ = transport.register(reg)
                                        lastRegisteredEnabled = true
                                    } else if (lastRegisteredEnabled) {
                                        val _ = transport.register(reg)
                                        lastRegisteredEnabled = false
                                    }
                                }
                        }
                        launch {
                            transport.cancels.collect { cancel ->
                                inFlightJobs.remove(cancel.callId)?.cancel(CancellationException(cancel.reason))
                            }
                        }
                        transport.calls.collect { call ->
                            cancelDisconnectGraceTimer()
                            val callJob = scope.launch {
                                try {
                                    val result = try {
                                        withTimeout(call.deadlineMs) {
                                            executor.execute(call)
                                        }
                                    } catch (_: TimeoutCancellationException) {
                                        PhoneControlResult.error("timeout", "Phone control call timed out")
                                    } catch (cancelled: CancellationException) {
                                        // Only emit cancelled result if this call was cancelled while runtime is still live.
                                        if (job.isActive) {
                                            val _ = transport.result(call.callId, PhoneControlResult.error("cancelled", "Phone control call cancelled"))
                                        }
                                        throw cancelled
                                    }
                                    val _ = transport.result(call.callId, result)
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
    }

    private fun buildRegistration(): PhoneControlRegistration {
        val snap = deviceStateProvider.snapshot()
        return PhoneControlRegistration(
            protocolVersion = 1,
            deviceId = deviceId,
            deviceName = deviceName,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            platform = "android",
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            androidSdk = Build.VERSION.SDK_INT,
            appVersion = appVersion,
            enabled = snap.masterEnabled,
            ready = snap.ready,
            capabilities = if (snap.masterEnabled) CAPABILITIES else emptyList(),
            permissions = snap.permissions,
            safetyPolicy = snap.safetyPolicy.toContract(),
            runtime = snap.toRuntimeInfo(),
        )
    }

    private fun handleOverlayUpdate(summary: OverlayTaskSummary) {
        if (!isEffectiveEnabled() || (!summary.isActive && summary.status == "idle")) {
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
                overlayActiveState.value = false
                releaseKeepAliveLease()
            },
        ).also {
            overlay = it
            PhoneControlGateState.activeOverlay = it
        }
        overlayActiveState.value = summary.isActive
        current.update(summary)
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

    private fun tearDownActiveSessionAndCalls(sendDisabledFrame: Boolean) {
        terminalReleaseJob?.cancel()
        terminalReleaseJob = null
        cancelDisconnectGraceTimer()
        inFlightJobs.values.forEach { it.cancel(CancellationException("control_disabled")) }
        inFlightJobs.clear()
        executor.resetSession()
        overlay?.hide()
        overlay = null
        PhoneControlGateState.activeOverlay = null
        overlayActiveState.value = false
        virtualDisplay.release()
        releaseKeepAliveLease()
        if (sendDisabledFrame && transport != null) {
            val _ = transport.register(buildRegistration().copy(enabled = false, ready = false, capabilities = emptyList()))
            lastRegisteredEnabled = false
        }
    }

    override fun close() {
        tearDownActiveSessionAndCalls(sendDisabledFrame = lastRegisteredEnabled)
        PhoneControlGateState.resetForShutdown()
        job.cancel()
        transport?.discardPending()
    }

    companion object {
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
        )
    }
}
