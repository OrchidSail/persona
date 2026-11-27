package persona.platform.phonetools.control

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import persona.platform.phonetools.control.adb.AdbAuth
import persona.platform.phonetools.control.adb.AdbConnection
import persona.platform.phonetools.control.adb.AdbConnectionException
import persona.platform.phonetools.control.adb.AdbConnectionReason
import persona.platform.phonetools.control.adb.AdbFallbackConnector
import persona.platform.phonetools.control.adb.AdbFallbackException
import persona.platform.phonetools.control.adb.AdbFallbackFailureReason
import persona.platform.phonetools.control.adb.AdbFallbackTransport
import persona.platform.phonetools.control.adb.AdbHosts
import persona.platform.phonetools.control.adb.AdbIdentity
import persona.platform.phonetools.control.adb.AdbMessage
import persona.platform.phonetools.control.adb.AdbPublicKey
import persona.platform.phonetools.control.adb.EdPoint
import java.io.DataInputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LoopbackTcpAdbFallbackTest {

    private fun loopbackAddress(): InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private fun decodeAndroidPublicKey(raw524: ByteArray): DecodedAndroidRsaPublicKey {
        assertEquals(AdbPublicKey.SIZE, raw524.size)
        val buf = ByteBuffer.wrap(raw524).order(ByteOrder.LITTLE_ENDIAN)
        val words = buf.getInt()
        val n0inv = buf.getInt().toLong() and 0xffffffffL
        val nLe = ByteArray(256).also { buf.get(it) }
        val rrLe = ByteArray(256).also { buf.get(it) }
        val exponent = buf.getInt().toLong() and 0xffffffffL
        val n = EdPoint.fromLe(nLe)
        val rr = EdPoint.fromLe(rrLe)
        val spec = RSAPublicKeySpec(n, BigInteger.valueOf(exponent))
        val rsaKey = KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
        return DecodedAndroidRsaPublicKey(words, n0inv, n, rr, exponent, rsaKey)
    }

    private data class DecodedAndroidRsaPublicKey(
        val words: Int,
        val n0inv: Long,
        val modulus: BigInteger,
        val rr: BigInteger,
        val exponent: Long,
        val rsaPublicKey: RSAPublicKey,
    )

    @Test
    fun rsaPublicKey524ByteStructSatisfiesAospMontgomeryInvariantsAndNullTerminatedWireFormat() {
        val identity = AdbIdentity.generate("persona@SM S936B")
        val packet = AdbAuth.encodePublicKeyPacket(identity)

        assertTrue("AUTH_RSAPUBLICKEY payload must be NUL-terminated", packet.isNotEmpty() && packet.last() == 0.toByte())
        val text = String(packet, 0, packet.size - 1, Charsets.UTF_8)
        val space = text.indexOf(' ')
        assertTrue("payload must contain '<base64> <name>'", space > 0)
        val b64 = text.substring(0, space)
        val comment = text.substring(space + 1)
        assertEquals("persona@SM_S936B", comment)
        assertEquals(700, b64.length) // ceil(524 / 3) * 4 = 700

        val raw = Base64.getDecoder().decode(b64)
        val decoded = decodeAndroidPublicKey(raw)
        assertEquals(64, decoded.words)
        assertEquals(identity.publicKey.modulus, decoded.modulus)
        assertEquals(65537L, decoded.exponent)

        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0 = decoded.modulus.mod(r32)
        val product = BigInteger.valueOf(decoded.n0inv).multiply(n0).mod(r32)
        assertEquals("n0inv * n[0] must be congruent to -1 (mod 2^32)", r32.subtract(BigInteger.ONE), product)

        val expectedRr = BigInteger.ONE.shiftLeft(4096).mod(decoded.modulus)
        assertEquals("rr must equal 2^4096 mod n", expectedRr, decoded.rr)
    }

    @Test
    fun authSignatureMatchesPkcs1v15Sha1DigestInfoBitForBitAndRawRsaVerification() {
        val identity = AdbIdentity.generate("persona@unit-test")
        val message = "deterministic-adb-auth-verification-message".toByteArray(Charsets.UTF_8)
        val token = MessageDigest.getInstance("SHA-1").digest(message)
        assertEquals(AdbAuth.TOKEN_SIZE, token.size)

        val sig = AdbAuth.signToken(identity, token)
        assertEquals(256, sig.size)

        // When token == SHA1(message), ADB's RSA_sign(NID_sha1, token) must be byte-for-byte
        // identical to standard SHA1withRSA(message).
        val stdSha1Rsa = Signature.getInstance("SHA1withRSA").run {
            initSign(identity.keyPair.private)
            update(message)
            sign()
        }
        assertArrayEquals(stdSha1Rsa, sig)
        assertTrue(AdbAuth.verifyTokenSignature(identity.publicKey, token, sig))

        // Verify raw RSA textbook decryption recovers exact PKCS#1 v1.5 Type 1 DigestInfo block.
        val emBig = BigInteger(1, sig).modPow(identity.publicKey.publicExponent, identity.publicKey.modulus)
        val emBytes = emBig.toByteArray()
        val em = ByteArray(256)
        val copyLen = minOf(emBytes.size, 256)
        System.arraycopy(emBytes, emBytes.size - copyLen, em, 256 - copyLen, copyLen)
        val expectedTail = AdbAuth.SHA1_DIGEST_INFO_PREFIX + token // 15 + 20 = 35 bytes
        val psLen = 256 - 3 - expectedTail.size
        assertEquals(0x00.toByte(), em[0])
        assertEquals(0x01.toByte(), em[1])
        for (i in 2 until 2 + psLen) {
            assertEquals("padding byte at $i must be 0xFF", 0xff.toByte(), em[i])
        }
        assertEquals(0x00.toByte(), em[2 + psLen])
        assertArrayEquals(expectedTail, em.copyOfRange(3 + psLen, 256))

        // Tampered token or wrong key must fail verification.
        val badToken = token.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(AdbAuth.verifyTokenSignature(identity.publicKey, badToken, sig))
        val other = AdbIdentity.generate("persona@other")
        assertFalse(AdbAuth.verifyTokenSignature(other.publicKey, token, sig))

        try {
            AdbAuth.signToken(identity, ByteArray(19))
            fail("expected non-20-byte token to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun loopbackHostGuardAndPortResolutionStrictlyEnforceLoopback127001() {
        val identity = AdbIdentity.generate("persona@guard-test")
        assertNotNull(AdbHosts.loopbackSocketAddress("127.0.0.1", 5555))
        assertNotNull(AdbHosts.loopbackSocketAddress("localhost", 5555))

        for (badHost in listOf("192.0.2.10", "198.51.100.13", "203.0.113.5", "8.8.8.8", "0.0.0.0", "127.0.0.2", "::1", "example.com", "127.0. 0.1")) {
            assertNull("must reject non-127.0.0.1 host $badHost", AdbHosts.loopbackSocketAddress(badHost, 5555))
            try {
                AdbConnection.openLoopbackTcp(host = badHost, port = 5555, identity = identity)
                fail("expected NOT_LOCAL for host $badHost")
            } catch (e: AdbConnectionException) {
                assertEquals("host=$badHost", AdbConnectionReason.NOT_LOCAL, e.reason)
            }
        }

        assertNull(AdbHosts.loopbackSocketAddress("127.0.0.1", 0))
        assertNull(AdbHosts.loopbackSocketAddress("127.0.0.1", 65536))

        assertEquals(5555, AdbAuth.resolveLoopbackTcpPort(null))
        assertEquals(5555, AdbAuth.resolveLoopbackTcpPort(""))
        assertEquals(5555, AdbAuth.resolveLoopbackTcpPort("-1"))
        assertEquals(5555, AdbAuth.resolveLoopbackTcpPort("0"))
        assertEquals(5555, AdbAuth.resolveLoopbackTcpPort("70000"))
        assertEquals(5555, AdbAuth.resolveLoopbackTcpPort("not-a-port"))
        assertEquals(5555, AdbAuth.resolveLoopbackTcpPort(" 5555 "))
        assertEquals(5556, AdbAuth.resolveLoopbackTcpPort("5556"))
    }

    @Test
    fun authorizedKeyHandshakeSucceedsSilentlyAndRunsShellAndExecStreams() {
        val identity = AdbIdentity.generate("persona@S25")
        val token = ByteArray(20) { (it * 7 + 3).toByte() }
        val serverErr = AtomicReference<Throwable?>(null)
        val execStdin = AtomicReference<String?>(null)

        ServerSocket(0, 50, loopbackAddress()).use { server ->
            val daemon = thread(isDaemon = true, name = "fake-adbd-authorized") {
                try {
                    server.accept().use { sock ->
                        val input = DataInputStream(sock.getInputStream())
                        val out = sock.getOutputStream()

                        val cnxn = AdbMessage.read(input, verifyChecksum = true)!!
                        assertEquals(AdbMessage.CNXN, cnxn.command)

                        out.write(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token).encode())
                        out.flush()

                        val authSig = AdbMessage.read(input, verifyChecksum = true)!!
                        assertEquals(AdbMessage.AUTH, authSig.command)
                        assertEquals(AdbMessage.AUTH_SIGNATURE, authSig.arg0)
                        assertTrue(AdbAuth.verifyTokenSignature(identity.publicKey, token, authSig.data))

                        out.write(
                            AdbMessage(
                                AdbMessage.CNXN,
                                AdbMessage.VERSION,
                                AdbMessage.MAX_PAYLOAD,
                                "device::ro.product.model=S25\u0000".toByteArray(Charsets.US_ASCII),
                            ).encode(),
                        )
                        out.flush()

                        // Handle 2 streams: shell:id and exec:start-helper
                        repeat(2) { idx ->
                            val open = AdbMessage.read(input, verifyChecksum = true)!!
                            assertEquals(AdbMessage.OPEN, open.command)
                            val localId = open.arg0
                            val remoteId = 100 + idx
                            val dest = open.data.toString(Charsets.UTF_8).trimEnd('\u0000')
                            out.write(AdbMessage(AdbMessage.OKAY, remoteId, localId).encode())
                            out.flush()

                            if (dest == "shell:id") {
                                out.write(AdbMessage(AdbMessage.WRTE, remoteId, localId, "uid=2000(shell)\n".toByteArray()).encode())
                                out.flush()
                                val ack = AdbMessage.read(input, verifyChecksum = true)!!
                                assertEquals(AdbMessage.OKAY, ack.command)
                                out.write(AdbMessage(AdbMessage.CLSE, remoteId, localId).encode())
                                out.flush()
                            } else if (dest == "exec:start-helper") {
                                val wrte = AdbMessage.read(input, verifyChecksum = true)!!
                                assertEquals(AdbMessage.WRTE, wrte.command)
                                execStdin.set(wrte.data.toString(Charsets.UTF_8))
                                out.write(AdbMessage(AdbMessage.OKAY, remoteId, localId).encode())
                                out.write(AdbMessage(AdbMessage.WRTE, remoteId, localId, "handoff-ok\n".toByteArray()).encode())
                                out.flush()
                                val ack = AdbMessage.read(input, verifyChecksum = true)!!
                                assertEquals(AdbMessage.OKAY, ack.command)
                                out.write(AdbMessage(AdbMessage.CLSE, remoteId, localId).encode())
                                out.flush()
                            } else {
                                fail("unexpected destination: $dest")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    serverErr.set(t)
                }
            }

            val awaitingFired = AtomicBoolean(false)
            AdbConnection.openLoopbackTcp(
                host = "127.0.0.1",
                port = server.localPort,
                identity = identity,
                onAwaitingAuthorization = { awaitingFired.set(true) },
            ).use { conn ->
                assertFalse("authorized key should reconnect silently", awaitingFired.get())
                assertEquals("device::ro.product.model=S25", conn.banner)

                conn.shell("id").use { s ->
                    assertEquals("uid=2000(shell)\n", s.readText())
                }
                conn.exec("start-helper").use { s ->
                    s.write("launch-nonce-xyz\n".toByteArray(Charsets.UTF_8))
                    assertEquals("handoff-ok\n", s.readText())
                }
            }
            daemon.join(5_000)
            serverErr.get()?.let { throw it }
            assertEquals("launch-nonce-xyz\n", execStdin.get())
        }
    }

    @Test
    fun unauthorizedKeySendsRsaPublicKeyTriggersPromptCallbackAndConnectsOnApproval() {
        val identity = AdbIdentity.generate("persona@FirstTime")
        val token1 = ByteArray(20) { (it + 11).toByte() }
        val token2 = ByteArray(20) { (it + 51).toByte() }
        val userApproved = CountDownLatch(1)
        val serverErr = AtomicReference<Throwable?>(null)
        val awaitingCount = AtomicInteger(0)

        ServerSocket(0, 50, loopbackAddress()).use { server ->
            val daemon = thread(isDaemon = true, name = "fake-adbd-first-auth") {
                try {
                    server.accept().use { sock ->
                        val input = DataInputStream(sock.getInputStream())
                        val out = sock.getOutputStream()

                        assertEquals(AdbMessage.CNXN, AdbMessage.read(input, verifyChecksum = true)!!.command)

                        // Challenge 1: client signs with unknown key.
                        out.write(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token1).encode())
                        out.flush()
                        val sigMsg = AdbMessage.read(input, verifyChecksum = true)!!
                        assertEquals(AdbMessage.AUTH, sigMsg.command)
                        assertEquals(AdbMessage.AUTH_SIGNATURE, sigMsg.arg0)

                        // Key not yet in adb_keys: issue second AUTH(TOKEN).
                        out.write(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token2).encode())
                        out.flush()
                        val pubMsg = AdbMessage.read(input, verifyChecksum = true)!!
                        assertEquals(AdbMessage.AUTH, pubMsg.command)
                        assertEquals(AdbMessage.AUTH_RSAPUBLICKEY, pubMsg.arg0)
                        assertTrue(pubMsg.data.isNotEmpty() && pubMsg.data.last() == 0.toByte())

                        // Decode the 524-byte Android RSAPublicKey and verify the earlier signature against it!
                        val keyText = String(pubMsg.data, 0, pubMsg.data.size - 1, Charsets.UTF_8)
                        val decoded = decodeAndroidPublicKey(Base64.getDecoder().decode(keyText.substringBefore(' ')))
                        assertTrue(
                            "reconstructed 524-byte Android RSAPublicKey must verify client's AUTH_SIGNATURE",
                            AdbAuth.verifyTokenSignature(decoded.rsaPublicKey, token1, sigMsg.data),
                        )

                        // Wait deterministically for simulated user tap on Allow.
                        assertTrue(userApproved.await(5, TimeUnit.SECONDS))
                        out.write(
                            AdbMessage(
                                AdbMessage.CNXN,
                                AdbMessage.VERSION,
                                AdbMessage.MAX_PAYLOAD,
                                "device::approved\u0000".toByteArray(Charsets.US_ASCII),
                            ).encode(),
                        )
                        out.flush()

                        val open = AdbMessage.read(input, verifyChecksum = true)!!
                        assertEquals(AdbMessage.OPEN, open.command)
                        out.write(AdbMessage(AdbMessage.OKAY, 77, open.arg0).encode())
                        out.write(AdbMessage(AdbMessage.WRTE, 77, open.arg0, "ok\n".toByteArray()).encode())
                        out.flush()
                        assertEquals(AdbMessage.OKAY, AdbMessage.read(input, verifyChecksum = true)!!.command)
                        out.write(AdbMessage(AdbMessage.CLSE, 77, open.arg0).encode())
                        out.flush()
                    }
                } catch (t: Throwable) {
                    serverErr.set(t)
                }
            }

            AdbConnection.openLoopbackTcp(
                host = "127.0.0.1",
                port = server.localPort,
                identity = identity,
                authApprovalTimeoutMs = 5_000,
                onAwaitingAuthorization = {
                    awaitingCount.incrementAndGet()
                    userApproved.countDown()
                },
            ).use { conn ->
                assertEquals(1, awaitingCount.get())
                assertEquals("device::approved", conn.banner)
                conn.shell("echo ok").use { s ->
                    assertEquals("ok\n", s.readText())
                }
            }
            daemon.join(5_000)
            serverErr.get()?.let { throw it }
        }
    }

    @Test
    fun unauthorizedKeyTimesOutAndDenialReportsDistinctReasons() {
        val identity = AdbIdentity.generate("persona@TimeoutDeny")
        val token1 = ByteArray(20) { 1 }
        val token2 = ByteArray(20) { 2 }

        // 1. Timeout when user never responds to authorization prompt.
        val releaseTimeoutServer = CountDownLatch(1)
        ServerSocket(0, 50, loopbackAddress()).use { server ->
            val daemon = thread(isDaemon = true, name = "fake-adbd-timeout") {
                val _ = runCatching {
                    server.accept().use { sock ->
                        val input = DataInputStream(sock.getInputStream())
                        val out = sock.getOutputStream()
                        val _ = AdbMessage.read(input)
                        out.write(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token1).encode())
                        out.flush()
                        val _ = AdbMessage.read(input)
                        out.write(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token2).encode())
                        out.flush()
                        val _ = AdbMessage.read(input) // AUTH_RSAPUBLICKEY
                        val _ = releaseTimeoutServer.await(5, TimeUnit.SECONDS)
                    }
                }
            }
            val awaiting = AtomicBoolean(false)
            try {
                AdbConnection.openLoopbackTcp(
                    host = "127.0.0.1",
                    port = server.localPort,
                    identity = identity,
                    authApprovalTimeoutMs = 150,
                    onAwaitingAuthorization = { awaiting.set(true) },
                )
                fail("expected AUTH_TIMEOUT")
            } catch (e: AdbConnectionException) {
                assertTrue(awaiting.get())
                assertEquals(AdbConnectionReason.AUTH_TIMEOUT, e.reason)
            } finally {
                releaseTimeoutServer.countDown()
                daemon.join(5_000)
            }
        }

        // 2. Denial when adbd closes socket after AUTH_RSAPUBLICKEY.
        ServerSocket(0, 50, loopbackAddress()).use { server ->
            val daemon = thread(isDaemon = true, name = "fake-adbd-deny") {
                val _ = runCatching {
                    server.accept().use { sock ->
                        val input = DataInputStream(sock.getInputStream())
                        val out = sock.getOutputStream()
                        val _ = AdbMessage.read(input)
                        out.write(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token1).encode())
                        out.flush()
                        val _ = AdbMessage.read(input)
                        out.write(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token2).encode())
                        out.flush()
                        val _ = AdbMessage.read(input) // AUTH_RSAPUBLICKEY -> close immediately
                    }
                }
            }
            val awaiting = AtomicBoolean(false)
            try {
                AdbConnection.openLoopbackTcp(
                    host = "127.0.0.1",
                    port = server.localPort,
                    identity = identity,
                    authApprovalTimeoutMs = 5_000,
                    onAwaitingAuthorization = { awaiting.set(true) },
                )
                fail("expected UNAUTHORIZED")
            } catch (e: AdbConnectionException) {
                assertTrue(awaiting.get())
                assertEquals(AdbConnectionReason.UNAUTHORIZED, e.reason)
            } finally {
                daemon.join(5_000)
            }
        }
    }

    @Test
    fun fallbackConnectorPrioritizesWifiTlsAndFallsBackToLoopbackOn4gOrTlsRefused() = runTest {
        val identity = AdbIdentity.generate("persona@Fallback")

        // Helper: find a guaranteed closed loopback port (ECONNREFUSED).
        val closedPort = ServerSocket(0, 1, loopbackAddress()).use { it.localPort }

        // 1. Wi-Fi ON + paired + wireless ON + TLS succeeds -> chooses WirelessTls without touching loopback.
        var loopbackCalls = 0
        val tlsFirst = AdbFallbackConnector(
            isWirelessPaired = { true },
            isWirelessDebuggingEnabled = { true },
            isWifiConnected = { true },
            openWirelessTls = { "tls-ok" },
            loopbackTcpPort = { 5555 },
            openLoopbackTcp = { _, _, _ -> loopbackCalls++; "loopback" },
        ).connect()
        assertEquals(AdbFallbackTransport.WirelessTls, tlsFirst.transport)
        assertEquals("tls-ok", tlsFirst.connection)
        assertEquals(0, loopbackCalls)

        // 2. 4G (no Wi-Fi) + paired + real loopback adbd running -> skips TLS and connects via loopback 127.0.0.1.
        startSingleShotAuthorizedFakeAdbd(identity) { port ->
            var tlsCalls = 0
            val result = AdbFallbackConnector(
                isWirelessPaired = { true },
                isWirelessDebuggingEnabled = { false },
                isWifiConnected = { false },
                openWirelessTls = { tlsCalls++; error("should not try TLS without Wi-Fi") },
                loopbackTcpPort = { port },
                openLoopbackTcp = { host, p, onAuth ->
                    AdbConnection.openLoopbackTcp(host = host, port = p, identity = identity, onAwaitingAuthorization = onAuth)
                },
            ).connect()
            result.connection.use { conn ->
                assertEquals(0, tlsCalls)
                assertEquals(AdbFallbackTransport.LoopbackTcp, result.transport)
                assertEquals(port, result.port)
                assertEquals("ok\n", conn.shell("ping").use { it.readText() })
            }
        }

        // 3. Wi-Fi ON + TLS ECONNREFUSED + real loopback adbd running -> falls back to loopback.
        startSingleShotAuthorizedFakeAdbd(identity) { port ->
            var tlsAttempts = 0
            val result = AdbFallbackConnector(
                isWirelessPaired = { true },
                isWirelessDebuggingEnabled = { true },
                isWifiConnected = { true },
                openWirelessTls = {
                    tlsAttempts++
                    throw AdbConnectionException(AdbConnectionReason.CONNECT_FAILED, "ECONNREFUSED")
                },
                loopbackTcpPort = { port },
                openLoopbackTcp = { host, p, onAuth ->
                    AdbConnection.openLoopbackTcp(host = host, port = p, identity = identity, onAwaitingAuthorization = onAuth)
                },
            ).connect()
            result.connection.use { conn ->
                assertEquals(1, tlsAttempts)
                assertEquals(AdbFallbackTransport.LoopbackTcp, result.transport)
                assertEquals("ok\n", conn.shell("ping").use { it.readText() })
            }
        }

        // 4. 4G (no Wi-Fi) + loopback ECONNREFUSED -> NoWifiAndLoopbackUnavailable.
        try {
            AdbFallbackConnector(
                isWirelessPaired = { true },
                isWirelessDebuggingEnabled = { false },
                isWifiConnected = { false },
                openWirelessTls = { error("no wifi") },
                loopbackTcpPort = { closedPort },
                openLoopbackTcp = { host, p, onAuth ->
                    AdbConnection.openLoopbackTcp(host = host, port = p, identity = identity, onAwaitingAuthorization = onAuth)
                },
            ).connect()
            fail("expected NoWifiAndLoopbackUnavailable")
        } catch (e: AdbFallbackException) {
            assertEquals(AdbFallbackFailureReason.NoWifiAndLoopbackUnavailable, e.reason)
        }

        // 5. Wi-Fi ON + wireless debugging OFF + loopback ECONNREFUSED -> WirelessDebuggingOff.
        try {
            AdbFallbackConnector(
                isWirelessPaired = { true },
                isWirelessDebuggingEnabled = { false },
                isWifiConnected = { true },
                openWirelessTls = { error("wireless off") },
                loopbackTcpPort = { closedPort },
                openLoopbackTcp = { host, p, onAuth ->
                    AdbConnection.openLoopbackTcp(host = host, port = p, identity = identity, onAwaitingAuthorization = onAuth)
                },
            ).connect()
            fail("expected WirelessDebuggingOff")
        } catch (e: AdbFallbackException) {
            assertEquals(AdbFallbackFailureReason.WirelessDebuggingOff, e.reason)
        }

        // 6. Wi-Fi ON + TLS UNAUTHORIZED + loopback ECONNREFUSED -> preserves AuthorizationRevoked.
        try {
            AdbFallbackConnector(
                isWirelessPaired = { true },
                isWirelessDebuggingEnabled = { true },
                isWifiConnected = { true },
                openWirelessTls = { throw AdbConnectionException(AdbConnectionReason.UNAUTHORIZED, "revoked") },
                loopbackTcpPort = { closedPort },
                openLoopbackTcp = { host, p, onAuth ->
                    AdbConnection.openLoopbackTcp(host = host, port = p, identity = identity, onAwaitingAuthorization = onAuth)
                },
            ).connect()
            fail("expected AuthorizationRevoked")
        } catch (e: AdbFallbackException) {
            assertEquals(AdbFallbackFailureReason.AuthorizationRevoked, e.reason)
        }

        // 7. 4G (no Wi-Fi) + loopback AUTH_TIMEOUT -> LoopbackAuthTimeout (never masked as NoWifi).
        try {
            AdbFallbackConnector<String>(
                isWirelessPaired = { true },
                isWirelessDebuggingEnabled = { false },
                isWifiConnected = { false },
                openWirelessTls = { error("no wifi") },
                loopbackTcpPort = { 5555 },
                openLoopbackTcp = { _, _, _ -> throw AdbConnectionException(AdbConnectionReason.AUTH_TIMEOUT, "timeout") },
            ).connect()
            fail("expected LoopbackAuthTimeout")
        } catch (e: AdbFallbackException) {
            assertEquals(AdbFallbackFailureReason.LoopbackAuthTimeout, e.reason)
        }

        // 8. Non-loopback host passed to fallback connector -> NotLocal.
        try {
            AdbFallbackConnector(
                isWirelessPaired = { false },
                isWirelessDebuggingEnabled = { false },
                isWifiConnected = { false },
                openWirelessTls = { error("unused") },
                loopbackTcpPort = { 5555 },
                openLoopbackTcp = { host, p, onAuth ->
                    AdbConnection.openLoopbackTcp(host = host, port = p, identity = identity, onAwaitingAuthorization = onAuth)
                },
                loopbackHost = "198.51.100.13",
            ).connect()
            fail("expected NotLocal")
        } catch (e: AdbFallbackException) {
            assertEquals(AdbFallbackFailureReason.NotLocal, e.reason)
        }
    }

    private suspend fun startSingleShotAuthorizedFakeAdbd(identity: AdbIdentity, block: suspend (port: Int) -> Unit) {
        val token = ByteArray(20) { (it + 33).toByte() }
        val serverErr = AtomicReference<Throwable?>(null)
        ServerSocket(0, 50, loopbackAddress()).use { server ->
            val daemon = thread(isDaemon = true, name = "fake-adbd-single") {
                try {
                    server.accept().use { sock ->
                        val input = DataInputStream(sock.getInputStream())
                        val out = sock.getOutputStream()
                        assertEquals(AdbMessage.CNXN, AdbMessage.read(input, verifyChecksum = true)!!.command)
                        out.write(AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_TOKEN, 0, token).encode())
                        out.flush()
                        val sig = AdbMessage.read(input, verifyChecksum = true)!!
                        assertTrue(AdbAuth.verifyTokenSignature(identity.publicKey, token, sig.data))
                        out.write(
                            AdbMessage(
                                AdbMessage.CNXN,
                                AdbMessage.VERSION,
                                AdbMessage.MAX_PAYLOAD,
                                "device::loopback\u0000".toByteArray(Charsets.US_ASCII),
                            ).encode(),
                        )
                        out.flush()
                        val open = AdbMessage.read(input, verifyChecksum = true)!!
                        out.write(AdbMessage(AdbMessage.OKAY, 42, open.arg0).encode())
                        out.write(AdbMessage(AdbMessage.WRTE, 42, open.arg0, "ok\n".toByteArray()).encode())
                        out.flush()
                        val _ = AdbMessage.read(input, verifyChecksum = true)
                        out.write(AdbMessage(AdbMessage.CLSE, 42, open.arg0).encode())
                        out.flush()
                    }
                } catch (t: Throwable) {
                    serverErr.set(t)
                }
            }
            block(server.localPort)
            daemon.join(5_000)
            serverErr.get()?.let { throw it }
        }
    }
}
