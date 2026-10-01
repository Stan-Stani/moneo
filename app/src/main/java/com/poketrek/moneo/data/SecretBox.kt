package com.poketrek.moneo.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (the 💬 API key and gateway headers) with an AES key
 * that never leaves the Android Keystore, so DataStore only holds ciphertext.
 * Stored form: "v1:" + base64(iv + ciphertext).
 */
object SecretBox {
    private const val TAG = "SecretBox"
    private const val ALIAS = "moneo_secrets"
    private const val PREFIX = "v1:"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    /** Returns the stored form, or null if the Keystore refused (logged). */
    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        PREFIX + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
    }.onFailure { Log.w(TAG, "encrypt failed", it) }.getOrNull()

    /** Returns the secret, or null if [stored] can't be read (e.g. the Keystore key is gone). */
    fun decrypt(stored: String): String? = runCatching {
        require(stored.startsWith(PREFIX)) { "unknown format" }
        val bytes = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }.onFailure { Log.w(TAG, "decrypt failed", it) }.getOrNull()

    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}
