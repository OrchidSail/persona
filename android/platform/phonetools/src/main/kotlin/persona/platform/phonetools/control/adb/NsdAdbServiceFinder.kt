package persona.platform.phonetools.control.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.NetworkInterface

internal class NsdAdbServiceFinder(context: Context) : AdbServiceFinder {
    private val nsd: NsdManager? = context.applicationContext.getSystemService(NsdManager::class.java)

    override suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int> {
        val manager = nsd ?: return emptyList()
        val services = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { val _ = services.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { val _ = services.close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        val started = runCatching {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            true
        }.getOrDefault(false)
        if (!started) return emptyList()

        val ports = LinkedHashSet<Int>()
        try {
            withTimeoutOrNull(timeoutMs) {
                var settleUntil = Long.MAX_VALUE
                while (System.currentTimeMillis() < settleUntil) {
                    val waitMs = (settleUntil - System.currentTimeMillis()).coerceAtMost(timeoutMs).coerceAtLeast(1L)
                    val info = withTimeoutOrNull(waitMs) {
                        services.receiveCatching().getOrNull()
                    } ?: break
                    resolve(manager, info)?.let { port ->
                        ports += port
                        if (settleUntil == Long.MAX_VALUE) settleUntil = System.currentTimeMillis() + SETTLE_MS
                    }
                }
            }
        } finally {
            val _ = runCatching { manager.stopServiceDiscovery(listener) }
        }
        return ports.toList()
    }

    private suspend fun resolve(manager: NsdManager, info: NsdServiceInfo): Int? {
        val done = Channel<Int?>(1)
        @Suppress("DEPRECATION")
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { val _ = done.trySend(null) }
            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val _ = done.trySend(info.port.takeIf { it > 0 && isLocal(info.host) })
            }
        })
        return withTimeoutOrNull(RESOLVE_MS) { done.receive() }
    }

    private fun isLocal(host: InetAddress?): Boolean {
        if (host == null) return false
        if (host.isLoopbackAddress) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .any { nic -> nic.inetAddresses.asSequence().any { it == host } }
        }.getOrDefault(false)
    }

    private companion object {
        const val SETTLE_MS = 400L
        const val RESOLVE_MS = 1_500L
    }
}
