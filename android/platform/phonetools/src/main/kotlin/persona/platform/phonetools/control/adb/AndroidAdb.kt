package persona.platform.phonetools.control.adb

import android.content.Context
import android.net.ssl.SSLSockets
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.net.ssl.SSLException

internal object AndroidAdb {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAP_ALIAS = "persona_phone_control_adb_wrap"
    private const val TAG = "PersonaAdbKey"

    val exporter = KeyingMaterialExporter { socket, label, length ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw SSLException("keying material export requires Android 12+")
        }
        SSLSockets.exportKeyingMaterial(socket, label, ByteArray(0), length)
            ?: throw SSLException("keying material export failed")
    }

    @Volatile
    private var source: AdbIdentitySource? = null

    fun identities(context: Context): AdbIdentitySource = source ?: synchronized(this) {
        source ?: AdbIdentitySource(keyStore(context), { "persona@${Build.MODEL}" }) { msg, e ->
            Log.w(TAG, msg, e)
        }.also { source = it }
    }

    fun identity(context: Context): AdbIdentity = identities(context).forConnect()

    fun pairingIdentity(context: Context): AdbIdentity = identities(context).forPairing()

    fun tls(identity: AdbIdentity) = AdbTls(identity, exporter)

    fun forget(context: Context) = identities(context).forget()

    private fun keyStore(context: Context): AdbKeyStore {
        val file = WrappedKeyFile(File(context.applicationContext.noBackupFilesDir, "phone_control/adbkey.bin")) { wrappingKey() }
        return object : AdbKeyStore by file {
            override fun clear() {
                file.clear()
                val _ = runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(WRAP_ALIAS) }
            }
        }
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
