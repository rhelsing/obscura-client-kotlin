package com.obscura.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages the SQLCipher database encryption key.
 * Follows Signal Android's DatabaseSecretProvider pattern:
 *
 * 1. Generate a 32-byte random key (high entropy, not a passphrase)
 * 2. Seal it with Android Keystore (AES-256-GCM)
 * 3. Store the sealed blob in SharedPreferences
 * 4. On launch, unseal with Keystore and pass to SQLCipher
 *
 * Because the key is already 256 bits of entropy, SQLCipher KDF iterations
 * are set to 1 (same as Signal) to avoid unnecessary CPU cost.
 */
object DatabaseSecretProvider {

    private const val KEYSTORE_ALIAS = "ObscuraDatabaseSecret"
    private const val PREFS_NAME = "obscura_db_secret"
    private const val PREF_KEY_IV = "sealed_iv"
    private const val PREF_KEY_DATA = "sealed_data"

    fun getOrCreate(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sealedIv = prefs.getString(PREF_KEY_IV, null)
        val sealedData = prefs.getString(PREF_KEY_DATA, null)

        if (sealedIv != null && sealedData != null) {
            return unseal(
                Base64.getDecoder().decode(sealedIv),
                Base64.getDecoder().decode(sealedData)
            )
        }

        // First launch — generate and seal
        val secret = ByteArray(32)
        SecureRandom().nextBytes(secret)

        val (iv, encrypted) = seal(secret)
        prefs.edit()
            .putString(PREF_KEY_IV, Base64.getEncoder().encodeToString(iv))
            .putString(PREF_KEY_DATA, Base64.getEncoder().encodeToString(encrypted))
            .apply()

        return secret
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        keyStore.getEntry(KEYSTORE_ALIAS, null)?.let {
            return (it as KeyStore.SecretKeyEntry).secretKey
        }

        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(spec)
        return generator.generateKey()
    }

    private fun seal(data: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
        return Pair(cipher.iv, cipher.doFinal(data))
    }

    private fun unseal(iv: ByteArray, encrypted: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }
}
