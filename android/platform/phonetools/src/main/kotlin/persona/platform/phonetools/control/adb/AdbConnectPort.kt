package persona.platform.phonetools.control.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal interface PortCache {
    fun load(): Int?
    fun save(port: Int)
}

internal class AdbConnectPort(
    private val property: () -> Int?,
    private val finder: AdbServiceFinder,
    private val cache: PortCache,
    private val mdnsTimeoutMs: Long = 3_000,
    private val scan: LocalPortScan? = null,
    private val manual: () -> Int? = { null },
    private val scanning: (Boolean) -> Unit = {},
    private val localHost: String = LOCALHOST,
    private val tlsRetryDelayMs: Long = 300,
) {
    suspend fun <T> connect(open: (host: String, port: Int) -> T): T {
        val tried = HashSet<AdbEndpoint>()
        val triedPorts = HashSet<Int>()
        var last: AdbConnectionException? = null
        var rejected: AdbConnectionException? = null
        val manualPort = manual()?.takeIf { it in 1..65535 }
        fun local(vararg ports: Int?) = ports.filterNotNull().map { AdbEndpoint(localHost, it) }
        val sources: List<suspend () -> List<AdbEndpoint>> = listOf(
            { local(manualPort) },
            { local(cache.load()) },
            { local(property()) },
            { finder.findPorts(AdbServiceTypes.CONNECT, mdnsTimeoutMs).map { AdbEndpoint(localHost, it) } },
            {
                if (scan == null || manualPort != null || rejected != null) {
                    emptyList()
                } else {
                    scanning(true)
                    try {
                        scan.scan(triedPorts).endpoints
                    } finally {
                        scanning(false)
                    }
                }
            },
        )
        for (source in sources) {
            val endpoints = try {
                source()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            for (ep in endpoints) {
                if (ep.port !in 1..65535 || !tried.add(ep)) continue
                triedPorts += ep.port
                var retried = false
                while (true) {
                    try {
                        return open(ep.host, ep.port).also { cache.save(ep.port) }
                    } catch (e: AdbConnectionException) {
                        if (e.reason == AdbConnectionReason.NOT_LOCAL) throw e
                        val tls = e.reason == AdbConnectionReason.UNAUTHORIZED ||
                            e.reason == AdbConnectionReason.TLS_FAILED
                        if (tls && !retried) {
                            retried = true
                            delay(tlsRetryDelayMs)
                            continue
                        }
                        if (e.reason == AdbConnectionReason.UNAUTHORIZED) rejected = e
                        last = e
                        break
                    }
                }
            }
        }
        throw rejected ?: last ?: AdbConnectionException(AdbConnectionReason.CONNECT_FAILED, "no wireless debugging port found")
    }

    companion object {
        const val LOCALHOST = "127.0.0.1"
    }
}
