package persona.platform.phonetools.control.shell

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import persona.platform.phonetools.control.adb.AdbConnectPort
import persona.platform.phonetools.control.adb.AdbConnection
import persona.platform.phonetools.control.adb.AdbConnectionException
import persona.platform.phonetools.control.adb.AdbConnectionReason
import persona.platform.phonetools.control.adb.AdbKeyUnreadableException
import persona.platform.phonetools.control.adb.AdbPairing
import persona.platform.phonetools.control.adb.AdbPairingException
import persona.platform.phonetools.control.adb.AdbPairingReason
import persona.platform.phonetools.control.adb.AdbServiceTypes
import persona.platform.phonetools.control.adb.AndroidAdb
import persona.platform.phonetools.control.adb.LocalPortScan
import persona.platform.phonetools.control.adb.NioPortScanner
import persona.platform.phonetools.control.adb.NsdAdbServiceFinder
import persona.platform.phonetools.control.adb.PortCache
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicReference

enum class PrivilegedShellStatus {
    NotConfigured,
    Pairing,
    Connecting,
    Running,
    WirelessDebuggingOff,
    Failed,
}

enum class PrivilegedShellError {
    None,
    UnsupportedSdk,
    InvalidCode,
    PairingWindowNotFound,
    WrongCode,
    NoWifi,
    AuthorizationRevoked,
    KeyUnreadable,
    ConnectFailed,
    HelperStartFailed,
    Unknown,
}

data class PrivilegedShellUiState(
    val status: PrivilegedShellStatus = PrivilegedShellStatus.NotConfigured,
    val backend: String = "none",
    val wirelessPaired: Boolean = false,
    val wirelessDebuggingEnabled: Boolean? = null,
    val pairingDiscoveryActive: Boolean = false,
    val discoveredPairingPort: Int? = null,
    val scanningPorts: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuPermissionGranted: Boolean = false,
    val error: PrivilegedShellError = PrivilegedShellError.None,
) {
    val isReady: Boolean get() = status == PrivilegedShellStatus.Running && backend != "none"
}

/**
 * Coordinates built-in wireless ADB self-pairing + resident uid-2000 helper and optional Shizuku
 * compatibility. Respects State 0 zero-overhead: registers zero observers or mDNS discovery until
 * [setMasterActive] receives `true`.
 */
class PrivilegedShellManager internal constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val hub = PrivilegedShellHub(app)
    private val shizuku = ShizukuShellBridge(app, hub) { scope.launch { onShizukuChanged() } }
    private val finder = NsdAdbServiceFinder(app)
    private val scan = LocalPortScan(
        scanner = NioPortScanner(),
        hosts = ::localHosts,
        range = { LocalPortScan.parseRange(runCatching { File(PORT_RANGE_FILE).readText() }.getOrNull()) },
    )
    private val portCache = object : PortCache {
        override fun load(): Int? = prefs.getInt(KEY_CONNECT_PORT, 0).takeIf { it > 0 }
        override fun save(port: Int) { prefs.edit().putInt(KEY_CONNECT_PORT, port).apply() }
    }

    private val connectLock = Mutex()
    private val manualConnectPort = AtomicReference<Int?>(null)
    private var masterActive = false
    private var watchJob: Job? = null
    private var retryJob: Job? = null
    private var discoveryJob: Job? = null
    private var wirelessObserver: ContentObserver? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var failures = 0

    private val _state = MutableStateFlow(computeInitialState())
    val state: StateFlow<PrivilegedShellUiState> = _state.asStateFlow()

    internal fun currentShell(): IPhoneControlShell? = hub.active.value?.shell

    internal fun currentBackend(): PrivilegedBackendKind = hub.active.value?.backend ?: PrivilegedBackendKind.None

    val isReady: Boolean get() = hub.active.value != null

    val isWirelessPaired: Boolean
        get() = Build.VERSION.SDK_INT >= MIN_SDK && prefs.getBoolean(KEY_PAIRED, false)

    @Synchronized
    fun setMasterActive(active: Boolean) {
        if (masterActive == active) return
        masterActive = active
        if (!active) {
            stopDiscovery()
            retryJob?.cancel()
            retryJob = null
            watchJob?.cancel()
            watchJob = null
            unregisterObservers()
            shizuku.stop()
            val _ = runCatching { hub.active.value?.shell?.releaseAll() }
            refreshState()
            return
        }
        shizuku.start()
        registerObservers()
        watchJob = scope.launch {
            hub.active.collect { activeShell ->
                if (activeShell != null) {
                    failures = 0
                    retryJob?.cancel()
                    retryJob = null
                    refreshState(overrideStatus = PrivilegedShellStatus.Running, error = PrivilegedShellError.None)
                } else if (masterActive) {
                    refreshState()
                    if (shizuku.status.value.ready || isWirelessPaired) {
                        scheduleConnect(if (shizuku.status.value.ready) 0L else GRACE_MS)
                    }
                }
            }
        }
        if (hub.active.value == null && (shizuku.status.value.ready || isWirelessPaired)) {
            scheduleConnect(0L)
        } else {
            refreshState()
        }
    }

    fun refreshStatus() {
        val _ = shizuku.refresh()
        refreshState()
    }

    fun startPairingDiscovery() {
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            refreshState(overrideStatus = PrivilegedShellStatus.Failed, error = PrivilegedShellError.UnsupportedSdk)
            return
        }
        discoveryJob?.cancel()
        _state.value = _state.value.copy(pairingDiscoveryActive = true, discoveredPairingPort = null)
        discoveryJob = scope.launch {
            val port = withContext(Dispatchers.IO) {
                finder.findPorts(AdbServiceTypes.PAIRING, DISCOVERY_TIMEOUT_MS).firstOrNull()
            }
            _state.value = _state.value.copy(
                pairingDiscoveryActive = false,
                discoveredPairingPort = port,
            )
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _state.value = _state.value.copy(pairingDiscoveryActive = false)
    }

    suspend fun pairWithCode(rawCode: String, manualPort: Int? = null): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            refreshState(overrideStatus = PrivilegedShellStatus.Failed, error = PrivilegedShellError.UnsupportedSdk)
            return false
        }
        val code = normalizePairingCode(rawCode)
        if (code == null) {
            refreshState(overrideStatus = PrivilegedShellStatus.Failed, error = PrivilegedShellError.InvalidCode)
            return false
        }
        refreshState(overrideStatus = PrivilegedShellStatus.Pairing, error = PrivilegedShellError.None)
        val port = manualPort?.takeIf { it in 1..65535 }
            ?: _state.value.discoveredPairingPort
            ?: withContext(Dispatchers.IO) { finder.findPorts(AdbServiceTypes.PAIRING, PAIRING_PORT_LOOKUP_MS).firstOrNull() }
        if (port == null) {
            refreshState(overrideStatus = PrivilegedShellStatus.Failed, error = PrivilegedShellError.PairingWindowNotFound)
            return false
        }
        val error = try {
            withContext(Dispatchers.IO) {
                val tls = AndroidAdb.tls(AndroidAdb.pairingIdentity(app))
                val _ = AdbPairing(tls).pair(AdbConnectPort.LOCALHOST, port, code)
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbPairingException) {
            Log.w(TAG, "wireless ADB pairing failed: ${e.reason}", e)
            when (e.reason) {
                AdbPairingReason.WRONG_CODE -> PrivilegedShellError.WrongCode
                AdbPairingReason.CONNECT_FAILED -> PrivilegedShellError.PairingWindowNotFound
                else -> PrivilegedShellError.Unknown
            }
        } catch (e: Exception) {
            Log.w(TAG, "wireless ADB pairing unexpected error", e)
            PrivilegedShellError.Unknown
        }
        if (error != null) {
            refreshState(overrideStatus = PrivilegedShellStatus.Failed, error = error)
            return false
        }
        prefs.edit().putBoolean(KEY_PAIRED, true).apply()
        stopDiscovery()
        failures = 0
        connectNow()
        return hub.active.value != null
    }

    suspend fun connectNow(portOverride: Int? = null) {
        if (portOverride != null && portOverride in 1..65535) {
            manualConnectPort.set(portOverride)
        }
        failures = 0
        retryJob?.cancel()
        retryJob = null
        attemptConnectOnce()
    }

    suspend fun unpair() {
        stopDiscovery()
        retryJob?.cancel()
        retryJob = null
        connectLock.withLock {
            withContext(Dispatchers.IO) {
                shizuku.unbind()
                hub.forget()
                AndroidAdb.forget(app)
                prefs.edit().putBoolean(KEY_PAIRED, false).remove(KEY_CONNECT_PORT).apply()
            }
            refreshState(overrideStatus = PrivilegedShellStatus.NotConfigured, error = PrivilegedShellError.None)
        }
    }

    fun requestShizukuOrOpen(context: Context): Boolean {
        val ok = shizuku.requestPermissionOrOpenApp(context)
        refreshStatus()
        return ok
    }

    fun openWirelessDebuggingSettings(context: Context): Boolean =
        DeveloperSettingsHelper.openWirelessDebuggingSettings(context)

    private fun onShizukuChanged() {
        if (!masterActive) return
        if (hub.active.value == null && shizuku.status.value.ready) {
            scheduleConnect(0L)
        } else {
            refreshState()
        }
    }

    private fun scheduleConnect(delayMs: Long) {
        if (!masterActive) return
        retryJob?.cancel()
        retryJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            attemptConnectOnce()
        }
    }

    private suspend fun attemptConnectOnce() = connectLock.withLock {
        if (!masterActive) return@withLock
        if (hub.active.value != null) {
            refreshState(overrideStatus = PrivilegedShellStatus.Running, error = PrivilegedShellError.None)
            return@withLock
        }
        // Prefer built-in wireless ADB when paired, and also try Shizuku if ready.
        if (isWirelessPaired) {
            if (DeveloperSettingsHelper.isWirelessDebuggingEnabled(app.contentResolver) == false) {
                if (shizuku.status.value.ready && tryBindShizuku()) return@withLock
                refreshState(overrideStatus = PrivilegedShellStatus.WirelessDebuggingOff, error = PrivilegedShellError.None)
                return@withLock
            }
            refreshState(overrideStatus = PrivilegedShellStatus.Connecting, error = PrivilegedShellError.None)
            val err = launchViaWirelessAdb()
            if (err == null) {
                refreshState(overrideStatus = PrivilegedShellStatus.Running, error = PrivilegedShellError.None)
                return@withLock
            }
            if (shizuku.status.value.ready && tryBindShizuku()) return@withLock
            handleFailure(err)
            return@withLock
        }
        if (shizuku.status.value.ready) {
            refreshState(overrideStatus = PrivilegedShellStatus.Connecting, error = PrivilegedShellError.None)
            if (tryBindShizuku()) return@withLock
            handleFailure(PrivilegedShellError.HelperStartFailed)
            return@withLock
        }
        refreshState(overrideStatus = PrivilegedShellStatus.NotConfigured, error = PrivilegedShellError.None)
    }

    private suspend fun tryBindShizuku(): Boolean {
        val bound = withContext(Dispatchers.IO) { shizuku.bindIfReady() }
        if (!bound) return false
        val attached = withTimeoutOrNull(HANDOFF_WAIT_MS) { hub.active.first { it != null } }
        if (attached != null) {
            refreshState(overrideStatus = PrivilegedShellStatus.Running, error = PrivilegedShellError.None)
            return true
        }
        shizuku.unbind()
        return false
    }

    private suspend fun launchViaWirelessAdb(): PrivilegedShellError? = try {
        withContext(Dispatchers.IO) {
            val tls = AndroidAdb.tls(AndroidAdb.identity(app))
            val certPin = ownCertPin() ?: error("missing app signing certificate")
            val command = ShellHandoff.command(app.applicationInfo.sourceDir, app.packageName, Process.myUid(), certPin)
            val connection = AdbConnectPort(
                property = ::tlsPortProperty,
                finder = finder,
                cache = portCache,
                scan = scan,
                manual = { manualConnectPort.getAndSet(null) },
                scanning = { scanning -> _state.value = _state.value.copy(scanningPorts = scanning) },
            ).connect { host, port -> AdbConnection.open(host, port, tls) }

            val before = hub.active.value
            connection.use { conn ->
                val stream = conn.exec(command)
                stream.write(ShellHandoff.stdinLine(hub.issueLaunchNonce()))
                val _ = withTimeoutOrNull(HANDOFF_WAIT_MS) { hub.active.first { it != null && it !== before } }
            }
        }
        if (hub.active.value != null) null else PrivilegedShellError.HelperStartFailed
    } catch (e: CancellationException) {
        throw e
    } catch (e: AdbConnectionException) {
        Log.w(TAG, "wireless ADB connect failed: ${e.reason}", e)
        when (e.reason) {
            AdbConnectionReason.UNAUTHORIZED -> PrivilegedShellError.AuthorizationRevoked
            AdbConnectionReason.CONNECT_FAILED, AdbConnectionReason.TLS_FAILED, AdbConnectionReason.PROTOCOL ->
                if (!wifiConnected()) PrivilegedShellError.NoWifi else PrivilegedShellError.ConnectFailed
            AdbConnectionReason.NOT_LOCAL -> PrivilegedShellError.ConnectFailed
        }
    } catch (e: AdbKeyUnreadableException) {
        Log.w(TAG, "stored ADB key unreadable", e)
        PrivilegedShellError.KeyUnreadable
    } catch (e: Exception) {
        Log.w(TAG, "wireless ADB helper launch failed", e)
        PrivilegedShellError.Unknown
    }

    private fun handleFailure(err: PrivilegedShellError) {
        refreshState(overrideStatus = PrivilegedShellStatus.Failed, error = err)
        val retryable = err == PrivilegedShellError.ConnectFailed ||
            err == PrivilegedShellError.HelperStartFailed ||
            err == PrivilegedShellError.Unknown
        if (retryable && failures < MAX_RETRY_ATTEMPTS) {
            failures++
            val backoffMs = (2_000L shl (failures - 1)).coerceAtMost(30_000L)
            scheduleConnect(backoffMs)
        }
    }

    private fun registerObservers() {
        if (wirelessObserver == null) {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    if (!masterActive || !isWirelessPaired) return
                    val on = DeveloperSettingsHelper.isWirelessDebuggingEnabled(app.contentResolver)
                    if (on == false && hub.active.value == null) {
                        refreshState(overrideStatus = PrivilegedShellStatus.WirelessDebuggingOff, error = PrivilegedShellError.None)
                    } else if (on == true && hub.active.value == null) {
                        failures = 0
                        scheduleConnect(500L)
                    }
                }
            }
            val registered = runCatching {
                app.contentResolver.registerContentObserver(DeveloperSettingsHelper.wirelessDebuggingUri, false, observer)
                true
            }.getOrDefault(false)
            if (registered) wirelessObserver = observer
        }
        if (wifiCallback == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!masterActive || !isWirelessPaired || hub.active.value != null) return
                    val cur = _state.value
                    if (cur.error == PrivilegedShellError.NoWifi || cur.status == PrivilegedShellStatus.Failed) {
                        failures = 0
                        scheduleConnect(500L)
                    }
                }
            }
            val registered = runCatching {
                val cm = app.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
                cm.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    cb,
                )
                true
            }.getOrDefault(false)
            if (registered) wifiCallback = cb
        }
    }

    private fun unregisterObservers() {
        wirelessObserver?.let { obs ->
            val _ = runCatching { app.contentResolver.unregisterContentObserver(obs) }
        }
        wirelessObserver = null
        wifiCallback?.let { cb ->
            val _ = runCatching { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        wifiCallback = null
    }

    private fun computeInitialState(): PrivilegedShellUiState {
        val active = hub.active.value
        val paired = isWirelessPaired
        val wirelessOn = DeveloperSettingsHelper.isWirelessDebuggingEnabled(app.contentResolver)
        val sh = shizuku.status.value
        val status = when {
            active != null -> PrivilegedShellStatus.Running
            paired && wirelessOn == false -> PrivilegedShellStatus.WirelessDebuggingOff
            paired || sh.ready -> PrivilegedShellStatus.Connecting
            else -> PrivilegedShellStatus.NotConfigured
        }
        return PrivilegedShellUiState(
            status = status,
            backend = active?.backend?.wireName ?: PrivilegedBackendKind.None.wireName,
            wirelessPaired = paired,
            wirelessDebuggingEnabled = wirelessOn,
            shizukuInstalled = sh.installed,
            shizukuRunning = sh.running,
            shizukuPermissionGranted = sh.permissionGranted,
        )
    }

    private fun refreshState(
        overrideStatus: PrivilegedShellStatus? = null,
        error: PrivilegedShellError? = null,
    ) {
        val active = hub.active.value
        val paired = isWirelessPaired
        val wirelessOn = DeveloperSettingsHelper.isWirelessDebuggingEnabled(app.contentResolver)
        val sh = shizuku.status.value
        val cur = _state.value
        val nextStatus = overrideStatus ?: when {
            active != null -> PrivilegedShellStatus.Running
            paired && wirelessOn == false -> PrivilegedShellStatus.WirelessDebuggingOff
            !paired && !sh.ready -> PrivilegedShellStatus.NotConfigured
            cur.status == PrivilegedShellStatus.Failed -> PrivilegedShellStatus.Failed
            else -> PrivilegedShellStatus.Connecting
        }
        _state.value = cur.copy(
            status = nextStatus,
            backend = active?.backend?.wireName ?: PrivilegedBackendKind.None.wireName,
            wirelessPaired = paired,
            wirelessDebuggingEnabled = wirelessOn,
            shizukuInstalled = sh.installed,
            shizukuRunning = sh.running,
            shizukuPermissionGranted = sh.permissionGranted,
            error = error ?: if (nextStatus == PrivilegedShellStatus.Running) PrivilegedShellError.None else cur.error,
        )
    }

    private fun wifiConnected(): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return@runCatching true
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(true)

    private fun localHosts(): List<String> = listOf(AdbConnectPort.LOCALHOST) + runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return@runCatching emptyList()
        @Suppress("DEPRECATION")
        cm.allNetworks
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
    }.getOrDefault(emptyList())

    @SuppressLint("PrivateApi")
    private fun tlsPortProperty(): Int? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, TLS_PORT_PROPERTY) as String?
    }.getOrNull()?.trim()?.toIntOrNull()

    private fun ownCertPin(): String? = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.getPackageInfo(
                app.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        val signers = info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
        ShellHandoff.certPin(signers)
    }.getOrNull()

    companion object {
        const val MIN_SDK = Build.VERSION_CODES.S
        private const val TAG = "PersonaPrivShell"
        private const val PREFS = "persona_phone_control_shell"
        private const val KEY_PAIRED = "wireless_paired"
        private const val KEY_CONNECT_PORT = "connect_port"
        private const val TLS_PORT_PROPERTY = "service.adb.tls.port"
        private const val PORT_RANGE_FILE = "/proc/sys/net/ipv4/ip_local_port_range"
        private const val GRACE_MS = 2_500L
        private const val HANDOFF_WAIT_MS = 15_000L
        private const val PAIRING_PORT_LOOKUP_MS = 5_000L
        private const val DISCOVERY_TIMEOUT_MS = 3 * 60_000L
        private const val MAX_RETRY_ATTEMPTS = 4

        fun normalizePairingCode(raw: String): String? {
            val digits = raw.filter { it in '0'..'9' }
            return digits.takeIf { it.length == 6 }
        }
    }
}

internal object PrivilegedShellRegistry {
    @Volatile
    private var instance: PrivilegedShellManager? = null

    fun get(context: Context): PrivilegedShellManager = instance ?: synchronized(this) {
        instance ?: PrivilegedShellManager(context.applicationContext).also { instance = it }
    }
}
