package persona.platform.phonetools.control.adb

import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * Standard Android classic TCP ADB RSA authentication helpers matching AOSP `adb_auth_host.cpp`
 * and `daemon/auth.cpp`:
 * - `AUTH(SIGNATURE)` signs the 20-byte challenge token directly as a SHA-1 DigestInfo payload
 *   using RSA PKCS#1 v1.5 Type 1 padding (WITHOUT hashing the token again).
 * - `AUTH(RSAPUBLICKEY)` sends Base64(524-byte Android RSAPublicKey struct) + " " + name + "\0".
 */
internal object AdbAuth {
    const val TOKEN_SIZE = 20
    const val DEFAULT_LOOPBACK_TCP_PORT = 5555

    // RFC 8017 §9.2 Notes 1 ASN.1 DER DigestInfo prefix for SHA-1 (15 bytes):
    // SEQUENCE(33) { SEQUENCE(9) { OID 1.3.14.3.2.26, NULL }, OCTET STRING(20) }
    internal val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
        0x30, 0x21,
        0x30, 0x09,
        0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
        0x05, 0x00,
        0x04, 0x14,
    )

    fun signToken(identity: AdbIdentity, token: ByteArray): ByteArray {
        require(token.size == TOKEN_SIZE) { "ADB AUTH token must be $TOKEN_SIZE bytes, got ${token.size}" }
        val digestInfo = SHA1_DIGEST_INFO_PREFIX + token
        return Signature.getInstance("NONEwithRSA").run {
            initSign(identity.keyPair.private)
            update(digestInfo)
            sign()
        }
    }

    fun verifyTokenSignature(publicKey: RSAPublicKey, token: ByteArray, signature: ByteArray): Boolean {
        if (token.size != TOKEN_SIZE) return false
        val digestInfo = SHA1_DIGEST_INFO_PREFIX + token
        return runCatching {
            Signature.getInstance("NONEwithRSA").run {
                initVerify(publicKey)
                update(digestInfo)
                verify(signature)
            }
        }.getOrDefault(false)
    }

    fun encodePublicKeyPacket(identity: AdbIdentity): ByteArray =
        (identity.adbPublicKey + "\u0000").toByteArray(Charsets.UTF_8)

    fun parseValidPort(raw: String?): Int? =
        raw?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    fun resolveLoopbackTcpPort(propertyValue: String?): Int =
        parseValidPort(propertyValue) ?: DEFAULT_LOOPBACK_TCP_PORT
}
