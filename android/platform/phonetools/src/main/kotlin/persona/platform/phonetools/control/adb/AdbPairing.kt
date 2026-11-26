package persona.platform.phonetools.control.adb

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

internal object AdbServiceTypes {
    const val PAIRING = "_adb-tls-pairing._tcp"
    const val CONNECT = "_adb-tls-connect._tcp"
}

internal fun interface AdbServiceFinder {
    suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int>
}

internal enum class AdbPairingReason {
    NOT_LOCAL,
    CONNECT_FAILED,
    TLS_FAILED,
    WRONG_CODE,
    PROTOCOL,
}

internal class AdbPairingException(val reason: AdbPairingReason, message: String, cause: Throwable? = null) : IOException(message, cause)

internal class AdbPeerInfo(val type: Int, val data: String)

internal class AdbPairing(private val tls: AdbTls) {
    fun pair(host: String, port: Int, code: String, timeoutMs: Int = 15_000): AdbPeerInfo {
        val target = AdbHosts.ownSocketAddress(host, port) ?: throw AdbPairingException(
            AdbPairingReason.NOT_LOCAL,
            "refusing to pair with $host: adb is only reachable on this device",
        )
        val socket = try {
            tls.connect(target, timeoutMs)
        } catch (e: SSLException) {
            throw AdbPairingException(AdbPairingReason.TLS_FAILED, "TLS handshake failed", e)
        } catch (e: IOException) {
            throw AdbPairingException(AdbPairingReason.CONNECT_FAILED, "cannot connect to $host:$port", e)
        }
        socket.use { return exchange(it, code) }
    }

    private fun exchange(socket: SSLSocket, code: String): AdbPeerInfo {
        val exported = tls.exportKeyingMaterial(socket, EXPORT_LABEL, EXPORT_LENGTH)
        val auth = PairingAuth.client(code.toByteArray(Charsets.UTF_8) + exported)
        val input = DataInputStream(socket.inputStream)
        val output = socket.outputStream

        writePacket(output, TYPE_SPAKE2, auth.message)
        val theirMsg = readPacket(input, TYPE_SPAKE2) ?: throw protocol("connection closed before key exchange")
        try {
            auth.initCipher(theirMsg)
        } catch (e: IllegalArgumentException) {
            throw protocol("bad key exchange message: ${e.message}")
        }

        val info = ByteArray(PEER_INFO_SIZE)
        info[0] = PEER_RSA_PUB_KEY
        val key = tls.identity.adbPublicKey.toByteArray(Charsets.UTF_8)
        check(key.size < PEER_INFO_SIZE - 1)
        System.arraycopy(key, 0, info, 1, key.size)
        writePacket(output, TYPE_PEER_INFO, auth.encrypt(info))

        val sealed = try {
            readPacket(input, TYPE_PEER_INFO)
        } catch (e: IOException) {
            if (e is AdbPairingException) throw e
            null
        } ?: throw AdbPairingException(AdbPairingReason.WRONG_CODE, "device closed connection (wrong pairing code?)")
        val plain = try {
            auth.decrypt(sealed)
        } catch (e: AEADBadTagException) {
            throw AdbPairingException(AdbPairingReason.WRONG_CODE, "cannot decrypt device info (wrong pairing code?)", e)
        }
        if (plain.size != PEER_INFO_SIZE) throw protocol("peer info has ${plain.size} bytes")
        var end = 1
        while (end < plain.size && plain[end] != 0.toByte()) end++
        return AdbPeerInfo(plain[0].toInt() and 0xff, String(plain, 1, end - 1, Charsets.UTF_8))
    }

    private fun protocol(msg: String) = AdbPairingException(AdbPairingReason.PROTOCOL, msg)

    companion object {
        const val EXPORT_LABEL = "adb-label\u0000"
        const val EXPORT_LENGTH = 64
        const val PEER_INFO_SIZE = 8192
        const val MAX_PAYLOAD = PEER_INFO_SIZE * 2
        const val VERSION: Byte = 1
        const val TYPE_SPAKE2: Byte = 0
        const val TYPE_PEER_INFO: Byte = 1
        const val PEER_RSA_PUB_KEY: Byte = 0
        const val PEER_DEVICE_GUID: Byte = 1

        fun writePacket(out: OutputStream, type: Byte, payload: ByteArray) {
            val header = ByteBuffer.allocate(6).put(VERSION).put(type).putInt(payload.size).array()
            out.write(header + payload)
            out.flush()
        }

        fun readPacket(input: DataInputStream, expectedType: Byte): ByteArray? {
            val header = ByteArray(6)
            try {
                input.readFully(header)
            } catch (_: EOFException) {
                return null
            }
            val bb = ByteBuffer.wrap(header)
            val version = bb.get()
            val type = bb.get()
            val len = bb.getInt()
            if (version != VERSION) throw AdbPairingException(AdbPairingReason.PROTOCOL, "unsupported pairing version $version")
            if (type != expectedType) throw AdbPairingException(AdbPairingReason.PROTOCOL, "unexpected packet type $type")
            if (len <= 0 || len > MAX_PAYLOAD) throw AdbPairingException(AdbPairingReason.PROTOCOL, "bad payload size $len")
            return ByteArray(len).also { input.readFully(it) }
        }
    }
}
