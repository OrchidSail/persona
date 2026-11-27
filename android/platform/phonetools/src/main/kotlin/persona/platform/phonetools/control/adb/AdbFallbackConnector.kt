package persona.platform.phonetools.control.adb

import java.io.IOException

internal enum class AdbFallbackTransport {
    WirelessTls,
    LoopbackTcp,
}

internal data class AdbFallbackResult<T>(
    val connection: T,
    val transport: AdbFallbackTransport,
    val port: Int? = null,
)

internal enum class AdbFallbackFailureReason {
    NoWifiAndLoopbackUnavailable,
    WirelessDebuggingOff,
    AuthorizationRevoked,
    LoopbackAuthTimeout,
    LoopbackAuthDenied,
    ConnectFailed,
    NotLocal,
}

internal class AdbFallbackException(
    val reason: AdbFallbackFailureReason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Coordinates built-in ADB connection priority:
 * 1. When Wi-Fi is available and Wireless Debugging is paired & enabled, prefer TLS Wireless Debugging.
 * 2. When Wireless Debugging is unavailable (no Wi-Fi, Wireless Debugging off, mDNS miss, or TLS failure),
 *    automatically fall back to loopback `127.0.0.1:<port>` classic TCP ADB (`service.adb.tcp.port` or 5555).
 */
internal class AdbFallbackConnector<T>(
    private val isWirelessPaired: () -> Boolean,
    private val isWirelessDebuggingEnabled: () -> Boolean?,
    private val isWifiConnected: () -> Boolean,
    private val openWirelessTls: suspend () -> T,
    private val loopbackTcpPort: () -> Int,
    private val openLoopbackTcp: suspend (host: String, port: Int, onAwaitingAuth: () -> Unit) -> T,
    private val allowLoopbackFallback: () -> Boolean = { true },
    private val loopbackHost: String = AdbConnectPort.LOCALHOST,
) {
    suspend fun connect(onAwaitingLoopbackAuth: () -> Unit = {}): AdbFallbackResult<T> {
        val paired = isWirelessPaired()
        val wifi = isWifiConnected()
        val wirelessOn = isWirelessDebuggingEnabled()
        var wirelessErr: AdbConnectionException? = null

        if (paired && wifi && wirelessOn != false) {
            try {
                return AdbFallbackResult(
                    connection = openWirelessTls(),
                    transport = AdbFallbackTransport.WirelessTls,
                )
            } catch (e: AdbConnectionException) {
                if (e.reason == AdbConnectionReason.NOT_LOCAL) {
                    throw AdbFallbackException(AdbFallbackFailureReason.NotLocal, e.message ?: "non-local ADB host", e)
                }
                wirelessErr = e
            }
        }

        if (allowLoopbackFallback()) {
            val port = loopbackTcpPort()
            try {
                return AdbFallbackResult(
                    connection = openLoopbackTcp(loopbackHost, port, onAwaitingLoopbackAuth),
                    transport = AdbFallbackTransport.LoopbackTcp,
                    port = port,
                )
            } catch (e: AdbConnectionException) {
                when (e.reason) {
                    AdbConnectionReason.NOT_LOCAL ->
                        throw AdbFallbackException(AdbFallbackFailureReason.NotLocal, e.message ?: "non-loopback host", e)
                    AdbConnectionReason.AUTH_TIMEOUT ->
                        throw AdbFallbackException(AdbFallbackFailureReason.LoopbackAuthTimeout, e.message ?: "ADB auth timeout", e)
                    AdbConnectionReason.UNAUTHORIZED ->
                        throw AdbFallbackException(AdbFallbackFailureReason.LoopbackAuthDenied, e.message ?: "ADB auth denied", e)
                    AdbConnectionReason.CONNECT_FAILED, AdbConnectionReason.TLS_FAILED, AdbConnectionReason.PROTOCOL -> {
                        throw classifyBothUnavailable(paired, wifi, wirelessOn, wirelessErr, e)
                    }
                }
            }
        }

        throw classifyBothUnavailable(paired, wifi, wirelessOn, wirelessErr, null)
    }

    private fun classifyBothUnavailable(
        paired: Boolean,
        wifi: Boolean,
        wirelessOn: Boolean?,
        wirelessErr: AdbConnectionException?,
        loopbackErr: AdbConnectionException?,
    ): AdbFallbackException {
        if (wirelessErr?.reason == AdbConnectionReason.UNAUTHORIZED) {
            return AdbFallbackException(
                AdbFallbackFailureReason.AuthorizationRevoked,
                wirelessErr.message ?: "wireless ADB authorization revoked",
                wirelessErr,
            )
        }
        if (!wifi) {
            return AdbFallbackException(
                AdbFallbackFailureReason.NoWifiAndLoopbackUnavailable,
                loopbackErr?.message ?: wirelessErr?.message ?: "no Wi-Fi and loopback TCP ADB unavailable",
                loopbackErr ?: wirelessErr,
            )
        }
        if (paired && wirelessOn == false) {
            return AdbFallbackException(
                AdbFallbackFailureReason.WirelessDebuggingOff,
                "wireless debugging is off and loopback TCP ADB unavailable",
                loopbackErr,
            )
        }
        val cause = wirelessErr ?: loopbackErr
        return AdbFallbackException(
            AdbFallbackFailureReason.ConnectFailed,
            cause?.message ?: "failed to connect to local ADB",
            cause,
        )
    }
}
