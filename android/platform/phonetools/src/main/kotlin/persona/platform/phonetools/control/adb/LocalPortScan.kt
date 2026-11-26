package persona.platform.phonetools.control.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.TreeSet

internal data class AdbEndpoint(val host: String, val port: Int)

internal interface LocalPortScanner {
    suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int>
    fun speaksAdbTls(host: String, port: Int, timeoutMs: Int): Boolean
}

internal data class ScanResult(val endpoints: List<AdbEndpoint>, val open: Int, val adb: Int)

internal class LocalPortScan(
    private val scanner: LocalPortScanner,
    private val hosts: () -> List<String>,
    private val range: () -> IntRange = { FALLBACK_RANGE },
    private val budgetMs: Long = 10_000,
    private val probeTimeoutMs: Int = 1_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun scan(skip: Set<Int>): ScanResult {
        val deadline = now() + budgetMs
        val ports = range()
        val found = ArrayList<AdbEndpoint>()
        var open = 0
        for (host in hosts().distinct()) {
            val left = deadline - now()
            if (left <= 0) break
            val openPorts = scanner.openPorts(host, ports, skip, left)
            open += openPorts.size
            for (port in openPorts) {
                if (now() >= deadline) break
                if (scanner.speaksAdbTls(host, port, probeTimeoutMs)) found += AdbEndpoint(host, port)
            }
            if (found.isNotEmpty()) break
        }
        return ScanResult(found, open, found.size)
    }

    companion object {
        val FALLBACK_RANGE = 30_000..65_535

        fun parseRange(text: String?): IntRange {
            val nums = text?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() } ?: return FALLBACK_RANGE
            if (nums.size != 2) return FALLBACK_RANGE
            val (lo, hi) = nums
            return if (lo in 1024..65_535 && hi in lo..65_535) lo..hi else FALLBACK_RANGE
        }
    }
}

internal class NioPortScanner(
    private val concurrency: Int = 200,
    private val connectTimeoutMs: Long = 150,
) : LocalPortScanner {
    override suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int> = withContext(Dispatchers.IO) {
        val address = AdbHosts.ownAddress(host) ?: return@withContext emptyList()
        val ctx = currentCoroutineContext()
        sweep(address, ports, skip, System.currentTimeMillis() + budgetMs) { ctx.isActive }
    }

    private fun sweep(addr: InetAddress, ports: IntRange, skip: Set<Int>, deadline: Long, active: () -> Boolean): List<Int> {
        val open = TreeSet<Int>()
        val next = ports.iterator()
        Selector.open().use { selector ->
            val startedAt = HashMap<SelectionKey, Long>()
            try {
                while ((next.hasNext() || startedAt.isNotEmpty()) && System.currentTimeMillis() < deadline && active()) {
                    while (next.hasNext() && startedAt.size < concurrency) {
                        val port = next.nextInt()
                        if (port in skip) continue
                        val ch = SocketChannel.open()
                        try {
                            val _ = ch.configureBlocking(false)
                            if (ch.connect(InetSocketAddress(addr, port))) {
                                if (!selfConnected(ch, port)) open += port
                                ch.close()
                            } else {
                                startedAt[ch.register(selector, SelectionKey.OP_CONNECT, port)] = System.currentTimeMillis()
                            }
                        } catch (_: IOException) {
                            ch.close()
                        }
                    }
                    val _ = selector.select(SELECT_MS)
                    for (key in selector.selectedKeys()) {
                        val ch = key.channel() as SocketChannel
                        val ok = try { ch.finishConnect() } catch (_: IOException) { false }
                        if (ok && !selfConnected(ch, key.attachment() as Int)) open += key.attachment() as Int
                        val _ = startedAt.remove(key)
                        key.cancel()
                        ch.close()
                    }
                    selector.selectedKeys().clear()
                    val t = System.currentTimeMillis()
                    val late = startedAt.filterValues { t - it > connectTimeoutMs }.keys.toList()
                    for (key in late) {
                        val _ = startedAt.remove(key)
                        key.cancel()
                        key.channel().close()
                    }
                }
            } finally {
                for (key in startedAt.keys) {
                    val _ = runCatching { key.channel().close() }
                }
            }
        }
        return open.toList()
    }

    private fun selfConnected(ch: SocketChannel, port: Int): Boolean = runCatching {
        val s = ch.socket()
        s.localPort == port && s.localAddress == s.inetAddress
    }.getOrDefault(false)

    override fun speaksAdbTls(host: String, port: Int, timeoutMs: Int): Boolean = try {
        val target = AdbHosts.ownSocketAddress(host, port) ?: return false
        Socket().use { s ->
            s.connect(target, timeoutMs)
            if (s.localPort == port && s.localAddress == s.inetAddress) return false
            s.soTimeout = timeoutMs
            s.getOutputStream().apply {
                write(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, HOST_BANNER).encode())
                flush()
            }
            AdbMessage.read(DataInputStream(s.getInputStream()), maxData = 4096)?.command == AdbMessage.STLS
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val SELECT_MS = 20L
        val HOST_BANNER = "host::\u0000".toByteArray(Charsets.US_ASCII)
    }
}
