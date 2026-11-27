package persona.platform.phonetools.control.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlin.concurrent.thread

internal enum class AdbConnectionReason {
    NOT_LOCAL,
    CONNECT_FAILED,
    TLS_FAILED,
    UNAUTHORIZED,
    AUTH_TIMEOUT,
    PROTOCOL,
}

internal class AdbConnectionException(val reason: AdbConnectionReason, message: String, cause: Throwable? = null) : IOException(message, cause)

internal class AdbConnection private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    val banner: String,
    private val peerVersion: Int,
    private val peerMaxData: Int,
    private val timeoutMs: Int,
) : Closeable {
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextId = AtomicInteger(1)
    @Volatile
    private var closed = false

    private val reader = thread(isDaemon = true, name = "persona-adb-reader") { readLoop() }

    fun exec(command: String): AdbStream = open("exec:$command")

    fun shell(command: String): AdbStream = open("shell:$command")

    fun open(destination: String): AdbStream {
        if (closed) throw IOException("adb connection closed")
        val s = AdbStream(nextId.getAndIncrement())
        streams[s.localId] = s
        send(AdbMessage(AdbMessage.OPEN, s.localId, 0, (destination + "\u0000").toByteArray(Charsets.UTF_8)))
        val ok = try {
            s.opened.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            streams.remove(s.localId)
            throw IOException("open $destination timed out", e)
        }
        if (!ok) {
            streams.remove(s.localId)
            throw IOException("device refused $destination")
        }
        return s
    }

    override fun close() {
        closed = true
        val _ = runCatching { socket.close() }
        streams.values.forEach { it.remoteClosed() }
        streams.clear()
    }

    private fun send(m: AdbMessage) {
        val bytes = m.encode()
        synchronized(output) {
            output.write(bytes)
            output.flush()
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val m = AdbMessage.read(input, AdbMessage.MAX_PAYLOAD, peerVersion < AdbMessage.VERSION_SKIP_CHECKSUM) ?: break
                val s = streams[m.arg1]
                when (m.command) {
                    AdbMessage.OKAY -> if (s != null) {
                        if (s.remoteId == 0) {
                            s.remoteId = m.arg0
                            val _ = s.opened.complete(true)
                        } else {
                            s.acks.release()
                        }
                    }
                    AdbMessage.WRTE -> if (s != null) {
                        s.incoming.put(m.data)
                        send(AdbMessage(AdbMessage.OKAY, s.localId, s.remoteId))
                    }
                    AdbMessage.CLSE -> if (s != null) {
                        val _ = streams.remove(s.localId)
                        val _ = s.opened.complete(false)
                        s.remoteClosed()
                    }
                    else -> Unit
                }
            }
        } catch (_: IOException) {
            // Stream ended.
        } finally {
            close()
        }
    }

    inner class AdbStream internal constructor(val localId: Int) : Closeable {
        @Volatile
        internal var remoteId = 0
        internal val opened = CompletableFuture<Boolean>()
        internal val acks = Semaphore(0)
        internal val incoming = LinkedBlockingQueue<ByteArray>()
        @Volatile
        private var remoteGone = false
        @Volatile
        private var localClosed = false

        fun read(): ByteArray? {
            val b = incoming.take()
            if (b === EOF) {
                incoming.put(EOF)
                return null
            }
            return b
        }

        fun readAll(): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) out.write(read() ?: return out.toByteArray())
        }

        fun readText(): String = readAll().toString(Charsets.UTF_8)

        fun write(data: ByteArray) {
            var pos = 0
            while (pos < data.size) {
                if (remoteGone || localClosed) throw IOException("stream closed")
                val n = minOf(peerMaxData, data.size - pos)
                send(AdbMessage(AdbMessage.WRTE, localId, remoteId, data.copyOfRange(pos, pos + n)))
                if (!acks.tryAcquire(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) throw IOException("write not acknowledged")
                if (remoteGone) throw IOException("stream closed")
                pos += n
            }
        }

        internal fun remoteClosed() {
            remoteGone = true
            val _ = opened.complete(false)
            acks.release()
            incoming.put(EOF)
        }

        override fun close() {
            if (localClosed) return
            localClosed = true
            if (!remoteGone && !closed) {
                val _ = runCatching { send(AdbMessage(AdbMessage.CLSE, localId, remoteId)) }
            }
            val _ = streams.remove(localId)
            remoteClosed()
        }
    }

    companion object {
        const val DEFAULT_AUTH_APPROVAL_TIMEOUT_MS = 25_000
        private val EOF = ByteArray(0)
        private val HOST_BANNER = "host::\u0000".toByteArray(Charsets.US_ASCII)

        fun open(host: String, port: Int, tls: AdbTls, timeoutMs: Int = 10_000): AdbConnection {
            val target = AdbHosts.ownSocketAddress(host, port) ?: throw AdbConnectionException(
                AdbConnectionReason.NOT_LOCAL,
                "refusing to connect to $host: adb is only reachable on this device",
            )
            val plain = Socket()
            try {
                plain.tcpNoDelay = true
                plain.connect(target, timeoutMs)
                plain.soTimeout = timeoutMs
            } catch (e: IOException) {
                plain.close()
                throw AdbConnectionException(AdbConnectionReason.CONNECT_FAILED, "cannot connect to $host:$port", e)
            }
            try {
                return handshakeTls(plain, target, tls, timeoutMs)
            } catch (e: Throwable) {
                val _ = runCatching { plain.close() }
                throw e
            }
        }

        /**
         * Connects over plaintext classic TCP ADB strictly to loopback `127.0.0.1` (never remote or LAN),
         * following Android's standard `CNXN` -> `AUTH(TOKEN)` -> `AUTH(SIGNATURE)` -> optional
         * `AUTH(RSAPUBLICKEY)` system debugging authorization flow.
         */
        fun openLoopbackTcp(
            host: String = AdbConnectPort.LOCALHOST,
            port: Int = AdbAuth.DEFAULT_LOOPBACK_TCP_PORT,
            identity: AdbIdentity,
            connectTimeoutMs: Int = 3_000,
            handshakeTimeoutMs: Int = 5_000,
            authApprovalTimeoutMs: Int = DEFAULT_AUTH_APPROVAL_TIMEOUT_MS,
            streamTimeoutMs: Int = 10_000,
            onSocketOpened: (Socket) -> Unit = {},
            onAwaitingAuthorization: () -> Unit = {},
        ): AdbConnection {
            if (port !in 1..65535) {
                throw AdbConnectionException(AdbConnectionReason.CONNECT_FAILED, "invalid loopback ADB port: $port")
            }
            val target = AdbHosts.loopbackSocketAddress(host, port) ?: throw AdbConnectionException(
                AdbConnectionReason.NOT_LOCAL,
                "refusing non-loopback classic TCP ADB target: $host:$port",
            )
            val plain = Socket()
            onSocketOpened(plain)
            try {
                plain.tcpNoDelay = true
                plain.connect(target, connectTimeoutMs)
                plain.soTimeout = handshakeTimeoutMs
            } catch (e: IOException) {
                val _ = runCatching { plain.close() }
                throw AdbConnectionException(AdbConnectionReason.CONNECT_FAILED, "cannot connect to $host:$port", e)
            }
            try {
                return handshakeLoopbackTcp(
                    plain = plain,
                    identity = identity,
                    authApprovalTimeoutMs = authApprovalTimeoutMs,
                    streamTimeoutMs = streamTimeoutMs,
                    onAwaitingAuthorization = onAwaitingAuthorization,
                )
            } catch (e: Throwable) {
                val _ = runCatching { plain.close() }
                throw e
            }
        }

        private fun handshakeTls(plain: Socket, target: InetSocketAddress, tls: AdbTls, timeoutMs: Int): AdbConnection {
            val out = plain.getOutputStream()
            out.write(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, HOST_BANNER).encode())
            out.flush()
            val first = AdbMessage.read(DataInputStream(plain.getInputStream()))
                ?: throw AdbConnectionException(AdbConnectionReason.PROTOCOL, "device closed connection")
            return when (first.command) {
                AdbMessage.CNXN -> finish(plain, DataInputStream(plain.getInputStream()), out, first, timeoutMs)
                AdbMessage.STLS -> {
                    out.write(AdbMessage(AdbMessage.STLS, AdbMessage.STLS_VERSION, 0).encode())
                    out.flush()
                    val ssl = try {
                        tls.wrap(plain, target, client = true)
                    } catch (e: IOException) {
                        throw AdbConnectionException(AdbConnectionReason.TLS_FAILED, "TLS handshake failed", e)
                    }
                    val input = DataInputStream(ssl.inputStream)
                    val cnxn = try {
                        AdbMessage.read(input)
                    } catch (e: SSLException) {
                        throw AdbConnectionException(AdbConnectionReason.UNAUTHORIZED, "TLS rejected client certificate", e)
                    } catch (e: IOException) {
                        if (e is SocketTimeoutException) {
                            throw AdbConnectionException(AdbConnectionReason.PROTOCOL, "no CNXN from device", e)
                        }
                        null
                    } ?: throw AdbConnectionException(AdbConnectionReason.UNAUTHORIZED, "device closed connection after TLS")
                    if (cnxn.command != AdbMessage.CNXN) {
                        throw AdbConnectionException(AdbConnectionReason.PROTOCOL, "expected CNXN, got $cnxn")
                    }
                    finish(ssl, input, ssl.outputStream, cnxn, timeoutMs)
                }
                AdbMessage.AUTH -> throw AdbConnectionException(AdbConnectionReason.UNAUTHORIZED, "legacy RSA auth port")
                else -> throw AdbConnectionException(AdbConnectionReason.PROTOCOL, "unexpected $first")
            }
        }

        private fun handshakeLoopbackTcp(
            plain: Socket,
            identity: AdbIdentity,
            authApprovalTimeoutMs: Int,
            streamTimeoutMs: Int,
            onAwaitingAuthorization: () -> Unit,
        ): AdbConnection {
            val input = DataInputStream(plain.getInputStream())
            val out = plain.getOutputStream()
            out.write(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, HOST_BANNER).encode())
            out.flush()

            var sentSignature = false
            var sentPublicKey = false
            var authTokensSeen = 0
            while (true) {
                val msg = try {
                    AdbMessage.read(input)
                } catch (e: SocketTimeoutException) {
                    if (sentPublicKey) {
                        throw AdbConnectionException(
                            AdbConnectionReason.AUTH_TIMEOUT,
                            "timed out waiting for local ADB debugging authorization",
                            e,
                        )
                    }
                    throw AdbConnectionException(AdbConnectionReason.PROTOCOL, "timed out during ADB handshake", e)
                } catch (e: IOException) {
                    if (sentPublicKey) {
                        throw AdbConnectionException(
                            AdbConnectionReason.UNAUTHORIZED,
                            "local ADB authorization connection closed",
                            e,
                        )
                    }
                    throw AdbConnectionException(AdbConnectionReason.PROTOCOL, "ADB handshake I/O error", e)
                } ?: throw AdbConnectionException(
                    if (sentPublicKey) AdbConnectionReason.UNAUTHORIZED else AdbConnectionReason.PROTOCOL,
                    if (sentPublicKey) "local ADB authorization denied or closed" else "device closed connection during handshake",
                )

                when (msg.command) {
                    AdbMessage.CNXN -> return finish(plain, input, out, msg, streamTimeoutMs)
                    AdbMessage.AUTH -> {
                        if (msg.arg0 != AdbMessage.AUTH_TOKEN) {
                            throw AdbConnectionException(AdbConnectionReason.PROTOCOL, "unexpected AUTH type ${msg.arg0}")
                        }
                        authTokensSeen++
                        if (authTokensSeen > 4) {
                            throw AdbConnectionException(AdbConnectionReason.UNAUTHORIZED, "too many AUTH challenges")
                        }
                        if (!sentSignature) {
                            if (msg.data.size != AdbAuth.TOKEN_SIZE) {
                                throw AdbConnectionException(
                                    AdbConnectionReason.PROTOCOL,
                                    "unexpected ADB AUTH token length: ${msg.data.size}",
                                )
                            }
                            val signature = AdbAuth.signToken(identity, msg.data)
                            out.write(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_SIGNATURE, 0, signature).encode())
                            out.flush()
                            sentSignature = true
                        } else if (!sentPublicKey) {
                            val pubKeyPacket = AdbAuth.encodePublicKeyPacket(identity)
                            out.write(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_RSAPUBLICKEY, 0, pubKeyPacket).encode())
                            out.flush()
                            sentPublicKey = true
                            plain.soTimeout = authApprovalTimeoutMs
                            onAwaitingAuthorization()
                        } else {
                            throw AdbConnectionException(
                                AdbConnectionReason.UNAUTHORIZED,
                                "local ADB daemon rejected RSA public key",
                            )
                        }
                    }
                    AdbMessage.STLS -> throw AdbConnectionException(
                        AdbConnectionReason.PROTOCOL,
                        "unexpected STLS on classic TCP ADB port",
                    )
                    else -> throw AdbConnectionException(AdbConnectionReason.PROTOCOL, "unexpected $msg")
                }
            }
        }

        private fun finish(socket: Socket, input: DataInputStream, out: OutputStream, cnxn: AdbMessage, timeoutMs: Int): AdbConnection {
            socket.soTimeout = 0
            val banner = cnxn.data.toString(Charsets.UTF_8).trimEnd('\u0000')
            val maxData = if (cnxn.arg1 in 1..AdbMessage.MAX_PAYLOAD) cnxn.arg1 else 4096
            return AdbConnection(socket, input, out, banner, cnxn.arg0, maxData, timeoutMs)
        }
    }
}
