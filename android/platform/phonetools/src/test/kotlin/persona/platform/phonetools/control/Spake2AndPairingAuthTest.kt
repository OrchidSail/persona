package persona.platform.phonetools.control

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import persona.platform.phonetools.control.adb.AdbHosts
import persona.platform.phonetools.control.adb.AdbIdentity
import persona.platform.phonetools.control.adb.AdbMessage
import persona.platform.phonetools.control.adb.AdbPublicKey
import persona.platform.phonetools.control.adb.EdPoint
import persona.platform.phonetools.control.adb.PairingAuth
import persona.platform.phonetools.control.adb.Spake2
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import javax.crypto.AEADBadTagException

class Spake2AndPairingAuthTest {

    @Test
    fun spake2DerivedConstantsMatchHardcodedPoints() {
        assertEquals(Spake2.M, Spake2.derivePoint(Spake2.SEED_M))
        assertEquals(Spake2.N, Spake2.derivePoint(Spake2.SEED_N))
    }

    @Test
    fun pairingAuthAgreesOnKeyAndEncryptsPeerInfoRoundTrip() {
        val password = "482913-exported-tls-keying-material".toByteArray(Charsets.UTF_8)
        val clientSeed = ByteArray(64) { (it + 1).toByte() }
        val serverSeed = ByteArray(64) { (it + 77).toByte() }

        val client = PairingAuth.client(password, clientSeed)
        val server = PairingAuth.server(password, serverSeed)
        client.initCipher(server.message)
        server.initCipher(client.message)

        val payload = "persona-rsa-pubkey-payload".toByteArray(Charsets.UTF_8)
        val sealed = client.encrypt(payload)
        assertArrayEquals(payload, server.decrypt(sealed))

        val reply = "device-guid-12345".toByteArray(Charsets.UTF_8)
        val sealedReply = server.encrypt(reply)
        assertArrayEquals(reply, client.decrypt(sealedReply))
    }

    @Test(expected = AEADBadTagException::class)
    fun pairingAuthRejectsWrongPairingCode() {
        val client = PairingAuth.client("123456-key".toByteArray(), ByteArray(64) { 3 })
        val server = PairingAuth.server("654321-key".toByteArray(), ByteArray(64) { 9 })
        client.initCipher(server.message)
        server.initCipher(client.message)
        val sealed = client.encrypt("secret".toByteArray())
        val _ = server.decrypt(sealed)
    }

    @Test
    fun adbIdentityEncodes524BytePublicKeyAndSelfSignedCertAndRecoversFromPkcs8() {
        val id = AdbIdentity.generate("persona@S25 Plus")
        val raw = AdbPublicKey.encode(id.publicKey)
        assertEquals(AdbPublicKey.SIZE, raw.size)
        assertTrue(id.adbPublicKey.endsWith(" persona@S25_Plus"))
        assertEquals("CN=Persona", id.certificate.subjectX500Principal.name)

        val restored = AdbIdentity.fromPkcs8(id.keyPair.private.encoded, "persona@S25 Plus")
        assertEquals(id.adbPublicKey, restored.adbPublicKey)
    }

    @Test
    fun adbMessageRoundTripAndChecksumVerification() {
        val msg = AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, "host::\u0000".toByteArray())
        val encoded = msg.encode()
        val decoded = AdbMessage.read(DataInputStream(ByteArrayInputStream(encoded)), verifyChecksum = true)
        assertNotNull(decoded)
        assertEquals(AdbMessage.CNXN, decoded!!.command)
        assertEquals(AdbMessage.VERSION, decoded.arg0)
        assertArrayEquals(msg.data, decoded.data)
    }

    @Test
    fun adbHostsAllowsOnlyLoopbackOrLocalLiteralAddresses() {
        assertNotNull(AdbHosts.ownAddress("127.0.0.1"))
        assertNotNull(AdbHosts.ownAddress("localhost"))
        assertNotNull(AdbHosts.ownAddress("::1"))
        assertNull(AdbHosts.ownAddress("example.com"))
        assertNull(AdbHosts.ownAddress("8.8.8.8"))
        assertNull(AdbHosts.ownAddress("127.0. 0.1"))
    }

    @Test
    fun edPointRejectsInvalidLengthAndMatchesIdentityArithmetic() {
        assertNull(EdPoint.decode(ByteArray(31)))
        assertEquals(EdPoint.BASE, EdPoint.BASE + EdPoint.IDENTITY)
        assertEquals(EdPoint.IDENTITY, EdPoint.BASE - EdPoint.BASE)
    }
}
