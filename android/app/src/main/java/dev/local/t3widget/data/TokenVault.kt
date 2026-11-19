package dev.local.t3widget.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the bearer token with an AES-256-GCM key that never leaves the Android Keystore
 * (hardware-backed on the Z Flip7). The ciphertext is what lands in DataStore.
 */
object TokenVault {
    private const val ALIAS = "t3widget-token-v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    @Volatile private var cache: Pair<String, String>? = null

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Base64(iv ‖ ciphertext). Empty in → empty out. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(out, Base64.NO_WRAP)
        cache = blob to plain
        return blob
    }

    /** Null if the blob can't be decrypted (e.g. key lost after a restore). */
    fun decrypt(blob: String): String? {
        if (blob.isEmpty()) return ""
        cache?.let { (b, p) -> if (b == blob) return p }
        return runCatching {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()?.also { cache = blob to it }
    }
}
